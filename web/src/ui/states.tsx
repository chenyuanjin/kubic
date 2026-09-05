import type { ReactNode } from 'react'
import { failureCopy } from '../lib/errorCopy'
import { Button, Note, Tag } from './primitives'

/**
 * 四态里的后三态 —— 空态 / 失败态 / 受限态。
 *
 * <h2>这个文件里<b>一句判断都没有</b></h2>
 *
 * 「这个码属于哪一档、说什么话」整个在 `lib/errorCopy.ts` 里,那一层不碰 DOM、
 * 在 node 里被测。这里只把那一层的结果画出来。两层不合并的理由与
 * `lib/rawImageCache.ts` / `lib/rawImageFs.ts` 那一对完全相同:
 * <b>合并之后,没被测到的那一半就有了藏逻辑的地方。</b>
 *
 * <h2>主态不在这里</h2>
 *
 * 主态是每一屏自己的样子,没有共同形状可抽。硬抽一个只会得到一个
 * 「什么都能画所以什么都画不好」的容器。
 */

/**
 * 空态。
 *
 * 🔴 空态<b>不是</b>失败:不用红色,不给重试。它说的是「这里现在没有东西」,
 * 而下一步通常是去<b>产生</b>一点东西,不是再拉一次。
 *
 * 🔴 也不拿「敬请期待」把空白填上(`design/h5/H5交互说明.md` S-BILL 那一格的原话)。
 */
export function EmptyState({
  title,
  body,
  action,
}: {
  title: string
  body: string
  action?: { label: string; onClick: () => void }
}) {
  return (
    <div className="flex flex-col items-start gap-3 px-[var(--rule)] py-10">
      <p className="text-[13px] text-tx">{title}</p>
      <p className="max-w-[46ch] text-[12px] leading-6 text-t2">{body}</p>
      {action ? (
        <Button variant="plain" onClick={action.onClick}>
          {action.label}
        </Button>
      ) : null}
    </div>
  )
}

/**
 * 失败态 / 受限态 —— 由错误码决定画哪一档。
 *
 * <ul>
 * <li><b>可重试</b> → 红字 + 重试按钮</li>
 * <li><b>不可重试</b> → 红字,<b>不给重试按钮</b>。再点一次还是同一个结果,
 *     给一个按钮等于让用户替一个不会变的结果反复付出期待</li>
 * <li><b>受限</b> → <b>没有一点红色</b>,主操作照常可点,兜底出口摆在最显眼处。
 *     `design/m1/交互说明.md` 05-quota:「额度耗尽时记录仍成功,<b>这一态不是失败</b>」</li>
 * </ul>
 *
 * @param code 服务端错误体里的 `code`;传输层失败时传 `null`
 * @param onRetry 只有 `retryable` 那一档会用到它
 * @param onFallback 兜底出口。这一档的 `fallback` 文案由判据层给
 * @param scope 「失败停在发生的那一区」(`design/m4/交互说明.md` 的收敛条款)——
 *   传一个区名,让用户知道<b>哪一块</b>没成,而不是以为整屏都完了
 */
export function FailureBlock({
  code,
  scope,
  onRetry,
  onFallback,
}: {
  code: string | null
  scope?: string
  onRetry?: () => void
  onFallback?: () => void
}) {
  const copy = failureCopy(code)
  const limited = copy.kind === 'limited'

  return (
    <div
      className={`mx-[var(--rule)] my-4 border-l-2 py-2 pl-3 ${limited ? 'border-l-hair2' : 'border-l-red'}`}
    >
      <div className="flex flex-wrap items-center gap-2">
        <Tag tone={limited ? 'plain' : 'warn'}>{limited ? '受限' : '没成'}</Tag>
        <span className={`text-[12.5px] ${limited ? 'text-tx' : 'text-red'}`}>{copy.title}</span>
        {scope !== undefined && <span className="font-mono text-[11px] text-t3">· {scope}</span>}
      </div>
      <p className="mt-1.5 max-w-[52ch] text-[11.5px] leading-6 text-t2">{copy.body}</p>
      <div className="mt-2.5 flex flex-wrap items-center gap-2">
        {copy.kind === 'retryable' && onRetry ? (
          <Button onClick={onRetry}>再试一次</Button>
        ) : null}
        {copy.fallback !== null && onFallback ? (
          // 受限态里兜底才是主操作 —— 它是那条不花额度的路
          <Button variant={limited ? 'primary' : 'plain'} onClick={onFallback}>
            {copy.fallback}
          </Button>
        ) : null}
        {copy.kind === 'terminal' && !onFallback ? (
          <span className="font-mono text-[11px] text-t3">这一档不给重试 —— 再点一次是同一个结果</span>
        ) : null}
      </div>
    </div>
  )
}

/**
 * 压平的灰块占位。
 *
 * 🔴 重算态里<b>一个旧数字都不出现</b>:不加删除线、不调透明度、不配「可能不准」小字
 * (`design/h5/H5交互说明.md` S-BLIND 的重算态那一格)。一个带着删除线的旧数字
 * 仍然是一个数字,而用户会读它。
 */
export function Placeholder({ w = '100%', h = 14 }: { w?: string; h?: number }) {
  return <span className="kb-ph inline-block" style={{ width: w, height: `${h}px` }} />
}

/** 常驻说明:告知,关不掉。🔴 与可关闭的提示<b>不合并</b> —— 合并等于把告知做成了可以关掉的东西。 */
export function StandingNote({ children }: { children: ReactNode }) {
  return (
    <div className="border-y border-hair bg-bg2 px-[var(--rule)] py-3">
      <Note>{children}</Note>
    </div>
  )
}

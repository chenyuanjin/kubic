import { useState } from 'react'
import { useDashboard } from '../api/queries'
import { LoginGate } from '../features/LoginGate'
import { Button, Kbd, Note, Tag } from '../ui/primitives'
import { StandingNote } from '../ui/states'

/**
 * 门 —— `M0 首次使用` + `M5 登录门`,合成未登录时唯一能看到的那一份界面。
 *
 * <h2>🔴 它不是一条路由</h2>
 *
 * `多端选型与端矩阵` §4.6.2:「`/login` 这个地址不存在。门只有里外,没有历史 ——
 * 做成路由的话,登录完按一下后退就回到登录页,再点一次又登进去,一个不存在的往返。
 * <b>未登录访问任何 route id,渲染的是门,地址不变;登录完成后原地留在那个地址。</b>」
 * 所以这个组件由 `AppShell` 在 `<Outlet/>` 的位置<b>替换</b>掉整棵路由树,
 * 而不是 `<Navigate to="/login">`。地址栏在整个登录过程里一个字都不变。
 *
 * <h2>🔴 没有「跳过登录」</h2>
 *
 * 2026-09-02 用户裁定「<b>没有本地模式</b>」(`多端选型与端矩阵` §4.6.2 那条注):
 * 未登录能看到的<b>只有</b>门 —— 首启那一屏产品说明 + 登录入口,<b>不给跳过</b>。
 * <p>
 * 这一条推翻了 `App.tsx` 里那个「跳过登录 · 直接看盲区」按钮。那个按钮当时的理由是
 * 「行为层还没有 user_id,这道门此刻挡的是界面不是数据」—— 理由本身没被推翻,
 * 但它不再能压过「没有本地模式」这条产品裁定。<b>一个能被绕开的门在界面上就不是门</b>,
 * 而三端首启第一屏是什么,是这一轮要交的东西。
 *
 * <h2>两步,不是两屏</h2>
 *
 * 第一步是产品说明(`U0.1`,`design/m0/m0-a-intro.html`),第二步才是登录门。
 * 它们共用一个地址、共用这一个组件 —— 说明屏读完按一下就进门,浏览器返回键
 * 在这里没有第二个落点。`U0.1 §7.1`:说明屏<b>零请求零往返</b>,所以它没有空态,
 * 唯一的失败落点是离线,而离线在这一屏上只表现为下面那条「连不上」的说明。
 */
export function IntroScreen({ onDone }: { onDone: () => void }) {
  const [step, setStep] = useState<'intro' | 'gate'>('intro')
  if (step === 'gate') return <LoginGate onDone={onDone} />
  return <Intro onNext={() => setStep('gate')} />
}

/**
 * `U0.1` 首启产品说明屏。
 *
 * 🔴 这一屏是<b>能力边界的第一次露面</b>,而且是唯一一次由产品主动说的。
 * 它必须把「不做什么」说在前面 —— 用户装这个东西之前应该知道它不会替他判断学得怎么样。
 * 界面上把它写成一条常驻说明,不是一个可以关掉的提示。
 */
function Intro({ onNext }: { onNext: () => void }) {
  // 说明屏零请求。这里读 dashboard 只为了拿到「后端通不通」这一个事实,
  // 而它是 AppShell 那一层本来就会拉的同一份缓存 —— 不新增任何一次往返。
  const { data } = useDashboard()
  const offline = data?.source === 'mock'

  return (
    <div className="kb-screen">
      <div className="kb-body">
        <div className="kb-cap px-[var(--rule)] py-12">
          <header className="mb-9">
            <div className="flex items-baseline gap-3">
              <span className="font-mono text-[13px] text-acid">考点盲区</span>
              <span className="text-[12px] text-t3">跨来源记学习</span>
            </div>
            <p className="mt-4 font-mono text-[15px] leading-8 text-tx">盲区 = 骨架层 − 行为层</p>
            <p className="mt-2 max-w-[46ch] text-[12px] leading-6 text-t2">
              一边是这门考试该会的东西,一边是你实际碰过的东西。两边一减,剩下的就是还没碰过的那些。
            </p>
          </header>

          <section className="mb-9">
            <p className="mb-3 font-mono text-[10px] tracking-[0.1em] text-t3 uppercase">它回答三个问题</p>
            <ul className="flex flex-col gap-2 text-[12.5px] leading-6 text-tx">
              <li>· 这个考点<b>有没有</b>碰过</li>
              <li>· 碰过<b>几次</b></li>
              <li>· 上一次是<b>多久前</b></li>
            </ul>
          </section>

          <StandingNote>
            这三个问题之外的它都不回答 —— 它不判断你做得怎么样,不教你这道题,
            也不替你安排下一步该干嘛。学科上的判断留给你自己接的模型,
            这个产品只管把你碰过什么记清楚。
          </StandingNote>

          <section className="mt-9">
            <p className="mb-3 font-mono text-[10px] tracking-[0.1em] text-t3 uppercase">两件事现在就说清楚</p>
            <div className="flex flex-col gap-3">
              <Note>
                <b>你拍的原图只留在这台设备上。</b>不上传、不同步、不共享。
                发给模型识别的那一次是内存里过一趟,不落任何一处云端。
              </Note>
              <Note>
                <b>不存培训机构的内容。</b>只记来源的名字和时间 —— 「粉笔 · 资料分析 L12」这样一行,
                课件、题干、讲义一个字都不进这个产品。
              </Note>
            </div>
          </section>

          {offline ? (
            <div className="mt-8">
              <div className="flex items-center gap-2">
                <Tag tone="warn">离线</Tag>
                <span className="font-mono text-[11px] text-t3">连不上服务端</span>
              </div>
              <p className="mt-2 max-w-[46ch] text-[11.5px] leading-6 text-t2">
                这一屏不需要网络,读到这里没有问题。但登录要 —— 网络回来之后再往下走。
              </p>
            </div>
          ) : null}

          <div className="mt-10 flex items-center gap-3">
            <Button variant="primary" size="lg" onClick={onNext}>
              知道了,去登录
            </Button>
            <span className="flex items-center gap-2 font-mono text-[11px] text-t3">
              <Kbd>↵</Kbd> 继续
            </span>
          </div>

          <p className="mt-6 text-[11px] leading-6 text-t3">
            没有单独的注册页 —— 号码没见过就建账号,见过就登进去。
          </p>
        </div>
      </div>
    </div>
  )
}

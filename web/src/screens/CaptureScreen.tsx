import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { useDashboard } from '../api/queries'
import { CaptureSheet } from '../features/CaptureSheet'
import { routeTo } from '../routes/routes'
import type { RouteId } from '../routes/routes'
import { ColL, ColR, Cols, ScreenBody, ScreenHead } from '../ui/layout'
import { Button, GroupHeader, Note, Tag } from '../ui/primitives'
import { EmptyState, FailureBlock, Placeholder, StandingNote } from '../ui/states'
import { RecordRow } from './RecordsScreen'

/** 采集的四种形式。`capture.photo` 在小程序上<b>没有这条路由</b>(§3.6.1),本端四条都有。 */
const MODES = [
  { id: 'capture.text' as RouteId, seg: 'text', label: '写', hint: '粘一段 / 自己敲一句' },
  { id: 'capture.audio' as RouteId, seg: 'audio', label: '说', hint: '录一段,转写完再挂考点' },
  { id: 'capture.photo' as RouteId, seg: 'photo', label: '拍', hint: '拍讲义或屏幕,原图只留本机' },
]

/**
 * `M1 记录` —— 采集入口 `/capture` 与三种形式 `/capture/{text,audio,photo}`。
 *
 * <h2>它从浮层变成了地址</h2>
 *
 * 改动之前这一屏是 `MainScreen` 里的 `captureOpen` 状态。`多端选型与端矩阵` §4.6.2:
 * 「<b>覆盖层也算页面。</b>采集、问 AI 现在是 `useState` 开的浮层;它们要有 URL,
 * 因为『默认用 URL 都能跳转』的意思就是每一个用户能看见的东西都能被一个地址指到。
 * <b>浮层的视觉形态不变,变的是它由谁开。</b>」
 * <p>
 * 所以 {@link CaptureSheet} 一个字没改 —— 换的只是谁把它挂上来。
 *
 * <h2>🔴 拍照那一格上的红线,在网页上的落法与 App 不一样</h2>
 *
 * `design/h5/H5交互说明.md` 的原话:「App 有应用内相机,可以在快门与落盘之间插一屏;
 * 网页没有 —— `input[capture]` 一按,系统相机拍完图<b>立刻</b>进页面。
 * 所以<b>同意那一行必须排在「拍一张」上面,且未勾选时按钮不可按</b>。
 * 顺序反了,『挡在收图之前』这条红线在网页上就等于没有。」
 *
 * <h2>四态</h2>
 *
 * 主态 = 三格入口;空态 = 骨架层还没建(没有考点就挂不上);
 * 失败态 = 拉树失败,只空这一屏、可切走;受限态 = `QUOTA_EXHAUSTED`,
 * 🔴 <b>它不是失败</b> —— 记录照样记得下,少的只是自动挂考点那一步。
 *
 * <h2>≥1024 双列 —— 稿 `design/m1/07-ipad.html`</h2>
 *
 * 稿上左栏是时间线、右栏是记一条。稿自己在第 3 行与第 21 行写明了左栏的性质:
 * 「左栏是内容(今天的时间线,随筛选整栏换掉)」「左栏:内容,不是菜单 —— 时间线<b>回看</b>」。
 *
 * 🔴 <b>左栏是只读的,这是落地时钉死的三条约束之一</b>(后端与AI打标 2026-09-05 裁定):
 * <ol>
 * <li>左栏<b>没有任何写入入口</b> —— 不可编辑、不可删、不可改标签。稿上那三个动作
 *     (「自己挑考点」「手动挂」「就这样留着」)<b>本轮不落</b>;行不给 `onOpen`,
 *     于是 `Row` 渲染成 `div`,连焦点都拿不到。</li>
 * <li><b>`/records` 仍是记录的唯一归属</b>,这一栏不引入第二套记录状态 ——
 *     它读的就是 `useDashboard()` 那一份 `records`,栏底那条链接直接指回 `/records`。
 *     `RecordsScreen` 注释里那条代价(「一条记录在哪一屏变成一个需要判断的问题」)
 *     成立的前提是两屏都能对记录<b>做事</b>;一个只读的尾巴不制造这个问题。</li>
 * <li>不引入新几何,复用 `Cols`/`ColL`/`ColR`。</li>
 * </ol>
 * <p>
 * ⚪ 左栏<b>只在 ≥1024 出现</b>(`hidden wide:flex`):`07-ipad.html` 是横屏稿,
 * 而 M1 的窄屏稿(`design/m1/01-write.html`~`06-timeline.html`)上<b>没有</b>这一栏。
 * 让它在 iPhone 上跟着堆下来,等于给窄屏加了一段稿上没有的内容。
 * <p>
 * 🔴 更正:上一轮这里引的是 `design/m1/07.html`,<b>那个文件不存在</b>(`ls design/m1/` 只有
 * `01-write` `02-photo` `03-voice` `04-quiz` `05-quota` `06-timeline` `07-ipad`)。
 * 判断本身仍然成立,但当时的依据是空的 —— `git show <不存在的路径> | grep -c` 也会回 0。
 */
export function CaptureScreen() {
  const { data, isPending, refetch } = useDashboard()
  const navigate = useNavigate()
  const params = useParams<{ '*'?: string }>()
  const seg = params['*'] ?? ''
  const mode = MODES.find((m) => m.seg === seg) ?? null

  /**
   * 🔴 收图之前的那一次同意。它在 `photo` 这一格上是<b>前置条件</b>,不是一条提示。
   * 未勾选时「拍一张」不可按 —— 这是这条红线在网页上唯一的落法。
   */
  const [agreed, setAgreed] = useState(false)

  if (isPending || !data) {
    return (
      <>
        <ScreenHead title={<Placeholder w="8ch" />} />
        <ScreenBody>
          <div className="flex flex-col gap-3 px-[var(--rule)] py-6">
            <Placeholder h={44} />
            <Placeholder h={44} />
            <Placeholder h={44} />
          </div>
        </ScreenBody>
      </>
    )
  }

  if (data.summary.total === 0) {
    return (
      <>
        <ScreenHead title="记一笔" />
        <ScreenBody>
          <EmptyState
            title="还没有可以挂的考点"
            body="记录要挂在骨架层的一个考点上,而这个科目的树还是空的。先把题型与考点建起来。"
            action={{ label: '去考点树', onClick: () => void navigate(routeTo('syllabus')) }}
          />
        </ScreenBody>
      </>
    )
  }

  return (
    <>
      <ScreenHead
        title="记一笔"
        sub={data.subject.display}
        right={<Tag>{data.source === 'mock' ? '离线示例' : '已连接'}</Tag>}
      />

      <ScreenBody>
        <Cols>
          {/* 左栏 = 时间线回看。只读,且只在 ≥1024 出现 —— 见上面那三条约束。 */}
          <ColL>
            <div className="hidden min-h-0 flex-1 flex-col overflow-y-auto wide:flex">
              <GroupHeader title="时间线" right={`${data.records.length}`} />
              {data.records.length === 0 ? (
                <p className="px-[var(--rule)] py-4 text-[12px] leading-6 text-t3">
                  还没有记录。右边记完一条,它就出现在这儿 —— 这一栏只回看,不在这里改。
                </p>
              ) : (
                data.records.map((r) => <RecordRow key={r.id} item={r} />)
              )}
              <div className="px-[var(--rule)] py-3">
                <button
                  type="button"
                  onClick={() => void navigate(routeTo('records'))}
                  className="font-mono text-[11px] text-t3 underline hover:text-tx"
                >
                  看全部记录
                </button>
              </div>
            </div>
          </ColL>

          <ColR>
          <StandingNote>
            记的是<b>你碰过什么</b>:考点 + 来源的名字 + 形式。
            粘进来的文字、录音、原图<b>都不进请求体</b> —— 库里没有能装下它们的字段。
          </StandingNote>

          <div className="flex flex-col">
            {MODES.map((m) => (
              <button
                key={m.seg}
                type="button"
                onClick={() => void navigate(routeTo(m.id))}
                className={`flex min-h-[56px] w-full items-center gap-4 border-b border-hair px-[var(--rule)] text-left ${
                  mode?.seg === m.seg ? 'bg-sel' : 'hover:bg-bg2'
                }`}
              >
                <span className="w-[3ch] font-mono text-[13px] text-acid">{m.label}</span>
                <span className="text-[12.5px] text-t2">{m.hint}</span>
              </button>
            ))}
          </div>

          {mode?.seg === 'photo' ? <PhotoConsent onAgree={() => setAgreed(true)} /> : null}

          {data.source === 'mock' ? (
            // 后端不可达 = 记不下去。🔴 这里不假装已经存下了:
            // 「记录动作永不失败」靠的是服务端先落地,而离线队列还没接。
            <FailureBlock code={null} scope="记一笔" onRetry={() => void refetch()} />
          ) : null}
          </ColR>
        </Cols>
      </ScreenBody>

      {/* 浮层的视觉形态不变,变的是它由谁开:现在是地址开的,关掉就回到 /capture。 */}
      {mode !== null && (mode.seg !== 'photo' || agreed) ? (
        <CaptureSheet
          groups={data.groups}
          records={data.records}
          initialNodeCode={data.blindspots[0]?.code ?? null}
          source={data.source}
          onClose={() => void navigate(routeTo('capture'))}
        />
      ) : null}
    </>
  )
}

/**
 * `U0.2` 权限申请时机与话术 —— 网页上它是收图<b>之前</b>的一次同意。
 *
 * 🔴 三处留存表达之一,与 `design/m1/02-photo.html` 逐字同一套话:
 * ① 原图只在这台设备上;② 发给模型的那一次是内存里过一趟;③ 到期进留存区。
 * `U6.1 §6.5` 要求这三处在桌面壳 / 移动 App / Web 三处齐全,不因端而弱化 ——
 * Web 独有的是<b>多</b>一块「浏览器可能清理」的告知,不是<b>少</b>一处承诺。
 */
function PhotoConsent({ onAgree }: { onAgree: () => void }) {
  /* 🔴 勾选只解锁按钮,不等于同意 —— 同意发生在按下「拍一张」那一刻。
     改这一处之前,闸长在 checkbox 上而按钮没有 onClick:勾上的同一帧浮层就挂上来把它盖住,
     那颗全屏唯一的主按钮从来没有被按到过。注释写的是按钮是闸,代码做的是勾选是闸。 */
  const [checked, setChecked] = useState(false)
  return (
    <section className="border-b border-hair px-[var(--rule)] py-4">
      <p className="mb-3 font-mono text-[10px] tracking-[0.1em] text-t3 uppercase">收图之前</p>

      <div className="flex flex-col gap-2.5">
        <Note>
          <b>原图只留在这台设备上。</b>不上传、不同步、不共享 —— 库里也没有一个字段能存下它。
        </Note>
        <Note>
          <b>发给模型识别的那一次是内存里过一趟。</b>不落盘、不进日志、不寄存在任何一家供应商那儿。
        </Note>
        <Note>
          <b>14 天后原图进留存区。</b>到期这件事在浏览器里只在你打开时推进 ——
          桌面壳里它是真的在走。这一条两个端不一样,不为了统一而抹平。
        </Note>
        <Note warn>
          浏览器可能清理掉这些图:换设备、清站点数据、隐私模式都会。
          这是网页这个端上多出来的一条,不是承诺打了折。
        </Note>
      </div>

      <label className="mt-4 flex items-start gap-2.5 text-[12px] leading-6 text-tx">
        <input
          type="checkbox"
          checked={checked}
          onChange={(e) => setChecked(e.target.checked)}
          className="mt-[5px] size-[14px] shrink-0 accent-[var(--color-acid)]"
        />
        <span>上面四条我看过了</span>
      </label>

      <div className="mt-3">
        {/* 🔴 未勾选时它不可按。灰掉不是装饰 —— 系统相机一按就把图交给页面了,
            这个按钮是那之前唯一的一道闸。 */}
        <Button variant="primary" disabled={!checked} onClick={onAgree}>
          拍一张
        </Button>
        {!checked ? (
          <p className="mt-2 font-mono text-[11px] text-t3">先看完上面四条,这颗按钮才会亮。</p>
        ) : null}
      </div>
    </section>
  )
}

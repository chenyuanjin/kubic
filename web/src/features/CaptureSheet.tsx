import { useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { useCreateRecord } from '../api/queries'
import type { DataSource, GroupView, TimelineItemDto, TouchKind } from '../api/types'
import { KIND_LABEL } from '../lib/nodeState'
import { Button, GroupHeader, GlyphIcon, Kbd, Note, Tag } from '../ui/primitives'

/**
 * 记一笔 —— 四个入口一屏全露,不做二级菜单。
 *
 * <h2>为什么四个入口挤在一屏</h2>
 *
 * 记录这个动作必须在两秒内完成。任何一次「先点开某个菜单」都会让人放弃记,
 * 而放弃记 = 行为层缺失 = 差集失真。密度在这里是功能,不是审美。
 *
 * <h2>🔴 四个入口的产物是同一种东西</h2>
 *
 * <b>考点 + 来源名 + 时间戳 + 形式</b>,「记做题」多两个用户自己填的整数。
 * 粘进来的文字、录音、原图<b>都不在请求体里</b> —— CreateRecordRequest 里没有它们的位置。
 * 文字只用来帮用户挑考点,挑完就没用了。
 *
 * <h2>🔴 语音与拍照当前是「未接入」,不是「即将上线」</h2>
 *
 * 后端识别链路还是 stub。把入口画出来但明确置灰并标注,好过做一个点下去转圈然后失败的按钮。
 */
export function CaptureSheet({
  groups,
  records,
  initialNodeCode,
  source,
  onClose,
}: {
  groups: GroupView[]
  records: TimelineItemDto[]
  initialNodeCode: string | null
  source: DataSource
  onClose: () => void
}) {
  const nodes = useMemo(() => groups.flatMap((g) => g.nodes), [groups])
  const knownSources = useMemo(() => [...new Set(records.map((r) => r.sourceName))], [records])

  const [nodeCode, setNodeCode] = useState(initialNodeCode ?? nodes[0]?.code ?? '')
  const [sourceName, setSourceName] = useState('')
  const [pasted, setPasted] = useState('')
  const [practiced, setPracticed] = useState('')
  const [correct, setCorrect] = useState('')

  const create = useCreateRecord()
  const node = nodes.find((n) => n.code === nodeCode)

  const p = toInt(practiced)
  const c = toInt(correct)
  const hasDrill = p !== null && p > 0

  // 形式由「哪个入口有内容」推出,不额外做一个单选框让用户再选一次
  const kind: TouchKind = hasDrill ? 'DRILL' : pasted.trim() ? 'PASTE' : 'MANUAL'

  const problem = validate({ nodeCode, sourceName, practiced, correct, p, c })

  /**
   * 🔴 请求体只有这五个字段,而且做题数是<b>扁平的 practiced / correct</b>。
   *
   * 服务端开了 `FAIL_ON_UNKNOWN_PROPERTIES=true`(R-07 的第二道锁),多带一个字段就是
   * `UNKNOWN_FIELD` 400。所以这里既不传 `occurredAt`(时间戳由服务端按 Clock 打,
   * 客户端自报会让「生疏」变成能被随手改掉的状态),也不传嵌套的 `drill`。
   * <p>
   * 两个数<b>要么都给,要么都不给</b>:只给 practiced 会让服务端替用户把 correct 填成 0,
   * 凭空造出一个「全错」的记录 —— 而那正好会把这个考点判成「弱」。
   */
  function submit() {
    if (problem || create.isPending) return
    create.mutate(
      {
        kind,
        sourceName: sourceName.trim(),
        nodeCode,
        ...(hasDrill ? { practiced: p, correct: c ?? 0 } : {}),
      },
      { onSuccess: onClose },
    )
  }

  return (
    <div className="fixed inset-0 z-50">
      <button
        type="button"
        aria-label="关闭记一笔"
        onClick={onClose}
        className="absolute inset-0 w-full cursor-default bg-[rgba(6,7,8,0.72)]"
      />

      {/* 面板本身不滚动:头(记到哪个考点)和脚(记下/取消)钉住,只有中间四个入口滚。
          「记下」永远在同一个位置 —— 记录这个动作要在两秒内完成,不能先去找按钮。
          用 dvh 不用 vh:手机浏览器的地址栏会伸缩,vh 取的是最大高度,
          于是「记下」那一排正好被地址栏盖住 —— 而它是这一屏唯一必须够得着的东西。 */}
      <div
        className="absolute top-2 left-1/2 flex max-h-[calc(100dvh-16px)] w-[calc(100vw-16px)] -translate-x-1/2 flex-col overflow-hidden rounded-sm border border-hair2 bg-bg sm:top-[64px] sm:max-h-[calc(100dvh-96px)] sm:w-[min(680px,calc(100vw-32px))]"
        onKeyDown={(e) => {
          if (e.key === 'Escape') {
            e.stopPropagation()
            onClose()
          }
          if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
            e.preventDefault()
            submit()
          }
        }}
      >
        <div className="flex h-[38px] shrink-0 items-center gap-[9px] border-b border-hair bg-bg2 px-3">
          <span aria-hidden className="size-[13px] shrink-0 rounded-xs border border-t3" />
          <span className="shrink-0 font-mono text-[12px] whitespace-nowrap text-t2">
            记一笔 / <b className="font-medium text-acid">{node?.name ?? '未选考点'}</b>
          </span>
          <span className="shrink-0 text-t3">·</span>
          <span className="min-w-0 flex-1 truncate text-[12.5px] text-t3">记完先落地,识别慢慢补</span>
          <Kbd>esc</Kbd>
        </div>

        {/* ── 挂到哪个考点 ───────────────────────────────────────────────── */}
        <GroupHeader title="挂到哪个考点" right="只能从树里选" />
        <div className="flex shrink-0 flex-wrap gap-3 px-4 py-3">
          <div className="min-w-[220px] flex-1">
            <Field>
              <span className="border-r border-hair pr-[9px] font-mono text-[12px] text-t2">考点</span>
              {/* 🔴 R-07 在 UI 层的形态:这个控件<b>打不出</b>树里没有的 code。
                  不是校验拦住了自由文本,是根本没有能输入自由文本的地方。 */}
              <select
                value={nodeCode}
                onChange={(e) => setNodeCode(e.target.value)}
                className="min-w-0 flex-1 appearance-none bg-transparent text-[13px]"
              >
                {groups.map((g) => (
                  <optgroup key={g.code} label={g.name}>
                    {g.nodes.map((n) => (
                      <option key={n.code} value={n.code}>
                        {n.name}
                      </option>
                    ))}
                  </optgroup>
                ))}
              </select>
              <span
                aria-hidden
                className="block size-0 border-t-[5px] border-r-[4px] border-l-[4px] border-t-t3 border-r-transparent border-l-transparent"
              />
            </Field>
          </div>
          <div className="min-w-[220px] flex-1">
            <Field focused={sourceName.trim().length > 0}>
              <span className="border-r border-hair pr-[9px] font-mono text-[12px] text-t2">来源</span>
              <input
                value={sourceName}
                onChange={(e) => setSourceName(e.target.value)}
                list="known-sources"
                placeholder="粉笔 · 资料分析系统班 L12"
                className="min-w-0 flex-1 bg-transparent text-[13px] placeholder:text-t3"
              />
              <datalist id="known-sources">
                {knownSources.map((s) => (
                  <option key={s} value={s} />
                ))}
              </datalist>
            </Field>
          </div>
        </div>

        {/* ── 四个入口:一屏全露,不做二级菜单。这一段是面板里唯一会滚的部分 ── */}
        <div className="min-h-0 flex-1 overflow-y-auto">
          {/* ── 入口一:粘一段 ─────────────────────────────────────────────── */}
          <GroupHeader title="粘一段文字" right="⌘V" />
          <div className="px-4 py-3">
            <textarea
              value={pasted}
              onChange={(e) => setPasted(e.target.value)}
              rows={3}
              placeholder="粘一段。课件上的一段话、一道题、你自己敲的两行都行。"
              className="w-full resize-none rounded-sm border border-hair bg-bg3 px-[11px] py-[9px] text-[12.5px] leading-[1.75] placeholder:text-t3"
            />
            {/* 常驻,不是弹窗提示 */}
            <div className="mt-2">
              <Note>
                这段文字<b className="font-normal text-t2">不会被存下来</b>
                ,它只用来帮你挑考点。
                <br />
                落进记录的只有:考点 + 来源名 + 时间 + 形式。
              </Note>
            </div>
          </div>

          {/* ── 入口二:拍照(未接入) ────────────────────────────────────────
              🔴 这里是「你自己截好的图、自己拖进来」,不是「替你去截屏」。
              设计稿 H12 那屏画过「边看课边记 · ⌥S 截屏记」——<b>那条不做</b>:
              iPad 沙箱不允许录别的 App 的声音或画面,桌面端要系统级录屏权限,
              而这个产品不该为了记一笔去要那种权限。用户自己切屏、自己截图再导进来,可以;
              「替你听、替你截」,不行。所以这里只有一个接收区,没有任何「开始捕捉」。 */}
          <GroupHeader title="拖张图进来" right="你自己截的图" />
          <div className="px-4 py-3">
            <div className="flex h-[62px] flex-col items-center justify-center gap-1.5 rounded-sm border border-dashed border-hair2 bg-bg3 text-[12px] text-t3 opacity-55">
              <GlyphIcon kind="image" />
              <span className="inline-flex items-center gap-2">
                把截图或照片拖到这儿 <Tag tone="warn">未接入</Tag>
              </span>
            </div>
            <div className="mt-2">
              <Note warn>
                识别链路还是 stub,拖进来也不会有反应 —— 这里不做假的进度条。
                <br />
                接通之后的规矩已经定死:原图 base64 送识别一次即弃,
                <b className="font-normal text-red">不上云、不共享、不长期存储</b>。
                <br />
                <b className="font-normal text-t2">不会去监听你的截图目录,也不会录别的 App 的屏。</b>
                图只能你自己拖进来。
              </Note>
            </div>
          </div>

          {/* ── 入口三:记做题 ───────────────────────────────────────────────
              右边原本写着「⌘L」,但 ⌘L 从来没被绑过,而且在浏览器里它是「跳到地址栏」——
              一个按下去必然不响应的徽标比不画更糟(骨架规则 9 的反面)。所以换成说明。 */}
          <GroupHeader title="记做题" right="你自己填的两个整数" />
          <div className="flex flex-wrap items-center gap-3 px-4 py-3">
            <div className="w-[calc(50%-6px)] sm:w-[150px]">
              <Field>
                <span className="border-r border-hair pr-[9px] font-mono text-[12px] text-t2">练</span>
                <input
                  value={practiced}
                  onChange={(e) => setPracticed(e.target.value)}
                  inputMode="numeric"
                  placeholder="0"
                  className="min-w-0 flex-1 bg-transparent font-mono text-[13px] tabular-nums placeholder:text-t3"
                />
                <span className="text-t3">道</span>
              </Field>
            </div>
            <div className="w-[calc(50%-6px)] sm:w-[150px]">
              <Field>
                <span className="border-r border-hair pr-[9px] font-mono text-[12px] text-t2">对</span>
                <input
                  value={correct}
                  onChange={(e) => setCorrect(e.target.value)}
                  inputMode="numeric"
                  placeholder="0"
                  className="min-w-0 flex-1 bg-transparent font-mono text-[13px] tabular-nums placeholder:text-t3"
                />
                <span className="text-t3">道</span>
              </Field>
            </div>
            <span className="text-[11.5px] text-t3">
              这两个数原样存下来。产品不判题、不给分,正确率就是它们相除。
            </span>
          </div>

          {/* ── 入口四:语音(未接入) ────────────────────────────────────────
              🔴 录的是<b>你对着麦克风说的话</b>,不是设备里正在播的课。
              「同屏录别的 App 的声音」在 iPad 上被沙箱挡死,在桌面端要系统级权限 —— 不做。
              标题原本写「按住 ⌥ 说话,松开就存」,那是一句<b>行为承诺</b>,
              而 ASR 还是 stub、⌥ 也没绑 —— 一个不成立的承诺比一句「未接入」贵得多。 */}
          <GroupHeader title="语音" right="未接入" />
          <div className="flex flex-wrap items-center gap-3.5 px-4 py-3">
            <div className="flex h-5 items-end gap-[2px] opacity-40" aria-hidden>
              {[5, 11, 17, 8, 20, 13, 6, 15, 19, 9, 4, 12, 18, 7, 14, 10, 16, 5, 11, 8].map((h, i) => (
                <i key={i} style={{ height: `${h}px` }} className="block w-[2px] bg-t3" />
              ))}
            </div>
            <Tag tone="warn">未接入</Tag>
            <div className="min-w-[220px] flex-1">
              <Note>
                ASR 还没接,现在按任何键都不会开始录。
                <br />
                接通之后录的也只是<b className="font-normal text-t2">你自己说的话</b> ——
                不录设备里正在播的课,那需要的权限这个产品不打算要。
              </Note>
            </div>
          </div>
        </div>

        {/* ── 落地 ───────────────────────────────────────────────────────── */}
        <div className="shrink-0 border-t border-hair px-4 py-3">
          <div className="flex flex-wrap items-center gap-2.5">
            <span className="text-[11.5px] text-t3">将记为</span>
            <Tag tone="on">{KIND_LABEL[kind]}</Tag>
            {hasDrill && (
              <Tag>
                {p}/{c ?? 0}
              </Tag>
            )}
            <span className="font-mono text-[11px] text-t3">
              {node?.name ?? '—'} · {sourceName.trim() || '(来源未填)'} · 现在
            </span>
            <span className="ml-auto flex items-center gap-[9px]">
              <Button onClick={onClose}>
                取消 <Kbd>esc</Kbd>
              </Button>
              <Button variant="primary" disabled={problem !== null || create.isPending} onClick={submit}>
                {create.isPending ? '记下中…' : '记下'} <Kbd tone="dark">⌘↵</Kbd>
              </Button>
            </span>
          </div>

          <div className="mt-3">
            {problem ? (
              <Note warn>{problem}</Note>
            ) : create.isError ? (
              <Note warn>
                没记下来:
                {create.error instanceof Error ? create.error.message : String(create.error)}
                <br />
                离线队列(阶段 2)还没接,所以这一笔
                <b className="font-normal text-red">确实没有落地</b>, 不会自己补上 —— 后端起来之后重记一次。
              </Note>
            ) : source === 'mock' ? (
              <Note warn>
                当前显示的是离线示例数据,后端不可达。现在点「记下」会失败,而且会如实告诉你失败了。
              </Note>
            ) : (
              <Note>记完立刻落地。识别认不出考点也不会把整条记录丢掉。</Note>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

function Field({ children, focused = false }: { children: ReactNode; focused?: boolean }) {
  return (
    <div
      className={`flex h-[34px] items-center gap-[9px] rounded-sm border bg-bg3 px-[11px] text-[13px] ${
        focused ? 'border-acid' : 'border-hair'
      }`}
    >
      {children}
    </div>
  )
}

function toInt(raw: string): number | null {
  const s = raw.trim()
  if (!s) return null
  return /^\d+$/.test(s) ? Number(s) : Number.NaN
}

/**
 * 校验 —— 逐条对着 server 侧 Touch / Touch.Drill 的构造器。
 *
 * 前端拦一次只是为了少一次往返,后端那两个 `IllegalArgumentException` 才是真正的闸门。
 */
function validate(f: {
  nodeCode: string
  sourceName: string
  practiced: string
  correct: string
  p: number | null
  c: number | null
}): string | null {
  if (!f.nodeCode) return '必须挂到一个考点上。'
  if (!f.sourceName.trim()) return '来源名不能空 —— 一条记录的全部内容就是「哪个考点、哪个来源、什么时候」。'
  if (f.p !== null && Number.isNaN(f.p)) return '「练」只能填非负整数。'
  if (f.c !== null && Number.isNaN(f.c)) return '「对」只能填非负整数。'
  if (f.p !== null && f.c !== null && f.c > f.p) return `对的题数不能多于练的题数:${f.c} > ${f.p}。`
  if (f.p === null && f.c !== null && f.c > 0) return '填了「对」就要填「练」。'
  return null
}

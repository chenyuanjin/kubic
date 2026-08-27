import { useMemo } from 'react'
import type { NodeView, TimelineItemDto } from '../api/types'
import { blindReason, pad2, relativeDay } from '../lib/format'
import { Button, GroupHeader, Kbd, Note, Row } from '../ui/primitives'

/**
 * 「先补这几个」+ 来源列表 + 那句能力边界。
 *
 * 「先补这几个」的排序分 = 近五年频次 × 状态权重。两个因子都在能力边界内:
 * 频次是真题统计事实,状态由「有没有 / 几次 / 多久前」推出。
 * <b>没有一处需要知道某道题的答案。</b>
 *
 * <h2>窄屏:从右侧副栏变成主屏下面的一段,<b>不是折叠掉</b></h2>
 *
 * 早先这里是 `hidden … xl:flex` —— 手机上「先补这几个」直接不存在。
 * 那是把这个产品唯一的<b>回答</b>藏了起来:主屏回答「差了哪些」,这一段回答「先补哪个」,
 * 后者才是用户真正要拿走的东西。所以窄屏只改位置(右侧 → 下方)、只改分隔线方向
 * (border-l → border-t),不改有没有。
 */
export function BlindSpotSide({
  blindspots,
  records,
  selectedCode,
  onSelect,
  onAskAi,
}: {
  blindspots: NodeView[]
  records: TimelineItemDto[]
  selectedCode: string | null
  onSelect: (code: string) => void
  onAskAi: () => void
}) {
  // 来源:只有名字和最近一次时间。结构上就没有放这个来源的内容的地方。
  const sources = useMemo(() => {
    const latest = new Map<string, string>()
    for (const r of records) {
      const seen = latest.get(r.sourceName)
      if (!seen || Date.parse(r.occurredAt) > Date.parse(seen)) latest.set(r.sourceName, r.occurredAt)
    }
    return [...latest.entries()]
      .map(([name, at]) => ({ name, at }))
      .sort((a, b) => Date.parse(b.at) - Date.parse(a.at))
  }, [records])

  return (
    <aside className="flex shrink-0 flex-col border-t border-hair xl:w-[300px] xl:border-t-0 xl:border-l">
      {/* xl 起只有榜单和来源列表滚动,底下那两句能力边界钉住不许被滚出屏幕;
          窄屏整段跟着主屏一起滚 —— 那时候它本来就在一屏之外,钉住反而会吃掉半个屏。 */}
      <div className="xl:min-h-0 xl:flex-1 xl:overflow-y-auto">
        <GroupHeader title="先补这几个" right="频次 × 生疏度" />
        {/* 🔴 名次与排序分都是服务端算的(rank / blindScore)。前端不留第二份权重表 ——
            「先补这几个」如果每个客户端都能自己重排,它就不再是一个回答。
            服务端返回的是有序前缀,所以切前 5 == 请求 top=5。 */}
        {blindspots.slice(0, 5).map((node) => (
        <button
          key={node.code}
          type="button"
          onClick={() => onSelect(node.code)}
          // w-full 不能省:<button> 的默认宽度是 fit-content。在 300px 侧栏里内容正好撑满,
          // 看不出来;窄屏一变宽,发丝线就只画到文字末尾,整段看起来像没对齐的碎块。
          className={`flex h-[66px] w-full shrink-0 items-center gap-2.5 border-b border-hair px-3 text-left ${
            node.code === selectedCode ? 'bg-sel shadow-[inset_2px_0_0_var(--color-acid)]' : 'hover:bg-bg2'
          }`}
        >
          {/* 酸性绿只给第一名的序号 —— 一屏用超过一次就说明用错了 */}
          <span
            className={`w-[18px] shrink-0 font-mono text-[12px] tabular-nums ${node.rank === 1 ? 'text-acid' : 'text-t3'}`}
          >
            {pad2(node.rank ?? 0)}
          </span>
          <span className="min-w-0 flex-1">
            <span className="block truncate">{node.name}</span>
            <span className="mt-0.5 block truncate font-mono text-[11px] text-t3">{blindReason(node)}</span>
          </span>
          <span className="shrink-0 font-mono text-t2 tabular-nums">{(node.blindScore ?? 0).toFixed(1)}</span>
        </button>
        ))}

        {/* 🔴 只有来源【名字】和最近一次时间。这一列结构上就没有放该来源内容的地方。 */}
        <GroupHeader title="来源" right={`${sources.length} · 最近一次`} />
        {sources.map((s) => (
          <Row key={s.name}>
            <span className="min-w-0 flex-1 truncate text-t2">{s.name}</span>
            <span className="w-[54px] shrink-0 text-right font-mono text-[11.5px] text-t3 tabular-nums">
              {relativeDay(s.at)}
            </span>
          </Row>
        ))}
      </div>

      <div className="shrink-0 border-t border-hair p-4">
        {/* 这两句是产品的能力边界本身,不是免责声明 —— 常驻、不折叠、不随列表滚走 */}
        <Note>
          不判断对错。正确率是你自己填的数。
          <br />
          这里只说有没有、几次、多久前。
        </Note>
        <div className="mt-3">
          <Button block onClick={onAskAi} title="⌘J · 组装并复制 —— 尚未接入">
            问 AI 时带上的东西 <Kbd>⌘J</Kbd>
          </Button>
        </div>
      </div>
    </aside>
  )
}

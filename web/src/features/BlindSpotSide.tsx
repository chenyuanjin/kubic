import { useMemo } from 'react'
import type { NodeView, TimelineItemDto } from '../api/types'
import { myFact, pad2, relativeDay } from '../lib/format'
import { Button, GroupHeader, Kbd, Note, Row } from '../ui/primitives'

/**
 * 「先补这几个」+ 来源列表 + 那句能力边界。
 *
 * 排序由服务端给(`rank`)。排序分是<b>近五年出现次数 × 一个只由「碰没碰过 / 多久前」
 * 决定的系数</b>——频次是真题统计事实,系数那一半只看有没有记录、多久前,两半都不判对错。
 * <b>没有一处需要知道某道题的答案。</b>
 * <p>
 * 🔴 这里原本写的是「排序分 = 近五年频次 × 状态权重」,并且把这个公式当表头、
 * 把那个数本身当行尾一列上了屏。两样都删于 2026-09-06(`KUBI-111`):
 * 公式里的「状态权重」落到界面上就是「频次 × 生疏度」,而生疏度是五档状态里的一档。
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
    /* 🔴 2026-09-05(`KUBI-113`):`xl:w-[300px] xl:border-l` 去掉了。
       那两个类是上一版「主列 + 右侧栏」那套布局的一部分,而排布现在归屏管
       (`screens/CoverageScreen.tsx` 的 `Cols`),断点也从 xl(1280)换成了
       U6.1 定的 1024。留着它们的后果实测过:在 480px 的左栏里,这块会和考点表
       挤成两个子列,两边都只剩一半宽 —— 那不是「密度」,那是没画完。 */
    <aside className="flex shrink-0 flex-col border-t border-hair">
      {/* xl 起只有榜单和来源列表滚动,底下那两句能力边界钉住不许被滚出屏幕;
          窄屏整段跟着主屏一起滚 —— 那时候它本来就在一屏之外,钉住反而会吃掉半个屏。 */}
      <div className="xl:min-h-0 xl:flex-1 xl:overflow-y-auto">
        {/* 🔴 2026-09-06(`KUBI-111`):右边那行从「频次 × 生疏度」换成排序口径本身 ——
            旧的那行把排序分公式写在了表头上,而「生疏度」是五档状态里的一档,
            界面上等于宣布产品在给用户的状态打分。

            🔴 但那次换成的「按近五年出现次数排」是假的,同轮裁定后改成现在这句。
            服务端的排序分是 `recent5yCount × weightOf(state)`
            (`CoverageService.java:44-50` / `:99`),而 `weightOf` 不是常量。判据钉在这里:
            <b>屏上「按出现次数排」为真 ⟺ `weightOf` 是常量</b> —— 那张表里只要有两个
            不同的值这句就是假的,与表里有没有对错判断无关。

            口径:排序 = 近五年出现次数 × 一个只由「碰没碰过 / 多久前」决定的系数。
            屏上说人话、不写公式 —— 把因子换成事实词并不改变「拿公式当表头」这件事,
            那正是上面摘掉「频次 × 生疏度」的理由。

            ⚠️ 与稿有意分歧,登记在此不改稿:`design/m3/01-blind.html:45` 那个口径行
            是纯次数(「按近 5 年出现次数排」);服务端不是纯次数,所以屏上跟服务端走。
            (旧注释指的 `:44` 是那行上面的注释行,顺手改对。) */}
        <GroupHeader title="先补这几个" right="按近五年出现次数排,没碰过的往前提" />
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
          {/* 稿的一行是上下两行:上行骨架事实(真题的),下行我的事实(你的)。
              分开是硬性的 —— `design/m3/01-blind.html:47-54`。 */}
          <span className="min-w-0 flex-1">
            <span className="block truncate">
              {node.name}
              <span className="ml-2.5 font-mono text-[11px] text-t3 tabular-nums">
                近五年 {node.recent5yCount} 次
              </span>
            </span>
            <span className="mt-0.5 block truncate text-[11.5px] text-t2">{myFact(node)}</span>
          </span>
          {/* 🔴 2026-09-06(`KUBI-111`):行尾那个 `6.4` 删掉。它是服务端的排序分
              (blindScore)直接上屏 —— 一个连续数值摆在每个考点后面,读出来就是给这个
              考点评了级。排序口径已经在表头写明,不需要把那个数
              本身摆给用户看;稿上也没有这一列。`blindScore` 字段是契约,前端不删,只是不显示。 */}
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
          不判断对错。练了几道、对了几道,都是你自己填的数。
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

import { useEffect, useMemo, useRef } from 'react'
import type { ReactNode } from 'react'
import type { GroupView, NodeView } from '../api/types'
import { isTouched, pad2, relativeDay } from '../lib/format'
import { GroupHeader, Row, StateDot } from '../ui/primitives'

/**
 * 18 个考点的分组密集行 —— 这一屏的主体。
 *
 * <h2>为什么要挤到一屏</h2>
 *
 * 一屏看全 18 个考点是这个产品的核心体验:差集要能<b>一眼</b>看见,少一行就少一分说服力。
 * 所以行高 29px、1px 发丝线、没有卡片、没有留白装饰。密度在这里是优点。
 *
 * <h2>红色分组头</h2>
 *
 * 「整块空白」用分组头表达,不用图表。它说的不是某个考点弱,是<b>一整块从来没碰过</b> ——
 * 这是三层树相对扁平清单的唯一优势(决策记录 §2.5),也是这一屏唯一想让人看见的东西。
 *
 * <h2>窄屏:一行折成两行,<b>不是砍列</b></h2>
 *
 * 手机上放不下七列,但能砍的只有排版,不是信息。标记、名称、近五年频次三样必须在 ——
 * 标记是碰没碰过本身,名称是这一行是什么,频次是「这个盲区值不值得补」的全部依据。
 * 早先频次那一列写的是 `hidden md:block`,于是手机上「近五年 9 次」直接消失,
 * 「先补这几个」的排序理由在小屏上就没了下半句。
 * <p>
 * 折行靠的是 flex-wrap + 一个零高度的 `basis-full` 断点占位:它在 md 以上 `display:none`,
 * 于是同一段 DOM 在宽屏是一行、窄屏是两行。<b>没有第二套组件,也没有 JS 断点判断</b> ——
 * 后者会在 SSR / 首帧闪一次错误的布局。列在两种排布下的先后由 `order-*` 调,不由 DOM 顺序调。
 */
export function NodeList({
  groups,
  selectedCode,
  onSelect,
  onOpen,
}: {
  groups: GroupView[]
  selectedCode: string | null
  onSelect: (code: string) => void
  onOpen: (node: NodeView) => void
}) {
  const scroller = useRef<HTMLDivElement>(null)

  // 键盘上下移动时把选中行滚进视野。列表本身不分页、不懒加载 —— 整棵树一次给完。
  useEffect(() => {
    if (!selectedCode || !scroller.current) return
    scroller.current.querySelector(`[data-code="${CSS.escape(selectedCode)}"]`)?.scrollIntoView({ block: 'nearest' })
  }, [selectedCode])

  // 序号 01–18 是跨分组连续的:它数的是「这棵树的第几个考点」,不是「这组的第几个」。
  const ordinals = useMemo(() => {
    const map = new Map<string, number>()
    let n = 0
    for (const g of groups) for (const node of g.nodes) map.set(node.code, ++n)
    return map
  }, [groups])

  return (
    /* 🔴 2026-09-05(`KUBI-113`):滚动与分栏都归屏管(`screens/CoverageScreen.tsx` 的
       `Cols` / `ColL`),这里只是左栏里的一块内容,所以 `xl:min-h-0 xl:flex-1
       xl:shrink xl:overflow-y-auto` 四个类一起去掉 —— 随 `BlindSpotSide` 同一条理由,
       见那个文件里同一天的那段注释。留着它们会在 480px 的左栏里再套一个小滚动区,
       而「页面里套一个只有 200px 高的小滚动区」正是手机上最难用的那种东西。 */
    <div ref={scroller} className="min-w-0 shrink-0">
      {groups.map((group) => (
        <div key={group.code}>
          <GroupHeader
            alarm={group.whollyEmpty}
            title={group.whollyEmpty ? `${group.name} · 整块空白` : group.name}
            right={
              group.whollyEmpty
                ? `0/${group.nodes.length} · 频次合计 ${group.recent5yCount}`
                : `${group.coveredCount}/${group.nodes.length} 有记录`
            }
          />
          {group.nodes.map((node) => (
            <NodeRow
              key={node.code}
              node={node}
              index={ordinals.get(node.code) ?? 0}
              selected={node.code === selectedCode}
              onSelect={() => onSelect(node.code)}
              onOpen={() => onOpen(node)}
            />
          ))}
        </div>
      ))}
    </div>
  )
}

function NodeRow({
  node,
  index,
  selected,
  onSelect,
  onOpen,
}: {
  node: NodeView
  index: number
  selected: boolean
  onSelect: () => void
  onOpen: () => void
}) {
  const empty = !isTouched(node)
  return (
    <div data-code={node.code}>
      {/* 高度交给 className:窄屏两行 46px,md 起一行 29px。
          `height` 那个 inline style 没有断点,给它就再也改不动了。 */}
      <Row
        height="auto"
        className="h-[46px] flex-wrap content-center gap-x-[11px] gap-y-0 leading-[1.45] md:h-[29px] md:flex-nowrap"
        selected={selected}
        onClick={onSelect}
        onDoubleClick={onOpen}
        title={`${node.name} · ${node.code}`}
      >
        <span className="order-1 flex shrink-0 items-center">
          <StateDot touched={isTouched(node)} />
        </span>
        <span className="order-2 w-[18px] shrink-0 text-right font-mono text-[10.5px] text-t3 tabular-nums">
          {pad2(index)}
        </span>
        <span className={`order-3 min-w-0 flex-1 truncate ${empty ? 'text-t2' : ''}`}>{node.name}</span>

        {/* 近五年频次 —— 窄屏跟着名字留在第一行(它是排序理由的一半),宽屏回到第 7 列。 */}
        <Cell width={54} dim className="order-4 md:order-7">
          {node.recent5yCount}次
        </Cell>

        {/* 零高度的断点占位:窄屏在这里换行,md 起整个消失,于是同一段 DOM 变回一行。 */}
        <i aria-hidden className="order-5 h-0 basis-full md:hidden" />

        {/* 🔴 2026-09-06(`KUBI-111`)这里少了两列,理由是同一条 ——
            ① 五档中文名那一列(node.stateLabel:空白/仅接触/生疏/弱/稳)。「弱」的定义是
               「练过但用户自填的对/练偏低」,把它印在每一行上就是逐行给用户下判断。
            ② 「练·对」那一列(drillText 的「12/10」)。它不含任何禁用词,但把「做了多少」
               和「对了多少」并排放上屏,读出来就是答得对不对 —— 与退役稿那行「练 8 对 4」
               加一个百分比同源,只少一次除法。
            🔴 上一版这里还留着一条说明,说「被删掉的只有那个比值,两个原始数留着」——
            那条说明本身就是这次要纠的口径:留着的两个数就是比值。
            替上来的是「碰过几次」:同一个位置,只回答「几次」。 */}
        <Cell width={62} dim={node.touchCount === 0} className="order-6 ml-auto md:order-4 md:ml-0">
          {node.touchCount === 0 ? '没碰过' : `碰过${node.touchCount}次`}
        </Cell>
        <Cell width={66} dim className="order-9 md:order-8">
          {relativeDay(node.latestAt)}
        </Cell>
      </Row>
    </div>
  )
}

/** 右对齐的等宽数字列。宽度写死,是为了 18 行的小数点能对齐。 */
function Cell({
  children,
  width,
  dim = false,
  className = '',
}: {
  children: ReactNode
  width: number
  dim?: boolean
  className?: string
}) {
  return (
    <span
      style={{ width: `${width}px` }}
      className={`shrink-0 text-right whitespace-nowrap font-mono text-[11.5px] tabular-nums ${
        dim ? 'text-t3' : ''
      } ${className}`}
    >
      {children}
    </span>
  )
}

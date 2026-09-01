import type {
  BlindSpotDto,
  BlindSpotsResponse,
  Dashboard,
  DataSource,
  GroupView,
  NodeView,
  SummaryDto,
  StateCountDto,
  NodeState,
  RecordPageResponse,
  TreeResponse,
} from './types'

/**
 * 把四个接口的响应合成一屏的视图模型。
 *
 * <h2>这里<b>不算</b>覆盖率、不算五态、不算盲区排序</h2>
 *
 * 那三样的口径全部在 server 侧 `CoverageService` 里,这个文件只做搬运:
 * 百分比取 `summary.percent`,状态名取 `stateLabel`,排序分与名次取 `/blindspots` 的
 * `blindScore` / `rank`。<b>两处算同一个数就一定会算出两个数</b> ——
 * 这个文件早先抄了一份状态权重表当兜底,那就是第二个真相,现在删掉了。
 *
 * <h2>唯一一处「前端自己求的数」:每个考点的 practiced / correct</h2>
 *
 * 树接口(`NodeDto`)不返回做题数,那四个字段只在单点详情 `NodeDetailDto` 里有,
 * 而一屏 18 行不可能发 18 个详情请求(docs/technical/INDEX.md §6.4:整棵树一次返回)。
 * 所以这两个数由 `GET /api/records` 那一页里<b>同一批原始记录</b>求和 —— 和 server 侧
 * `CoverageService.compute` 逐行同一个写法:只累加 `practiced > 0` 的那些,不做四舍五入。
 * <p>
 * 但它有一个前提:拿到的记录必须是<b>全量</b>。那一页一旦被 limit 截断,求出来的和就偏小,
 * 而偏小的正确率会把「稳」显示成「弱」—— 那是产品最没资格说的一句话。
 * 所以 {@link buildDrillIndex} 用 `returned === total` 当闸门,不满足就整体给 null,
 * 界面显示「—」并说明原因。<b>宁缺毋滥:算不准就不显示,不硬凑。</b>
 */

/* ========================================================================== */
/* 做题数索引 —— 唯一的前端求和,带截断闸门                                     */
/* ========================================================================== */

interface Drill {
  practiced: number
  correct: number
}

/**
 * 闸门是 `returned === total`,<b>不是 `!page.hasMore`</b>。
 *
 * 端点换成 `/api/records` 之后这两个判据在第一页上恰好同真同假,但它们问的不是一件事:
 * `hasMore` 说的是「这个游标之后还有没有」,而这里要的是「手上这批是不是<b>全部</b>」。
 * 哪天这里带上 cursor 翻第二页,`hasMore` 会在最后一页变成 false,而那一页只有几条 ——
 * 求和照样是错的,闸门却放行了。两个字段名都在,选错的那个不会报错,只会悄悄算偏。
 *
 * @returns 记录全量时返回 `code → {practiced, correct}`;被截断时返回 `null`,
 *          调用方据此把三个字段全部置为「不知道」
 */
function buildDrillIndex(page: RecordPageResponse): Map<string, Drill> | null {
  if (page.returned !== page.total) return null

  const byNode = new Map<string, Drill>()
  for (const item of page.items) {
    // 与 server 侧 Touch.hasDrill() 同一条判据:practiced > 0 才算一笔做题
    if (item.practiced === null || item.practiced <= 0) continue
    const acc = byNode.get(item.nodeCode) ?? { practiced: 0, correct: 0 }
    acc.practiced += item.practiced
    acc.correct += item.correct ?? 0
    byNode.set(item.nodeCode, acc)
  }
  return byNode
}

/* ========================================================================== */
/* 合成                                                                        */
/* ========================================================================== */

function toNodeView(
  node: TreeResponse['groups'][number]['nodes'][number],
  group: TreeResponse['groups'][number],
  drills: Map<string, Drill> | null,
  blindByCode: Map<string, BlindSpotDto>,
): NodeView {
  const drill = drills?.get(node.code)
  // drills 为 null = 记录残缺,不知道;有索引但查不到 = 确实一道没练,是 0
  const practiced = drills === null ? null : (drill?.practiced ?? 0)
  const correct = drills === null ? null : (drill?.correct ?? 0)
  const blind = blindByCode.get(node.code)

  return {
    code: node.code,
    name: node.name,
    groupCode: group.code,
    groupName: group.name,
    recent5yCount: node.recent5yCount,
    state: node.state,
    stateLabel: node.stateLabel,
    touchCount: node.touchCount,
    latestAt: node.latestAt,
    practiced,
    correct,
    // 用户自填正确率:没练过是 null,不是 0% —— 0% 会被读成「答全错了」
    accuracy: practiced === null || correct === null || practiced === 0 ? null : correct / practiced,
    blindScore: blind?.blindScore ?? null,
    rank: blind?.rank ?? null,
  }
}

/**
 * 四个响应 → 一屏。`source` 与 `offlineReason` 由调用方给,这里不猜。
 *
 * @param recordPage `GET /api/records` 的<b>第一页</b>。这里不接 `/api/timeline` 的聚合视图 ——
 *                   那边出的是一格一格的统计,`items` 一条都没有,而这一层要的是逐条记录
 */
export function toDashboard(
  source: DataSource,
  tree: TreeResponse,
  summary: SummaryDto,
  blindspots: BlindSpotsResponse,
  recordPage: RecordPageResponse,
  offlineReason?: string,
): Dashboard {
  const drills = buildDrillIndex(recordPage)
  const blindByCode = new Map(blindspots.items.map((b) => [b.code, b]))

  const groups: GroupView[] = tree.groups.map((g) => ({
    code: g.code,
    name: g.name,
    nodeCount: g.nodeCount,
    coveredCount: g.coveredCount,
    recent5yCount: g.recent5yCount,
    whollyEmpty: g.whollyEmpty,
    nodes: g.nodes.map((n) => toNodeView(n, g, drills, blindByCode)),
  }))

  const byCode = new Map(groups.flatMap((g) => g.nodes).map((n) => [n.code, n]))

  return {
    source,
    offlineReason,
    subject: tree.subject,
    summary,
    groups,
    // 顺序即服务端给的名次,前端一个字都不重排
    blindspots: blindspots.items
      .map((b) => byCode.get(b.code))
      .filter((n): n is NodeView => n !== undefined),
    records: recordPage.items,
    drillsKnown: drills !== null,
  }
}

/* ========================================================================== */
/* 读取辅助                                                                    */
/* ========================================================================== */

/** 摊平成一条 18 行的列表,顺序就是树的顺序。 */
export function flattenNodes(groups: GroupView[]): NodeView[] {
  return groups.flatMap((g) => g.nodes)
}

/**
 * 命令面板的默认序:上过盲区榜的按<b>服务端给的名次</b>在前,其余(稳)按树序在后。
 *
 * 这里没有第二套排序口径 —— 名次直接读 `node.rank`。
 */
export function orderedByBlindRank(groups: GroupView[]): NodeView[] {
  return flattenNodes(groups)
    .map((node, index) => ({ node, index }))
    .sort((a, b) => {
      const ra = a.node.rank ?? Number.POSITIVE_INFINITY
      const rb = b.node.rank ?? Number.POSITIVE_INFINITY
      return ra - rb || a.index - b.index
    })
    .map((it) => it.node)
}

/**
 * 五态分布按<b>界面顺序</b>取出,中文名一律用服务端给的 `label`。
 *
 * 服务端给的是列表(顺序是产品语义),界面横向排列另有顺序,所以这里按 state 建索引再取。
 * 缺项补 0 —— 服务端保证五项齐全,补 0 只是让渲染不依赖这条保证。
 */
export function distributionByState(summary: SummaryDto): Map<NodeState, StateCountDto> {
  return new Map(summary.distribution.map((d) => [d.state, d]))
}

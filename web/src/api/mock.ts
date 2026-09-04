import { toDashboard } from './derive'
import type {
  BlindSpotDto,
  BlindSpotsResponse,
  Dashboard,
  GroupDto,
  NodeState,
  RecordPageResponse,
  StateCountDto,
  SubjectDto,
  SummaryDto,
  TimelineItemDto,
  TouchKind,
  TreeNodeDto,
  TreeResponse,
} from './types'

/**
 * 离线示例数据 —— 后端不可达时用它把界面填满。
 *
 * <h2>它扮演的是<b>服务端</b>,不是「另一份界面数据」</h2>
 *
 * 这个文件产出的是 `/syllabus/tree`、`/coverage/summary`、`/coverage/blindspots`、
 * `/records` 四个端点的<b>响应体</b>,然后走 {@link toDashboard} —— 和 live 分支同一条路。
 * 这样做的理由很实在:如果 mock 直接拼视图模型,它就绕开了合成逻辑,
 * 于是「离线时好好的、接上后端就炸」这类事没有任何一层能拦住。<b>离线路径是 live 路径的排练。</b>
 * 反过来也成立:live 那边换了端点(时间线从 `/timeline` 搬到 `/records`),
 * <b>这里的形状必须当天跟着换</b> —— 两边一旦分叉,排练的就是另一出戏了。
 * 也因此,五态的中文名、名次、排序分在这里也由「服务端」给出,而不是留给界面去补。
 *
 * <h2>为什么值得在前端重算一遍,而不是塞一份写死的 JSON</h2>
 *
 * 「多久前」是这个产品三个词里的一个。写死时间戳的话,示例数据放两天就会变成
 * 「34天前 / 35天前」,连「今天」这一行都没了,而这一屏最想让人看见的正是
 * 「今天碰过」和「32天前」的对比。所以这里存的是<b>相对天数</b>,每次打开按当下算。
 *
 * <h2>口径与后端钉在同一根钉子上</h2>
 *
 * 骨架来自 `server/src/main/resources/seed/syllabus-ziliao.json`,
 * 行为层逐条抄自 `CoverageServiceTest#contractTouches`,推导规则抄自 `NodeState.derive`。
 * 结果必然是 `ApiContractTest` 断言的那组数:
 * <b>18 个考点 / 8 个有记录 / 44% / 10 个空白 / 2 组整块空白</b>,
 * 五态 稳3 弱2 生疏2 仅接触1 空白10,Top 5 为 6.4 / 6.0 / 5.6 / 5.0 / 5.0。
 * 任何一边改了口径,两边的数字立刻对不上。
 *
 * 🔴 这份数据里同样没有一个字段能装内容 —— 示例数据也不许给「存内容」开先例。
 */

const DAY_MS = 86_400_000

/* ========================================================================== */
/* 服务端的话术与口径副本 —— 这个文件在扮演服务端,所以它得会说这些话             */
/* ========================================================================== */

/** 抄自 server 侧 `NodeState.label()`。live 分支不用它,那边的中文名由真服务端给。 */
const STATE_LABEL: Record<NodeState, string> = {
  EMPTY: '空白',
  TOUCHED_ONLY: '仅接触',
  RUSTY: '生疏',
  WEAK: '弱',
  STABLE: '稳',
}

/** 抄自 server 侧 `TouchKind.label()`。 */
const KIND_LABEL: Record<TouchKind, string> = {
  VOICE: '语音记',
  PHOTO: '拍照记',
  PASTE: '粘一段',
  DRILL: '记做题',
  MANUAL: '手动挂',
}

/** 抄自 server 侧 `CoverageService.weightOf`。排序分 = 近五年频次 × 状态权重。 */
const STATE_WEIGHT: Record<NodeState, number> = {
  EMPTY: 1.0, // 空白 —— 差集的正主
  TOUCHED_ONLY: 0.9, // 听过看过,一道没练
  WEAK: 0.8, // 练过,但用户自填的对/练偏低
  RUSTY: 0.7, // 练过,但超过 30 天没碰
  STABLE: 0.0, // 近期练过且用户说还行 —— 不需要补
}

/** 五态在 `distribution` 列表里的顺序 = server 侧 `NodeState.values()` 的声明顺序。 */
const STATE_ORDER: NodeState[] = ['EMPTY', 'TOUCHED_ONLY', 'RUSTY', 'WEAK', 'STABLE']

/** 超过这个天数没碰 → 生疏。纯时间判断,与答得怎么样无关。 */
const RUSTY_AFTER_DAYS = 30

/** 用户自填的对/练低于此值 → 弱。这是一条显示分组的阈值,不是评分。 */
const WEAK_BELOW = 0.6

/* ========================================================================== */
/* 骨架层 —— 抄自 seed/syllabus-ziliao.json                                    */
/* ========================================================================== */

const SUBJECT: SubjectDto = {
  code: 'sd-xingce-ziliao',
  region: '山东省考',
  exam: '行测',
  module: '资料分析',
  recent5yWindow: '2021-2025',
  display: '山东省考 · 行测 · 资料分析',
}

interface SeedNode {
  code: string
  name: string
  recent5yCount: number
}

interface SeedGroup {
  code: string
  name: string
  nodes: SeedNode[]
}

const SEED: SeedGroup[] = [
  {
    code: 'growth',
    name: '增长类',
    nodes: [
      { code: 'growth-rate', name: '增长率计算', recent5yCount: 9 },
      { code: 'growth-amount', name: '增长量计算', recent5yCount: 8 },
      { code: 'base-value', name: '基期量计算', recent5yCount: 7 },
      { code: 'interval-growth', name: '间隔增长率', recent5yCount: 6 },
      { code: 'current-value', name: '现期量计算', recent5yCount: 5 },
      { code: 'annual-avg-growth', name: '年均增长率', recent5yCount: 4 },
      { code: 'mixed-growth', name: '混合增长率', recent5yCount: 2 },
    ],
  },
  {
    code: 'multiple',
    name: '倍数与比较',
    nodes: [
      { code: 'multiple-calc', name: '倍数计算', recent5yCount: 5 },
      { code: 'multiple-change', name: '倍数变化', recent5yCount: 3 },
      { code: 'yoy-mom', name: '同比与环比', recent5yCount: 4 },
    ],
  },
  {
    code: 'effect',
    name: '效应类',
    nodes: [
      { code: 'contribution-rate', name: '贡献率', recent5yCount: 3 },
      { code: 'pull-growth', name: '拉动增长', recent5yCount: 3 },
    ],
  },
  {
    code: 'average-share',
    name: '平均与比重',
    nodes: [
      { code: 'share-calc', name: '比重计算', recent5yCount: 8 },
      { code: 'average-calc', name: '平均数计算', recent5yCount: 6 },
      { code: 'share-change', name: '比重变化', recent5yCount: 5 },
    ],
  },
  {
    code: 'fast-math',
    name: '速算技巧',
    nodes: [
      { code: 'truncate-divide', name: '截位直除', recent5yCount: 7 },
      { code: 'feature-number', name: '特征数字法', recent5yCount: 4 },
      { code: 'fraction-compare', name: '分数比较', recent5yCount: 3 },
    ],
  },
]

/* ========================================================================== */
/* 行为层 —— 逐条抄自 CoverageServiceTest#contractTouches                       */
/* ========================================================================== */

interface SeedTouch {
  node: string
  source: string
  kind: TouchKind
  days: number
  /** 几点记的。只为让时间线上的钟点稳定,不参与任何计算。 */
  hour: number
  practiced?: number
  correct?: number
}

const SEED_TOUCHES: SeedTouch[] = [
  // 稳:近期练过,用户自填的对/练 ≥ 60%
  { node: 'growth-rate', source: '粉笔 · 资料分析系统班 L12', kind: 'DRILL', days: 0, hour: 9, practiced: 12, correct: 10 },
  { node: 'share-calc', source: '华图 · 资料速算网课', kind: 'DRILL', days: 1, hour: 21, practiced: 9, correct: 8 },
  { node: 'feature-number', source: '自己刷题 · 2023 国考真题', kind: 'DRILL', days: 3, hour: 20, practiced: 7, correct: 6 },
  // 弱:近期练过,但用户自填的对/练 < 60%
  { node: 'growth-amount', source: '自己刷题 · 2023 国考真题', kind: 'DRILL', days: 2, hour: 21, practiced: 8, correct: 4 },
  { node: 'truncate-divide', source: 'B站 · 资料分析技巧', kind: 'DRILL', days: 4, hour: 19, practiced: 6, correct: 2 },
  // 生疏:练过,但超过 30 天没碰
  { node: 'base-value', source: '中公 · 资料分析专项', kind: 'DRILL', days: 32, hour: 22, practiced: 5, correct: 4 },
  { node: 'interval-growth', source: '中公 · 资料分析专项', kind: 'DRILL', days: 33, hour: 22, practiced: 3, correct: 2 },
  // 仅接触:听过课,一道题没练
  { node: 'share-change', source: '粉笔 · 资料分析系统班 L12', kind: 'VOICE', days: 5, hour: 8 },
]

/* ========================================================================== */
/* 推导 —— 抄自 NodeState.derive 与 CoverageService.compute                     */
/* ========================================================================== */

function deriveState(items: TimelineItemDto[], now: number): NodeState {
  if (items.length === 0) return 'EMPTY' // 有没有:没有

  let practiced = 0
  let correct = 0
  let latest = 0

  for (const t of items) {
    const at = Date.parse(t.occurredAt)
    if (at > latest) latest = at // 多久前
    if (t.practiced !== null && t.practiced > 0) {
      practiced += t.practiced // 几次
      correct += t.correct ?? 0
    }
  }

  if (practiced === 0) return 'TOUCHED_ONLY' // 碰过,但没练过
  if (now - latest > RUSTY_AFTER_DAYS * DAY_MS) return 'RUSTY' // 练过,但太久没碰
  return correct / practiced < WEAK_BELOW ? 'WEAK' : 'STABLE' // 用户自填的两个数相除
}

/* ========================================================================== */
/* 四个端点的响应体                                                            */
/* ========================================================================== */

/**
 * `GET /api/v1/records` 的第一页。
 *
 * <b>不是 `/api/v1/timeline`</b>:那个端点出的是按天/周分桶的聚合视图,一条 `items` 都没有,
 * 而这一屏(最近记录、来源名、每个考点的做题数)要的全是逐条记录。
 */
function buildRecordPage(now: number): RecordPageResponse {
  const groupOf = new Map(SEED.flatMap((g) => g.nodes.map((n) => [n.code, g])))
  const nameOf = new Map(SEED.flatMap((g) => g.nodes.map((n) => [n.code, n.name])))

  const items: TimelineItemDto[] = SEED_TOUCHES.map((s, i) => {
    const at = new Date(now - s.days * DAY_MS)
    at.setHours(s.hour, i * 7 + 5, 0, 0)
    // 「今天」那条如果落在当前钟点之后,就会变成一条未来的记录 —— 往前挪一天更诚实
    if (at.getTime() > now) at.setTime(at.getTime() - DAY_MS)
    const group = groupOf.get(s.node)
    return {
      id: `mock-${s.node}`,
      occurredAt: at.toISOString(),
      kind: s.kind,
      kindLabel: KIND_LABEL[s.kind],
      sourceName: s.source,
      nodeCode: s.node,
      nodeName: nameOf.get(s.node) ?? null,
      groupCode: group?.code ?? null,
      groupName: group?.name ?? null,
      practiced: s.practiced ?? null,
      correct: s.correct ?? null,
    }
  }).sort((a, b) => Date.parse(b.occurredAt) - Date.parse(a.occurredAt)) // 最近的在最上面

  // 🔴 不给 nextCursor 这个 key —— 这份示例数据永远是全量,不会触发 derive.ts 的截断闸门。
  // 8 条记录一页装得下,没有第二页。
  //
  // 写成 `nextCursor: null` 是这里最容易犯的错:那样 mock 扮演的就不是服务端了 ——
  // 服务端上 @JsonInclude(NON_NULL) 保证没有下一页时【这个 key 整个不出现】,
  // 而闸门判的正是 `!== undefined`。给一个 null 会让 live 分支被截断时的行为
  // 在 mock 分支上永远排练不到,却看不出任何差别。
  return { items }
}

function buildGroups(recordPage: RecordPageResponse, now: number): GroupDto[] {
  const byNode = new Map<string, TimelineItemDto[]>()
  for (const t of recordPage.items) {
    const list = byNode.get(t.nodeCode)
    if (list) list.push(t)
    else byNode.set(t.nodeCode, [t])
  }

  return SEED.map((g) => {
    let covered = 0
    const nodes: TreeNodeDto[] = g.nodes.map((n) => {
      const ts = byNode.get(n.code) ?? []
      const state = deriveState(ts, now)
      if (state !== 'EMPTY') covered++

      let latestAt: string | null = null
      for (const t of ts) {
        if (latestAt === null || Date.parse(t.occurredAt) > Date.parse(latestAt)) latestAt = t.occurredAt
      }

      return {
        code: n.code,
        name: n.name,
        recent5yCount: n.recent5yCount,
        state,
        stateLabel: STATE_LABEL[state],
        touchCount: ts.length,
        latestAt,
      }
    })

    return {
      code: g.code,
      name: g.name,
      nodeCount: nodes.length,
      coveredCount: covered,
      recent5yCount: g.nodes.reduce((sum, n) => sum + n.recent5yCount, 0),
      // 整块空白 —— 这个题型下一个考点都没碰过。红色分组头就靠它。
      whollyEmpty: covered === 0 && nodes.length > 0,
      nodes,
    }
  })
}

function summarize(groups: GroupDto[]): SummaryDto {
  const counts = new Map<NodeState, number>(STATE_ORDER.map((s) => [s, 0]))
  let total = 0
  let covered = 0
  let whollyEmptyGroups = 0

  for (const g of groups) {
    if (g.whollyEmpty) whollyEmptyGroups++
    for (const n of g.nodes) {
      total++
      if (n.state !== 'EMPTY') covered++
      counts.set(n.state, (counts.get(n.state) ?? 0) + 1)
    }
  }

  const distribution: StateCountDto[] = STATE_ORDER.map((state) => ({
    state,
    label: STATE_LABEL[state],
    count: counts.get(state) ?? 0,
  }))

  return {
    total,
    covered,
    empty: total - covered,
    // 与 server 侧 Summary.percent() 同一个取整方式,不能一边 round 一边 trunc
    percent: total === 0 ? 0 : Math.round((covered / total) * 100),
    whollyEmptyGroups,
    distribution,
  }
}

/** 抄自 `CoverageService.blindSpots`:降序,同分按树序,分数为 0 的不入榜。 */
function buildBlindSpots(groups: GroupDto[], top: number): BlindSpotsResponse {
  const flat = groups.flatMap((g) => g.nodes.map((node) => ({ node, group: g })))

  const items: BlindSpotDto[] = flat
    .map((it, index) => ({ ...it, index, score: it.node.recent5yCount * STATE_WEIGHT[it.node.state] }))
    .sort((a, b) => b.score - a.score || a.index - b.index)
    .filter((it) => it.score > 0)
    .slice(0, top)
    .map((it, i) => ({
      rank: i + 1,
      code: it.node.code,
      name: it.node.name,
      groupCode: it.group.code,
      groupName: it.group.name,
      recent5yCount: it.node.recent5yCount,
      state: it.node.state,
      stateLabel: it.node.stateLabel,
      blindScore: it.score,
    }))

  return { requestedTop: top, returned: items.length, items }
}

/**
 * 组装一份完整的离线示例 Dashboard —— 先造四个响应体,再走和 live 完全相同的合成。
 *
 * @param top 与 live 分支请求的 `?top=` 保持一致,免得两边的名次范围不同
 */
export function buildMockDashboard(offlineReason: string, now: number = Date.now(), top = 100): Dashboard {
  const recordPage = buildRecordPage(now)
  const groups = buildGroups(recordPage, now)
  const summary = summarize(groups)
  const tree: TreeResponse = { subject: SUBJECT, summary, groups }

  return toDashboard('mock', tree, summary, buildBlindSpots(groups, top), recordPage, offlineReason)
}

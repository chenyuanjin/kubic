/**
 * 后端 `/api/*` 的响应形状 —— 手写,<b>逐字段对着 `server/src/main/java/com/kaodian/server/api/dto/`
 * 里的 record 抄,不是照着「大概长这样」写</b>。
 *
 * 之所以手写而不是先等 OpenAPI:后端接口层与这份前端是两条并行的线,谁都不等谁。
 * 但「并行」不等于「各写各的」—— 一旦这里的形状和 server 侧对不上,live 分支会在运行时
 * 才炸(`.map is not a function` / `NaN%`),而 tsc 一句话都不会说,因为 `as T` 是断言不是校验。
 * 所以每个接口下面都标了它对应的那个 Java 文件名,改动时两边一起看。
 *
 * 🔴 这里没有 content / text / question / transcript / imageUrl 这类字段,
 *    因为 server 侧的 Touch 里就没有 —— 不是不填,是不存在这个位置(01 §2.2 不碰内容)。
 *    前端不能凭空长出一个后端没有的字段,否则「结构上没地方放内容」这条线就是假的。
 */

/* ========================================================================== */
/* 五态 —— 与 server 侧 NodeState 逐个对应                                     */
/* ========================================================================== */

/**
 * tsconfig 开了 `erasableSyntaxOnly`,不能用 TS enum(它会生成运行时代码)。
 * const 数组 + 索引类型是等价物。
 *
 * <b>这个数组的顺序只是「界面从左到右怎么排」,不是数据顺序。</b>
 * 五态的中文名一律取服务端给的 `stateLabel` / `StateCountDto.label`,不在前端硬编码 ——
 * 状态改名时改一处,不是改两端(server 侧 StateCountDto 的 javadoc 明说了这条)。
 */
export const NODE_STATES = ['STABLE', 'WEAK', 'RUSTY', 'TOUCHED_ONLY', 'EMPTY'] as const

export type NodeState = (typeof NODE_STATES)[number]

/** 一笔记录是怎么记的。与 server 侧 TouchKind 对应。 */
export const TOUCH_KINDS = ['VOICE', 'PHOTO', 'PASTE', 'DRILL', 'MANUAL'] as const

export type TouchKind = (typeof TOUCH_KINDS)[number]

/* ========================================================================== */
/* GET /api/syllabus/tree            → server: dto/TreeResponse.java           */
/* ========================================================================== */

/** server: dto/SubjectDto.java */
export interface SubjectDto {
  code: string
  region: string
  exam: string
  module: string
  /** 频次统计的年份窗口,如 `2021-2025`。界面上那句「近五年」指的是它。 */
  recent5yWindow: string
  /** 如「山东省考 · 行测 · 资料分析」。<b>拼接规则在服务端</b>,前端不自己拼。 */
  display: string
}

/**
 * 树上的一个考点 —— server: dto/NodeDto.java。
 *
 * 🔴 <b>字段就这七个。</b>没有 practiced / correct / accuracy / sources ——
 * 那四个只在 `GET /api/syllabus/nodes/{code}`(NodeDetailDto)里有。
 * 早先这里多写了几个后端并不返回的字段,结果 `correct / practiced` 变成
 * `undefined / undefined` = NaN,18 行的正确率列全部渲染成「NaN%」。
 * 所以宁可让这个接口看起来「少」,也不给不存在的数据留字段。
 */
export interface TreeNodeDto {
  code: string
  name: string
  /** 近五年真题出现次数。统计事实,也是盲区排序的权重之一。 */
  recent5yCount: number
  state: NodeState
  /** 中文名,服务端给,前端直接显示。 */
  stateLabel: string
  touchCount: number
  /** ISO-8601。没有任何记录时为 null,界面显示「—」而不是某个默认日期。 */
  latestAt: string | null
}

/** server: dto/GroupDto.java */
export interface GroupDto {
  code: string
  name: string
  nodeCount: number
  coveredCount: number
  recent5yCount: number
  /** 整块空白 —— 这个题型下一个考点都没碰过。树相对扁平清单的唯一优势。 */
  whollyEmpty: boolean
  nodes: TreeNodeDto[]
}

/**
 * 五态分布里的一项 —— server: dto/StateCountDto.java。
 *
 * 🔴 <b>是数组,不是 `{EMPTY: 10, ...}` 这样的对象。</b>
 * 服务端刻意选了列表:顺序是产品语义(空白 → 仅接触 → 生疏 → 弱 → 稳),
 * 而 JSON 对象的键顺序不是契约。早先这里写成 `Record<NodeState, number>`,
 * 于是 `distribution['EMPTY']` 恒为 undefined,计量条五段全 0、图例全 0,
 * 同屏还挂着一个 44% 的大字。
 */
export interface StateCountDto {
  state: NodeState
  label: string
  count: number
}

/**
 * 覆盖概览 —— server: dto/SummaryDto.java。
 *
 * `percent` 是<b>必填</b>,由服务端 `Summary.percent()` 取整给出。
 * 前端不要拿 covered/total 自己算:两处算同一个数就一定会算出两个数
 * (一边四舍五入一边截断,44% 和 43% 同屏)。
 */
export interface SummaryDto {
  total: number
  covered: number
  empty: number
  percent: number
  whollyEmptyGroups: number
  distribution: StateCountDto[]
}

/** server: dto/TreeResponse.java —— 树与概览同出一次 compute,必须同行。 */
export interface TreeResponse {
  subject: SubjectDto
  summary: SummaryDto
  groups: GroupDto[]
}

/* ========================================================================== */
/* GET /api/coverage/blindspots      → server: dto/BlindSpotsResponse.java     */
/* ========================================================================== */

/**
 * 盲区清单里的一行 —— server: dto/BlindSpotDto.java。
 *
 * `rank` 与 `blindScore` <b>都由服务端给</b>。前端不复制那张状态权重表 ——
 * 复制一份就有了第二个真相,而排序口径正是产品的判断本身。
 */
export interface BlindSpotDto {
  rank: number
  code: string
  name: string
  groupCode: string
  groupName: string
  recent5yCount: number
  state: NodeState
  stateLabel: string
  blindScore: number
}

/** server: dto/BlindSpotsResponse.java —— 🔴 是对象 `{requestedTop, returned, items}`,不是裸数组。 */
export interface BlindSpotsResponse {
  requestedTop: number
  returned: number
  items: BlindSpotDto[]
}

/* ========================================================================== */
/* GET /api/timeline                 → server: dto/TimelineResponse.java       */
/* ========================================================================== */

/**
 * 时间线上的一条 —— server: dto/TimelineItemDto.java。
 *
 * 🔴 这里没有内容字段,一个都没有:没有 content / text / transcript / imageUrl。
 * 语音的转写文本用完即弃,原图送识别一次即删,它们从来没有进过任何一条记录。
 *
 * 🔴 做题数是<b>扁平的两个字段</b>,不是嵌套的 `drill: {practiced, correct}`。
 * 早先前端按嵌套写,于是 `r.drill` 永远是 undefined,「记做题」那条在最近记录里
 * 一个数都显示不出来。两个数是用户自己敲进来的整数,属于「几次」不属于「对不对」;
 * 非做题类记录它们是 null。
 */
export interface TimelineItemDto {
  id: string
  occurredAt: string
  kind: TouchKind
  /** 中文名,服务端给,前端不硬编码。 */
  kindLabel: string
  sourceName: string
  nodeCode: string
  /** 骨架树删过节点而历史记录还在时为 null —— 不该导致整条时间线炸掉。 */
  nodeName: string | null
  groupCode: string | null
  groupName: string | null
  practiced: number | null
  correct: number | null
}

/**
 * server: dto/TimelineResponse.java。
 *
 * `total` 是行为层记录总数,`returned` 是本次截取的条数。
 * <b>两个数不等,就说明这一屏拿到的记录是残缺的</b> —— 见 derive.ts 里那个截断闸门。
 */
export interface TimelineResponse {
  total: number
  returned: number
  items: TimelineItemDto[]
}

/* ========================================================================== */
/* POST /api/records                 → server: dto/CreateRecordRequest.java    */
/* ========================================================================== */

/**
 * 建一条记录。<b>字段就这五个,多一个字段服务端当场 400。</b>
 *
 * 🔴 一:`nodeCode` 只能是考点树里已经存在的 code(R-07)。
 *    界面上这个值来自一个 `<select>`,选项由树生成 —— 自由文本标签在 UI 层就打不出来。
 *
 * 🔴 二:<b>没有 `occurredAt`。</b>时间戳由服务端按 Clock 打。
 *    「多久前」是五态里唯一的时间依据,让客户端自报会让「生疏」变成一个能被随手改掉的状态。
 *
 * 🔴 三:做题数是<b>扁平的 practiced / correct</b>,不是 `drill: {...}`。
 *    服务端开了 `FAIL_ON_UNKNOWN_PROPERTIES=true`(R-07 的第二道锁),
 *    传 `drill` 或 `occurredAt` 会得到 `UNKNOWN_FIELD` 400 —— 那两把锁是冲着
 *    `{"tag":"我自己想的考点"}` 去的,前端不该去撞它。
 *
 * 两个数要么都给要么都不给:只给 practiced 会让服务端替用户把 correct 填成 0,
 * 凭空造出一个「全错」的记录,而那正好会把考点判成「弱」。
 */
export interface CreateRecordRequest {
  kind: TouchKind
  sourceName: string
  nodeCode: string
  practiced?: number
  correct?: number
}

/* ========================================================================== */
/* 骨架层的写 —— server: api/SyllabusAdminController.java                      */
/* ========================================================================== */

/**
 * 考点管理的请求体。
 *
 * <h2>🔴 全是 POST,而且是<b>动作路径</b>,不是 REST 资源路径</h2>
 *
 * `POST /nodes/{code}/rename`、`/move`、`/frequency`、`/archive`、`/delete`,
 * 没有一个 PATCH、没有一个 DELETE。两个理由都写在 server 侧那个控制器的 javadoc 里:
 * <ul>
 * <li>`ApiCorsConfig` 的方法白名单只有 `GET / POST`。前端偷发 DELETE,dev 下同源能过,
 *     <b>上生产才被挡</b> —— 本地全绿线上全红,最难查的一类差异。
 * <li>语义:这里的删除<b>不是「让一个资源消失」,而是一条带前置条件的命令</b>,
 *     它会失败,而且失败才是常态(有记录就不许删)。
 * </ul>
 *
 * <h2>🔴 没有「从机构导入考点体系」,以后也不会有</h2>
 *
 * 只有逐个新增。一个能一次提交整棵子树的端点,现实中的第一个用途一定是把某家机构的
 * 目录页整块拷进来 —— 而 R-07 / docs/04 §1.2 要求考点自行归纳、不沿用机构既有体系与措辞。
 * <b>逐个新增很慢,慢正是要的效果。</b>
 * 导出是有的(`GET /api/syllabus/export`),它的反向操作是把文件放回 `~/.kaodian/syllabus.json`,
 * 不是一个接受任意树形 JSON 的接口。
 *
 * <h2>🔴 只有题型和考点两种对象</h2>
 *
 * 模块 → 题型 → 考点,<b>三层封顶</b>(01 §2.5)。`CreateNodeRequest` 里只有 `groupCode`,
 * 没有 `parentNodeCode`、没有 `children` —— 第四层在请求体里就没有位置,界面自然也做不出来。
 */

/** server: dto/CreateGroupRequest.java —— 就一个 name,<b>没有 code</b>。 */
export interface CreateGroupRequest {
  name: string
}

/**
 * server: dto/CreateNodeRequest.java。
 *
 * 🔴 <b>没有 `code` 字段</b> —— code 由服务端生成,客户端指定不了,更不能拿中文名当 code。
 * `recent5yCount` 是<b>统计事实</b>(近五年真题里出现几次),不是难度也不是重要性评分:
 * 没查到就填 0,填一个拍脑袋的数会直接污染盲区排序。
 */
export interface CreateNodeRequest {
  /** 挂在哪个题型下。🔴 只能是题型的 code —— 考点下面挂不了考点,那是第四层。 */
  groupCode: string
  /** ≤ 40 字,不许有换行(server 侧 `@Size(max = 40)` + INVALID_NAME)。 */
  name: string
  /** 0–999。 */
  recent5yCount: number
}

/**
 * server: dto/RenameRequest.java。考点与题型共用。
 *
 * 🔴 <b>改名只改 name,code 一个字符都不动 —— 所以历史记录一条都不会丢。</b>
 * 这句话必须同时出现在界面上(见 `SyllabusEditor` 顶部那条常驻说明):
 * 阶段 1 要反复校正命名,而「改名会不会把记录弄没」是唯一会让人不敢改的顾虑。
 */
export interface RenameRequest {
  name: string
}

/** server: dto/MoveNodeRequest.java —— 移到另一个题型。code 不变,记录一条都不受影响。 */
export interface MoveNodeRequest {
  groupCode: string
}

/** server: dto/SetFrequencyRequest.java —— 0–999 的统计事实。 */
export interface SetFrequencyRequest {
  recent5yCount: number
}

/**
 * server: dto/MoveRecordsRequest.java —— 把一个考点上的记录整体搬到另一个考点。
 *
 * 这是「删除守则」给出的第一条出路。搬迁<b>只改 nodeCode</b>:时间戳、来源名、做题数原样保留,
 * 记录总数不变。不重置时间戳是有意的 —— 「多久前」是仅有的三个维度之一,
 * 让一批记录因为搬家集体变年轻,五态会不报错地整体漂移。
 */
export interface MoveRecordsRequest {
  toNodeCode: string
}

/**
 * server: dto/GroupOrderRequest.java —— 🔴 <b>是整个排列,不是「移到第 N 位」。</b>
 *
 * 服务端会校验它是现有条目的一个<b>排列</b>(少一个就等于悄悄删一个,直接 400
 * `ORDER_NOT_A_PERMUTATION`)。所以前端上移/下移一行时要发<b>换过位之后的完整数组</b>。
 */
export interface GroupOrderRequest {
  groupCodes: string[]
}

/** server: dto/NodeOrderRequest.java —— 同上,某个题型下考点的完整排列。 */
export interface NodeOrderRequest {
  nodeCodes: string[]
}

/**
 * server: dto/SyllabusNodeDto.java —— 管理视角下的考点。
 *
 * 与 `TreeNodeDto` 的差别正是管理界面需要的两样:
 * `recordCount`(上面挂了几条记录 —— <b>删除能不能进行全看它</b>)
 * 和 `archived`(退出差集但记录还在)。它没有 state / stateLabel:
 * 管理这棵树时要看的是结构,不是五态。
 */
export interface SyllabusNodeDto {
  code: string
  name: string
  groupCode: string | null
  groupName: string | null
  recent5yCount: number
  archived: boolean
  recordCount: number
}

/** server: dto/ArchivedNodesResponse.java —— `GET /api/syllabus/archived`。 */
export interface ArchivedNodesResponse {
  count: number
  items: SyllabusNodeDto[]
}

/**
 * 写端点的响应 —— 界面<b>一个字段都不读</b>。
 *
 * 服务端每次都把改完之后的 `summary` 一起带回来(NodeEditResponse / GroupEditResponse /
 * DeletedResponse / RecordsMovedResponse 都有),完全可以拿来直接更新界面。这里刻意不用:
 * 一屏的数据是<b>四个 GET 一起拉齐</b>的整体(见 queries.ts 那段「要么全真要么全假」),
 * 只把 summary 塞进去会造出「覆盖率是新的、树还是旧的」这种同屏矛盾。
 * <p>
 * 所以路径只有一条:成功 → invalidate → 四个 GET 重拉。慢半拍,但一屏上的数只有一个来源。
 */
export type SyllabusWriteResponse = unknown

/**
 * 服务端拒绝骨架层编辑时的 `code`。server: syllabus/SyllabusEditException.Reason。
 *
 * 🔴 <b>这是一份手抄件,不是生成的。</b>服务端那个 enum 每加一个 Reason,这里就要跟着加一行 ——
 * 已知的重复源,列在这儿是为了让下一个人看见它,而不是发现之后再当一次 bug 查。
 * <p>
 * 漏抄不会有任何编译错误,后果是<b>一个永远为 false 的分支</b>:
 * {@link EditRejection} 约束住了 `rejectedAs()` 的第二个参数,所以拼错能在编译期拦下,
 * 但「服务端会发一个这里没有的 code」编译期看不出来 —— 它的表现是那一段专门的说明
 * <b>从来不出现</b>,用户永远只看到那句笼统的「没存下来」。而那正是没人会去主动测的路径。
 */
export const EDIT_REJECTIONS = [
  'NODE_NOT_FOUND',
  'GROUP_NOT_FOUND',
  /** 🔴 删除守则:考点上还挂着记录。界面要给出「搬记录」和「归档」,不是一句「删除失败」。 */
  'NODE_HAS_RECORDS',
  /** 题型下面还有考点(含已归档的)。 */
  'GROUP_NOT_EMPTY',
  'NODE_ALREADY_ARCHIVED',
  /** 目标考点在树里但<b>已归档</b>,记录搬不进去。出路是先给它取消归档,不是刷新页面。 */
  'NODE_ARCHIVED',
  'NODE_NOT_ARCHIVED',
  'INVALID_NAME',
  /**
   * 🔴 名字被占了。<b>整棵树唯一,而且包含已归档的考点。</b>
   *
   * 界面上不能落成一句「重名」:要说出<b>撞的是谁、它在哪</b>。
   * 冲突对象已归档时尤其 —— 那个考点<b>不在树上</b>,用户翻遍界面也看不见它,
   * 只会觉得「我明明没有重名」。那种情况必须显式说「被一个已归档的考点占着」并给出出路。
   */
  'NAME_TAKEN',
  'INVALID_FREQUENCY',
  'ORDER_NOT_A_PERMUTATION',
  'SAME_NODE',
] as const

export type EditRejection = (typeof EDIT_REJECTIONS)[number]

/** 考点详情 —— server: dto/NodeDetailDto.java。🔴 没有讲解字段(R-05)。 */
export interface NodeDetailDto {
  code: string
  name: string
  groupCode: string
  groupName: string
  recent5yCount: number
  state: NodeState
  stateLabel: string
  touchCount: number
  practiced: number
  correct: number
  /** 用户自填正确率;没练过是 null,界面显示「—」<b>不是 0%</b>。 */
  accuracy: number | null
  latestAt: string | null
  /** 来源名的集合。只有名字,没有该来源的任何内容。 */
  sources: string[]
}

/** server: dto/CreateRecordResponse.java —— 落下的那条记录 + 那个考点的新状态。 */
export interface CreateRecordResponse {
  record: TimelineItemDto
  node: NodeDetailDto | null
}

/* ========================================================================== */
/* 视图模型 —— 由上面四个响应合成,组件只认这一层                                */
/* ========================================================================== */

/** 数据从哪来。界面必须把这个如实标出来,不许静默回退。 */
export type DataSource = 'live' | 'mock'

/**
 * 一个考点在界面上的样子 = 树给的 + 盲区榜给的 + 时间线里那两个整数。
 *
 * `practiced` / `correct` / `accuracy` 是 <b>`number | null`,null 的意思是「不知道」,
 * 不是「0」</b>。树接口本身不返回做题数(那四个字段只在 NodeDetailDto 里),
 * 所以它们由时间线里同一批原始记录求和得来;时间线一旦被 limit 截断,
 * 求出来的和就是错的 —— 那种情况下这三个字段一律给 null,界面显示「—」。
 * <b>宁可显示「—」,也不显示一个算不准的数。</b>
 */
export interface NodeView {
  code: string
  name: string
  groupCode: string
  groupName: string
  recent5yCount: number
  state: NodeState
  stateLabel: string
  touchCount: number
  latestAt: string | null
  practiced: number | null
  correct: number | null
  accuracy: number | null
  /** 服务端算的排序分。`null` 表示它不在盲区榜上(稳,权重 0)。 */
  blindScore: number | null
  /** 服务端给的名次,从 1 开始。`null` 同上。 */
  rank: number | null
}

export interface GroupView {
  code: string
  name: string
  nodeCount: number
  coveredCount: number
  recent5yCount: number
  whollyEmpty: boolean
  nodes: NodeView[]
}

/**
 * 一屏所需的全部数据。
 *
 * 故意做成一个整体:四个端点要么全是真的,要么全是离线示例。
 * 混着来会出现「覆盖率是真的、盲区榜是假的」这种同屏矛盾 —— 那比整体离线更误导人。
 */
export interface Dashboard {
  source: DataSource
  /** 回退到离线示例数据的原因,原样显示给用户,不做美化。 */
  offlineReason?: string
  subject: SubjectDto
  summary: SummaryDto
  groups: GroupView[]
  /** 服务端排好序的盲区榜,顺序即名次。 */
  blindspots: NodeView[]
  records: TimelineItemDto[]
  /**
   * 做题数是否可信。时间线被 limit 截断时为 false,此时所有节点的
   * practiced/correct/accuracy 都是 null,界面显示「—」并说明原因。
   */
  drillsKnown: boolean
}

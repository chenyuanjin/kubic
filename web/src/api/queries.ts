import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { describeError, getJson, postAction, postJson } from './client'
import { toDashboard } from './derive'
import { buildMockDashboard } from './mock'
import type {
  ArchivedNodesResponse,
  BlindSpotsResponse,
  CreateGroupRequest,
  CreateNodeRequest,
  CreateRecordRequest,
  CreateRecordResponse,
  Dashboard,
  SummaryDto,
  SyllabusWriteResponse,
  TimelineResponse,
  TreeResponse,
} from './types'

export const DASHBOARD_KEY = ['dashboard'] as const

/**
 * 时间线一次要多少条。
 *
 * 200 是服务端 `TimelineController` 的上限。要满额是因为每个考点的做题数由这批记录求和
 * (树接口不返回做题数),<b>取不满就得整体显示「—」</b> —— 见 derive.ts 的截断闸门。
 * 真到需要分页的量级时,该补的是服务端的 cursor(docs/10 §6.2),不是在这里悄悄多请求几次。
 */
const TIMELINE_LIMIT = 200

/**
 * 盲区榜一次要多少个。
 *
 * 侧栏「先补这几个」只显示前 5,但命令面板要按盲区名次排全部考点,
 * 而<b>名次只有服务端算</b>(前端不留第二份权重表)。所以一次要够多的名次回来,
 * 侧栏自己 slice(0,5) —— 服务端返回的是有序前缀,切前 5 和请求 top=5 是同一批。
 * 100 是服务端上限;当前单模块 18 个考点,一次全要得到。
 */
const BLINDSPOT_TOP = 100

/**
 * 一次拉齐一屏所需的四份数据。
 *
 * <h2>为什么是一个 query,不是四个</h2>
 *
 * 因为回退必须是<b>整体</b>的。四个独立 query 各自回退的话,会出现「覆盖率 44% 是真的、
 * 盲区榜是示例数据」这种同屏矛盾 —— 用户看到的两个数字来自两个世界,比整屏离线更误导人。
 * 所以这里的约定是:<b>四个端点要么全真,要么全假,并且在界面上标出来是哪一种。</b>
 *
 * <h2>路径逐条对着 server 侧的 @GetMapping</h2>
 *
 * 时间线在 `/api/timeline`,<b>不在 `/api/records`</b> —— 后者只有 `@PostMapping`,
 * GET 过去是 405。早先这里写的是 `GET /api/records`,后果是:后端跑得好好的,
 * 整屏也永远退回离线示例数据,而且理由写着「HTTP 405」,查起来像后端坏了。
 */
async function fetchDashboard(): Promise<Dashboard> {
  try {
    const [tree, summary, blindspots, timeline] = await Promise.all([
      // 没有 withCoverage 开关 —— 一棵不带覆盖的树没有任何用处,服务端也没定义这个参数
      getJson<TreeResponse>('/syllabus/tree'),
      getJson<SummaryDto>('/coverage/summary'),
      getJson<BlindSpotsResponse>(`/coverage/blindspots?top=${BLINDSPOT_TOP}`),
      getJson<TimelineResponse>(`/timeline?limit=${TIMELINE_LIMIT}`),
    ])

    return toDashboard('live', tree, summary, blindspots, timeline)
  } catch (err) {
    // 🔴 不静默失败,也不假装是真数据:把原因原样带到界面上。
    return buildMockDashboard(describeError(err))
  }
}

export function useDashboard() {
  return useQuery({
    queryKey: DASHBOARD_KEY,
    queryFn: fetchDashboard,
    // fetchDashboard 自己兜住了错误,永远 resolve。重试只会白等,反而拖慢首屏。
    retry: false,
    // 后端起来之后不用刷页面 —— 切回窗口就会重新试一次真接口。
    refetchOnWindowFocus: true,
    // 离线示例数据也会被当成一次成功缓存住,所以别把它压得太久:
    // 底栏那句「窗口重新聚焦会自动再试一次真接口」得是真的。
    staleTime: 3_000,
  })
}

/**
 * 记一笔。
 *
 * 这个 mutation 只做一件事:把「考点 + 来源名 + 形式」发给后端,做题时多带两个整数。
 * 粘进来的文字、录音、原图都不在请求体里 —— CreateRecordRequest 里没有它们的位置。
 *
 * 🔴 <b>请求体只能有那五个字段。</b>服务端开了 `FAIL_ON_UNKNOWN_PROPERTIES=true`,
 * 多一个字段就是 `UNKNOWN_FIELD` 400 —— 那道锁是拦 `{"tag":"我自己想的考点"}` 的,
 * 前端不该去撞它。时间戳也不许自己带:`occurredAt` 由服务端按 Clock 打,
 * 客户端自报会让「生疏」变成一个能被随手改掉的状态。
 *
 * 失败时<b>如实报错</b>。docs/08 §1.3.7 的「记录动作永不失败」说的是识别挂了不能连累落库,
 * 靠的是服务端先落地;离线队列(阶段 2)还没接,这里就不能装作已经存下了。
 */
export function useCreateRecord() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateRecordRequest) => postJson<CreateRecordResponse>('/records', body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: DASHBOARD_KEY })
    },
  })
}

/* ========================================================================== */
/* 骨架层的写 —— 考点管理                                                       */
/* ========================================================================== */

export const ARCHIVED_KEY = ['syllabus', 'archived'] as const

/**
 * 已归档的考点。`GET /api/syllabus/archived`
 *
 * <h2>为什么必须单拉一次</h2>
 *
 * 归档的意思就是<b>退出差集</b>,所以它们不在 `/syllabus/tree` 里 —— 主屏那 18 行看不见它们。
 * 但「一个看不见又删不掉的东西是最糟的状态」(server 侧那个端点的 javadoc):
 * 归档如果没有一处能看见,它就从「弃用」变成了「丢失」。所以考点管理里必须有这一段。
 * <p>
 * 只在考点管理这一屏拉,主屏不拉:主屏说的是差集,而归档过的东西按定义不在差集里。
 */
export function useArchivedNodes(enabled: boolean) {
  return useQuery({
    queryKey: ARCHIVED_KEY,
    queryFn: () => getJson<ArchivedNodesResponse>('/syllabus/archived'),
    enabled,
    retry: false,
    staleTime: 3_000,
  })
}

/**
 * 骨架层的每一次改动 —— server: api/SyllabusAdminController.java。
 *
 * <h2>🔴 一律<b>不做</b>乐观更新</h2>
 *
 * TanStack 的 `onMutate` 能让改名在界面上立刻生效,回滚也只是一行。这里<b>刻意不用</b>:
 * 乐观更新会让每一次失败先显示成成功、再无声地弹回去,用户剩下的是「刚才那下到底存没存」。
 * 这个产品的整个价值就是「界面上的数字是真的」,一次假的成功比十次诚实的失败贵得多。
 * <p>
 * 所以路径只有一条:<b>请求成功 → invalidate → 四个 GET 重新拉 → 界面才变</b>。
 * 中间那半秒行上显示「存…」,失败就把输入框退回服务端的值,并把错误原样贴在行下。
 * <p>
 * 服务端每个写响应都带着新的 `summary`,拿来直接更新会快半拍 —— 但那样一屏上就有了两个
 * 来源:summary 是刚回来的,树还是上一轮的。宁可慢半拍。
 *
 * <h2>为什么是一个 hook 返回一堆 mutation</h2>
 *
 * 因为它们的 `onSuccess` 是同一件事,而这件事必须一致:骨架层动一下,差集的<b>两边</b>都变了,
 * 覆盖率、五态、盲区榜全要重算。漏 invalidate 一处,界面就会挂着一棵新树和一份旧覆盖率。
 * 归档相关的三个还要额外刷 `/archived` —— 归档就是在这两份清单之间搬一个考点,
 * 只刷一边必然出现「树里没了,归档区也没有」。
 */
export function useSyllabusEdit() {
  const qc = useQueryClient()

  const invalidate = () => {
    void qc.invalidateQueries({ queryKey: DASHBOARD_KEY })
  }
  const invalidateBoth = () => {
    invalidate()
    void qc.invalidateQueries({ queryKey: ARCHIVED_KEY })
  }

  const path = (p: string) => `/syllabus${p}`
  const nodePath = (code: string, action: string) => path(`/nodes/${encodeURIComponent(code)}${action}`)
  const groupPath = (code: string, action: string) => path(`/groups/${encodeURIComponent(code)}${action}`)

  /* —— 考点 —— */

  const createNode = useMutation({
    mutationFn: (body: CreateNodeRequest) => postJson<SyllabusWriteResponse>(path('/nodes'), body),
    onSuccess: invalidate,
  })

  /** 🔴 只改 name,code 不动 —— 记录挂在 code 上,所以改名之后一条都不会少。 */
  const renameNode = useMutation({
    mutationFn: (v: { code: string; name: string }) =>
      postJson<SyllabusWriteResponse>(nodePath(v.code, '/rename'), { name: v.name }),
    onSuccess: invalidate,
  })

  const moveNode = useMutation({
    mutationFn: (v: { code: string; groupCode: string }) =>
      postJson<SyllabusWriteResponse>(nodePath(v.code, '/move'), { groupCode: v.groupCode }),
    onSuccess: invalidate,
  })

  const setFrequency = useMutation({
    mutationFn: (v: { code: string; recent5yCount: number }) =>
      postJson<SyllabusWriteResponse>(nodePath(v.code, '/frequency'), { recent5yCount: v.recent5yCount }),
    onSuccess: invalidate,
  })

  /** 「有记录但想弃用」的正确出路:退出差集,code 与记录一条都不动。 */
  const archiveNode = useMutation({
    mutationFn: (code: string) => postAction<SyllabusWriteResponse>(nodePath(code, '/archive')),
    onSuccess: invalidateBoth,
  })

  const unarchiveNode = useMutation({
    mutationFn: (code: string) => postAction<SyllabusWriteResponse>(nodePath(code, '/unarchive')),
    onSuccess: invalidateBoth,
  })

  /** 有记录时服务端回 409 `NODE_HAS_RECORDS`,消息里带条数。<b>没有 force 参数。</b> */
  const deleteNode = useMutation({
    mutationFn: (code: string) => postAction<SyllabusWriteResponse>(nodePath(code, '/delete')),
    onSuccess: invalidateBoth,
  })

  /** 删除守则给出的另一条出路:把记录整体搬走,搬完这个考点就真的能删了。 */
  const moveRecords = useMutation({
    mutationFn: (v: { code: string; toNodeCode: string }) =>
      postJson<SyllabusWriteResponse>(nodePath(v.code, '/records/move'), { toNodeCode: v.toNodeCode }),
    onSuccess: invalidate,
  })

  /* —— 题型 —— */

  const createGroup = useMutation({
    mutationFn: (body: CreateGroupRequest) => postJson<SyllabusWriteResponse>(path('/groups'), body),
    onSuccess: invalidate,
  })

  const renameGroup = useMutation({
    mutationFn: (v: { code: string; name: string }) =>
      postJson<SyllabusWriteResponse>(groupPath(v.code, '/rename'), { name: v.name }),
    onSuccess: invalidate,
  })

  /** 下面还有考点(含已归档的)时回 409 `GROUP_NOT_EMPTY`。 */
  const deleteGroup = useMutation({
    mutationFn: (code: string) => postAction<SyllabusWriteResponse>(groupPath(code, '/delete')),
    onSuccess: invalidateBoth,
  })

  /* —— 顺序 —— */
  /* 🔴 两个都发【完整排列】,不是「移到第 N 位」。服务端会校验它是现有条目的一个排列,
     少一个就等于悄悄删一个,直接 400 ORDER_NOT_A_PERMUTATION。 */

  const reorderGroups = useMutation({
    mutationFn: (groupCodes: string[]) => postJson<SyllabusWriteResponse>(path('/groups/order'), { groupCodes }),
    onSuccess: invalidate,
  })

  const reorderNodes = useMutation({
    mutationFn: (v: { groupCode: string; nodeCodes: string[] }) =>
      postJson<SyllabusWriteResponse>(groupPath(v.groupCode, '/nodes/order'), { nodeCodes: v.nodeCodes }),
    onSuccess: invalidate,
  })

  return {
    createNode,
    renameNode,
    moveNode,
    setFrequency,
    archiveNode,
    unarchiveNode,
    deleteNode,
    moveRecords,
    createGroup,
    renameGroup,
    deleteGroup,
    reorderGroups,
    reorderNodes,
  }
}

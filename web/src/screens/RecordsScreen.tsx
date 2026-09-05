import { useNavigate, useParams, useSearchParams } from 'react-router'
import { useDashboard } from '../api/queries'
import type { TimelineItemDto } from '../api/types'
import { relativeDayTime } from '../lib/format'
import { routeTo } from '../routes/routes'
import { ColL, ColR, Cols, ScreenBody, ScreenHead } from '../ui/layout'
import { GroupHeader, Row, Tag } from '../ui/primitives'
import { EmptyState, FailureBlock, Placeholder } from '../ui/states'

/**
 * `M1 记录`(时间线)+ `M2 打标与未分类`(未分类队列)。
 *
 * <h2>🔴 未分类<b>不是一条新路由</b>,是 `/records?filter=unclassified`</h2>
 *
 * `多端选型与端矩阵` §4.6.2 那张路由表里没有「未分类」这一行,而 §4.6.3 的 query 白名单里
 * 有 `filter`。这两件事合起来说得很明确:<b>未分类是这份记录的一个筛子,不是另一份记录。</b>
 * <p>
 * 反过来做(新开一条 `/inbox`)要付两笔账:路由表多一行要过人审,
 * 而且从那天起「一条记录在哪一屏」变成一个需要判断的问题 —— 它会同时出现在两屏上。
 *
 * <h2>≥1024 双列 —— 稿 `design/m2/m2-ipad.html`(S-TAG)</h2>
 *
 * 这一屏与 `CoverageScreen` <b>同型</b>:左栏是列表,右栏是选中那一条的详情,
 * 而详情是独立的 route id(`/records/:recordId`)。所以两条地址渲染的是同一个组件,
 * 「有没有选中一条记录」决定窄屏上看见哪一栏 —— 不写第二套 DOM。
 * <p>
 * ⚪ <b>稿右栏的下半截本轮没有落地,这是一处登记不是遗漏。</b>
 * 稿上右栏是「记录卡 + 候选考点三条 + 还有 2 个 + 都不是 / 自己挑一个 / 跳过这条」,
 * 后半段是 `M2` 的<b>人工挑选</b>流程,而契约里没有它的落点:`SuggestTagResponse`
 * 只回一个 `candidateCount`(数)和已经落下的 `tags`,<b>没有任何端点返回可供人挑的候选列表</b>
 * (`api/types.ts` 的 `SuggestTagResponse`)。前端自己凑一份候选就正面撞 `R-07`
 * 闭集打标 ——「只从候选集选 id,不生成标签文本」。所以右栏本轮只落稿上那张记录卡对应的部分,
 * 候选面板留给契约补齐之后。
 *
 * <h2>四态</h2>
 *
 * 主态 = 时间线;空态 = 一条记录都没有(和「筛完是空的」是两句不同的话);
 * 失败态 = 拉不到记录;受限态 = 未分类里那几条因额度没能自动挂上考点的,
 * 🔴 它们<b>已经记下了</b>,受限的是打标不是记录。
 */
export function RecordsScreen() {
  const [params] = useSearchParams()
  const { recordId } = useParams<{ recordId?: string }>()
  const navigate = useNavigate()
  const { data, isPending, refetch } = useDashboard()
  const unclassifiedOnly = params.get('filter') === 'unclassified'

  if (isPending || !data) {
    return (
      <>
        <ScreenHead title={<Placeholder w="6ch" />} />
        <ScreenBody>
          <div className="flex flex-col gap-2 px-[var(--rule)] py-5">
            {Array.from({ length: 10 }, (_, i) => (
              <Placeholder key={i} h={22} />
            ))}
          </div>
        </ScreenBody>
      </>
    )
  }

  // 「没挂上考点」= nodeCode 为空。这是 M2 四种成因在读侧的共同表现,
  // 至于是哪一种成因,要看那条记录自己的失败落点 —— 列表这一层不猜。
  const unclassified = data.records.filter((r) => r.nodeName === null)
  const shown = unclassifiedOnly ? unclassified : data.records
  const selected = recordId !== undefined ? (data.records.find((r) => r.id === recordId) ?? null) : null

  // 地址里有一个指不到任何记录的标识。与 `CoverageScreen` 的 NODE_NOT_FOUND 同档:
  // terminal —— 再拉一次列表还是同一个结果,所以不给重试。
  if (recordId !== undefined && selected === null) {
    return (
      <>
        <ScreenHead title="记录" sub={recordId} />
        <ScreenBody>
          <FailureBlock
            code="RECORD_NOT_FOUND"
            scope="这条记录"
            onFallback={() => void navigate(routeTo('records'))}
          />
        </ScreenBody>
      </>
    )
  }

  return (
    <>
      <ScreenHead
        title="记录"
        sub={`${data.records.length} 条`}
        right={
          <span className="flex items-center gap-1.5">
            <FilterTab to="/records" on={!unclassifiedOnly} label="全部" />
            <FilterTab
              to="/records?filter=unclassified"
              on={unclassifiedOnly}
              label={`未分类 ${unclassified.length}`}
            />
          </span>
        }
      />

      <Cols>
        {/* 左栏 = 列表。选中一条之后,<1024 上它让位给详情;≥1024 上两栏并存。 */}
        <ColL>
          <div
            className={`min-h-0 flex-1 flex-col overflow-y-auto ${
              selected !== null ? 'hidden wide:flex' : 'flex'
            }`}
          >
            {data.source === 'mock' ? (
              <FailureBlock code={null} scope="记录列表" onRetry={() => void refetch()} />
            ) : null}

            {shown.length === 0 ? (
              unclassifiedOnly ? (
                <EmptyState
                  title="没有未分类的记录"
                  body="每一条都挂上考点了。挂不上的会留在这儿等你自己挑 —— 匹配不上就丢弃,不硬塞一个。"
                  action={{ label: '看全部记录', onClick: () => void navigate('/records') }}
                />
              ) : (
                <EmptyState
                  title="还没有记录"
                  body="记第一笔之后,这里会按时间倒着排。写、说、拍三条路记下来的东西在这一屏长得一样。"
                  action={{ label: '去记一笔', onClick: () => void navigate(routeTo('capture')) }}
                />
              )
            ) : (
              <>
                <GroupHeader
                  title={unclassifiedOnly ? '未分类' : '时间线'}
                  right={`${shown.length}`}
                  alarm={unclassifiedOnly && unclassified.length > 0}
                />
                {shown.map((r) => (
                  <RecordRow
                    key={r.id}
                    item={r}
                    selected={r.id === recordId}
                    onOpen={() => void navigate(routeTo('records.detail', { recordId: r.id }))}
                  />
                ))}
              </>
            )}
          </div>
        </ColL>

        {/* 右栏 = 选中那一条记录。没选中时它在窄屏上整栏不渲染,在宽屏上是一句提示。
            空态两行照 `CoverageScreen` 那一处:第二句不是补充说明,它是这一栏此刻的用法。 */}
        <ColR>
          <div
            className={`min-h-0 flex-1 flex-col overflow-y-auto ${
              selected === null ? 'hidden wide:flex' : 'flex'
            }`}
          >
            {selected === null ? (
              <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-1 px-[var(--rule)] text-center">
                <span className="text-[13px] text-t2">从左边挑一条记录</span>
                <span className="text-[12px] text-t3">点开哪条,它记下了什么就在这里。</span>
              </div>
            ) : (
              <RecordDetail
                item={selected}
                onBack={() => void navigate(routeTo('records'))}
                onPickNode={() => void navigate(routeTo('coverage'))}
              />
            )}
          </div>
        </ColR>
      </Cols>
    </>
  )
}

function FilterTab({ to, on, label }: { to: string; on: boolean; label: string }) {
  const navigate = useNavigate()
  return (
    <button
      type="button"
      onClick={() => void navigate(to)}
      className={`h-[22px] rounded-xs border px-2 font-mono text-[11px] ${
        on ? 'border-acid text-acid' : 'border-hair text-t3 hover:text-tx'
      }`}
    >
      {label}
    </button>
  )
}

/**
 * 一条记录。<b>两屏共用同一份</b> —— `/records` 与 `/capture` 的 ≥1024 左栏。
 *
 * 🔴 行上<b>没有内容</b>:显示的是考点名、来源的名字、形式、什么时候。
 * 粘进来的那段文字不在这一行上,因为它不在库里。
 *
 * 🔴 <b>不给 `onOpen` 就是只读的</b>:`Row` 那时渲染成 `div` 而不是 `button`,
 * 连焦点都拿不到。`/capture` 左栏用的正是这一档 —— 它是回看,不是第二个记录管理面。
 * 抄一份「只读版的行」出去的代价不是多几行,是同一条记录从此有两种长相。
 */
export function RecordRow({
  item,
  selected = false,
  onOpen,
}: {
  item: TimelineItemDto
  selected?: boolean
  onOpen?: () => void
}) {
  return (
    <Row
      height="auto"
      className={`min-h-[46px] py-2 ${selected ? 'bg-bg2' : ''}`}
      onClick={onOpen}
    >
      <span className="w-[9ch] shrink-0 font-mono text-[11px] text-t3 tabular-nums">
        {relativeDayTime(item.occurredAt)}
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-[12.5px] text-tx">
          {item.nodeName ?? <span className="text-red">未挂考点</span>}
        </span>
        <span className="block truncate font-mono text-[11px] text-t3">{item.sourceName}</span>
      </span>
      <Tag>{item.kindLabel}</Tag>
    </Row>
  )
}

/**
 * 单条记录的详情 —— `U1.7`,地址 `/records/:recordId`。
 *
 * 它是独立 route id 而不是一个浮层,理由与 `coverage.node` 同:
 * 删除是<b>一条带前置条件的命令</b>,要有一个能被地址指到的落点 ——
 * 但那也只是这一屏上的一个按钮,不是 `/delete-record/:id`
 * (§4.6.2 三条命名规矩的第三条:路径段只表示位置,不表示动作)。
 */
function RecordDetail({
  item,
  onBack,
  onPickNode,
}: {
  item: TimelineItemDto
  onBack: () => void
  /** 🔴 挂不上考点时的下一步是<b>去树里自己挑一个</b>,不是回列表 —— 回列表等于什么都没发生。 */
  onPickNode: () => void
}) {
  return (
    <div className="px-[var(--rule)] py-5">
      {/* ≥1024 上左栏就在旁边,这一行是多余的;窄屏上它是唯一一条回列表的路 ——
          P-NAV 说返回归浏览器返回键,所以这里给的是「回列表」这个位置,不是一个返回箭头。 */}
      <button
        type="button"
        onClick={onBack}
        className="mb-4 font-mono text-[11px] text-t3 underline hover:text-tx wide:hidden"
      >
        回列表
      </button>

      <dl className="grid grid-cols-2 gap-x-6 gap-y-4">
        <Fact k="什么时候" v={relativeDayTime(item.occurredAt)} />
        <Fact k="来源" v={item.sourceName} />
        <Fact k="题型" v={item.groupName ?? '—'} />
        <Fact k="形式" v={item.kindLabel} />
        {/* 🔴 2026-09-06(`KUBI-111`):「对了」那一格摘掉。两个数并排读出来就是答得对不对 ——
            与退役稿那行「练 8 对 4」加一个百分比同源,只少一次除法。
            「练了」留着:它只回答「几道」,是一个次数。
            两个数照旧收(`CaptureSheet` 的两格没动),`correct` 也仍在契约里。 */}
        <Fact k="练了" v={item.practiced === null ? '—' : `${item.practiced} 道`} />
      </dl>

      {item.nodeName === null ? (
        <FailureBlock
          code="NO_MATCH_AND_NO_USER_NODE"
          scope="这条记录的考点"
          onFallback={onPickNode}
        />
      ) : null}
    </div>
  )
}

function Fact({ k, v }: { k: string; v: string }) {
  return (
    <div>
      <dt className="mb-1 font-mono text-[10px] tracking-[0.1em] text-t3 uppercase">{k}</dt>
      <dd className="text-[13px] break-words text-tx">{v}</dd>
    </div>
  )
}

import { useNavigate, useParams, useSearchParams } from 'react-router'
import { useDashboard } from '../api/queries'
import type { TimelineItemDto } from '../api/types'
import { relativeDayTime } from '../lib/format'
import { routeTo } from '../routes/routes'
import { ScreenBody, ScreenHead } from '../ui/layout'
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
 * <h2>四态</h2>
 *
 * 主态 = 时间线;空态 = 一条记录都没有(和「筛完是空的」是两句不同的话);
 * 失败态 = 拉不到记录;受限态 = 未分类里那几条因额度没能自动挂上考点的,
 * 🔴 它们<b>已经记下了</b>,受限的是打标不是记录。
 */
export function RecordsScreen() {
  const [params] = useSearchParams()
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

      <ScreenBody>
        <div className="kb-cap">
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
                  onOpen={() => void navigate(routeTo('records.detail', { recordId: r.id }))}
                />
              ))}
            </>
          )}
        </div>
      </ScreenBody>
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
 * 一条记录。
 *
 * 🔴 行上<b>没有内容</b>:显示的是考点名、来源的名字、形式、什么时候。
 * 粘进来的那段文字不在这一行上,因为它不在库里。
 */
function RecordRow({ item, onOpen }: { item: TimelineItemDto; onOpen: () => void }) {
  return (
    <Row height="auto" className="min-h-[46px] py-2" onClick={onOpen}>
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
 * 单条记录 —— `/records/:recordId`。
 *
 * 它存在的理由只有一条:`U1.7` 的删除动作要有一个能被地址指到的落点。
 * 🔴 删除是<b>一条带前置条件的命令</b>,不是「让一个资源消失」——
 * 所以它是这一屏上的一个按钮,不是 `/delete-record/:id` 那种路由
 * (§4.6.2 三条命名规矩的第三条:路径段只表示位置,不表示动作)。
 */
export function RecordDetailScreen() {
  const { recordId } = useParams<{ recordId: string }>()
  const navigate = useNavigate()
  const { data, isPending } = useDashboard()

  if (isPending || !data) {
    return (
      <>
        <ScreenHead title={<Placeholder w="8ch" />} />
        <ScreenBody>
          <div className="px-[var(--rule)] py-6">
            <Placeholder h={80} />
          </div>
        </ScreenBody>
      </>
    )
  }

  const item = data.records.find((r) => r.id === recordId) ?? null
  if (item === null) {
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
      <ScreenHead title={item.nodeName ?? '未挂考点'} sub={item.kindLabel} />
      <ScreenBody>
        <div className="kb-cap px-[var(--rule)] py-5">
          <dl className="grid grid-cols-2 gap-x-6 gap-y-4">
            <Fact k="什么时候" v={relativeDayTime(item.occurredAt)} />
            <Fact k="来源" v={item.sourceName} />
            <Fact k="题型" v={item.groupName ?? '—'} />
            <Fact k="形式" v={item.kindLabel} />
            <Fact k="练了" v={item.practiced === null ? '—' : `${item.practiced} 道`} />
            <Fact k="对了" v={item.correct === null ? '—' : `${item.correct} 道`} />
          </dl>

          {item.nodeName === null ? (
            <FailureBlock
              code="NO_MATCH_AND_NO_USER_NODE"
              scope="这条记录的考点"
              onFallback={() => void navigate(routeTo('coverage'))}
            />
          ) : null}
        </div>
      </ScreenBody>
    </>
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

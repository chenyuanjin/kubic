import { distributionByState } from '../api/derive'
import { NODE_STATES } from '../api/types'
import type { SubjectDto, SummaryDto } from '../api/types'
import { percentWidth } from '../lib/format'
import { STATE_BAR } from '../lib/nodeState'
import { BigNumber, Label, Meter, StateDot } from '../ui/primitives'

/**
 * 覆盖概览 —— 那个 44% 的大字。
 *
 * 大字是覆盖率,不是分数。分母是考点总数,分子是<b>有记录</b>的考点数,
 * 「有记录」只看有没有,不看答得怎么样。
 *
 * 🔴 大字直接用 `summary.percent`,<b>不拿 covered/total 自己算</b>。
 * 两处算同一个数就一定会算出两个数(一边四舍五入一边截断,44% 和 43% 同屏)。
 * 五段计量条与图例的数字同理,一律读服务端的 `distribution`。
 */
export function CoverageHeader({ summary, subject }: { summary: SummaryDto; subject: SubjectDto }) {
  // 服务端给的是列表(顺序是产品语义),这一屏横向另有排列顺序,所以按 state 建索引再取
  const dist = distributionByState(summary)
  const segments = NODE_STATES.map((state) => ({
    key: state,
    width: percentWidth(dist.get(state)?.count ?? 0, summary.total),
    className: STATE_BAR[state],
  }))

  return (
    <div className="flex shrink-0 flex-wrap items-center gap-x-[26px] gap-y-2 border-b border-hair px-4 py-2.5 sm:gap-y-3 sm:py-3 lg:h-20 lg:flex-nowrap lg:py-0">
      <div className="shrink-0">
        <Label>覆盖</Label>
        <BigNumber value={summary.percent} suffix="%" />
      </div>

      <div className="min-w-[220px] flex-1 lg:max-w-[520px]">
        <div className="flex items-baseline gap-3.5 text-[12px]">
          <span>
            <span className="font-mono tabular-nums">{summary.covered}</span> 个有记录
          </span>
          <span className="text-t2">/</span>
          <span className="text-t2">
            <span className="font-mono tabular-nums">{summary.empty}</span> 个空白
          </span>
          <span className="text-t3">·</span>
          {/* 整块空白是这棵树相对扁平清单的唯一优势,所以它在概览里就要红 */}
          <span className="text-red">
            <span className="font-mono tabular-nums">{summary.whollyEmptyGroups}</span> 组整块空白
          </span>
        </div>

        <div className="my-2">
          <Meter tall segments={segments} />
        </div>

        <div className="flex flex-wrap gap-x-[13px] gap-y-1 font-mono text-[10.5px] text-t3">
          {NODE_STATES.map((state) => (
            <span key={state} className="inline-flex items-center gap-[5px]">
              <StateDot state={state} />
              {/* 中文名由服务端给,前端不硬编码「空白」「生疏」这些词 */}
              {dist.get(state)?.label ?? state}{' '}
              <span className="tabular-nums">{dist.get(state)?.count ?? 0}</span>
            </span>
          ))}
        </div>
      </div>

      <div className="ml-auto hidden text-right xl:block">
        <div className="font-mono text-[11.5px] text-t2">
          {summary.total} 个考点 · {subject.display}
        </div>
        <div className="mt-1.5 font-mono text-[10.5px] text-t3">
          列:状态 / 练·对 / 近五年 / 最近一次
        </div>
        <div className="mt-1 font-mono text-[10.5px] text-t3">实心是碰过的,空心虚线是还没碰过的</div>
      </div>
    </div>
  )
}

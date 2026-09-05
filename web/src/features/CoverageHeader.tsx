import type { SubjectDto, SummaryDto } from '../api/types'

/**
 * 覆盖概览 —— <b>竖式减法</b>:一共 − 你碰过 = 没碰过。
 *
 * <h2>2026-09-06:这一块换掉了整个构成(`KUBI-111`)</h2>
 *
 * 换掉的是「44% 大字 + 五段计量条 + 五档图例(稳/弱/生疏/仅接触/空白)」那一套。
 * 它照的是 `design/archive/ui-a-kubi72/`,而 `design/README.md:60-69` 降级那一代的
 * <b>头一条理由</b>逐字就是这个:五档里的「弱」定义成「练过但用户自填的对/练偏低」,
 * 于是覆盖度被读成了「学得怎么样」—— 产品最没资格说的那句话被做成了一块状态色。
 * <p>
 * 现役稿(`design/m3/01-blind.html:18-34`,与 `design/v10/blind.html:17-33` 逐字节同源)
 * 给的是三行竖式:两行白底 + 一行结果面,重心在最后那个数。三个数全部只回答「有没有」——
 * 分母是考点总数,分子是<b>有记录</b>的考点数,不看答得怎么样。
 *
 * <h2>三处照稿、一处不照稿</h2>
 *
 * 照稿的三处:① 三行两列、数字右对齐成一列(`tabular-nums`);② <b>运算线是结果行的上缘</b>,
 * 不是一条单独画的 `<hr>`;③ 「个」这个量词只进 `aria-label`,不上屏。
 * <p>
 * 不照稿的一处:稿的结果面是长春花蓝 `--accent`,这里是本工程的暗色底 + 一条 1.5px 墨线。
 * 本工程的骨架规则第 8 条把酸性绿定成<b>唯一</b>强调色且只给「当前主操作」和序号,
 * 换一块蓝面进来就是第二个强调色。改的是构成不是配色 —— 配色这条登记给产品裁。
 *
 * 🔴 三个数直接读服务端的 `total` / `covered` / `empty`,<b>不在前端自己相减</b>。
 * 两处算同一个数就一定会算出两个数。
 *
 * 稿上还有四行注脚(已标断言数 / 已归档数 / 未对上记录数 / 统计截止年)和一行口径行
 * (筛选态 + 排序切换),四个字段与那两个交互契约里都没有 —— <b>只登记不造</b>,见议题。
 */
export function CoverageHeader({ summary, subject }: { summary: SummaryDto; subject: SubjectDto }) {
  return (
    <div className="shrink-0 border-b border-hair px-3 py-3">
      <div
        // 竖式整体是一句话,所以 label 挂在外层;三行各自的数字对读屏没有单独意义。
        aria-label={`${subject.display}一共 ${summary.total} 个考点,你碰过 ${summary.covered} 个,没碰过 ${summary.empty} 个`}
      >
        <div className="flex items-baseline py-[3px]">
          <span className="text-[13px] text-t2">一共</span>
          <span className="ml-auto font-mono text-[22px] text-tx tabular-nums">{summary.total}</span>
        </div>
        <div className="flex items-baseline py-[3px]">
          <span className="text-[13px] text-t2">你碰过</span>
          {/* 减号只在第二行 —— 第一行没有运算符,这是竖式而不是三个并列的数 */}
          <span aria-hidden className="ml-auto mr-2.5 font-mono text-[15px] text-t3">
            −
          </span>
          <span className="font-mono text-[22px] text-tx tabular-nums">{summary.covered}</span>
        </div>

        {/* 🔴 运算线 = 这一行的上缘。画成独立的横线元素会在 hairline(1px)体系里
            多出一种线宽,而这条线不是分隔线,它是算式的一部分。 */}
        <div className="mt-2.5 flex items-baseline border-t-[1.5px] border-tx pt-3">
          <span className="text-[13px] text-tx">没碰过</span>
          <span className="ml-auto font-mono text-[40px] leading-none text-tx tabular-nums">
            {summary.empty}
          </span>
        </div>
      </div>

      {/* 整块空白是这棵三层树相对扁平清单的唯一优势(决策记录 §2.5),所以它在概览里就要红。
          它同样只回答「有没有」:这一整个题型下一个考点都没碰过。 */}
      {summary.whollyEmptyGroups > 0 && (
        <p className="mt-3 font-mono text-[11px] text-red">
          <span className="tabular-nums">{summary.whollyEmptyGroups}</span> 组整块空白
        </p>
      )}

      <p className="mt-1.5 font-mono text-[10.5px] text-t3">
        {subject.display} · 实心是碰过的,空心虚线是还没碰过的
      </p>
    </div>
  )
}

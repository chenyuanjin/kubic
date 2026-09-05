const DAY_MS = 86_400_000

/**
 * 距今几天 —— <b>按自然日算,不按经过的小时数算</b>。
 *
 * 昨晚 23:00 和今早 07:00 只隔 8 小时,但用户心里那是「昨天」。
 * 用 `(now - t) / 86400000` 取整会把它算成「今天」,同理会把 32 天前的记录
 * 显示成「31天前」—— 差一天,而这一列正是能力边界里的「多久前」。
 *
 * 所以两边都归零到当天零点再相减。用 round 而不是 floor 是为了扛过夏令时那两天
 * (23 小时 / 25 小时的自然日)。
 */
export function daysAgo(iso: string | null, now: number = Date.now()): number | null {
  if (!iso) return null
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return null
  return Math.max(0, Math.round((startOfDay(now) - startOfDay(t)) / DAY_MS))
}

function startOfDay(ms: number): number {
  const d = new Date(ms)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}

/**
 * 「多久前」—— 能力边界里的第三个词,界面上出现频率最高的一列。
 *
 * 没有记录时返回破折号,不是「0 天前」:没碰过和刚碰过是两回事。
 */
export function relativeDay(iso: string | null, now: number = Date.now()): string {
  const d = daysAgo(iso, now)
  if (d === null) return '—'
  if (d === 0) return '今天'
  if (d === 1) return '昨天'
  return `${d}天前`
}

/** 「今天 09:40」这种带钟点的写法,时间线与最近记录用。 */
export function relativeDayTime(iso: string, now: number = Date.now()): string {
  const t = new Date(iso)
  const hh = String(t.getHours()).padStart(2, '0')
  const mm = String(t.getMinutes()).padStart(2, '0')
  return `${relativeDay(iso, now)} ${hh}:${mm}`
}

/*
 * 🔴 这里原本有一个 accuracyText —— 把 correct/practiced 渲染成百分比的那一列。
 * KUBI-107 按 `B0` §11.4 整条删掉:`design/README.md:45` 的平铺禁令赢,
 * 用户自己填的两个整数相除得到的那个比值也算禁的那一个词。
 * <p>
 * 🔴 2026-09-06(`KUBI-111`)再删一层:当时留下的 drillText(「12/10」)与
 * blindReason 的「练 N 对 M」<b>也是同一个判断</b>,少一次除法而已。判据不是它含不含
 * 禁用词(它一个都不含,65 词硬名单从头到尾是绿的),而是<b>把「做了多少」和「对了多少」
 * 并排放上屏,读出来的就是答得对不对</b>。退役稿 `design/archive/ui-a-kubi72/app.html:239`
 * 那一行是「练 8 对 4」再跟一个百分比 —— 摘掉的是后半截,活下来的是同一个判断。
 * <p>
 * 两个数<b>照旧收</b>(`CaptureSheet` 的「练」「对」两格没动),删掉的只有把它们并排
 * 显示回去的那几处。`NodeView.practiced / correct / accuracy` 三个字段也没跟着删:
 * 它们是服务端 NodeDetailDto 的契约字段,前端类型逐字段对着它写(见 api/types.ts
 * 开头那句),删了就不再是同一份契约。
 */

/**
 * 碰没碰过 —— 呈现层认得的<b>唯一</b>一档状态。
 *
 * 判据是 `touchCount`,不是服务端那五档 `state`:「有没有记录」是一个计数事实,
 * 不需要经过「稳 / 弱 / 生疏 / 仅接触 / 空白」那把阶梯。五档为什么从呈现层整个撤掉,
 * 见 `lib/nodeState.ts` 头上那段。
 */
export function isTouched(node: { touchCount: number }): boolean {
  return node.touchCount > 0
}

/**
 * 一个考点的「我的事实」—— 现役稿 `design/m3/01-blind.html:51` 那个下行。
 *
 * 稿把一行切成上下两行是<b>硬性</b>的:上行是骨架事实(真题的,近五年出现几次),
 * 下行是我的事实(你的)。这个函数只负责下行,而下行的取值稿上就三种,
 * 逐字都在这里 —— 全是「有没有 / 几次 / 多久前」,一个「对」字都没有。
 */
export function myFact(
  // 结构化入参而不是整个 NodeView:这一层只读两个字段,而写成 NodeView 就得在测试里
  // 造一个 16 字段的对象才能验三句话 —— 那会让「纯判断层能在 node 里被单独测」变贵。
  node: { touchCount: number; latestAt: string | null },
  now: number = Date.now(),
): string {
  if (node.touchCount === 0) return '你没碰过'
  if (node.latestAt === null) return `你碰过 ${node.touchCount} 次`
  return `你碰过 ${node.touchCount} 次,最近一次 ${relativeDay(node.latestAt, now)}`
}

/** 两位序号:01 02 … 18。等宽字体下这一列才对得齐。 */
export function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

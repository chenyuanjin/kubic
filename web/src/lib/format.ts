import type { NodeView } from '../api/types'

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

/**
 * 用户自填正确率的显示值。
 *
 * 三种情况都显示「—」,而且都不是 0%:
 * <b>没练过</b>(0% 会被读成「答全错了」,那是一句产品没资格说的话)、
 * <b>算不出来</b>(时间线被截断,做题数不全)。
 * 这一列宁可空着,也不给一个不确定的数 —— 它是覆盖度失真最容易溜进来的地方。
 */
export function accuracyText(node: NodeView): string {
  return node.accuracy === null ? '—' : `${Math.round(node.accuracy * 100)}%`
}

/** 「12/10」这一列:练了几道 / 对了几道。两个数都是用户自己敲进来的。 */
export function drillText(node: NodeView): string {
  // null = 不知道(记录不全),不是「一道没练」—— 两者绝不能显示成同一个样子
  if (node.practiced === null) return node.touchCount > 0 ? `${node.touchCount} 条记录` : '—'
  if (node.practiced > 0) return `${node.practiced}/${node.correct ?? 0}`
  if (node.touchCount > 0) return `听课${node.touchCount}次,未练`
  return '—'
}

/**
 * 「先补这几个」里那行理由。
 *
 * 全部由已有字段拼出来,没有新的判断。它要回答的是「凭什么排这么前」,
 * 而不是「你哪里不会」—— 后者产品答不了。
 */
export function blindReason(node: NodeView): string {
  if (node.practiced !== null && node.practiced > 0) {
    return `练 ${node.practiced} 对 ${node.correct ?? 0} · 正确率 ${accuracyText(node)}`
  }
  if (node.state === 'EMPTY') {
    return `近五年 ${node.recent5yCount} 次 · 无任何记录`
  }
  // 练没练不确定时,只说得出「多久前」—— 就只说这一句
  const touched = node.practiced === null ? `${node.touchCount} 条记录` : '听过没练'
  return `近五年 ${node.recent5yCount} 次 · ${touched} · ${relativeDay(node.latestAt)}`
}

/** 百分比字符串,给计量条宽度用。保留一位小数,免得五段加起来不是 100%。 */
export function percentWidth(part: number, total: number): string {
  return total === 0 ? '0%' : `${((part / total) * 100).toFixed(1)}%`
}

/** 两位序号:01 02 … 18。等宽字体下这一列才对得齐。 */
export function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

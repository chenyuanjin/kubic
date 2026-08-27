import type { NodeState, TouchKind } from '../api/types'

/**
 * 五态的<b>颜色</b> —— 纯呈现层。
 *
 * 状态本身由后端从「有没有 / 几次 / 多久前」推出(server 侧 NodeState),
 * 这里只负责把它翻译成一块颜色,不参与任何判断。
 *
 * 🔴 <b>中文名不在这里。</b>「空白」「生疏」这些词由服务端随 `stateLabel` /
 * `StateCountDto.label` 一起给(server 侧 NodeDto、StateCountDto 的 javadoc 明写了这条),
 * 前端硬编码一份就是第二个真相 —— 状态改名时会变成改两端,而且必定漏一边。
 * 颜色留在前端是因为它<b>不是数据</b>,后端不该知道酸性绿是哪个色号。
 */

/** 状态点的样式。实心 = 碰过,空心虚线 = 还没碰过。 */
export const STATE_DOT: Record<NodeState, string> = {
  STABLE: 'bg-s-stable',
  WEAK: 'bg-s-weak',
  RUSTY: 'bg-s-rusty',
  TOUCHED_ONLY: 'border-[1.5px] border-s-touch',
  EMPTY: 'border border-dashed border-s-empty',
}

/** 计量条里那一段的底色。 */
export const STATE_BAR: Record<NodeState, string> = {
  STABLE: 'bg-s-stable',
  WEAK: 'bg-s-weak',
  RUSTY: 'bg-s-rusty',
  TOUCHED_ONLY: 'bg-s-touch',
  EMPTY: 'bg-s-empty',
}

/**
 * 状态文字要不要标红。
 *
 * 只有「弱」标红 —— 红是唯一告警色,给弱 / 空白块 / 危险动作。
 * 「空白」在行里不标红,因为整块空白已经由红色分组头承担了,
 * 十行都红等于没有红。
 */
export function stateIsAlarming(state: NodeState): boolean {
  return state === 'WEAK'
}

/**
 * 记录形式的中文名。与 server 侧 `TouchKind.label()` 一致。
 *
 * 这一份留在前端是有理由的:它用在<b>提交之前</b>的那句「将记为 记做题」——
 * 那一刻还没有任何响应可读,服务端的 `kindLabel` 要等这一笔落地才拿得到。
 * 已经落地的记录(时间线)一律显示服务端给的 `kindLabel`,不走这张表。
 */
export const KIND_LABEL: Record<TouchKind, string> = {
  VOICE: '语音记',
  PHOTO: '拍照记',
  PASTE: '粘一段',
  DRILL: '记做题',
  MANUAL: '手动挂',
}

/**
 * 会不会消耗 AI 录入额度。与 server 侧 TouchKind.consumesAiQuota() 一致。
 *
 * 界面上要看得见这条区分:额度用尽时停掉的只有前两种,后三种永远可用 ——
 * 「额度用尽 ≠ 记不了」(docs/11 §二)。
 */
export const KIND_USES_AI: Record<TouchKind, boolean> = {
  VOICE: true,
  PHOTO: true,
  PASTE: false,
  DRILL: false,
  MANUAL: false,
}

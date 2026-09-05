import type { TouchKind } from '../api/types'

/**
 * 碰没碰过 —— 呈现层认得的<b>唯一</b>一档状态。
 *
 * <h2>2026-09-06:五档塌成两档(`KUBI-111`)</h2>
 *
 * 这里原本有三样东西:`STATE_DOT`(五档状态色)、`STATE_BAR`(计量条五段底色)、
 * `stateIsAlarming`(「弱」标红)。三样一起删掉,理由是同一条:
 * 五档里的「弱」在 `index.css` 里的定义逐字是「练过,但用户自填的对/练偏低」——
 * <b>把「答得对不对」做成了一块状态色</b>,而这个产品的边界是不判对不对。
 * `design/README.md:60-69` 降级 `ui-a/` 那一代的头一条理由写的就是这个。
 * <p>
 * 现役稿(`design/m3/04-tree.html:6`)原文:「三态靠文字,实心/空心标记只是辅助」——
 * <b>标记只有两个字符 `●`/`○`、单色、`aria-hidden`</b>。所以呈现层只需要一个布尔。
 *
 * 🔴 服务端的 `NodeState` 五档<b>没有动</b>,`NodeDto.state / stateLabel` 照旧收 ——
 * 契约是服务端的,不由前端删。删掉的只有前端把它渲染成一把阶梯的那几处。
 * 前端也不再显示 `stateLabel`:那个中文名(空白/仅接触/生疏/弱/稳)是五档本身,
 * 显示它等于把五档换个地方摆回去。

 * 「碰没碰过」这个判断本身落在 `lib/format.ts` 的 {@link isTouched} —— 和 `myFact`
 * 同一层(都是一个考点的接触事实),而且那一层没有任何 import,跑得进 node 被单测。
 */

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
 * 「额度用尽 ≠ 记不了」(docs/product/商业化与额度设计.md §二)。
 */
export const KIND_USES_AI: Record<TouchKind, boolean> = {
  VOICE: true,
  PHOTO: true,
  PASTE: false,
  DRILL: false,
  MANUAL: false,
}

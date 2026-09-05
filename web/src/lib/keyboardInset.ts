/**
 * 软键盘从屏底吃掉的那一段高度,单位 px。
 *
 * <h2>为什么这一层里没有 `window`</h2>
 *
 * 这是纯判断层:它只吃三个数,不碰 `visualViewport`、不碰 `document`,于是能在 node 里被断言
 * (`tests/keyboardInset.test.ts`)。碰真实浏览器 API 的那一半在 `AppShell` 里,只有三行,
 * 且不含任何判断。两层合起来写的话,没被测到的那一半就有了藏逻辑的地方。
 *
 * <h2>算法</h2>
 *
 * 布局视口(`window.innerHeight`)不随软键盘收缩 —— iPad 实测(iPad Pro 13″ / iOS 26.5,
 * 2026-09-05):`100dvh` 跟地址栏走,不跟键盘走。而<b>视觉</b>视口是收缩的,
 * 于是被吃掉的高度 = 布局高 − 视觉高 − 视觉视口顶部偏移(页面被顶上去的那一段)。
 *
 * <h2>为什么有个 24 的下限</h2>
 *
 * 地址栏伸缩、橡皮筋回弹与亚像素取整都会让这个差值在 0 附近抖几个 px。
 * 把这种抖动写进 CSS 变量,屏底动作区就会跟着抖 —— 而它是「全产品动作的固定住址」。
 * 24 取的是「比任何一次抖动都大、比任何一个软键盘都小」的那一档:
 * iPad 上最矮的键盘形态(浮动键盘收起成候选条)也有 50+ px。
 */
export function keyboardInset(
  layoutHeight: number,
  visual: { height: number; offsetTop: number },
): number {
  const eaten = layoutHeight - visual.height - visual.offsetTop
  return eaten > 24 ? Math.round(eaten) : 0
}

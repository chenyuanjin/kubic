/**
 * 软键盘避让的纯判断层断言 —— `KUBI-119`,Pad 真形态那一轮的四件之三。
 *
 * <h2>这个文件存在的理由</h2>
 *
 * 「记下」在键盘弹起时够不着,是一件<b>只在真设备上才看得见</b>的事:浏览器窗口拉窄不弹键盘,
 * 于是 113 那一轮量到的几何全对、这条却漏在外面。真设备验一次只能说明「那一次对了」;
 * 要让它以后不再回来,判断得落在一个能在 node 里跑的函数上。
 * <p>
 * 被测的 `src/lib/keyboardInset.ts` 里没有 `window`,碰 `visualViewport` 的三行在 `AppShell`
 * 里且不含判断 —— 与 `routes` / `captchaPolicy` 那两对同型。
 *
 * <h2>🔴 它们红过</h2>
 *
 * <ul>
 *   <li>把下限 24 去掉(`eaten > 0`)→「地址栏抖动不算键盘」当场红:传 1013 报 11 而不是 0</li>
 *   <li>把 `offsetTop` 那一项去掉 → 「页面被顶上去时不重复计一遍」红:报 346 而不是 300</li>
 * </ul>
 */
import assert from 'node:assert/strict'
import test from 'node:test'
import { keyboardInset } from '../src/lib/keyboardInset.ts'

test('键盘没弹:一个 px 都不让', () => {
  assert.equal(keyboardInset(1024, { height: 1024, offsetTop: 0 }), 0)
})

test('键盘弹起:让开被吃掉的那一段', () => {
  // iPad Pro 13″ 竖屏 1366pt,系统键盘 ~372pt。
  assert.equal(keyboardInset(1366, { height: 994, offsetTop: 0 }), 372)
})

test('页面被顶上去时不把同一段算两遍', () => {
  // 视觉视口整体上移 46:布局高 1366 − 视觉高 1020 − 偏移 46 = 300。
  assert.equal(keyboardInset(1366, { height: 1020, offsetTop: 46 }), 300)
})

test('地址栏伸缩与亚像素抖动不算键盘', () => {
  assert.equal(keyboardInset(1024, { height: 1013, offsetTop: 0 }), 0)
  assert.equal(keyboardInset(1024, { height: 1000, offsetTop: 0 }), 0)
  // 24 是下限本身,不含:25 才算。
  assert.equal(keyboardInset(1024, { height: 1000, offsetTop: 0 }), 0)
  assert.equal(keyboardInset(1024, { height: 999, offsetTop: 0 }), 25)
})

test('视觉视口比布局视口高时不返回负数', () => {
  assert.equal(keyboardInset(800, { height: 900, offsetTop: 0 }), 0)
})

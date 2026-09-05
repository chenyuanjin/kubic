/**
 * 覆盖度那一屏的构成 —— `KUBI-111`,把 `/coverage` 从五档状态换回竖式减法那一轮。
 *
 * <h2>这个文件存在的理由</h2>
 *
 * 能力边界扫描(`scripts/capability-boundary-scan.mjs`)这一轮从词表扩到了形态,
 * 它现在拦得住「练 N 对 M」「裸分数列」「五档状态色」这三类<b>写死在源码里</b>的东西。
 * 但它是一把静态尺子,有两件事它<b>看不见</b>:
 *
 * <ul>
 * <li><b>运行时数据。</b>`node.stateLabel` 里的五档中文名(空白/仅接触/生疏/弱/稳)
 *     是服务端送上来的,源码里一个字都没有 —— 把 `{node.stateLabel}` 摆回界面上,
 *     扫描器全绿,而五档原样回来了。这一条只能靠「呈现层里不许出现这个字段」来钉。</li>
 * <li><b>该在的东西没在。</b>扫描是禁令,禁令只能证明坏东西不在,不能证明好东西在。
 *     把 `CoverageHeader` 整个删掉,扫描照样全绿。竖式那三个词得有人正面钉住。</li>
 * </ul>
 *
 * <h2>为什么是这两层</h2>
 *
 * 纯判断层(`myFact` / `isTouched`)不碰任何浏览器 API,直接 import 进来跑;
 * 「哪个字段不许上屏」这类只能读源文本,与 `retentionCopy.test.ts` 同型,
 * 也与本仓那两把扫描尺子同型 —— 不引第三种做法。
 */

import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { isTouched, myFact } from '../src/lib/format.ts'

const SRC = 'src/'

function read(...files: string[]): string {
  return files.map((f) => strip(readFileSync(SRC + f, 'utf8'))).join('\n')
}

/**
 * 注释换成等长空白 —— 行号不偏,而说理不再被当成实现。
 *
 * 🔴 这一步是被自己绊了一次才加上的:下面「blindScore 不再被渲染」那条第一跑就红了,
 * 红在 `BlindSpotSide.tsx` 里一句解释<b>为什么把它删掉</b>的注释上;「不是 &lt;hr&gt;」
 * 那条同理,红在 `CoverageHeader.tsx` 的 javadoc 上。
 * 与 `scripts/design-baseline-scan.mjs:100-104` 的 `maskComments` 同一个坑、同一个解法。
 */
function strip(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\/|(?<!:)\/\/[^\n]*/g, (m) => m.replace(/[^\n]/g, ' '))
}

/* ========================================================================== */
/* 一 · 纯判断层:碰没碰过,只有两档                                            */
/* ========================================================================== */

test('isTouched 只看 touchCount,不经过任何状态阶梯', () => {
  assert.equal(isTouched({ touchCount: 0 }), false)
  assert.equal(isTouched({ touchCount: 1 }), true)
  assert.equal(isTouched({ touchCount: 37 }), true)
})

/* ========================================================================== */
/* 二 · 纯判断层:「我的事实」三种取值,逐字                                     */
/* ========================================================================== */

// 稿:design/m3/01-blind.html:51-53 的下行,取值就这三种。
const NOW = Date.parse('2026-09-06T12:00:00+08:00')

test('没碰过 —— 逐字「你没碰过」', () => {
  assert.equal(myFact({ touchCount: 0, latestAt: null }, NOW), '你没碰过')
  // 🔴 没碰过和刚碰过绝不能长成同一句话
  assert.notEqual(myFact({ touchCount: 0, latestAt: null }, NOW), myFact({ touchCount: 1, latestAt: null }, NOW))
})

test('碰过 —— 逐字「你碰过 N 次,最近一次 X」,只答几次和多久前', () => {
  assert.equal(
    myFact({ touchCount: 3, latestAt: '2026-08-31T09:00:00+08:00' }, NOW),
    '你碰过 3 次,最近一次 6天前',
  )
  assert.equal(myFact({ touchCount: 1, latestAt: '2026-09-06T08:00:00+08:00' }, NOW), '你碰过 1 次,最近一次 今天')
})

test('碰过但没有时间 —— 不编一个「多久前」出来', () => {
  // relativeDay 对 null 回「—」。把它拼进去会变成「最近一次 —」,那是在假装知道。
  assert.equal(myFact({ touchCount: 2, latestAt: null }, NOW), '你碰过 2 次')
})

test('「我的事实」里不会出现对错比 —— 任何输入都不含「对」', () => {
  const outputs = [
    myFact({ touchCount: 0, latestAt: null }, NOW),
    myFact({ touchCount: 2, latestAt: null }, NOW),
    myFact({ touchCount: 8, latestAt: '2026-01-01T00:00:00+08:00' }, NOW),
  ]
  for (const s of outputs) {
    assert.ok(!s.includes('对'), `「我的事实」只答有没有/几次/多久前,这句里出现了「对」:${s}`)
  }
})

/* ========================================================================== */
/* 三 · 呈现层不许再渲染五档 —— 扫描器看不见的那一半                            */
/* ========================================================================== */

/** 会把考点状态说给用户听的那几个文件。 */
const SPEAKERS = [
  'features/CoverageHeader.tsx',
  'features/NodeList.tsx',
  'features/BlindSpotSide.tsx',
  'features/CommandPalette.tsx',
  'screens/CoverageScreen.tsx',
]

test('五档中文名(stateLabel)不再被渲染 —— 它是运行时数据,静态扫描抓不到', () => {
  const src = read(...SPEAKERS)
  assert.ok(
    !/\{[^}\n]*\bstateLabel\b/.test(src),
    'stateLabel 装的是服务端给的五档中文名(空白/仅接触/生疏/弱/稳)。' +
      '把它插回 JSX 里,能力边界扫描仍然全绿,而五档掌握阶梯原样回到了界面上。',
  )
})

test('排序分(blindScore)不再被渲染 —— 契约里留着,界面上不给', () => {
  const src = read(...SPEAKERS)
  assert.ok(
    !/\{[^}\n]*\bblindScore\b/.test(src),
    'blindScore 是服务端算的一个连续数值。摆在每个考点后面,读出来就是给这个考点评了级。',
  )
})

/* ========================================================================== */
/* 四 · 竖式减法在不在 —— 禁令证明不了的那一半                                  */
/* ========================================================================== */

test('竖式三行逐字在 CoverageHeader 上:一共 / 你碰过 / 没碰过', () => {
  const src = read('features/CoverageHeader.tsx')
  // 稿 design/m3/01-blind.html:18-34。三个词一个都不能少,少一个减法就读不成一句话。
  for (const word of ['一共', '你碰过', '没碰过']) {
    assert.ok(src.includes(`>${word}<`), `竖式缺了「${word}」这一行 —— design/m3/01-blind.html:18-34`)
  }
})

test('三个数各读各的字段,前端不自己相减', () => {
  const src = read('features/CoverageHeader.tsx')
  // 🔴 两处算同一个数就一定会算出两个数。「没碰过」读 summary.empty,不写 total - covered。
  assert.ok(src.includes('summary.total') && src.includes('summary.covered') && src.includes('summary.empty'))
  assert.ok(
    !/summary\.total\s*-\s*summary\.covered/.test(src),
    '「没碰过」要读服务端的 summary.empty,不在前端拿 total - covered 再算一遍',
  )
})

test('运算线是结果行的上缘,不是一条单独画的横线', () => {
  const src = read('features/CoverageHeader.tsx')
  // 稿:.panel 的 border-top 就是运算线(design/v10/v10.css:62-63)。
  // 画成独立元素会在 1px 发丝线体系里多出一种线宽,而这条线不是分隔线,它是算式的一部分。
  assert.ok(src.includes('border-t-[1.5px]'), '结果行缺了那条 1.5px 上缘线 —— 竖式没有运算线就不是竖式')
  assert.ok(!src.includes('<hr'), '运算线不该是一个独立的 <hr>')
})

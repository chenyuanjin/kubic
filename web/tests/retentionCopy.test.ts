import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

/**
 * 「留存三处」的逐字表达 —— `design/README.md:37-43` 那三条不可弱化的东西。
 *
 * <h2>为什么这条测试存在</h2>
 *
 * 因为它本来不存在,而这正好是它该存在的证据:2026-09-06 对稿时量到实现里写的是
 * 「转<b>入</b>留存区」,而稿(`design/m8/交互说明.md:64` 的 E-RAW-13、`m8/raw.html` 四处、
 * `m8/raw-detail.html`)逐字是「转<b>进</b>留存区」。三处文案漂了一个字,86 条测试全绿 ——
 * 因为**没有一条测试钉住过这三句话**。「留存三处要有对应测试,不能只在界面上看着有」
 * 说的就是这件事:界面上看着有,不等于它明天还在。
 *
 * <h2>为什么是读源文本,不是渲染组件</h2>
 *
 * 这三条要保的是<b>那句话本身</b>,不是它渲染出来的 DOM。渲染测试保不住「用词」——
 * 把「转进」改成「转入」渲染照样通过。而本仓已有两把同型的尺子
 * (`scripts/capability-boundary-scan.mjs` / `scripts/design-baseline-scan.mjs`)
 * 都是扫源文本的,这条跟着它们走,不引第三种做法。
 * <p>
 * 它不碰浏览器 API,跑得进 node —— 与 `routes.test.ts` 同一层。
 */

const SRC = 'src/'

function read(...files: string[]): string {
  return files.map((f) => readFileSync(SRC + f, 'utf8')).join('\n')
}

/** 会把留存去向说给用户听的那几个文件。 */
const SPEAKERS = ['features/RawImageDrop.tsx', 'features/CaptureSheet.tsx']

test('E-RAW-13:到期去向逐字是「转进留存区」,不是「转入」', () => {
  const src = read(...SPEAKERS)
  assert.ok(
    src.includes('转进留存区'),
    '稿 design/m8/交互说明.md:64 的 E-RAW-13 是「转进留存区」,实现里找不到这五个字',
  )
  assert.equal(
    src.includes('转入留存区'),
    false,
    '「转入留存区」是 2026-09-06 之前漂掉的写法,稿上从来没有过这个词',
  )
})

test('到期是归档保留,不是删除 —— 每一处说到期的地方都得自己说清这一点', () => {
  const src = read(...SPEAKERS, 'screens/SettingsScreen.tsx')
  // 2026-08-30 起到期行为由「删除」改为「归档保留」(main.tsx:17)。
  // 判据不是「没出现『删』字」——「随时可以自己删」是第三处留存表达,必须留着;
  // 判据是:说到期的时候,同一句里得把「不会删 / 不是被删掉」讲出来。
  assert.ok(
    src.includes('不会删') || src.includes('不是被删掉'),
    '到期文案里找不到「不会删」也找不到「不是被删掉」—— 到期口径退回成删除了',
  )
})

test('同意挡在收图之前 —— 那一段的标题就是「收图之前」', () => {
  const src = read('screens/CaptureScreen.tsx')
  assert.ok(src.includes('收图之前'), '收图前置同意那一段的标题没了')
  // 🔴 挡住的是浮层的挂载,不是一条提示。条件里没有 agreed,这道闸就等于没有。
  assert.match(
    src,
    /mode\.seg !== 'photo' \|\| agreed/,
    '收图浮层的挂载条件里没有 agreed —— 前置同意被绕过了',
  )
})

test('立即删除那条路仍在 —— 用户手按的删除是整层唯一的真删', () => {
  const src = read('features/RawImageDrop.tsx')
  assert.ok(src.includes('全部删除'), '原图列表上的「全部删除」没了')
})

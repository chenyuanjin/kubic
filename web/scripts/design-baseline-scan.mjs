#!/usr/bin/env node
/**
 * 底座复用纪律扫描 —— `KUBI-80` 第 2 条硬约束的机器面。
 *
 * <h2>为什么会有这条扫描</h2>
 *
 * 2026-09-05 `KUBI-79` 重审:`tokens.css` 一个字节没改、色值零新增、禁用词零命中、
 * 三道自检全绿 —— 而八份稿里被独立写第二遍的组件有 <b>21 个</b>、被就地覆写的底座类
 * <b>18 个</b>、`.line` 一个类长出 <b>六种</b>行高。全部靠人逐个 grep 量出来。
 * 产品经理的裁定原话:<b>「这一层机器扫不出正是这轮多跑一整轮的原因」</b>。
 *
 * 所以这条扫描只做一件事:把「量一下就现形」变成「跑一条命令就现形」。
 * 它<b>不判视觉</b> —— 好不好看、方向对不对,机器一个字都说不上,那仍然是人的活。
 *
 * <h2>判据,逐条能在别处找到原话</h2>
 *
 * <ul>
 * <li><b>R-1 覆写</b> —— `KUBI-79` 2026-09-05 产品裁定第 ③ 条:
 *     「模块一律不得覆写底座类。底座缺哪一档,就往 `v10.css` 加一档有名字的。」</li>
 * <li><b>R-2 重复</b> —— 同日裁定第 ① 条,「自造组件 ≤ 3」补的第二句:
 *     「跨模块同名同用途,全产品只准有一个定义。第二个模块要用它,就上抬进 `v10.css`。」
 *     原来那句「每模块 ≤ 3」只约束真正只此一处的东西,由人数,这里不数。</li>
 * <li><b>R-3 字阶</b> —— 同日裁定第 ③ 条后半:「字号一律走 `--t-*`;
 *     底座自己那十三个写死值先归位。」所以这条<b>照样扫底座自己</b>——
 *     模块写 22px/26px 不是各自跑偏,是照着底座 demo 页抄的。</li>
 * <li><b>R-4 底座副本</b> —— `design/README.md`:「底座唯一权威是 `design/v10/`」。
 *     九个目录各带一份 `tokens.css` / `v10.css` 副本,审核那轮是人手 `cmp` 了 18 次。
 *     副本一旦被改,上面三条的「底座」就不是同一个东西,整条扫描的前提塌掉。
 *     所以它排在最前面,前提不成立就不必往下报。</li>
 * </ul>
 *
 * <h2>三处刻意的边界,写下来是为了下次有人想改时先推翻它</h2>
 *
 * <b>① 只看模块 `<style>` 里的类定义,不看外部 `.css`。</b>
 * 判据原文就是「模块 inline `<style>` 里定义的类名」。唯一的外部差分层
 * `design/h5/h5.css` 是 2026-09-05 审核认过的形态差分(五处,每处指得回 `M6` 一行),
 * 它写的是 `.h5 .dock{...}` 这类<b>带作用域的</b>覆写,不是 `.line{...}` 那种裸重定义,
 * 两者不同类。<b>但这留了个洞</b>:把覆写搬进一个新的模块 `.css` 就能绕过 R-1/R-2。
 * 洞由 R-4 堵 —— 模块目录里出现第三个 `.css` 即失败,新增差分层必须先过人这一关。
 *
 * <b>② `design/v10/` 自己也当一个模块扫。</b>八份稿的字号是照底座 demo 页抄的,
 * 只扫模块等于让八份稿去追一个底座自己都没遵守的字阶。
 *
 * <c>③ `archive/` 与 `explorations/` 不扫。</c>与能力边界扫描同一个理由:
 * 它们是决策记录,留着原文才知道当时为什么否掉它。
 *
 * <h2>豁免</h2>
 *
 * 按 <b>file + rule + match</b> 生效,四个字段一个不能省,`reason` 空的等于没有豁免。
 * 匹配不到任何一处的豁免条目<b>也算红</b> —— 改完还留着的豁免是一张挡箭牌,
 * 它会替下一处真的越界提前买好单(这条与能力边界扫描同规,原因见那个文件)。
 *
 * 但先看一眼那三条的真正出口,多数情况都不该走豁免:
 * R-1/R-2 的出口是<b>把这个类上抬进 `v10.css`</b>,R-3 的出口是
 * <b>往 `tokens.css` 加一档有名字的 `--t-*`</b>。豁免表是给
 * 「这一处确实不是产品界面」准备的(评审稿标注、logo 字样),不是给赶工准备的。
 *
 * 零依赖,只用 node: 内置模块 —— 和它旁边那条一样,必须能在任何一台没装
 * node_modules 的机器上跑。
 */

import assert from 'node:assert/strict'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { basename, dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = resolve(HERE, '..', '..')
const DESIGN_ROOT = join(REPO_ROOT, 'design')
const BASE_DIR = 'v10'
const BASE_SHEET = join(DESIGN_ROOT, BASE_DIR, 'v10.css')
const BASE_TOKENS = join(DESIGN_ROOT, BASE_DIR, 'tokens.css')
/** 底座副本的文件名。模块目录里只允许这两个 `.css`,外加下面这一个认过的差分层。 */
const BASE_COPIES = new Set(['tokens.css', 'v10.css'])
const ALLOWED_EXTRA_CSS = new Set(['h5/h5.css'])
const SKIP_DIRS = new Set(['archive', 'explorations', 'node_modules'])
const ALLOW_FILE = join(HERE, 'design-baseline-allow.json')
const ALLOW_LABEL = 'web/scripts/design-baseline-allow.json'
const RULES = new Set(['override', 'duplicate', 'font-size'])

function fail(msg) {
  process.stderr.write(`\n底座复用纪律扫描:配置有问题\n  ${msg}\n\n`)
  process.exit(1)
}

/**
 * 把注释整段换成等长空白 —— 换成空串会让后面所有行号偏掉。
 *
 * 必须先做这一步:这个仓库的稿子里注释比代码长,而且注释里天天出现
 * 「`.line` 覆写了底座」「font-size:15px 不在字阶上」这种说理原文。
 * 不屏蔽,这条扫描第一跑就红在自己的说理上 —— 和能力边界那条不敢把
 * 「讲解」放硬名单是同一个坑。
 */
function maskComments(text) {
  const blank = (m) => m.replace(/[^\n]/g, ' ')
  return text.replace(/\/\*[\s\S]*?\*\//g, blank).replace(/<!--[\s\S]*?-->/g, blank)
}

/** offset → 行号(1 起)。稿子都是几百行的量级,线性数就够,不值得上二分。 */
function lineIndex(text) {
  const starts = [0]
  for (let i = 0; i < text.length; i++) if (text[i] === '\n') starts.push(i + 1)
  return (offset) => {
    let lo = 0
    for (let i = 0; i < starts.length; i++) {
      if (starts[i] > offset) break
      lo = i
    }
    return lo + 1
  }
}

const CLASS_TOKEN = /\.(-?[_a-zA-Z][\w-]*)/g
/** `:not(.x)` / `:is(.a,.b)` 里的类名是条件不是对象,先抹掉再取类名。 */
const PSEUDO_ARGS = /:[a-zA-Z-]+\([^)]*\)/g

/**
 * 从一段 CSS 里取出「被定义的类名」。
 *
 * 手写一个不到二十行的扫描,而不是塞一个 CSS parser:这条断言的价值在于
 * 它能在一台什么都没装的机器上跑。代价是它只认最朴素的那层结构 ——
 * 但设计稿的 `<style>` 里本来就只有选择器和声明。
 *
 * 走法:攒字符当选择器,遇 `{` 记一条并清空,遇 `}` 清空。
 * 于是 `@media(...){.col{...}}` 会依次记下 `@media(...)`(以 @ 开头,丢掉)和 `.col`,
 * 声明体因为被 `{` 清过一次,不会被当成选择器。
 */
function classDefs(css, lineAt, offset0 = 0) {
  const out = []
  let start = 0
  let buf = ''
  for (let i = 0; i < css.length; i++) {
    const ch = css[i]
    if (ch === '{') {
      const sel = buf.trim()
      if (sel && !sel.startsWith('@')) {
        const seen = new Set()
        for (const m of sel.replace(PSEUDO_ARGS, '').matchAll(CLASS_TOKEN)) {
          if (seen.has(m[1])) continue
          seen.add(m[1])
          out.push({ cls: m[1], line: lineAt(offset0 + start), sel })
        }
      }
      buf = ''
      start = i + 1
      continue
    }
    if (ch === '}') {
      buf = ''
      start = i + 1
      continue
    }
    if (!buf.trim() && /\s/.test(ch)) start = i + 1
    buf += ch
  }
  return out
}

/** 模块 html 里的每一段 `<style>`。`style="..."` 属性不参与类定义,它定义不了类。 */
function styleBlocks(html) {
  const out = []
  for (const m of html.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/gi)) {
    out.push({ css: m[1], offset: m.index + m[0].indexOf(m[1]) })
  }
  return out
}

/** 写死的字号。行内 `style="font-size:22px"` 一样算 —— 绕过字阶不看它写在哪。 */
const FONT_LITERAL = /font-size:\s*([0-9.]+)(px|rem|em|pt)/gi

function loadAllow() {
  let doc
  try {
    doc = JSON.parse(readFileSync(ALLOW_FILE, 'utf8'))
  } catch (e) {
    fail(`读不到或读不懂豁免表 ${ALLOW_LABEL}:${e.message}。它是这条扫描的一半,不能删。`)
  }
  const list = Array.isArray(doc) ? doc : doc?.allow
  if (!Array.isArray(list)) fail(`${ALLOW_LABEL} 里应当有一个 allow 数组。`)
  const map = new Map()
  list.forEach((it, i) => {
    const where = `${ALLOW_LABEL} allow[${i}]`
    for (const k of ['file', 'rule', 'match', 'reason']) {
      if (typeof it?.[k] !== 'string' || it[k].trim() === '') {
        fail(`${where} 缺少非空的 "${k}" —— file / rule / match / reason 一个都不能省。`)
      }
    }
    const extra = Object.keys(it).filter((k) => !['file', 'rule', 'match', 'reason'].includes(k))
    if (extra.length) fail(`${where} 有多余字段 ${extra.join(' / ')} —— 这张表只认这四个。`)
    if (!RULES.has(it.rule)) fail(`${where} 的 rule "${it.rule}" 不是 ${[...RULES].join(' / ')} 之一。`)
    if (it.reason.trim().length < 12) {
      fail(`${where} 的 reason 太短。要写的是「为什么这一处不是产品界面 / 为什么它不能上抬进底座」。`)
    }
    const key = `${it.file}\u0000${it.rule}\u0000${it.match}`
    if (map.has(key)) fail(`${where} 与前面的条目重复(同 file 同 rule 同 match)。`)
    map.set(key, it)
  })
  return map
}

// —— 自检:`node scripts/design-baseline-scan.mjs --selftest` ——
//
// 这条扫描交付时是<b>红的</b>(84 + 26 + 99),它会在 KUBI-79 底座归位之后转绿。
// 从红转绿是它该走的路 —— 但「解析器写坏了,一处都扫不出来」也长成绿色。
// 所以留这一组断言:解析器塌了它先红,而且不依赖 design/ 里的任何一份稿。
if (process.argv.includes('--selftest')) {
  const cls = (css) => {
    const m = maskComments(css)
    return classDefs(m, lineIndex(m)).map((d) => d.cls)
  }
  const fonts = (t) => [...maskComments(t).matchAll(FONT_LITERAL)].map((m) => m[1] + m[2])

  assert.deepEqual(cls('.line{padding:9px}'), ['line'], '裸重定义')
  assert.deepEqual(cls('.a{color:red}.b{color:blue}'), ['a', 'b'], '声明体不能被当成选择器')
  assert.deepEqual(cls('@media (min-width:1024px){.col{flex:1}}'), ['col'], '@ 规则跳过,内层照收')
  assert.deepEqual(cls('.cand .line .sub{font-weight:600}'), ['cand', 'line', 'sub'], '祖先位置也算')
  assert.deepEqual(cls('.a:not(.b){color:red}'), ['a'], ':not() 里的是条件不是对象')
  assert.deepEqual(cls('/* .fake 覆写了 .line */\n.real{color:red}'), ['real'], '注释里的说理不算命中')
  assert.deepEqual(cls('button.calc__row{width:100%}'), ['calc__row'], '带标签限定')
  assert.equal(classDefs('\n\n.x{color:red}', lineIndex('\n\n.x{color:red}'))[0].line, 3, '行号')

  assert.deepEqual(fonts('font-size:var(--t-body)'), [], '走字阶的不算')
  assert.deepEqual(fonts('<i style="font-size:11.5px">'), ['11.5px'], '行内 style 一样算')
  assert.deepEqual(fonts('<!-- font-size:22px 是错的 -->'), [], 'HTML 注释里的不算')

  const blk = styleBlocks('<head><style>\n.q{color:red}\n</style></head>')
  assert.equal(blk.length, 1)
  assert.deepEqual(classDefs(blk[0].css, lineIndex('<head><style>\n.q{color:red}\n</style></head>'), blk[0].offset)[0].line, 2)

  process.stdout.write('\n底座复用纪律扫描 · 自检通过(12 条:选择器解析 8 · 字号 3 · style 块 1)\n\n')
  process.exit(0)
}

// —— 收文件 ——

function collect(dir, out = []) {
  for (const name of readdirSync(dir).sort()) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) {
      if (!SKIP_DIRS.has(name)) collect(full, out)
      continue
    }
    if (/\.(html|css)$/i.test(name)) out.push(full)
  }
  return out
}

const rel = (full) => relative(REPO_ROOT, full).split('\\').join('/')
const moduleOf = (full) => relative(DESIGN_ROOT, full).split('\\')[0].split('/')[0]

const allow = loadAllow()
const used = new Set()
const passed = (file, rule, match) => {
  const key = `${file}\u0000${rule}\u0000${match}`
  if (!allow.has(key)) return false
  used.add(key)
  return true
}

let files
try {
  files = collect(DESIGN_ROOT)
} catch (e) {
  fail(`扫不到 design/:${e.message}`)
}

// —— R-4:底座副本必须逐字节等于 design/v10/,模块目录里不许有第三个 .css ——
// 排在最前面,因为下面三条的「底座」就是它。前提塌了,后面报什么都不算数。

const baseBytes = new Map([
  ['tokens.css', readFileSync(BASE_TOKENS)],
  ['v10.css', readFileSync(BASE_SHEET)],
])
const copyHits = []
const strayCss = []

for (const full of files) {
  if (!full.endsWith('.css')) continue
  const mod = moduleOf(full)
  if (mod === BASE_DIR) continue
  const name = basename(full)
  if (BASE_COPIES.has(name)) {
    if (!readFileSync(full).equals(baseBytes.get(name))) copyHits.push({ rel: rel(full), name })
    continue
  }
  if (!ALLOWED_EXTRA_CSS.has(`${mod}/${name}`)) strayCss.push({ rel: rel(full) })
}

// —— R-1 / R-2 / R-3 ——

const baseText = maskComments(readFileSync(BASE_SHEET, 'utf8'))
const BASE = new Set(classDefs(baseText, lineIndex(baseText)).map((d) => d.cls))

/** 类名 → 定义它的模块 → 出处。模块内多处定义只算一次,报第一处。 */
const defs = new Map()
const overrides = []
/**
 * 同一个文件里同一个底座类只报一处。
 *
 * `.cand .line{…}` `.cand .line .sub{…}` `.line{…}` 是三条规则,但它们说的是同一件事:
 * 这个模块动了底座的 `.line`。报三遍只会让人少读两行。
 *
 * 祖先位置也算 —— `.foot p{padding:3px 0}` 没写 `.foot{…}`,但底座的屏底说明区
 * 在这一屏就是长得不一样。前端照样得问一句「这里行距多少」,和裸覆写同一个后果。
 */
const overrideSeen = new Set()
const fontHits = []

for (const full of files) {
  const mod = moduleOf(full)
  const relPath = rel(full)
  const isCopy = mod !== BASE_DIR && full.endsWith('.css') && BASE_COPIES.has(basename(full))
  // 副本的内容已经由 R-4 保证等于底座,再扫一遍只会把底座自己的 2 处字面量报九遍。
  if (isCopy) continue

  const text = maskComments(readFileSync(full, 'utf8'))
  const lineAt = lineIndex(text)

  if (full.endsWith('.html')) {
    for (const blk of styleBlocks(text)) {
      for (const d of classDefs(blk.css, lineAt, blk.offset)) {
        if (BASE.has(d.cls)) {
          const key = `${relPath} ${d.cls}`
          if (!overrideSeen.has(key) && !passed(relPath, 'override', d.cls)) {
            overrideSeen.add(key)
            overrides.push({ rel: relPath, line: d.line, cls: d.cls, sel: d.sel })
          }
          continue
        }
        if (!defs.has(d.cls)) defs.set(d.cls, new Map())
        const byMod = defs.get(d.cls)
        if (!byMod.has(mod)) byMod.set(mod, { rel: relPath, line: d.line })
      }
    }
  }

  for (const m of text.matchAll(FONT_LITERAL)) {
    const lit = `${m[1]}${m[2]}`
    if (passed(relPath, 'font-size', lit)) continue
    fontHits.push({ rel: relPath, line: lineAt(m.index), lit })
  }
}

const duplicates = []
for (const [cls, byMod] of defs) {
  if (byMod.size < 2) continue
  const where = [...byMod.entries()].sort((a, b) => a[0].localeCompare(b[0]))
  if (where.every(([, w]) => passed(w.rel, 'duplicate', cls))) continue
  duplicates.push({ cls, where })
}

const stale = [...allow.values()].filter(
  (it) => !used.has(`${it.file}\u0000${it.rule}\u0000${it.match}`),
)

// —— 报 ——

const modules = [...new Set(files.map(moduleOf))].sort()

if (
  copyHits.length === 0 &&
  strayCss.length === 0 &&
  overrides.length === 0 &&
  duplicates.length === 0 &&
  fontHits.length === 0 &&
  stale.length === 0
) {
  process.stdout.write(
    '\n底座复用纪律扫描通过 —— 「八份稿并排放,看不出是八个人画的」(KUBI-80 第 2 条硬约束)\n' +
      `  ${modules.length} 个目录(${modules.join(' ')})/ ${files.length} 个文件\n` +
      `  底座 design/${BASE_DIR}/v10.css 提供 ${BASE.size} 个类,副本 ${
        files.filter((f) => f.endsWith('.css')).length - 2
      } 份逐字节一致\n` +
      `  覆写 0 · 跨模块重名 0 · 写死字号 0 · 豁免表 ${allow.size} 项(全部命中)\n\n`,
  )
  process.exit(0)
}

const out = ['\n底座复用纪律扫描未通过 —— KUBI-80 第 2 条硬约束 + 2026-09-05 产品裁定三条']

if (copyHits.length || strayCss.length) {
  out.push(
    `\n【R-4 底座副本】${copyHits.length + strayCss.length} 处 —— 这条不过,下面三条报什么都不算数。`,
  )
  for (const h of copyHits) out.push(`  ${h.rel} · 与 design/${BASE_DIR}/${h.name} 不是逐字节一致`)
  for (const h of strayCss) out.push(`  ${h.rel} · 模块目录里多出来的 .css`)
  out.push('  怎么修:副本从 design/v10/ 重新拷一份(底座唯一权威是它,design/README.md 写着);')
  out.push('  确实需要一个新的形态差分层,先在议题里过人这一关,再把它加进脚本的 ALLOWED_EXTRA_CSS ——')
  out.push('  否则「模块不许覆写底座」只要把覆写搬进一个新 .css 就能绕过去。')
}

if (overrides.length) {
  const cls = [...new Set(overrides.map((h) => h.cls))].sort()
  out.push(
    `\n【R-1 覆写底座类】${overrides.length} 处 · 涉及 ${cls.length} 个底座类 —— ` +
      '模块把底座已有的类在自己这儿改了个样子。',
  )
  out.push(`  被动过的底座类:${cls.map((c) => `.${c}`).join(' ')}`)
  for (const h of overrides) out.push(`  ${h.rel}:${h.line} · .${h.cls} · 选择器 \`${h.sel}\``)
  out.push('  怎么修(裁定原话:「模块一律不得覆写底座类」):')
  out.push('   1) 底座缺这一档 → 往 design/v10/v10.css 加一档<b>有名字的</b>(例如台账行的紧凑档),')
  out.push('      高度落 --base:8px 的格,八份稿改成引用它;')
  out.push('   2) 只是这一屏的位置微调 → 那它不该顶着底座类的名字,换一个自己的类名。')
}

if (duplicates.length) {
  out.push(`\n【R-2 跨模块重名】${duplicates.length} 个类 —— 同一件东西被 ≥2 个模块各写了一遍。`)
  for (const d of duplicates) {
    out.push(`  .${d.cls} · ${d.where.map(([m, w]) => `${m}(${w.rel}:${w.line})`).join(' · ')}`)
  }
  out.push('  怎么修(裁定原话:「跨模块同名同用途,全产品只准有一个定义」):')
  out.push('   上抬进 design/v10/v10.css 一次,各模块删掉自己那份 —— 不是把名字改得各不相同。')
  out.push('   「自造组件 ≤ 3」那句只约束真正只此一处的东西,它由人数,这条扫描不数。')
}

if (fontHits.length) {
  const byLit = new Map()
  for (const h of fontHits) byLit.set(h.lit, (byLit.get(h.lit) ?? 0) + 1)
  const tally = [...byLit.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
  out.push(`\n【R-3 绕过字阶】${fontHits.length} 处 —— 字号写死,没走 --t-*。`)
  out.push(`  值分布:${tally.map(([v, n]) => `${v}×${n}`).join(' · ')}`)
  for (const h of fontHits) out.push(`  ${h.rel}:${h.line} · font-size:${h.lit}`)
  out.push('  字阶(design/v10/tokens.css:40-46):--t-hero:56 --t-display:30 --t-body:17')
  out.push('   --t-sub:14 --t-micro:12 --t-btn:16 --t-idx:13')
  out.push('  怎么修:改成 var(--t-*);阶上没有这一级就往 tokens.css 加一档有名字的 ——')
  out.push('  裁定原话是「从底座改起,不是让八份稿去追一个自己都没遵守的字阶」,底座自己的也在上面这张单子里。')
}

if (stale.length) {
  out.push(`\n【豁免表·空转】${stale.length} 项 —— 已经匹配不到任何一处了。`)
  for (const it of stale) out.push(`  ${it.file} · ${it.rule} · ${it.match}`)
  out.push(`  怎么修:从 ${ALLOW_LABEL} 的 allow 数组里删掉。留着的空豁免会替下一处真的越界提前买单。`)
}

out.push('')
process.stderr.write(`${out.join('\n')}\n`)
process.exit(1)

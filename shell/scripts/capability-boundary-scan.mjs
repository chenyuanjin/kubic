#!/usr/bin/env node
/**
 * 能力边界文案扫描 —— 壳侧(docs/08 §四 R-05:产品永不判断「对不对」)。
 *
 * <h2>🔴 词表不在这个文件里,一个字都没有</h2>
 *
 * 它是从 <b>web/scripts/capability-boundary-scan.mjs</b> 里现读出来的。
 *
 * 这不是省事,是这条断言能不能成立的前提。KUBI-65 的验收原话是:
 * <b>「文案扫描撞词时改文案,不要改词表绕过」</b>。
 * 如果壳这边复制一份词表,那句话当场失效 —— 因为壳里那份改了不影响 web,
 * 于是「改词表」就从一件所有人都会看见的事,变成了一件只有壳的人知道的事。
 *
 * 现在这条路被堵死了:改词表只有一处可改,而那一处同时管着 web 的 30 个文件。
 * 代价是这个脚本要按文本去解析另一个脚本里的两个数组 ——
 * 解析不出来时它<b>报错退出</b>,不静默降级成「零命中,通过」。
 * 一条会假绿的断言比没有断言更糟。
 *
 * <h2>扫哪些文件</h2>
 *
 * docs/15 §六 的原话是「壳里所有面向用户的字符串集中在 shell/src/strings.rs 一个文件,
 * 因此只有一处要扫」。这里扫得比那句更宽:<b>src 全树 + tauri.conf.json5</b>。
 * 理由有两条 ——
 *   ① productName 在 tauri.conf.json5 里,它是货真价实的界面文案(菜单栏第一项就是它);
 *   ② 「只有一处」是当下的事实,不是被任何东西保证的事实。扫全树的成本是零,
 *      而漏掉的那一处不会报错,它只会安安静静地在界面上说一句产品没资格说的话。
 *
 * 豁免表:shell/scripts/capability-boundary-allow.json,格式与 web 那份完全一致
 *(file + match + reason,不按行号)。硬名单没有豁免这一说。
 *
 * 零依赖,只用 node: 内置模块 —— 与 web 那份同一条理由:
 * 这条断言必须能在任何一台没装 node_modules 的机器上跑。
 */

import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const SHELL_ROOT = resolve(HERE, '..')
const REPO_ROOT = resolve(SHELL_ROOT, '..')
const WEB_SCAN = join(REPO_ROOT, 'web', 'scripts', 'capability-boundary-scan.mjs')
const WEB_SCAN_LABEL = 'web/scripts/capability-boundary-scan.mjs'
const ALLOW_FILE = join(HERE, 'capability-boundary-allow.json')
const ALLOW_FILE_LABEL = 'shell/scripts/capability-boundary-allow.json'

/** 会变成文案或标识符的文本。二进制、锁文件、生成物扫了只会制造噪音。 */
const SCAN_EXT = new Set(['.rs', '.json5', '.json', '.toml'])

/** 扫描目标:src 全树 + 这几个单文件。 */
const SCAN_DIRS = [join(SHELL_ROOT, 'src'), join(SHELL_ROOT, 'capabilities')]
const SCAN_FILES = [join(SHELL_ROOT, 'tauri.conf.json5')]

const ASCII = /^[\x20-\x7e]+$/

function fail(msg) {
  process.stderr.write(`\n能力边界扫描(壳):配置有问题\n  ${msg}\n\n`)
  process.exit(1)
}

/**
 * 从 web 那份脚本里把一个数组按文本读出来。
 *
 * 先剥掉行注释再取字符串字面量 —— 那两个数组上面写着长长的入选理由,
 * 而理由里为了讲清楚必然会把被禁的词写出来(docs/14 §9.10 记的那条设计教训)。
 * 不剥注释的话,读出来的词表会比真词表多一堆。
 */
function extractList(source, name) {
  const m = source.match(new RegExp(`const ${name} = \\[([\\s\\S]*?)\\n\\]`))
  if (!m) {
    fail(
      `在 ${WEB_SCAN_LABEL} 里找不到 ${name} 数组。\n` +
        `  壳侧的词表是从那个文件现读的(见本脚本顶部)。那边改了结构,这边就要跟着改 ——\n` +
        `  这个错误是【这条断言暂时失效了】,不是「壳没问题」。不要把它当成噪音关掉。`,
    )
  }
  const body = m[1].replace(/\/\/.*$/gm, '')
  const terms = [...body.matchAll(/'([^']*)'/g)].map((x) => x[1]).filter(Boolean)
  if (terms.length < 10) {
    fail(`从 ${WEB_SCAN_LABEL} 读出的 ${name} 只有 ${terms.length} 个词,不合常理 —— 解析多半错了。`)
  }
  return terms
}

let webSource
try {
  webSource = readFileSync(WEB_SCAN, 'utf8')
} catch {
  fail(`读不到 ${WEB_SCAN_LABEL} —— 壳侧的词表就在那里,没有第二份。`)
}
const HARD = extractList(webSource, 'HARD')
const SOFT = extractList(webSource, 'SOFT')

function collect(dir, out = []) {
  let names
  try {
    names = readdirSync(dir).sort()
  } catch {
    return out
  }
  for (const name of names) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) {
      collect(full, out)
      continue
    }
    const dot = name.lastIndexOf('.')
    if (dot > 0 && SCAN_EXT.has(name.slice(dot))) out.push(full)
  }
  return out
}

/** 与 web 那份逐字同构:ASCII 词按小写比,中文词按原文比,同行同词只报一次。 */
function hitsIn(line, terms) {
  const lower = line.toLowerCase()
  const hit = []
  for (const term of terms) {
    const hay = ASCII.test(term) ? lower : line
    if (hay.includes(term)) hit.push(term)
  }
  return hit
}

function loadAllow() {
  let raw
  try {
    raw = readFileSync(ALLOW_FILE, 'utf8')
  } catch {
    fail(`读不到豁免表 ${ALLOW_FILE_LABEL}。它是这条扫描的一半,不能删。`)
  }
  let doc
  try {
    doc = JSON.parse(raw)
  } catch (e) {
    fail(`${ALLOW_FILE_LABEL} 不是合法 JSON:${e.message}`)
  }
  const list = Array.isArray(doc) ? doc : doc?.allow
  if (!Array.isArray(list)) fail(`${ALLOW_FILE_LABEL} 里应当有一个 allow 数组。`)

  const map = new Map()
  list.forEach((it, i) => {
    const where = `${ALLOW_FILE_LABEL} allow[${i}]`
    for (const k of ['file', 'match', 'reason']) {
      if (typeof it?.[k] !== 'string' || it[k].trim() === '') {
        fail(`${where} 缺少非空的 "${k}" —— file / match / reason 三个字段一个都不能省。`)
      }
    }
    const extra = Object.keys(it).filter((k) => !['file', 'match', 'reason'].includes(k))
    if (extra.length) fail(`${where} 有多余字段 ${extra.join(' / ')} —— 这张表只认 file / match / reason。`)
    if (!SOFT.includes(it.match)) {
      fail(
        `${where} 的 match "${it.match}" 不在灰名单里。` +
          (HARD.includes(it.match)
            ? '它是硬名单词 —— 硬名单没有豁免这一说,只能改文案。'
            : '不在任何名单里的词根本不会被扫到,这条豁免是空的。'),
      )
    }
    if (it.reason.trim().length < 12) {
      fail(`${where} 的 reason 太短。要写的是「为什么这处不构成产品替用户判断」,不是「无害」。`)
    }
    const key = `${it.file}\u0000${it.match}`
    if (map.has(key)) fail(`${where} 与前面的条目重复(同 file 同 match)。`)
    map.set(key, it)
  })
  return map
}

// —— 扫 ——

const allow = loadAllow()
const used = new Set()
const files = [...SCAN_DIRS.flatMap((d) => collect(d)), ...SCAN_FILES]
const hardHits = []
const softHits = []
let lines = 0

for (const full of files) {
  let text
  try {
    text = readFileSync(full, 'utf8').split('\n')
  } catch {
    continue
  }
  const rel = relative(REPO_ROOT, full).split('\\').join('/')
  lines += text.length
  text.forEach((line, i) => {
    for (const term of hitsIn(line, HARD)) hardHits.push({ rel, no: i + 1, term })
    for (const term of hitsIn(line, SOFT)) {
      const key = `${rel}\u0000${term}`
      if (allow.has(key)) used.add(key)
      else softHits.push({ rel, no: i + 1, term })
    }
  })
}

const stale = [...allow.values()].filter((it) => !used.has(`${it.file}\u0000${it.match}`))

if (hardHits.length === 0 && softHits.length === 0 && stale.length === 0) {
  process.stdout.write(
    '\n能力边界扫描通过(壳)—— docs/08 §四 R-05「产品永不判断对不对」\n' +
      `  扫描 ${files.length} 个文件 / ${lines} 行(shell/src + capabilities + tauri.conf.json5)\n` +
      `  词表现读自 ${WEB_SCAN_LABEL}:硬名单 ${HARD.length} 词,灰名单 ${SOFT.length} 词\n` +
      `  豁免表 ${allow.size} 项(全部命中)\n\n`,
  )
  process.exit(0)
}

const out = []
out.push('\n能力边界扫描未通过(壳)—— docs/08 §四 R-05「产品永不判断对不对」')
out.push('判据:用户自己填的数不算判定,产品替他判断对错才算。\n')

if (hardHits.length) {
  out.push(`【硬名单】${hardHits.length} 处 —— 这些词在一个不判对错、不做教研的产品里没有合法用法。`)
  for (const h of hardHits) out.push(`  ${h.rel}:${h.no} · ${h.term} · 硬名单`)
  out.push('  怎么修:只有改文案一条路。豁免表对硬名单无效,')
  out.push(`  而词表在 ${WEB_SCAN_LABEL} —— 改它会同时改掉 web 侧那 30 个文件的判据,`)
  out.push('  所以「改词表绕过」在这里不是一条捷径,是一次全仓库范围的决定。\n')
}

if (softHits.length) {
  out.push(`【灰名单·未豁免】${softHits.length} 处 —— 词本身可以合法出现,判据在这一处的上下文。`)
  for (const h of softHits) out.push(`  ${h.rel}:${h.no} · ${h.term} · 灰名单`)
  out.push('  怎么修,二选一:')
  out.push('   1) 改文案 —— 这处确实是产品在替用户下判断,删掉或改写;')
  out.push(`   2) 加豁免 —— 在 ${ALLOW_FILE_LABEL} 的 allow 数组里追加:`)
  const s = softHits[0]
  out.push(`      { "file": "${s.rel}", "match": "${s.term}", "reason": "这一处为什么不是产品替用户判断" }`)
  out.push('      豁免按 file + match 生效;拿不准就别豁免,留着红让人判。\n')
}

if (stale.length) {
  out.push(`【豁免表·空转】${stale.length} 项 —— 在对应文件里已经匹配不到任何一行了。`)
  for (const it of stale) out.push(`  ${it.file} · ${it.match} · 该文件已无此词`)
  out.push('  怎么修:从 allow 数组里删掉。文案改完还留着的豁免是一张挡箭牌。\n')
}

process.stderr.write(`${out.join('\n')}\n`)
process.exit(1)

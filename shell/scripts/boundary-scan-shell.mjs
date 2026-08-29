#!/usr/bin/env node
/**
 * 能力边界文案扫描 —— 壳这一侧(docs/15 §六)。
 *
 * <h2>为什么不复制一份词表过来</h2>
 *
 * 因为复制出来的第二份词表,就是「改词表绕过扫描」这件事的现成入口:
 * 撞词的人只要改壳这一份就行了,web 那份一个字没动,看上去谁也没绕过谁。
 *
 * 所以这里<b>读 `web/scripts/capability-boundary-scan.mjs` 的源文本</b>,
 * 把 HARD / SOFT 两张表抠出来用。词表全仓库只有一份,而且住在 web 里 ——
 * 壳这边连改的地方都没有。这也顺带满足了「web 工程一行不改」:只读,不写。
 *
 * <h2>为什么这边没有豁免表</h2>
 *
 * web 那侧的灰名单要豁免,是因为「正确率是用户自己填的两个整数相除」这类
 * 合法用法真实存在。壳里没有这种东西:壳不新增任何界面,它面向用户的字符串
 * 只有拒绝启动和连不上上游那几句。<b>灰名单在壳里命中,只可能是写偏了。</b>
 * 一张空的豁免表比没有豁免表更危险 —— 它是一个已经打开的口子。
 *
 * 零依赖,只用 node: 内置模块。
 */

import { readFileSync } from 'node:fs'
import { dirname, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO = resolve(HERE, '..', '..')
const WEB_SCAN = resolve(REPO, 'web/scripts/capability-boundary-scan.mjs')

/** 壳里所有面向用户的文本只有这两处。多一处就是漏了一处没扫。 */
const TARGETS = ['shell/src/strings.rs', 'shell/tauri.conf.json']

function fail(msg) {
  process.stderr.write(`\n能力边界扫描(壳):${msg}\n\n`)
  process.exit(1)
}

/** 从 web 那份脚本里抠出一张词表。抠不到就红 —— 静默拿到空表等于这条扫描从来没跑过。 */
function extract(source, name, min) {
  const m = source.match(new RegExp(`const ${name} = \\[([\\s\\S]*?)\\n\\]`))
  if (!m) fail(`在 web/scripts/capability-boundary-scan.mjs 里找不到 ${name} 词表。`)
  const words = []
  for (const line of m[1].split('\n')) {
    const code = line.replace(/^\s*\/\/.*$/, '')
    for (const q of code.matchAll(/'([^']+)'/g)) words.push(q[1])
  }
  if (words.length < min) {
    fail(`${name} 只抠出 ${words.length} 个词(至少应有 ${min} 个)—— 词表格式变了,这条扫描已经失效。`)
  }
  return words
}

let webSource
try {
  webSource = readFileSync(WEB_SCAN, 'utf8')
} catch {
  fail('读不到 web/scripts/capability-boundary-scan.mjs —— 词表是这条扫描的一半,不能绕开它。')
}

const HARD = extract(webSource, 'HARD', 40)
const SOFT = extract(webSource, 'SOFT', 15)
const ASCII = /^[\x20-\x7e]+$/

const hits = []
for (const rel of TARGETS) {
  const abs = resolve(REPO, rel)
  let text
  try {
    text = readFileSync(abs, 'utf8')
  } catch {
    fail(`读不到 ${rel} —— 它在扫描名单里,缺文件不能算通过。`)
  }
  text.split('\n').forEach((line, i) => {
    const lower = line.toLowerCase()
    for (const [list, terms] of [['硬名单', HARD], ['灰名单', SOFT]]) {
      for (const term of terms) {
        const hay = ASCII.test(term) ? lower : line
        if (hay.includes(term)) hits.push({ rel, line: i + 1, list, term, text: line.trim() })
      }
    }
  })
}

if (hits.length) {
  process.stderr.write('\n能力边界扫描(壳):不通过\n\n')
  for (const h of hits) {
    process.stderr.write(`  ✗ ${h.rel}:${h.line}  [${h.list}] ${h.term}\n      ${h.text}\n`)
  }
  process.stderr.write(
    '\n  撞词时改文案。词表在 web/scripts/capability-boundary-scan.mjs,' +
      '改它是绕过,不是修复。\n\n',
  )
  process.exit(1)
}

process.stdout.write(
  `能力边界扫描(壳):通过 —— ${TARGETS.length} 个文件,` +
    `硬名单 ${HARD.length} 词 / 灰名单 ${SOFT.length} 词(取自 ${relative(REPO, WEB_SCAN)})\n`,
)

/**
 * docs/technical/INDEX.md §8.1 禁令 3 的<b>客户端对应物</b>:不把原图打进 console 的任何级别。
 *
 * <h2>为什么这条必须是一条断言,不能是一句注释</h2>
 *
 * 服务端那一侧已经有 `ImageRetentionTest#recognizePackageNeverPersistsBytes` 守着了 ——
 * 而 `R-75` 登记的原话是:这两条原图红线「在框架层没有结构约束……
 * <b>也没有任何结构能阻止一行 `log.debug(request)`</b>」。
 * 浏览器这一侧一模一样:一句 `console.log(photos)` 会把整张图的字节
 * 打进 devtools 的 console,而 devtools 的 console <b>会被用户截图发出去、
 * 会被错误上报 SDK 抓走、会留在录屏里</b>。它比服务端那行 `log.debug` 更容易外流。
 * <p>
 * 这条断言不能防住所有写法(把 blob 先赋给一个叫 `x` 的变量再打就绕过去了)。
 * 它防的是<b>顺手</b>那一类:调试时加一行、忘了删、code review 一眼扫过去。
 * `交付工作流` §9.10 的教训之一是黑名单不能命中仓库自己的合规注释,所以下面
 * {@link scan} 会先剥掉注释 —— 而这个仓库的边界声明恰恰全是否定式,注释里满是这些词。
 *
 * <h2>零新增依赖</h2>
 *
 * `node:fs` + `node:test`。和 `scripts/capability-boundary-scan.mjs` 同一条路:
 * 这种断言必须能在一台没装 node_modules 的机器上跑。
 */

import assert from 'node:assert/strict'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const WEB_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const SRC_ROOT = join(WEB_ROOT, 'src')
const SCAN_EXT = new Set(['.ts', '.tsx'])

/**
 * 这些文件在原图那条链路上,里面<b>一句 console 都不许有</b>。
 *
 * <p>比逐个参数看更硬:这几个文件里的任何一个局部变量都可能间接指着原图字节
 * (`row`、`rows`、`meta`、`err` —— IndexedDB 抛出来的错里就带着行数据)。
 * 与其逐个判断哪个变量安全,不如整个文件禁掉 —— <b>这一层本来也没有需要打日志的东西</b>。
 */
const NO_CONSOLE_AT_ALL = [
  'src/lib/rawImageCache.ts',
  'src/lib/rawImageDb.ts',
  'src/api/recognize.ts',
  'src/features/RawImageDrop.tsx',
]

/**
 * 全 src 通用:console 的参数里出现这些词就算红。
 *
 * <p>都按小写比。挑选标准和硬名单一样 —— 这些词今天在 src 里<b>没有一处</b>
 * 是「该被打进 console 的东西」。
 */
const BYTE_BEARING = [
  'base64',
  'btoa',
  'dataurl',
  'blob',
  'arraybuffer',
  'photos',
  'rawimage',
  'readasdataurl',
  'objecturl',
]

/** console 的哪些方法。`console.log` 只是其中一个 —— 禁令说的是「任何级别」。 */
const CONSOLE_CALL = /console\s*\.\s*[a-zA-Z]+\s*\(/g

/**
 * 剥掉注释。
 *
 * <p>🔴 这一步是 `交付工作流` §9.10 那条教训的直接落地:<b>黑名单不能命中这个仓库自己的
 * 合规注释</b>。这几个文件的注释里反复出现「不把 base64 打进 console 的任何级别」——
 * 不剥注释的话,这条断言第一次跑就红在它自己要保护的那句话上,
 * 而一条天天误报的断言两天内就会被关掉,等于从来没有过。
 * <p>
 * 做法很粗:块注释整段去掉;行注释只在 `//` 前面不是 `:` 时去掉(躲开 `https://`)。
 * 粗的代价是可能少剥一点(于是多报),不会多剥(于是漏报)——<b>方向选的是宁可多报</b>。
 */
function stripComments(text: string): string {
  const noBlock = text.replace(/\/\*[\s\S]*?\*\//g, ' ')
  return noBlock
    .split('\n')
    .map((line) => {
      const at = line.search(/(^|[^:])\/\//)
      return at < 0 ? line : line.slice(0, at === 0 ? 0 : at + 1)
    })
    .join('\n')
}

/** 从 `console.x(` 的左括号开始,按括号配平取出实参那一段(跨行)。 */
function argsAfter(text: string, openParen: number): string {
  let depth = 0
  for (let i = openParen; i < text.length; i++) {
    const ch = text[i]
    if (ch === '(') depth += 1
    else if (ch === ')') {
      depth -= 1
      if (depth === 0) return text.slice(openParen + 1, i)
    }
  }
  return text.slice(openParen + 1) // 括号没配平:整段都算,宁可多报
}

export interface Violation {
  file: string
  kind: 'console-in-raw-image-file' | 'raw-bytes-in-console'
  detail: string
}

/**
 * 扫一份源码。<b>纯函数</b> —— 所以它自己也能被断言。
 *
 * @param rel  相对 web/ 的路径,只用于报错文案与「整文件禁 console」名单
 * @param raw  源码原文
 */
export function scan(rel: string, raw: string): Violation[] {
  const code = stripComments(raw)
  const found: Violation[] = []

  CONSOLE_CALL.lastIndex = 0
  let m: RegExpExecArray | null
  while ((m = CONSOLE_CALL.exec(code)) !== null) {
    const call = m[0]
    if (NO_CONSOLE_AT_ALL.includes(rel)) {
      found.push({
        file: rel,
        kind: 'console-in-raw-image-file',
        detail: `${call.trim()} —— 这个文件在原图链路上,一句 console 都不许有`,
      })
      continue
    }
    const args = argsAfter(code, m.index + call.length - 1).toLowerCase()
    for (const term of BYTE_BEARING) {
      if (args.includes(term)) {
        found.push({
          file: rel,
          kind: 'raw-bytes-in-console',
          detail: `${call.trim()}…) 的实参里出现了「${term}」`,
        })
      }
    }
  }
  return found
}

function collect(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir).sort()) {
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

/* ========================================================================== */

/**
 * 🔴 先证明这把尺子是活的。
 *
 * <p>`交付工作流` §9.10 的第一条:<b>每条断言都必须先红过一次</b>。
 * 而一条「扫全树、什么都没扫到、于是通过」的断言,和一条被注释掉的断言在输出上完全一样。
 * 所以这里先喂三段人造源码进去 —— 它们必须红。哪天 {@link scan} 被改成一个空函数,
 * 红的是这一条,而不是等到某次真的把 base64 打进 console 时才发现尺子早就坏了。
 */
test('扫描器本身:该红的必须红', () => {
  assert.equal(
    scan('src/features/Whatever.tsx', 'console.log(base64)').length,
    1,
    'console.log(base64) 必须被抓到',
  )
  assert.equal(
    scan('src/features/Whatever.tsx', 'console.debug("送图", { photos })').length,
    1,
    '任何级别都算,不只是 log',
  )
  assert.equal(
    scan(
      'src/features/Whatever.tsx',
      ['console.warn(', '  "存图失败",', '  blob,', ')'].join('\n'),
    ).length,
    1,
    '跨行的实参也要抓到',
  )
  assert.equal(
    scan('src/lib/rawImageDb.ts', 'console.info("ok")').length,
    1,
    '原图链路上的文件里,一句无害的 console 也算红',
  )
})

/** 同样要证明它<b>不会</b>红在这个仓库自己的合规注释上 —— 否则它两天内就会被关掉。 */
test('扫描器本身:该绿的必须绿', () => {
  assert.deepEqual(
    scan('src/features/Whatever.tsx', '// 🔴 不把 base64 打进 console 的任何级别'),
    [],
    '行注释里的合规声明不算命中',
  )
  assert.deepEqual(
    scan('src/features/Whatever.tsx', '/**\n * 不 console.log(base64),一次都不。\n */\nconst a = 1'),
    [],
    '块注释里的合规声明不算命中',
  )
  assert.deepEqual(
    scan('src/features/Whatever.tsx', 'console.warn("后端 :8080 没起来?见 https://example.com")'),
    [],
    'URL 里的 // 不该被当成行注释起点',
  )
})

test('🔴 web/src 里没有任何一处把原图打进 console', () => {
  const files = collect(SRC_ROOT)
  assert.ok(files.length > 10, `只扫到 ${files.length} 个文件,路径大概错了`)

  const violations = files.flatMap((full) =>
    scan(relative(WEB_ROOT, full).split('\\').join('/'), readFileSync(full, 'utf8')),
  )

  assert.deepEqual(
    violations,
    [],
    `docs/technical/INDEX.md §8.1 禁令 3:\n${violations.map((v) => `  ${v.file} · ${v.detail}`).join('\n')}`,
  )
})

test('原图链路上的四个文件都还在 —— 名单空转等于没有这条纪律', () => {
  for (const rel of NO_CONSOLE_AT_ALL) {
    assert.doesNotThrow(
      () => statSync(join(WEB_ROOT, rel)),
      `${rel} 不见了。文件改名之后残留的名单是一张挡箭牌,它会替下一处真的越界提前买好单`,
    )
  }
})

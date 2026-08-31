#!/usr/bin/env node
/**
 * 能力边界文案扫描 —— docs/总路线图 §四 R-05:产品永不判断「对不对」。
 *
 * <h2>这条扫描要防的到底是什么</h2>
 *
 * docs/总路线图 给 UI 审核留的原话是判据本身:
 * <b>「用户自己填的练习条数不算判定,产品替他判断对错才算。」</b>
 *
 * 所以「正确率」「rank」这类词在这个仓库里是<b>合法</b>的 ——
 * 正确率是用户自己敲进来的两个整数相除,rank 是盲区榜的名次(服务端 blindScore 排序),
 * 都不是产品在评价用户答得对不对。一个朴素的关键词黑名单会把它们全判成红,
 * 而一条天天误报的断言两天内就会被关掉,等于从来没有过。
 *
 * 于是分两级:
 *
 * <ul>
 * <li><b>硬名单</b> —— 这些词在一个「不判对错、不做教研」的产品里没有任何合法用法。
 *     出现即红,豁免表对它无效,只能改文案。
 *     入选条件不是「听起来像教研」,而是:<b>今天在 src 全树里一次都不出现,
 *     包括这个仓库那些否定式的边界声明注释里</b>。做不到这一条的词一律降到灰名单 ——
 *     否则第一次跑就红在自己的合规注释上。</li>
 * <li><b>灰名单</b> —— 词本身两可,判据在上下文。命中就去 allow 表里查 file+match,
 *     查不到就红。豁免必须写清楚「为什么这一处不构成产品替用户判断」。</li>
 * </ul>
 *
 * 豁免按 <b>file + match</b> 生效,不按行号 —— 行号会随着任何一次编辑漂移,
 * 漂移之后要么假绿要么假红,两种都比没有这条扫描更糟。代价是同一文件里
 * 这个词的后续出现也一起被放行,所以豁免的 reason 写的是「这个文件为什么可以用这个词」,
 * 而不是「第 94 行为什么可以」。
 *
 * 豁免表里<b>匹配不到任何一行的条目也算红</b>:文案改掉之后残留的豁免是一张挡箭牌,
 * 它会替下一处真的越界提前买好单。
 *
 * 零依赖,只用 node: 内置模块 —— 这条断言必须能在任何一台没装 node_modules 的机器上跑。
 */

import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const WEB_ROOT = resolve(HERE, '..')
const SRC_ROOT = join(WEB_ROOT, 'src')
const ALLOW_FILE = join(HERE, 'capability-boundary-allow.json')
const ALLOW_FILE_LABEL = 'web/scripts/capability-boundary-allow.json'

/** 只扫会变成文案或标识符的文本。图片、字体、锁文件扫了只会制造噪音。 */
const SCAN_EXT = new Set(['.ts', '.tsx', '.js', '.jsx', '.mjs', '.css', '.html'])

/**
 * 硬名单:出现即红。
 *
 * 每一组后面那句是它为什么不可能有合法用法 —— 将来有人想加豁免时,
 * 先得推翻这句话,而不是往 allow 表里塞一行。
 */
const HARD = [
  // 教研本身。「不做教研」是 docs/决策记录 的硬约束,学科判断整个外包给外部模型。
  // (「讲解」在灰名单,不在这里 —— 见下面 SOFT 里的说明。)
  '精讲',
  '详解',
  '名师',
  '视频课',
  '知识点总结',
  // 替用户做学习判断。产品只回答「有没有 / 几次 / 多久前」,不回答「接下来该干嘛」。
  '学习建议',
  '复习建议',
  '备考建议',
  '学习计划',
  '复习计划',
  '智能推荐',
  '推荐练习',
  '推荐题',
  '为你推荐',
  // 记忆科学。一旦开始安排复习节奏,产品就在替用户判断「你这个记住了没有」。
  '艾宾浩斯',
  'ebbinghaus',
  '记忆曲线',
  '遗忘曲线',
  '复习曲线',
  '间隔重复',
  'spacedrepetition',
  'spaced repetition',
  // 习惯 / 游戏化。北极星是「主动查看盲区的人数」,不是连续天数。
  '打卡',
  '签到',
  '徽章',
  '勋章',
  '成就系统',
  '积分',
  '段位',
  '排行榜',
  '榜首',
  'leaderboard',
  'streak',
  'badge',
  // 与别人比。这个产品从头到尾只有一个人的记录,任何「击败了 xx%」都需要它没有的数据。
  '击败了',
  '超越了',
  '学霸',
  '打败全国',
  // 真题原文 / 答案。docs/数据线 §二 R-01:线上 schema 不许有能装下题干的字段。
  '正确答案',
  '标准答案',
  '参考答案',
  '答案解析',
  '错题解析',
  '题目解析',
  '真题解析',
  '解题思路',
  '解题步骤',
  '题干原文',
  'correctanswer',
  'answerkey',
  // 判卷。产品不判题,这几个词连否定式都用不上(仓库里写的是「不判题」「不给分」)。
  '自动批改',
  '自动判题',
  '判卷',
  '阅卷',
  // 效果承诺 / 押题。都要求产品知道题目对不对,外加一个它没有的因果结论。
  '提分',
  '涨分',
  '保分',
  '押题',
  '预测考点',
  '难度系数',
  '熟练度',
  '精通度',
  'proficiency',
  'mastery',
]

/**
 * 灰名单:命中要去 allow 表里查 file+match。
 *
 * 这里的每个词都有真实的合法用法,判据是<b>这个数是谁产生的</b>:
 * 用户自己填的、服务端按频次×状态排的序 —— 合法;产品对内容作出的评价 —— 越界。
 */
const SOFT = [
  '正确率',
  'accuracy',
  '得分',
  '分数',
  'score',
  '排名',
  '名次',
  'rank',
  '对错',
  '强弱',
  '榜单',
  // 下面这几个原本想放硬名单,但仓库的边界声明注释里就有它们的否定式
  //(「更没有掌握度」「不是难度、不是重要性」「产品不判题、不给分」),
  // 放硬名单第一次跑就会红在自证合规的那几行上。降级到灰名单,单独豁免、写明理由。
  '掌握度',
  '难度',
  '重要性',
  '评分',
  '判题',
  '给分',
  '批改',
  '答案',
  // 「解析」在这个仓库里还有 parse 的意思(近五年频次的解析),同上。
  '解析',
  // 「讲解」本该是硬名单第一个词。它降到这里只因为 types.ts 有一行
  // 「🔴 没有讲解字段(R-05)」—— 合规声明必须能说出它拒绝的那个词。
  '讲解',
]

const ASCII = /^[\x20-\x7e]+$/

/** 递归收集 src 下所有会变成文案或标识符的文本文件。 */
function collect(dir, out = []) {
  for (const name of readdirSync(dir).sort()) {
    const full = join(dir, name)
    const st = statSync(full)
    if (st.isDirectory()) {
      collect(full, out)
      continue
    }
    const dot = name.lastIndexOf('.')
    if (dot > 0 && SCAN_EXT.has(name.slice(dot))) out.push(full)
  }
  return out
}

/**
 * 一行里命中的词。
 *
 * ASCII 词按小写比 —— `Rank` / `blindScore` / `Accuracy` 都要算命中,
 * 大小写换个写法就能绕过的断言不算断言。中文词按原文比。
 * 同一行同一个词只报一次:重复报只会让人少读两行。
 */
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

  // 结构校验放在这里而不是靠人自觉:一条 reason 写成空串的豁免,
  // 和没有理由的豁免是同一个东西,而后者正是这张表存在的意义要挡住的。
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

function fail(msg) {
  process.stderr.write(`\n能力边界扫描:配置有问题\n  ${msg}\n\n`)
  process.exit(1)
}

// —— 扫 ——

const allow = loadAllow()
const used = new Set()
const files = collect(SRC_ROOT)
const hardHits = []
const softHits = []
let lines = 0

for (const full of files) {
  const rel = relative(WEB_ROOT, full).split('\\').join('/')
  const text = readFileSync(full, 'utf8').split('\n')
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
    '\n能力边界扫描通过 —— docs/总路线图 §四 R-05「产品永不判断对不对」\n' +
      `  扫描 ${files.length} 个文件 / ${lines} 行(web/src)\n` +
      `  硬名单 ${HARD.length} 词,灰名单 ${SOFT.length} 词,豁免表 ${allow.size} 项(全部命中)\n\n`,
  )
  process.exit(0)
}

const out = []
out.push('\n能力边界扫描未通过 —— docs/总路线图 §四 R-05「产品永不判断对不对」')
out.push('判据:用户自己填的数不算判定,产品替他判断对错才算。\n')

if (hardHits.length) {
  out.push(`【硬名单】${hardHits.length} 处 —— 这些词在一个不判对错、不做教研的产品里没有合法用法。`)
  for (const h of hardHits) out.push(`  ${h.rel}:${h.no} · ${h.term} · 硬名单`)
  out.push('  怎么修:只有改文案一条路。豁免表对硬名单无效 ——')
  out.push('  如果你确信这个词该被允许,那要动的是本脚本里的 HARD 名单和它上面那段理由,')
  out.push('  而且得先说清楚为什么它不再是「产品替用户判断」。\n')
}

if (softHits.length) {
  out.push(`【灰名单·未豁免】${softHits.length} 处 —— 词本身可以合法出现,判据在这一处的上下文。`)
  for (const h of softHits) out.push(`  ${h.rel}:${h.no} · ${h.term} · 灰名单`)
  out.push('  怎么修,二选一:')
  out.push('   1) 改文案 —— 这处确实是产品在替用户下判断(而不是显示用户自己填的数),删掉或改写;')
  out.push(`   2) 加豁免 —— 在 ${ALLOW_FILE_LABEL} 的 allow 数组里追加:`)
  const s = softHits[0]
  out.push(`      { "file": "${s.rel}", "match": "${s.term}", "reason": "这个数是谁产生的、为什么不是产品替用户判断" }`)
  out.push('      豁免按 file + match 生效,覆盖该文件里这个词的全部出现;拿不准就别豁免,留着红让人判。\n')
}

if (stale.length) {
  out.push(`【豁免表·空转】${stale.length} 项 —— 在对应文件里已经匹配不到任何一行了。`)
  for (const it of stale) out.push(`  ${it.file} · ${it.match} · 该文件已无此词`)
  out.push('  怎么修:从 allow 数组里删掉。文案改完还留着的豁免是一张挡箭牌,')
  out.push('  它会替下一处真的越界提前买好单。\n')
}

process.stderr.write(`${out.join('\n')}\n`)
process.exit(1)

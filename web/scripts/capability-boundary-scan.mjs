#!/usr/bin/env node
/**
 * 能力边界文案扫描 —— `R-05`:产品永不判断「对不对」。
 *
 * <h2>这条扫描的依据,以及它 2026-09-04 被改过一次</h2>
 *
 * 依据两条,<b>都能在被引的文件里逐字找到</b>:
 *
 * <ul>
 * <li>docs/execution/INDEX.md:591 的红线表:
 *     <b>「`R-05` | 产品判断「对不对」 | 全局 | 只做「有没有、几次、多久前」」</b></li>
 * <li>design/README.md:45 的平铺禁令:
 *     <b>「以及能力边界:界面上不得出现正确率、得分、排名、题目讲解、学习建议、复习提醒、打卡、徽章。」</b></li>
 * </ul>
 *
 * 🔴 <b>这段注释以前写的不是这两条。</b>它把「正确率/rank 在本仓合法」这条豁免的依据
 * 写成「docs/execution/INDEX.md 给 UI 审核留的原话」,并逐字引了一句
 * 「用户自己填的练习条数不算判定,产品替他判断对错才算」——
 * <b>那句话不在 docs/execution/INDEX.md 里,也不在 docs/ 任何一处</b>,
 * 全仓只有本文件自己有它(实测见 `B0` §11.3)。一条「依据在别处」的豁免
 * 等于没有依据的豁免,所以它整条作废,`正确率` 随之从灰名单升到硬名单
 * (2026-09-04 chenyj 拍板,`B0` §11.4;落地 KUBI-107)。
 *
 * <p>`rank` / `accuracy` / `得分` 这些词<b>没有</b>跟着升 —— 那次拍板的范围只到
 * `正确率` 一个词。rank 是盲区榜的名次(服务端 blindScore 排序),
 * 它排的是骨架上的缺口不是人。一个朴素的关键词黑名单会把它们全判成红,
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
 * <h2>2026-09-06:第三级 —— 形态规则(`KUBI-111`)</h2>
 *
 * 上面两级都是<b>逐行裸子串</b>。它们漏掉了 <b>`练 8 对 4`</b>:一个禁用词都不含,
 * 而「做了多少」和「对了多少」并排上屏,读者自己做一次除法就得到那个已经被禁掉的比值 ——
 * 词删了,判对错的那件事一个字没少。所以要拦的不是词,是<b>形态</b>。
 *
 * 形态规则<b>与硬名单同待遇:出现即红,豁免表对它无效</b>(`loadAllow` 只认灰名单词,
 * 往 allow.json 里塞形态是塞不进去的 —— 它会在配置校验那一步就红)。
 * 每条规则自带 `why`(为什么这个形态本身就是在判对错)与 `fix`(出口在哪),
 * 因为一条只说「你红了」的断言两天内就会被关掉。
 *
 * 三类:
 * <ul>
 * <li><b>S1 对错比</b> —— 「练 N 对 M」「对 N 错 M」「练·对」并列列名、
 *     模板插值里的 `correct`、把「对了」当 JSX 标签。分子分母只要一个是「对了几道」,
 *     形态就成立;而「有记录数 / 考点总数」不是(它只答「有没有」)。</li>
 * <li><b>S2 裸分数列 / 排序公式上屏</b> —— 呈现层的 `score…toFixed`,
 *     以及把「频次 × 生疏度」这种排序分公式写成表头。`生疏度` 不在任何词名单上,
 *     正是靠这条抓 —— 词表扩不动它,因为「生疏」本身是五态之一的合法状态名。</li>
 * <li><b>S3 五档掌握度</b> —— 五档状态色 token / `bg-s-*` 工具类、五档中文名的字符串字面量、
 *     `STATE_DOT` 这类五态→样式映射表。<b>`src/api/` 刻意在 scope 外</b>:
 *     那一层是服务端响应的形状镜像(`mock.ts` 必须与服务端 DTO 逐字段一致),
 *     它是<b>线格式</b>不是上屏文案 —— 在那里出现 `'生疏'` 是契约,搬到呈现层才是产品在评价人。</li>
 * </ul>
 *
 * <h3>地基:注释屏蔽,以及为什么只有形态规则用它</h3>
 *
 * 这个仓库的注释里全是<b>否定式的合规说理</b>(「而偏小的对/练会把「稳」显示成「弱」」),
 * 形态正则不屏蔽注释,第一跑就红在自证合规的那几行上 —— 和 `讲解` 当年不敢进硬名单同一个坑。
 * `maskComments` 抄自 `design-baseline-scan.mjs`,补上了 `//` 行注释:
 * 注释整段换成<b>等长空白</b>而不是空串,于是屏蔽后的文本与原文<b>行数、行号、列号全部对齐</b>,
 * 报告里能直接印未屏蔽的原文。`//` 要求前一个字符不是 `:`,否则 `https://` 会被当成行注释。
 *
 * 🔴 <b>硬名单 / 灰名单照旧跑在未屏蔽的原文上,一个字节都没改。</b>
 * 它们今天是绿的,而且硬名单的入选条件写死了「今天在 src 全树一次都不出现,
 * <b>包括这个仓库那些否定式的边界声明注释里</b>」—— 改成屏蔽注释会把这句判据的语义换掉,
 * 硬名单从「全树不出现」松成「上屏文案里不出现」,而那是一条没人审过的新规则。
 *
 * <h3>自检</h3>
 *
 * `node scripts/capability-boundary-scan.mjs --selftest` —— 纯字符串 in / 判定 out,
 * 不读 `src/` 一个字节。理由与 `design-baseline-scan.mjs` 那组一模一样:
 * 这条扫描从红转绿是它该走的路,但<b>「正则写坏了、一处都扫不出来」也长成绿色</b>。
 * 每条形态规则至少两条断言:一条该红的,一条形近但该绿的(那些真实的假阳性诱因 ——
 * `NodeList.tsx` 的「有记录数/考点总数」、`layout.tsx` 的 `44×44`、`format.ts` 的
 * `percentWidth`——逐条写成断言,而不是靠下一个人记得它们)。
 *
 * 零依赖,只用 node: 内置模块 —— 这条断言必须能在任何一台没装 node_modules 的机器上跑。
 */

import assert from 'node:assert/strict'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const WEB_ROOT = resolve(HERE, '..')
const SRC_ROOT = join(WEB_ROOT, 'src')
// 2026-09-05 KUBI-79:扫描范围从「只有 web/src」扩到「web/src + design/」。
// 缺口是这样暴露的:这个脚本的判据写着 design/README.md:45,却从不扫 design/,
// 于是 design/ui-a/ 里 9 处「正确率」、五档掌握度状态色、.meter 进度条
// 在红绿灯全绿的情况下躺了四天。判据指向哪儿,就得扫到哪儿。
const REPO_ROOT = resolve(WEB_ROOT, '..')
const DESIGN_ROOT = join(REPO_ROOT, 'design')
// archive/ 与 explorations/ 是决策记录:留着原文才知道当时为什么否掉它,
// 把禁用词从作废稿里洗掉等于把证据洗掉。它们不会上屏,所以不扫。
const SKIP_DIRS = new Set(['archive', 'explorations', 'node_modules'])
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
  // 教研本身。「不做教研」是 docs/decisions/INDEX.md 的硬约束,学科判断整个外包给外部模型。
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
  // 真题原文 / 答案。docs/data/INDEX.md §二 R-01:线上 schema 不许有能装下题干的字段。
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
  // 用户自己填的两个整数相除得到的那个比值。
  // 🔴 2026-09-04 chenyj 拍板「算」(`B0` §11.4):design/README.md:45 的平铺禁令赢,
  // 「这个数是用户自己填的」不构成豁免理由 —— 界面上不得出现,就是不得出现。
  // 它满足硬名单的入选条件:KUBI-107 改完之后,web/src 全树一次都不出现,
  // 包括本仓库那些否定式的边界声明注释里(那几处改说「练了几道 / 对了几道」,
  // 两个原始数照留,被删掉的只有它们相除得到的比值)。
  // 旁证:docs/technical/INDEX.md:414 撤回 practice_log 时写的就是
  // 「『正确率』这个字段在整个文档集里从未被定义过」。
  '正确率',
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
  // 🔴 `正确率` 不在这里了 —— 2026-09-04 升硬名单,见上面 HARD 里那段。
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

// —— 形态规则(第三级)——

/**
 * 把注释整段换成<b>等长</b>空白 —— 抄自 `design-baseline-scan.mjs:99`,补了 `//` 行注释。
 *
 * 等长而不是空串:屏蔽后的文本与原文行数、行号、列号全部对齐,于是形态规则可以在
 * 屏蔽后的行上判、在原文的同一行上印。换成空串行号立刻偏,报出来的位置就是错的。
 *
 * `//` 要求前一个字符不是 `:` —— 否则 `https://kubic` 的后半截会被当成行注释屏蔽掉,
 * 而 URL 里恰好可能带着要拦的形态(查询串里的 `correct=`)。代价是字符串字面量里
 * 独立出现的 `// ` 也会被屏蔽:这是刻意换来的简单,`.ts` 里那种写法本来就该改。
 */
function maskComments(text) {
  const blank = (m) => m.replace(/[^\n]/g, ' ')
  return text
    .replace(/\/\*[\s\S]*?\*\//g, blank)
    .replace(/<!--[\s\S]*?-->/g, blank)
    .replace(/(^|[^:])\/\/[^\n]*/gm, (m, pre) => pre + blank(m.slice(pre.length)))
}

/** 一个数:字面量,或者模板插值。`练 8 对 4` 和 `练 ${a} 对 ${b}` 是同一个形态。 */
const N = String.raw`(?:\d+|\$\{[^}]*\})`

/** S2/S3 的「呈现层」。`src/api/` 不在里面 —— 那一层是服务端响应的形状镜像,理由见各规则的 why。 */
const VIEW = ['src/features/', 'src/screens/', 'src/ui/']

/**
 * 形态规则:硬的,豁免表对它无效(和硬名单同待遇)。
 *
 * `scope` 是路径前缀数组,空数组 = 全量。路径基准与报告里印的 `rel` 一致
 * (`web/` 下的按 web 根,`design/` 下的按仓库根)。
 * 正则<b>不带 `g`</b>:一行一条规则只报一处,报三遍只会让人少读两行。
 */
const FORM = [
  {
    id: 'S1-drill-pair',
    name: '对错比 · 练 N 对 M',
    why: '把「做了多少」和「对了多少」并排上屏,读者做一次除法就得到那个已经被禁掉的比值 —— 一个禁用词都不用出现,判对错这件事一个字没少。',
    re: new RegExp(String.raw`(?:练|做)\s*${N}\s*对\s*${N}|对\s*${N}\s*错\s*${N}`),
    scope: [],
    fix: '只留「练了几道」这一个数,或整列删掉。「把除法留给读者」不构成豁免 —— 并排本身就是那个比值。',
  },
  {
    id: 'S1-pair-col',
    name: '对错比 · 「练·对」并列列名',
    why: '一个列名把两个数捆成一列,这一列的意义只能是它们的比 —— 否则没有理由并列。',
    re: /练\s*[·/／]\s*对|对\s*[·/／]\s*练/,
    scope: [],
    fix: '拆成两列,或只保留「练了几道」那一列。列名里的 · 和 / 就是那个除号。',
  },
  {
    id: 'S1-correct-interp',
    name: '对错比 · 模板插值里的 correct',
    why: '「对了几道」这个数只要进了字符串就是要上屏的;它进了字符串,形态就成立,不必等到旁边再摆一个 practiced。',
    re: /\$\{[^}]*correct[^}]*\}/i,
    scope: [],
    fix: '这个数不渲染。它作为服务端契约字段留在 types/api 层没问题,进模板字符串就是上屏。',
  },
  {
    id: 'S1-correct-label',
    name: '对错比 · 把「对了」当标签',
    why: 'JSX 属性或文本节点里的「对了」是一个字段名,字段名上屏意味着后面必然跟着那个数。',
    re: /[\w-]+\s*=\s*["']对了["']|>\s*对了\s*</,
    scope: [],
    fix: '删掉这一项。产品只答「有没有 / 几次 / 多久前」,「对了」不在这三个里。',
  },
  {
    id: 'S2-score-fixed',
    name: '裸分数列 · 连续数值当分数上屏',
    why: '一个带小数的连续数值挂在人或考点旁边,读者只会把它读成分。排序分是服务端排序的中间量,不是给用户看的刻度。',
    re: /score\b[^\n]{0,80}\.toFixed\s*\(|\.toFixed\s*\([^\n]{0,80}score\b/i,
    scope: VIEW,
    fix: '排序分只用来排序,不上屏 —— 名次(第几个该补)已经把这个信息表达完了。',
  },
  {
    id: 'S2-formula-head',
    name: '裸分数列 · 把排序公式写成表头',
    why: '把「频次 × 生疏度」印成表头,等于告诉用户这一栏是产品给他算的一个评价分;`生疏度` 不在任何词名单上,词表扩不动它 —— 「生疏」本身是五态之一的合法状态名。',
    re: /[×✕*xX]\s*(?:生疏度|熟练度|掌握度|状态权重|权重|难度)/,
    scope: [],
    fix: '表头写这一栏是什么(「先补这几个」),不写它是怎么算出来的。公式留在服务端。',
  },
  {
    id: 'S3-state-token',
    name: '五档掌握度 · 状态色 token',
    why: '五档颜色是「掌握程度」这套评价体系的视觉形态 —— 换个词表拦不住,颜色不需要文字就把五档排好了。api 层不在 scope 里:那一层是服务端 DTO 的形状镜像(mock.ts 要与服务端逐字段一致),是线格式不是上屏。',
    re: /[a-z]+-s-(?:stable|weak|rusty|touch|empty)\b/,
    scope: [...VIEW, 'src/lib/', 'src/index.css'],
    fix: '五档塌成「有记录 / 没记录」两档,或改成不承载评价的中性色(时间远近)。',
  },
  {
    id: 'S3-state-label',
    name: '五档掌握度 · 五档中文名字面量',
    why: '呈现层写死这五个名字,产品就有了自己的一套评价词 —— 注释已被屏蔽,所以能中的只有真的要上屏的那种。同上,api 层是服务端 stateLabel 的镜像,不在 scope 里。',
    re: /(['"`])(?:稳|弱|生疏|仅接触|空白)\1/,
    scope: [...VIEW, 'src/lib/', 'src/index.css'],
    fix: '中文名由服务端随 stateLabel 给(nodeState.ts 的类注释已经写了这条),前端不留第二份。',
  },
  {
    id: 'S3-state-map',
    name: '五档掌握度 · 五态→样式映射表',
    why: '一张五个键的表把状态映射成样式,就是把五档排序固化在前端 —— 它比单个 token 更硬,因为它同时定义了「有哪五档」。api 层同上不在 scope 里。',
    re: /\bSTATE_(?:DOT|BAR|COLOR|CLASS|STYLE|MAP)\b/,
    scope: [...VIEW, 'src/lib/', 'src/index.css'],
    fix: '这张表跟着五档一起去掉;真要留视觉区分,留「有记录 / 没记录」两个键。',
  },
]

const inScope = (rel, scope) => scope.length === 0 || scope.some((p) => rel.startsWith(p))

/**
 * 一个文件里的形态命中。
 *
 * 屏蔽后的文本与原文<b>逐行对齐</b>(maskComments 换的是等长空白),
 * 所以判在 masked 上、印在 raw 上,行号是同一个。
 */
function formHits(rel, raw) {
  const masked = maskComments(raw).split('\n')
  const lines = raw.split('\n')
  const out = []
  masked.forEach((line, i) => {
    for (const rule of FORM) {
      if (!inScope(rel, rule.scope)) continue
      if (rule.re.test(line)) out.push({ rel, no: i + 1, rule, text: lines[i].trim() })
    }
  })
  return out
}

/** 递归收集 src 下所有会变成文案或标识符的文本文件。 */
function collect(dir, out = []) {
  for (const name of readdirSync(dir).sort()) {
    const full = join(dir, name)
    const st = statSync(full)
    if (st.isDirectory()) {
      if (!SKIP_DIRS.has(name)) collect(full, out)
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

// —— 自检:`node scripts/capability-boundary-scan.mjs --selftest` ——
//
// 纯字符串 in / 判定 out,不读 src/ 一个字节。
// 这条扫描今天是<b>红的</b>(形态规则刚加,src/ 还没摘完),从红转绿是它该走的路 ——
// 但「正则写坏了、一处都扫不出来」也长成绿色。所以每条规则都留一红一绿两条断言,
// 绿的那条抄的是仓库里真实的假阳性诱因,而不是编一个不可能出现的字符串。
if (process.argv.includes('--selftest')) {
  /** 某条文本在某个路径下命中的规则 id。路径决定 scope。 */
  const ids = (rel, s) => formHits(rel, s).map((h) => h.rule.id)
  const F = 'src/features/X.tsx'
  const has = (rel, s, id) => assert.ok(ids(rel, s).includes(id), `该红没红:${id} ← ${s}`)
  const not = (rel, s, id) => assert.ok(!ids(rel, s).includes(id), `该绿红了:${id} ← ${s}`)

  // —— 地基:注释屏蔽。等长是硬要求,行号靠它对齐 ——
  const eqLen = (s) => assert.equal(maskComments(s).length, s.length, `长度变了:${s}`)
  eqLen('/* 而偏小的对/练会把「稳」显示成「弱」 */')
  eqLen('const u = "https://kubic.example/a"')
  eqLen('a // 练 8 对 4\nb')
  assert.equal(maskComments('/* 练 8 对 4 */').trim(), '', '块注释整段变空白')
  assert.equal(maskComments('<!-- 练 8 对 4 -->').trim(), '', 'HTML 注释一样')
  assert.equal(maskComments('// 练 8 对 4').trim(), '', '// 行注释 —— 这次补上的就是它')
  assert.equal(maskComments('x = 1 // 练 8 对 4').trim(), 'x = 1', '行尾注释只吃注释那一段')
  assert.equal(
    maskComments('const u = "https://kubic.example/a"'),
    'const u = "https://kubic.example/a"',
    'https:// 不是行注释 —— // 前面是 : 就不屏蔽',
  )
  assert.equal(maskComments('/* a\nb */\nc').split('\n').length, 3, '跨行注释不吞换行')
  assert.equal(maskComments('/* a\nb */\nc').split('\n')[2], 'c', '注释后面那一行还在原位')

  // —— S1 对错比 ——
  has(F, '练 8 对 4', 'S1-drill-pair')
  has(F, '`${label} · 练 ${node.practiced} 对 ${node.correct ?? 0}`', 'S1-drill-pair')
  has(F, '这次对 8 错 2', 'S1-drill-pair')
  // 🔴 假阳性诱因,逐条抄自仓库:分子分母是「有记录数 / 考点总数」,只答「有没有」。
  assert.deepEqual(ids(F, '`${group.coveredCount}/${group.nodes.length} 有记录`'), [], 'NodeList 的覆盖数不是对错比')
  assert.deepEqual(ids(F, '触控目标 44×44 是 `U6.4` 的无障碍下限,所以高度写死 52'), [], '44×44 / U6.4 不是形态')
  assert.deepEqual(ids(F, '<h2>手机 · 375 × 812</h2>'), [], '尺寸标注不是排序公式')

  has(F, '记录超出单次上限,练·对显示为「—」', 'S1-pair-col')
  has(F, '列:状态 / 练·对 / 近五年 / 最近一次', 'S1-pair-col')
  has(F, '<div><u>练 / 对</u><b>8<s>/</s>4</b></div>', 'S1-pair-col')
  has(F, '<span>你填的对/练</span>', 'S1-pair-col')
  not(F, '// 而偏小的对/练会把「稳」显示成「弱」', 'S1-pair-col')
  not(F, '{/* 练·对 —— 这两个数是用户自己填的 */}', 'S1-pair-col')

  has(F, 'v={`${item.correct} 道`}', 'S1-correct-interp')
  has(F, '` ${r.practiced}/${r.correct ?? 0}`', 'S1-correct-interp')
  not(F, "const [correct, setCorrect] = useState('')", 'S1-correct-interp')
  not(F, 'value={correct}', 'S1-correct-interp')
  not(F, '...(hasDrill ? { practiced: p, correct: c ?? 0 } : {}),', 'S1-correct-interp')

  has(F, '<Fact k="对了" v={node.correct === null ? \'—\' : x} />', 'S1-correct-label')
  has(F, '<span>对了</span>', 'S1-correct-label')
  not(F, '不判断对错。练了几道、对了几道,都是你自己填的数。', 'S1-correct-label')

  // —— S2 裸分数列 ——
  has(F, '<span>{(node.blindScore ?? 0).toFixed(1)}</span>', 'S2-score-fixed')
  not('src/lib/format.ts', '<span>{(node.blindScore ?? 0).toFixed(1)}</span>', 'S2-score-fixed')
  // 🔴 percentWidth:toFixed 的结果只喂 CSS width,不上屏 —— 连放进呈现层都不该红。
  not(F, "return total === 0 ? '0%' : `${((part / total) * 100).toFixed(1)}%`", 'S2-score-fixed')
  not(F, 'return `${(bytes / 1024 / 1024).toFixed(1)} MB`', 'S2-score-fixed')

  has(F, '<GroupHeader title="先补这几个" right="频次 × 生疏度" />', 'S2-formula-head')
  has(F, '<span>权重 = 近五年频次 × 生疏度</span>', 'S2-formula-head')
  has(F, '<th>频次 × 掌握度</th>', 'S2-formula-head')
  not(F, '/* 排序分 = 近五年频次 × 状态权重。两个因子都在能力边界内 */', 'S2-formula-head')

  // —— S3 五档掌握度 ——
  const CSS = 'src/index.css'
  const API = 'src/api/mock.ts'
  has(CSS, '  --color-s-rusty: #61666e;', 'S3-state-token')
  has('src/lib/nodeState.ts', "  STABLE: 'bg-s-stable',", 'S3-state-token')
  has(F, "border-[1.5px] border-s-touch", 'S3-state-token')
  has(CSS, '  --color-s-empty: #3a3e44;', 'S3-state-token')
  has(F, "EMPTY: 'bg-s-empty', WEAK: 'bg-s-weak',", 'S3-state-token')
  not(API, '  --color-s-rusty: #61666e;', 'S3-state-token')
  not(F, 'className="bg-slate-500 text-t3 tabular-nums"', 'S3-state-token')

  has('src/lib/x.ts', "  RUSTY: '生疏',", 'S3-state-label')
  has(F, 'const label = "仅接触"', 'S3-state-label')
  has(F, "if (s === '空白') return red", 'S3-state-label')
  has(F, 'const t = `弱`', 'S3-state-label')
  not(API, "  RUSTY: '生疏',", 'S3-state-label')
  not(F, '`${group.name} · 整块空白`', 'S3-state-label')
  not(F, '// 「空白」「生疏」这些词由服务端随 stateLabel 给', 'S3-state-label')

  has('src/ui/primitives.tsx', "import { STATE_DOT } from '../lib/nodeState'", 'S3-state-map')
  has(F, 'className: STATE_BAR[state],', 'S3-state-map')
  not(API, "export const STATE_DOT: Record<NodeState, string> = {", 'S3-state-map')
  not(F, 'const state = node.state', 'S3-state-map')

  // 形态规则是硬的:allow.json 只认灰名单词,塞形态 id 进去会在配置校验那一步就红。
  for (const r of FORM) {
    assert.ok(!SOFT.includes(r.id) && !HARD.includes(r.id), `形态 id ${r.id} 不能同时是词名单条目`)
    assert.ok(r.why.length > 20 && r.fix.length > 10, `${r.id} 的 why / fix 太短`)
  }

  process.stdout.write(
    `\n能力边界扫描 · 自检通过(注释屏蔽 10 条 · 形态规则 ${FORM.length} 条 / 断言 46 条)\n\n`,
  )
  process.exit(0)
}

// —— 扫 ——

const allow = loadAllow()
const used = new Set()
const files = [...collect(SRC_ROOT), ...collect(DESIGN_ROOT)]
const hardHits = []
const softHits = []
const formHitList = []
let lines = 0

for (const full of files) {
  // design/ 下的文件按仓库根算相对路径,web/ 下的仍按 web/ 根 ——
  // allow.json 里的键是 web/ 根相对的,换基准会让整张豁免表失配。
  const base = full.startsWith(DESIGN_ROOT) ? REPO_ROOT : WEB_ROOT
  const rel = relative(base, full).split('\\').join('/')
  const raw = readFileSync(full, 'utf8')
  // 🔴 形态规则跑在【屏蔽注释后】的文本上;下面的词名单照旧跑未屏蔽的原文,
  //    一个字节都没改 —— 硬名单的入选条件写死了「包括否定式的边界声明注释里」。
  formHitList.push(...formHits(rel, raw))
  const text = raw.split('\n')
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

if (hardHits.length === 0 && softHits.length === 0 && formHitList.length === 0 && stale.length === 0) {
  process.stdout.write(
    '\n能力边界扫描通过 —— R-05「产品永不判断对不对」(docs/execution/INDEX.md:591 + design/README.md:45)\n' +
      `  扫描 ${files.length} 个文件 / ${lines} 行(web/src + design/)\n` +
      `  硬名单 ${HARD.length} 词,灰名单 ${SOFT.length} 词,形态 ${FORM.length} 条,豁免表 ${allow.size} 项(全部命中)\n\n`,
  )
  process.exit(0)
}

const out = []
out.push('\n能力边界扫描未通过 —— R-05「产品永不判断对不对」(docs/execution/INDEX.md:591 + design/README.md:45)')
out.push('判据:design/README.md:45「界面上不得出现正确率、得分、排名…」+ docs/execution/INDEX.md:591 红线表 R-05。\n')

if (hardHits.length) {
  out.push(`【硬名单】${hardHits.length} 处 —— 这些词在一个不判对错、不做教研的产品里没有合法用法。`)
  for (const h of hardHits) out.push(`  ${h.rel}:${h.no} · ${h.term} · 硬名单`)
  out.push('  怎么修:只有改文案一条路。豁免表对硬名单无效 ——')
  out.push('  如果你确信这个词该被允许,那要动的是本脚本里的 HARD 名单和它上面那段理由,')
  out.push('  而且得先说清楚为什么它不再是「产品替用户判断」。\n')
}

if (formHitList.length) {
  const byRule = new Map()
  for (const h of formHitList) {
    if (!byRule.has(h.rule.id)) byRule.set(h.rule.id, [])
    byRule.get(h.rule.id).push(h)
  }
  out.push(
    `【形态】${formHitList.length} 处 / ${byRule.size} 条规则 —— 拦的不是词是形态:` +
      '不含任何禁用词、但摆出来就是在判对错的那些写法(`练 8 对 4`)。\n',
  )
  for (const rule of FORM) {
    const hits = byRule.get(rule.id)
    if (!hits) continue
    out.push(`  【${rule.id} · ${rule.name}】${hits.length} 处 —— ${rule.why}`)
    for (const h of hits) {
      const t = h.text.length > 120 ? `${h.text.slice(0, 120)}…` : h.text
      out.push(`    ${h.rel}:${h.no} · ${h.rule.id} · ${t}`)
    }
    out.push(`    怎么修:${rule.fix}`)
  }
  out.push('  形态规则与硬名单同待遇:豁免表对它无效(allow.json 只认灰名单词,塞不进去)。')
  out.push('  确信某一处形态命中合法,那要动的是本脚本的 FORM 规则和它的 why —— 先说清楚')
  out.push('  为什么这个形态不再是「产品替用户判断」,而不是在豁免表里开一行口子。\n')
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

import type { GroupView, SyllabusNodeDto } from '../api/types'

/**
 * 名字的比较口径,以及「这个名字现在被谁占着」。
 *
 * <h2>为什么名字必须唯一</h2>
 *
 * 记一笔的时候,考点是<b>从面板里按名字挑</b>的 —— 面板上只有名字和状态,没有题型。
 * 于是树上两个都叫「增长量计算」的考点,在挑的那一刻是<b>同一个东西</b>:
 * 这次挑中 A,下次挑中 B,同一个知识点的记录被劈到两个 code 上,
 * 覆盖率的分子被稀释、「整块空白」跟着失真 —— <b>而覆盖率是这个产品唯一的那个数</b>。
 * 这正是 决策记录 §2.2「宁缺毋滥」要防的东西:宁可少一个考点,也不要两个分不出来的考点。
 *
 * <h2>🔴 这份是「体验」,不是「判据」</h2>
 *
 * 唯一性在服务端 store 层强制,<b>409 `NAME_TAKEN` 才是权威</b>。这里这份只做一件事:
 * 让用户在<b>还在打字的时候</b>就看见「这个名字已经有了、在谁那儿」。
 * 阶段 1(docs/decisions/实施路径.md §1.2)要把 18 个考点的名字来回改十几轮,每撞一次都要一个来回才知道撞了,
 * 这轮校正就做不下去。
 * <p>
 * 所以两个方向要分清楚:
 * <ul>
 * <li><b>本地判出冲突</b> → 不发请求,当场把冲突对象指出来(省一个来回)。
 * <li><b>本地没判出、服务端拒绝</b> → 完全正常(本地这棵树是上一轮 GET 的,归档清单还可能压根没拉到),
 *     此时必须把 409 原样摆出来。<b>不许因为「本地判过了」就假设一定成功。</b>
 * </ul>
 */

/* ========================================================================== */
/* 看不见的字符 —— 两个集合,不是一个                                             */
/* ========================================================================== */

/**
 * 这个码点<b>渲染不出任何东西</b>吗。对应服务端的 `SyllabusNames.isInvisible`。
 *
 * <h2>🔴 为什么不能只判 `\p{Cf}`</h2>
 *
 * Cf(格式字符)只是「看不见的字符」的一部分,而且不是最好用的那部分。实测能绕过纯 Cf 判定、
 * 并且造出一个肉眼分不出的重名的,至少有:变体选择符 U+FE00–FE0F(类别 <b>Mn</b>)、
 * 组合字素连接符 U+034F(Mn)、谚文填充符 U+3164 与 U+115F/U+1160(类别 <b>Lo,字母</b>)、
 * 盲文空点 U+2800(类别 So)。网上那些「隐形字符生成器」的主力正是谚文填充符 ——
 * 它是个「字母」,任何按类别做的黑名单都会漏掉它。
 *
 * 所以口径取 Unicode 的 <b>`Default_Ignorable_Code_Point`</b> 属性(「渲染引擎应当当它不存在」),
 * 再补上 U+2800。JS 的正则没有暴露这个属性,于是把非 Cf 的那几段逐个列在下面 ——
 * <b>列出来的东西是可以被审的,靠一个类别判断「大概覆盖了」的东西不能。</b>
 *
 * 🔴 <b>逐段抄自服务端 `SyllabusNames.isInvisible`,那边加一段这里就要加一段。</b>
 * 不跟的后果不是报错:是服务端 400 拒掉的名字,这里连一句提示都给不出来。
 *
 * 🔴 一律写 <b>`\u{...}` 转义,不写字面量</b>。这一段列的全是渲染不出东西的码点 ——
 * 直接贴进源码就是一串看不见的字符,谁也审不了它到底列了什么,而「可以被审」正是列它们的理由。
 */
// `no-misleading-character-class` 防的是「把一个多码点字素拆进字符类」(❤️ 被拆成 U+2764 与 U+FE0F)。
// 这里要匹配的正是<b>落单的</b>组合记号本身:缀在名字末尾、不依附任何字符的 U+FE0F,
// 就是造重名的那个东西。规则的前提在这里不成立,所以关掉它 —— 关一行,并写清为什么。
// oxlint-disable-next-line no-misleading-character-class
const INVISIBLE = /[\p{Cf}\u{034F}\u{115F}-\u{1160}\u{17B4}-\u{17B5}\u{180B}-\u{180F}\u{2065}\u{2800}\u{3164}\u{FE00}-\u{FE0F}\u{FFA0}\u{FFF0}-\u{FFF8}\u{E0000}-\u{E0FFF}]/u

/** 同上,`replace` 用的全局版。两条分开写:`test` 用带 `g` 的正则会被 `lastIndex` 咬。 */
const INVISIBLE_G = new RegExp(INVISIBLE.source, 'gu')

/**
 * 这个码点是<b>变体选择符</b>吗 —— 「不可见,但依附于前一个字符」的那一类。
 * 对应服务端的 `SyllabusNames.isVariationSelector`。
 *
 * 它被单拎出来只为一件事:服务端 `validName` <b>拒绝其它不可见字符,但放行它</b>,
 * 所以这里也必须放行。理由是来路完全不同:填充符、零宽空格、盲文空点是<b>独立成字</b>的,
 * 出现即异常;而变体选择符永远跟在一个看得见的字符后面,最常见的来路是 emoji ——
 * 「❤️」就是 U+2764 U+FE0F。拦下它等于告诉一个刚把考点命名为「增长量计算❤️」的用户
 * 「名称里不能有看不见的字符」,他看着屏幕上那颗心只会觉得这条提示在胡说。
 * <b>误伤比漏放更糟。</b>
 *
 * 放行的代价由 {@link nameKey} 兜住:比较时它照样被剥掉,所以缀一个变体选择符
 * <b>造不出第二个名字</b>,只会得到一句说得清的「这个名字已经有了」。
 */
const VARIATION_SELECTOR = /[\u{FE00}-\u{FE0F}\u{E0100}-\u{E01EF}]/u

/* ========================================================================== */
/* 规范化 —— 五步,顺序与 server 侧 SyllabusNames.nameKey 一致                   */
/* ========================================================================== */

/**
 * 把一个名字折成用于<b>比较</b>的 key。存的时候存用户输入的原样(只 trim),只有比的时候用它。
 *
 * <ol>
 * <li>去掉首尾空白 —— 「增长量计算」和「  增长量计算  」是同一个名字,不是两个。
 * <li><b>NFKC</b> —— 让全角「ＡＢＣ」撞上半角「ABC」、全角「（）」撞上「()」。
 *     输入法一个状态没切,就能造出一个渲染起来几乎一样的「新」考点。
 * <li><b>剥掉所有看不见的码点</b>({@link INVISIBLE})—— 见下面那一段,这一步是兜底。
 *     放在 NFKC <b>之后</b>,因为 NFKC 会把 U+3164、U+FFA0 都变成 U+1160,先归一再剥,一遍就够。
 * <li>内部连续空白折成一个空格 —— 「资料 分析」和「资料(全角空格)分析」是同一个名字。
 * <li>大小写折叠 —— 英文/拼音缩写(GDP / gdp)同理。JS 的 `toLowerCase` 本身就与语言环境无关,
 *     对应服务端的 `toLowerCase(Locale.ROOT)`。
 * </ol>
 *
 * <h2>🔴 为什么这里还要剥一遍,明明不可见字符已经被提示拦下了</h2>
 *
 * 因为<b>变体选择符是故意放行的</b>(理由见 {@link VARIATION_SELECTOR})。放行的代价必须由这一步兜住:
 * 否则「增长量计算」后面缀一个 U+FE0F 就是一个新名字,渲染出来一模一样 ——
 * 而这正是整条约束要防的东西。
 *
 * <h2>🔴 这五步是抄服务端的</h2>
 *
 * 服务端改了规范化步骤,这里要跟着改。不跟的后果不是报错,是本地判过的名字在服务端被拒
 * (多一个来回),或者反过来,本地拦下一个服务端本来允许的名字(<b>用户改不动一个合法的名字</b>,更糟)。
 *
 * 折叠空白的字符类补了一个 `\u{0085}`(NEL):JS 的 `\s` 不认它,而服务端用的
 * `UNICODE_CHARACTER_CLASS` 认。差这一个字符就会差出一个「本地没判撞、服务端判撞」。
 */
export function nameKey(raw: string): string {
  return raw
    .trim()
    .normalize('NFKC')
    .replace(INVISIBLE_G, '')
    .replace(/[\s\u{0085}]+/gu, ' ')
    .trim()
    .toLowerCase()
}

/* ========================================================================== */
/* 看不见的字符 —— 拒绝,不是悄悄规范化掉                                          */
/* ========================================================================== */

/** 一个混在名字里的隐形字符:它在原串里的位置,和它的码位。 */
export interface InvisibleChar {
  /** 在原始字符串里的下标(以 code unit 计)。 */
  index: number
  /** 形如 `U+200B`。<b>要在界面上说出来</b> —— 「有个看不见的东西」不算一句能行动的话。 */
  code: string
}

/**
 * 这个字符该<b>被拦下来</b>吗 —— 「看不见」且「不是变体选择符」。
 *
 * 逐字对应服务端 `FileSyllabusStore.validName` 里的那一行
 * `isInvisible(cp) && !isVariationSelector(cp)`。<b>两边必须是同一个集合</b>:
 * 这里多拦一个,用户就改不动一个服务端本来允许的名字;这里少拦一个,
 * 他会在提交之后才吃一个 400,而那正是这份本地预判想省掉的来回。
 */
function isBlockedInvisible(ch: string): boolean {
  return INVISIBLE.test(ch) && !VARIATION_SELECTOR.test(ch)
}

/**
 * 找出名字里该被拦下的隐形字符。
 *
 * 🔴 <b>只找出来,不替用户删。</b>零宽空格、谚文填充符、盲文空点这一类在考点名里没有任何正当用途,
 * 它唯一的效果就是制造一个肉眼分不出的区别 —— 两个渲染起来一模一样的「增长量计算」。
 * 但静默删掉更糟:用户不会知道自己粘进来的东西不干净,下次还这么粘,
 * 而且他会以为那段来源(某个 App 的复制按钮)是可信的。
 *
 * <b>变体选择符不在这里</b> —— 服务端放行它,这里跟着放行,理由见 {@link VARIATION_SELECTOR}。
 * 它造不出重名,因为 {@link nameKey} 比较时会把它剥掉。
 */
export function invisibleChars(raw: string): InvisibleChar[] {
  const found: InvisibleChar[] = []
  let at = 0
  for (const ch of raw) {
    if (isBlockedInvisible(ch)) found.push({ index: at, code: codePoint(ch) })
    at += ch.length
  }
  return found
}

function codePoint(ch: string): string {
  return `U+${(ch.codePointAt(0) ?? 0).toString(16).toUpperCase().padStart(4, '0')}`
}

/** 名字被切成的一段:普通文字,或者一个隐形字符(此时 `text` 为空,`code` 是它的码位)。 */
export interface NamePiece {
  text: string
  code: string | null
}

/**
 * 把名字切成「能看见的段」+「隐形字符」,好在界面上<b>把看不见的东西画出来</b>。
 *
 * 光说「名字里有零宽字符」用户还是找不到它在哪 —— 它本来就看不见。所以要渲染成
 * `增长量⟨U+200B⟩计算` 这样,位置一眼可见。
 */
export function revealName(raw: string): NamePiece[] {
  const pieces: NamePiece[] = []
  let buf = ''
  for (const ch of raw) {
    if (isBlockedInvisible(ch)) {
      if (buf !== '') {
        pieces.push({ text: buf, code: null })
        buf = ''
      }
      pieces.push({ text: '', code: codePoint(ch) })
    } else {
      buf += ch
    }
  }
  if (buf !== '') pieces.push({ text: buf, code: null })
  return pieces
}

/* ========================================================================== */
/* 名字被谁占着                                                                 */
/* ========================================================================== */

/** 占着某个名字的那个东西。冲突提示要说出它<b>在哪儿</b>,所以位置信息要一起带着。 */
export interface NameOwner {
  kind: 'node' | 'group'
  code: string
  /** 原样的名字(服务端存的就是原样)。提示里显示它,而不是显示折叠后的 key。 */
  name: string
  /** 考点在哪个题型下;题型自身为 null。 */
  groupName: string | null
  /**
   * 🔴 <b>已归档</b>。归档的考点<b>不在树上</b> —— 用户翻遍界面也看不见它,
   * 但它的名字还占着(否则「取消归档」就能静默造出一个重名)。
   * 这是最容易让人困惑的一类冲突,所以它必须是一个显式的字段,而不是靠 groupName 为空去猜。
   */
  archived: boolean
  /** 已归档考点上还挂着几条记录;树上的考点为 null(那边有自己的 touchCount)。 */
  recordCount: number | null
}

/**
 * 谁占着哪些名字。
 *
 * 考点和题型分成两张表:规则说的是「考点名整树唯一」和「题型名整树唯一」两条,
 * 不是「考点不能和题型同名」。合成一张表会拦下服务端本来允许的名字 ——
 * <b>本地宁可漏判(服务端会拦),也不要多判(那会让人改不动一个合法的名字)。</b>
 */
export interface NameIndex {
  nodes: Map<string, NameOwner>
  groups: Map<string, NameOwner>
  /**
   * 归档清单拉到了没有。
   *
   * 没拉到时(接口挂了 / 还在飞)本地必然漏掉「被一个已归档的考点占着」那一类冲突。
   * 界面要据此把话说软一点,而不是拍胸脯说「没重名」。
   */
  archivedKnown: boolean
}

export function buildNameIndex(
  groups: GroupView[],
  archivedItems: SyllabusNodeDto[],
  archivedKnown: boolean,
): NameIndex {
  const nodes = new Map<string, NameOwner>()
  const groupNames = new Map<string, NameOwner>()

  for (const g of groups) {
    put(groupNames, g.name, { kind: 'group', code: g.code, name: g.name, groupName: null, archived: false, recordCount: null })
    for (const n of g.nodes) {
      put(nodes, n.name, { kind: 'node', code: n.code, name: n.name, groupName: g.name, archived: false, recordCount: null })
    }
  }

  // 🔴 归档的也算 —— 名字被一个看不见的东西占着,是本轮最需要说清楚的那种冲突。
  for (const n of archivedItems) {
    put(nodes, n.name, {
      kind: 'node',
      code: n.code,
      name: n.name,
      groupName: n.groupName,
      archived: true,
      recordCount: n.recordCount,
    })
  }

  return { nodes, groups: groupNames, archivedKnown }
}

/**
 * 先到先得。
 *
 * 树上<b>本来就可能有重名</b>(唯一性是这一轮才加的,之前建的树里可能已经躺着两个「增长量计算」)。
 * 那种时候随便指出其中一个就够了:用户要做的是把这个名字腾开,而不是清点有几个。
 */
function put(into: Map<string, NameOwner>, name: string, owner: NameOwner): void {
  const key = nameKey(name)
  if (key !== '' && !into.has(key)) into.set(key, owner)
}

/**
 * 这个名字撞上谁了。没撞上返回 null。
 *
 * @param self 改名时是自己的 code。<b>改回自己原来的名字不算冲突</b> ——
 *             不排除自己的话,一个「点进去又原样退出来」的输入框会被判成重名。
 */
export function findNameClash(owners: Map<string, NameOwner>, raw: string, self: string | null): NameOwner | null {
  const key = nameKey(raw)
  if (key === '') return null
  const hit = owners.get(key)
  if (!hit || hit.code === self) return null
  return hit
}

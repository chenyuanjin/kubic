/**
 * 原图本地缓存的<b>判据层</b> —— docs/总路线图 `1.1.3`「原图过期删除写死」`P0-4` 🔴`R-04`。
 *
 * <h2>这一层为什么和 IndexedDB 分开写</h2>
 *
 * docs/总路线图 给这条红线留的三个子项里,只有一项是「存」:
 * `1.1.3.1` 存图即写过期戳 / `1.1.3.2` 到期自动<b>归档</b> / `1.1.3.3` <b>验证:改系统时间实测归档生效</b>。
 * 后两项是<b>判断</b>——「到期了没有」「该删哪几张」——而判断跑不进浏览器就永远只能靠人手改系统时间。
 * <p>
 * 所以这个文件里<b>一个 DOM 符号都没有</b>:没有 `indexedDB`、没有 `window`、没有 `Blob`。
 * 它只认一个窄到不能再窄的存储口({@link RawImageBackend})和一个<b>注入的时钟</b>,
 * 于是 `tests/rawImageCache.test.ts` 能拿一个内存实现 + 一个能往前拨的假时钟把
 * `1.1.3.3` 变成一条机器断言。真正碰 IndexedDB 的那一层在 `rawImageDb.ts`,
 * 它<b>不含任何判断</b> —— 一层能被测但不接真存储,一层接真存储但没有可测的判断,
 * 未被测到的那半边因此没有地方藏逻辑。
 *
 * <h2>🔴 「存图即写过期戳」在这里是<b>形态</b>,不是纪律</h2>
 *
 * 字节和过期戳<b>是同一个对象的两个字段</b>({@link StoredRawImage}),
 * 一次 {@link RawImageBackend.put} 写下去。不存在「先写图、回头补一个戳」的路径 ——
 * 不是因为没人这么写,是因为<b>写不出来</b>:{@link RawImageBackend} 上没有第二个写方法。
 * <p>
 * 而 TypeScript 的 `expiresAt: number` 只在编译期成立,所以还有一道运行期的
 * {@link requireAtomicExpiry}:算不出一个合法的过期戳就<b>当场抛,一个字节都不落</b>。
 * 宁可这张图没被缓存,也不要一张<b>没有过期戳的原图</b> ——
 * 那正是 `R-04` 要防的那个终局:<b>没有过期戳 = 永不过期</b>。
 *
 * <h2>🔴 2026-08-29:到期【归档】,不再删除</h2>
 *
 * 改动之前这一层的到期行为是<b>删除</b>,并且有一条写死的判据
 * ——「过期的原图必须从存储里消失,不是被隐藏」。<b>这条判据已被有意反转。</b>
 * <p>
 * 决策人:项目所有者,2026-08-29。当时摆在面前的两个选项里,
 * 另一个是「保持删除,把 `R-102` 归档等桌面壳补足」,选的是这一个。
 * <p>
 * <b>守住的边界没有变,变的只有本机保留时长:</b>
 * <table border="1">
 *   <tr><th>仍然成立</th><th>已经改变</th></tr>
 *   <tr><td>原图<b>只在本机</b>,不上云、不同步、不共享(`R-04` 的主干)</td>
 *       <td>到期不再删除,改为置 `archivedAt` 后<b>无限期本地保留</b></td></tr>
 *   <tr><td>用户按「立即删除」就是<b>真删</b>({@link RawImageCache.forget})</td>
 *       <td>{@link RawImageCache.read} 现在<b>读得出归档行</b></td></tr>
 *   <tr><td>存图即写过期戳,算不出戳就一个字节都不落</td>
 *       <td>{@link RawImageBackend} 多了第二个写方法 {@link RawImageBackend.archive}</td></tr>
 * </table>
 *
 * <p>⚠️ <b>这一改把空间问题从「本层自己解决」变成了「配额问题」</b>:
 * {@link RawImageCache.sweep} 不再释放任何空间,而 `maxEntries` 只管活跃段。
 * 归档段会一直长到 IndexedDB 配额上限,届时 {@link RawImageCache.store} 会失败。
 * 已登记为 `R-105`,<b>没有在本层擅自加一条「归档也淘汰」的规则</b> ——
 * 那等于把刚被改掉的删除行为从后门放回来。
 *
 * <h2>归档有两条触发,和原先的删除触发是同两条</h2>
 *
 * <table border="1">
 *   <tr><th>触发</th><th>它单独漏掉的场景</th></tr>
 *   <tr><td>启动时扫一遍({@link RawImageCache.sweep},由界面在挂载时调)</td>
 *       <td>用户开着页面不动</td></tr>
 *   <tr><td>存新图时顺带扫({@link RawImageCache.store} 内部)</td>
 *       <td>用户再也不导第二张图</td></tr>
 * </table>
 *
 * <p>⚪ `R-102`(「页面关掉之后没有定时器能删东西」)<b>因这次改动不再是缺口</b> ——
 * 到期本来就不删了,漏不漏扫都不改变字节的去留,只影响 `archivedAt` 晚写多久。
 */


/* ========================================================================== */
/* TTL                                                                        */
/* ========================================================================== */

/**
 * 原图在本机活多久 —— <b>6 小时</b>。
 *
 * <h2>⚪ 这是一个待人确认的产品参数,不是一条已决的红线</h2>
 *
 * 已决的是「短期」两个字(docs/决策记录 §2.3 / docs/技术架构 §8.2),<b>具体几小时文档里一个数都没写</b>。
 * `KUBI-6` 上记着 `raw_ttl_hours: 6` —— 那是 agent 填的值,不是人做的决定,
 * 这里沿用它,并把它留在<b>一个常量</b>上等人拍板,而不是散在三处。
 *
 * <h2>6 小时这个量级凭什么</h2>
 *
 * <table border="1">
 *   <tr><th>它要够长的理由</th><th>它要够短的理由</th></tr>
 *   <tr><td>一次晚间学习是 2–3 小时,中途识别挂了要能重来一次;
 *           跨过一顿饭再回来接着记也还在窗口里</td>
 *       <td>原图是这个产品<b>唯一</b>会碰到的、可能属于别人的内容
 *           (课件、讲义)。存得越久,「本地短期缓存」离「盗版课件托管」越近</td></tr>
 *   <tr><td>—</td><td>跨夜是一条清晰的分界:<b>睡一觉起来图还在,已经不叫「短期」了</b>。
 *           6 小时保证晚上开始的那批图在同一天内清空</td></tr>
 * </table>
 *
 * <b>何时改变</b>:有第一个真实用户说「我第二天想再传一次那张图」时 ——
 * 那时要动的是这一个数,不是这条链路。反过来,任何把它调到 24 小时以上的提议,
 * 都要先回答 docs/决策记录 §2.3 那句「否则产品会成为盗版课件的托管方」。
 */
export const RAW_IMAGE_TTL_MS = 6 * 60 * 60 * 1000

/**
 * 本机最多同时缓存几张原图。
 *
 * <p>TTL 管的是「一张图活多久」,管不住「6 小时里能堆多少张」——
 * 单次 6 张、一晚上记十几笔,就是上百 MB 躺在用户的磁盘上。
 * 上限到了先删<b>最早到期</b>的那几张:它们本来也最先该走。
 * <p>12 = 两次满额连拍。不是省空间,是让「短期缓存」这四个字在数量上也成立。
 */
export const RAW_IMAGE_MAX_ENTRIES = 12

/* ========================================================================== */
/* 形状                                                                        */
/* ========================================================================== */

/**
 * 时钟 —— <b>注入,不直接调 `Date.now()`</b>。
 *
 * <p>`1.1.3.3` 的原文是「改系统时间实测删除生效」。真去改机器的系统时间,
 * 这条验证就只能由人做、而且每次都得做一遍;把 `now` 变成一个参数之后,
 * 「把时间拨到过期之后」是测试里的一行赋值。
 * <b>能被机器反复跑的验证,和一次性做过的验证,不是同一个东西。</b>
 */
export type Clock = () => number

/**
 * 一张本机原图的<b>元信息</b> —— 不含字节。
 *
 * <p>逐条对着 docs/技术架构 §8.2 那张表的「客户端本地」一列:本地路径 ✅、过期时间戳 ✅。
 * 🔴 <b>这一整个形状没有任何一个字段会被发到服务端</b> ——
 * 服务端关于图片知道的全部信息是一个枚举值(`record_event.capture_type='photo'`)。
 * 尤其是 {@link label}:它取自用户本机的文件名,docs/技术架构 §8.2 明说<b>路径也是设备信息</b>。
 */
export interface RawImageMeta {
  /** 本机 id。只在这台设备上有意义,不进任何请求体。 */
  readonly id: string
  /**
   * 这张图当时挂在哪条记录上;还没落库时为 `null`。
   *
   * <p>🔴 因为 `R-85`:`CreateRecordRequest.nodeCode` 是 `@NotBlank`,
   * <b>没有考点就没有记录,没有记录就没有 `/records/{id}/image` 可打</b>。
   * 所以图进缓存的时刻常常早于记录存在的时刻,这个字段那时只能是 `null` ——
   * 而<b>它是 `null` 也照样有过期戳</b>,缓存的生命周期不依赖任何一条记录。
   */
  readonly recordId: string | null
  /** 服务端会自己从字节里认一遍格式,这里存的只是本机挑文件时浏览器报的类型。 */
  readonly mime: string
  readonly byteSize: number
  /** 给用户在列表里认出「哪一张」用。<b>本机文件名,只留在本机。</b> */
  readonly label: string
  /** 写入时刻。 */
  readonly storedAt: number
  /** 🔴 过期时刻。与字节<b>同一次写入</b>,见本文件头。 */
  readonly expiresAt: number
  /**
   * 🔴 归档时刻;`null` = 仍在活跃期。
   *
   * <p><b>2026-08-29 决策变更</b>:到期<b>不再删除</b>,改为归档保留(本机)。
   * 见本文件头「归档而非删除」一节与 `docs/决策记录 §2.3` 的加注。
   * <p>它由 {@link RawImageBackend.archive} 单独写,<b>不能在 {@link RawImageBackend.put} 里带进来</b> ——
   * 存图那一刻它必然是 `null`,没有「一存进来就是归档态」这种行。
   */
  readonly archivedAt: number | null
}

/**
 * 原图字节的<b>结构类型</b> —— 刻意<b>不写</b> `Blob`。
 *
 * <p>写 `Blob` 就得给这一层挂上 DOM 类型,而挂上 DOM 之后它就没法在 node 里被测
 * (`tests/` 那个 tsconfig 只有 `ES2023` + `@types/node`,没有 `DOM`)。
 * 浏览器的 `Blob`、node 的 `Blob`、`File` 三者都结构上满足这三个成员,
 * 于是判据层能被测,而真实类型在 `rawImageDb.ts` / 界面那侧一点都没丢。
 */
export interface RawImageBytes {
  readonly size: number
  readonly type: string
  arrayBuffer(): Promise<ArrayBuffer>
}

/** 🔴 元信息 + 字节 = <b>一行</b>。一次写入,写的就是这个对象。 */
export interface StoredRawImage extends RawImageMeta {
  readonly blob: RawImageBytes
}

/**
 * 存储口 —— <b>窄到写不出「先存图后补戳」</b>。
 *
 * <h2>四个方法,一个写</h2>
 *
 * 只有 {@link put} 能写,而它收的是整行。没有 `setExpiry`、没有 `update`、没有 `putBlob` ——
 * 「补一个过期戳」这件事在这个接口上<b>没有对应的方法</b>。
 * 接口窄不是简洁,是这条红线在类型上的形态。
 *
 * <h2>为什么 {@link listMeta} 不带字节</h2>
 *
 * 界面上的倒计时、扫过期、上限淘汰,三件事都只需要元信息。让它顺手把 blob 一起带出来,
 * 意味着每一次扫描都把全部原图<b>再过一遍手</b> —— 而这一层的全部纪律就是「少碰几次字节」。
 * 字节只在 {@link read} 里出现一次,而 {@link read} 只有「这张图现在要送去识别」这一个调用点。
 */
export interface RawImageBackend {
  /** 🔴 一次事务写下整行。实现必须保证「要么整行在,要么整行不在」。 */
  put(row: StoredRawImage): Promise<void>
  /** 全部元信息,不含字节。顺序无所谓,判据层自己排。 */
  listMeta(): Promise<RawImageMeta[]>
  /** 取一行(含字节)。不存在返回 `null`。 */
  read(id: string): Promise<StoredRawImage | null>
  /** 按 id 批量删。删不存在的 id 不算错。<b>只服务于用户手按的删</b>,不再服务于到期。 */
  deleteMany(ids: readonly string[]): Promise<void>
  /**
   * 🔴 按 id 批量归档 —— <b>只写 `archivedAt` 一个字段,不碰字节、不碰过期戳</b>。
   *
   * <p>它是 {@link put} 之外的第二个写方法,而这个接口原本刻意只有一个写。
   * 加它的理由只有一条:到期行为从「删」改成「归档」(2026-08-29),
   * 而归档必须能<b>就地改一个字段</b>,不能靠「读出整行再 put 回去」——
   * 那条路要把全部字节再过一遍手,正是本层一直在避免的事。
   * <p>它<b>无法</b>被用来实现「先存图后补戳」:它只认 `archivedAt`,
   * 碰不到 `expiresAt`,也碰不到 `blob`。接口仍然窄,只是窄在了新的地方。
   */
  archive(ids: readonly string[], at: number): Promise<void>
}

/**
 * 算不出合法过期戳时抛它 —— 于是<b>这张图不会被存下来</b>。
 *
 * <p>把它做成一个专门的类型是为了让调用方能分辨:
 * 「本地缓存不可用」(用户仍然可以把图送去识别一次,服务端本来就不落盘)
 * 与「本地缓存坏了」是两回事,界面上该说的话也不同。
 */
export class RawImageExpiryError extends Error {
  constructor(reason: string) {
    super(reason)
    this.name = 'RawImageExpiryError'
  }
}

/**
 * 本地存储整个用不了时抛它(隐私模式、被策略禁用、配额拒绝、目录没权限、磁盘满)。
 *
 * <p>🔴 <b>这不是一次「记录失败」。</b> docs/后端详设 §1.5「降级方向是『少功能』,不是『少记录』」:
 * 缓存不上照样能把这张图送去识别一次(服务端本来就不落盘),照样能记下这一笔。
 * 界面要说的是「这张图没被本地缓存」,不是「记不下来」。
 *
 * <h2>🔴 2026-08-31:它从 `rawImageDb.ts` 搬到这里,理由是一条结构约束</h2>
 *
 * 原先它定义在 IndexedDB 实现里。加上文件系统实现之后,
 * `rawImageFs.ts` 要抛同一个类型,就得 `import` 一次 `rawImageDb.ts` ——
 * <b>那会让两个存储实现互相认识</b>,而 `docs/原图存储 §3.1` 冻结的依赖图是
 * 「两个存储实现各自只依赖判据层的类型,彼此不认识」。
 * <p>
 * 所以它搬到契约所在的这一层。判断它是不是该属于「判据」不重要,
 * <b>它属于 {@link RawImageBackend} 的契约</b>(`docs/原图存储 §2.1` 的错误码表:
 * 两个错误类型,不新增第三个),而契约就在这个文件里。
 * <p>它不含任何 DOM 符号,所以搬进来不破坏这一层「跑得进 node」的性质。
 */
export class RawImageStorageError extends Error {
  constructor(reason: string, cause?: unknown) {
    super(reason, { cause })
    this.name = 'RawImageStorageError'
  }
}

/* ========================================================================== */
/* 判据                                                                        */
/* ========================================================================== */

/**
 * 🔴 <b>没有合法过期戳的行,一律当成已经过期。</b>
 *
 * <p>这一行的方向是刻意的。反过来写(「戳读不出来就先留着,回头再说」)会让
 * 一行坏数据变成一张<b>永不过期的原图</b>,而那正好是 `R-04` 的终局。
 * 版本升级、手改 IndexedDB、写到一半断电,任何原因产生的残行都走这条路 ——
 * <b>存疑就删,不是宁可留着</b>。这也是「宁缺毋滥」在这条链路上的形态。
 */
export function isArchived(meta: RawImageMeta): boolean {
  return typeof meta.archivedAt === 'number' && Number.isFinite(meta.archivedAt)
}

/**
 * 🔴 <b>没有合法过期戳的行,一律当成已经过期。</b>(判据方向未变)
 */
export function isExpired(meta: RawImageMeta, now: number): boolean {
  if (typeof meta.expiresAt !== 'number' || !Number.isFinite(meta.expiresAt)) return true
  return meta.expiresAt <= now
}

/**
 * 还剩多少毫秒删。已过期返回 0(不返回负数 —— 界面上「还有 -3 分钟」没有意义)。
 */
export function remainingMs(meta: RawImageMeta, now: number): number {
  if (isExpired(meta, now)) return 0
  return meta.expiresAt - now
}

/**
 * 🔴 写之前的最后一道:过期戳算不出来就<b>不写</b>。
 *
 * <p>`expiresAt` 的类型是 `number`,但 `NaN` 是 number,`Infinity` 也是 number,
 * 而一个 `Infinity` 的过期戳<b>正是「永不过期」本身</b>。
 * 时钟被注入之后这条更有必要:传进来的 `now` 是外面给的,可能是任何东西。
 */
function requireAtomicExpiry(row: StoredRawImage): void {
  if (!Number.isFinite(row.storedAt)) {
    throw new RawImageExpiryError('拿不到一个有效的当前时间,这张原图没有被缓存 —— 没有过期戳的原图等于永不过期。')
  }
  if (!Number.isFinite(row.expiresAt)) {
    throw new RawImageExpiryError('算不出过期时刻,这张原图没有被缓存 —— 没有过期戳的原图等于永不过期。')
  }
  if (row.expiresAt <= row.storedAt) {
    throw new RawImageExpiryError('过期时刻不晚于写入时刻,这张原图没有被缓存 —— 存下去也只是一张立刻该删的图。')
  }
}

/* ========================================================================== */
/* 缓存本体                                                                    */
/* ========================================================================== */

/**
 * 默认 id 生成器。`crypto.randomUUID` 在非安全上下文里没有,退回两段随机数。
 *
 * <h2>🔴 2026-08-31:退路里的 `Date.now()` 被换掉了,而这不是洁癖</h2>
 *
 * 原先这一行是 `` `${Date.now().toString(36)}-${随机}` ``。它<b>用时钟当熵源</b>,
 * 不是当时刻,所以它没有制造过任何一个错误的到期判断。
 * <p>
 * 但它让「这条链路上 `Date.now()` 只出现一处」这句话<b>字面上是假的</b> ——
 * 而那句话正是「时钟必须被注入」这条纪律唯一可执行的形态:
 * `shell/build.sh` 步骤 ③.5 数的就是它。一条<b>数不准的断言等于没有断言</b>,
 * 而「这一处是良性的、那一处不是」这种区分留不住 ——
 * 下一个人只会看见判据层里有一个现成的 `Date.now()`,然后用它。
 * <p>
 * 换掉的代价:id 不再带时间前缀。**没有任何地方依赖那个前缀** ——
 * 排序一律按 `expiresAt`({@link RawImageCache.list} / {@link evictBeyondCap}),
 * 而且这条退路只在没有 `crypto.randomUUID` 的环境里走,同时活着的行不超过
 * {@link RAW_IMAGE_MAX_ENTRIES} 条,两段随机足够。
 */
function defaultNewId(): string {
  const c: { randomUUID?: () => string } | undefined = globalThis.crypto
  if (typeof c?.randomUUID === 'function') return c.randomUUID()
  const seg = (): string => Math.random().toString(36).slice(2, 10)
  return `${seg()}-${seg()}`
}

export interface RawImageCacheOptions {
  backend: RawImageBackend
  /** 🔴 注入。见 {@link Clock}。 */
  now: Clock
  ttlMs?: number
  maxEntries?: number
  newId?: () => string
}

/**
 * 本机原图缓存。
 *
 * <p>它只有五个动作:{@link store}(存,顺带清)、{@link sweep}(扫,启动时来一次)、
 * {@link list}(给界面看倒计时)、{@link read}(送去识别那一次)、
 * {@link forget} / {@link forgetAll}(用户自己按的删)。
 * <b>没有「延期」,也没有「续命」</b> —— 那是这条红线上唯一不该存在的动作。
 */
export class RawImageCache {
  private readonly backend: RawImageBackend
  private readonly now: Clock
  private readonly ttlMs: number
  private readonly maxEntries: number
  private readonly newId: () => string

  constructor(opts: RawImageCacheOptions) {
    this.backend = opts.backend
    this.now = opts.now
    this.ttlMs = opts.ttlMs ?? RAW_IMAGE_TTL_MS
    this.maxEntries = opts.maxEntries ?? RAW_IMAGE_MAX_ENTRIES
    this.newId = opts.newId ?? defaultNewId
  }

  /** 这个缓存约定的存活时长。界面上那句「本机保留 N 小时」读的就是它,不另写一个数。 */
  get ttl(): number {
    return this.ttlMs
  }

  /**
   * 🔴 把注入的时钟<b>借给界面读一次</b>。
   *
   * <p>倒计时要算「还有多久」,而它必须和「到期了没有」用<b>同一个时钟</b>。
   * 界面自己写一行 `Date.now()` 平时看不出差别,`1.1.3.3` 拨时间那一刻就会:
   * 一边动了,另一边没动,于是显示「还有 3 小时」的那一行其实已经被删掉了。
   * <p>它<b>只读不写</b>,没有任何方法能从外面换掉这个时钟 —— 换时钟只能在构造时。
   */
  currentTime(): number {
    return this.now()
  }

  /**
   * 🔴 存一张原图 —— <b>过期戳与字节同一次写入</b>。
   *
   * <h2>顺序是有意的:先算戳、再校验、再清旧、最后才写</h2>
   *
   * <ol>
   *   <li><b>算戳</b> —— `now` 与 `expiresAt` 在同一行代码里定出来,中间没有 await,
   *       所以不会出现「取完时间等了 300ms 才写」的偏移</li>
   *   <li><b>校验</b>({@link requireAtomicExpiry})—— 不合法就抛,
   *       <b>这时后端一个字节都还没碰过</b>。「做不到原子就宁可不存这张图」落在这一步</li>
   *   <li><b>清旧</b>({@link sweep} + 上限淘汰)—— 两条触发里的第二条。
   *       ⚠️ 清不掉就<b>不存新的</b>:让写入依赖清理,是为了不让「删不掉」这件事
   *       安安静静地变成「越攒越多」。宁可这次导入失败并把原因摆出来</li>
   *   <li><b>写</b> —— 一次 {@link RawImageBackend.put},整行</li>
   * </ol>
   */
  async store(input: {
    blob: RawImageBytes
    label: string
    mime?: string
    recordId?: string | null
  }): Promise<RawImageMeta> {
    const storedAt = this.now()
    const expiresAt = storedAt + this.ttlMs

    const row: StoredRawImage = {
      id: this.newId(),
      recordId: input.recordId ?? null,
      mime: input.mime ?? input.blob.type,
      byteSize: input.blob.size,
      label: input.label,
      storedAt,
      expiresAt,
      archivedAt: null,
      blob: input.blob,
    }
    requireAtomicExpiry(row)

    await this.sweep()
    await this.evictBeyondCap(this.maxEntries - 1)

    await this.backend.put(row)
    return toMeta(row)
  }

  /**
   * 扫一遍,把到期的<b>归档</b>,返回被归档的 id。
   *
   * <p><b>2026-08-29 之前这里是删除</b>。改成归档之后,这个方法不再释放任何空间 ——
   * 它只是把行从「活跃」挪到「归档」。空间问题因此从本层消失、转移到配额上,见 `R-105`。
   *
   * <p>已归档的行不会被重复归档({@link isArchived} 过滤),
   * 所以反复调用它不会把 `archivedAt` 一路往后推。
   */
  async sweep(): Promise<string[]> {
    const now = this.now()
    const all = await this.backend.listMeta()
    const doomed = all.filter((m) => !isArchived(m) && isExpired(m, now)).map((m) => m.id)
    if (doomed.length > 0) await this.backend.archive(doomed, now)
    return doomed
  }

  /**
   * 已归档的原图,<b>最近归档的排在前面</b>。
   *
   * <p>它和 {@link list} 是两个不相交的集合:活跃的不在这里,归档的不在那里。
   * 界面要分两处显示,<b>不要合成一个列表再用状态标记区分</b> ——
   * 那会让「本机还留着多少张原图」这个数字从界面上消失,而那正是用户唯一该看见的数。
   */
  async listArchived(): Promise<RawImageMeta[]> {
    await this.sweep()
    return (await this.backend.listMeta())
      .filter(isArchived)
      .sort((a, b) => (b.archivedAt ?? 0) - (a.archivedAt ?? 0))
  }

  /**
   * 还活着的原图,<b>最先到期的排在前面</b>。
   *
   * <p>先 {@link sweep} 再列:界面读到的每一行都是刚刚校验过的。
   * 列完再过一次 {@link isExpired} 不是多余 —— sweep 与 list 之间隔着一次异步,
   * 而这一层唯一不能出的错就是<b>把一张已经该删的图显示成「还有 3 分钟」</b>。
   */
  async list(): Promise<RawImageMeta[]> {
    await this.sweep()
    const now = this.now()
    return (await this.backend.listMeta())
      .filter((m) => !isArchived(m) && !isExpired(m, now))
      .sort((a, b) => a.expiresAt - b.expiresAt)
  }

  /**
   * 取回一张图的字节 —— 唯一会碰到原图内容的读口。
   *
   * <p>🔴 <b>2026-08-29 起,已归档的行照样读得出来。</b>
   * 改动之前这里写的是「过期的当场删掉并返回 null」,理由是「留一条『过期但还能读出来』
   * 的路,等于给这条红线开一个后门」。<b>那扇门现在是被有意打开的</b> ——
   * 归档的全部意义就是「留着还能用」,读不出来的归档等于删除。
   * <p>仍然守住的是另外两条:归档只在<b>本机</b>,以及用户按删就是真删。
   */
  async read(id: string): Promise<StoredRawImage | null> {
    await this.sweep()
    return await this.backend.read(id)
  }

  /** 用户按的「立即删除」。docs/总路线图 的 UI 审核项之一:随时能手动删掉。 */
  async forget(id: string): Promise<void> {
    await this.backend.deleteMany([id])
  }

  /** 「全部删除」。 */
  async forgetAll(): Promise<void> {
    const all = await this.backend.listMeta()
    if (all.length > 0) await this.backend.deleteMany(all.map((m) => m.id))
  }

  /**
   * 把条数压到 `keep` 以内,先删<b>最早到期</b>的。
   *
   * <p>按 `expiresAt` 而不是 `storedAt` 排:TTL 将来若被人调过,同一时刻的库里会同时存在
   * 两种时长的行,那时「最早存的」和「最早该走的」不再是同一批,而该先走的显然是后者。
   */
  private async evictBeyondCap(keep: number): Promise<void> {
    /* 🔴 R-106(2026-08-30 修):超出上限的也【归档】,不再 deleteMany。
     *
     * 上一版这里 filter 掉归档行之后对【活跃】行 deleteMany —— 本意是「别让上限
     * 把归档删掉」,实际后果是把删除的对象整个调转了个方向:
     *
     *   过期的图          → 归档,永久保留
     *   没过期但超上限的图 → 真删
     *
     * 用得越勤活图越容易被删,放着不管的过期图一张不走。而 8-29 决策的原话是
     * 「到期不删、改为归档保留」——本意显然是「不要自动删用户的图」,
     * 不是「把删除挪到另一条路上」。
     *
     * 所以现在整层【没有任何自动删除路径】:deleteMany 只服务于 forget/forgetAll,
     * 也就是用户自己按的那一下。上限仍然有意义 —— 它限的是【活跃段】的条数
     * (界面上列几张、倒计时显示几条),超出的进归档而不是进垃圾桶。
     *
     * 代价是归档只增不减,见 R-105(配额)。那条要等壳形态改存文件系统才根治。
     */
    const now = this.now()
    const live = (await this.backend.listMeta()).filter((m) => !isArchived(m))
    if (live.length <= keep) return
    const overflow = [...live]
      .sort((a, b) => a.expiresAt - b.expiresAt)
      .slice(0, live.length - keep)
    await this.backend.archive(overflow.map((m) => m.id), now)
  }
}

function toMeta(row: StoredRawImage): RawImageMeta {
  return {
    id: row.id,
    recordId: row.recordId,
    mime: row.mime,
    byteSize: row.byteSize,
    label: row.label,
    storedAt: row.storedAt,
    expiresAt: row.expiresAt,
    archivedAt: row.archivedAt,
  }
}

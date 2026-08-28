/**
 * 原图本地缓存的<b>判据层</b> —— docs/08 `1.1.3`「原图过期删除写死」`P0-4` 🔴`R-04`。
 *
 * <h2>这一层为什么和 IndexedDB 分开写</h2>
 *
 * docs/08 给这条红线留的三个子项里,只有一项是「存」:
 * `1.1.3.1` 存图即写过期戳 / `1.1.3.2` 到期自动删除 / `1.1.3.3` <b>验证:改系统时间实测删除生效</b>。
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
 * <h2>到期删除有两条触发,少一条都会漏</h2>
 *
 * <table border="1">
 *   <tr><th>触发</th><th>它单独漏掉的场景</th></tr>
 *   <tr><td>启动时扫一遍({@link RawImageCache.sweep},由界面在挂载时调)</td>
 *       <td>用户开着页面不动:一个整天不刷新的标签页里,图放到天荒地老</td></tr>
 *   <tr><td>存新图时顺带清({@link RawImageCache.store} 内部)</td>
 *       <td><b>用户再也不导第二张图</b>:最后那一批原图永远等不到下一次写</td></tr>
 * </table>
 *
 * 两条都不覆盖的场景仍然存在(用户从此不再打开这个产品),那一段只能靠 TTL 本身够短。
 * ⚪ 这是本层的已知边界:<b>浏览器里没有后台定时器能在页面关掉之后删东西</b>,
 * 写一个 Service Worker 也只是把「再也不打开」推后一步,不是消除它。
 */

/* ========================================================================== */
/* TTL                                                                        */
/* ========================================================================== */

/**
 * 原图在本机活多久 —— <b>6 小时</b>。
 *
 * <h2>⚪ 这是一个待人确认的产品参数,不是一条已决的红线</h2>
 *
 * 已决的是「短期」两个字(docs/01 §2.3 / docs/10 §8.2),<b>具体几小时文档里一个数都没写</b>。
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
 * 都要先回答 docs/01 §2.3 那句「否则产品会成为盗版课件的托管方」。
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
 * <p>逐条对着 docs/10 §8.2 那张表的「客户端本地」一列:本地路径 ✅、过期时间戳 ✅。
 * 🔴 <b>这一整个形状没有任何一个字段会被发到服务端</b> ——
 * 服务端关于图片知道的全部信息是一个枚举值(`record_event.capture_type='photo'`)。
 * 尤其是 {@link label}:它取自用户本机的文件名,docs/10 §8.2 明说<b>路径也是设备信息</b>。
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
  /** 按 id 批量删。删不存在的 id 不算错。 */
  deleteMany(ids: readonly string[]): Promise<void>
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

/** 默认 id 生成器。`crypto.randomUUID` 在非安全上下文里没有,退回时间戳 + 随机数。 */
function defaultNewId(): string {
  const c: { randomUUID?: () => string } | undefined = globalThis.crypto
  if (typeof c?.randomUUID === 'function') return c.randomUUID()
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
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
      blob: input.blob,
    }
    requireAtomicExpiry(row)

    await this.sweep()
    await this.evictBeyondCap(this.maxEntries - 1)

    await this.backend.put(row)
    return toMeta(row)
  }

  /**
   * 扫一遍,把到期的删掉,返回被删掉的 id。
   *
   * <p>界面挂载时调一次(触发一),{@link store} 里调一次(触发二)。
   * 它<b>不返回还活着的那些</b> —— 那是 {@link list} 的事,两件事混在一个返回值里,
   * 调用方迟早会拿「删了几张」当「还剩几张」用。
   */
  async sweep(): Promise<string[]> {
    const now = this.now()
    const all = await this.backend.listMeta()
    const doomed = all.filter((m) => isExpired(m, now)).map((m) => m.id)
    if (doomed.length > 0) await this.backend.deleteMany(doomed)
    return doomed
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
      .filter((m) => !isExpired(m, now))
      .sort((a, b) => a.expiresAt - b.expiresAt)
  }

  /**
   * 取回一张图的字节 —— 唯一会碰到原图内容的读口。
   *
   * <p>过期的取不到:不是「取到了但标记为过期」,是<b>当场删掉并返回 `null`</b>。
   * 留一条「过期但还能读出来」的路,等于给这条红线开一个后门。
   */
  async read(id: string): Promise<StoredRawImage | null> {
    await this.sweep()
    const row = await this.backend.read(id)
    if (row === null) return null
    if (isExpired(row, this.now())) {
      await this.backend.deleteMany([id])
      return null
    }
    return row
  }

  /** 用户按的「立即删除」。docs/08 的 UI 审核项之一:随时能手动删掉。 */
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
    const all = await this.backend.listMeta()
    if (all.length <= keep) return
    const doomed = [...all].sort((a, b) => a.expiresAt - b.expiresAt).slice(0, all.length - keep)
    await this.backend.deleteMany(doomed.map((m) => m.id))
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
  }
}

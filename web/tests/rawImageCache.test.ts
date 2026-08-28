/**
 * `1.1.3.3` —— docs/08 原文:「验证:改系统时间实测删除生效」。
 *
 * <h2>这个文件存在的理由:那条验证不该只有人能做</h2>
 *
 * 「改系统时间」是一条只能手工做、做完没有留痕、下次改代码不会自动重做的验证。
 * 而 `1.1.3` 在 docs/08 上标着「<b>第一天不定就改不回来</b>」——
 * 强度与后果不匹配的红线,`R-75` 已经点过一次名了。
 * 所以这里把「改系统时间」换成<b>拨一个注入的时钟</b>,判据一个字不改。
 *
 * <h2>🔴 零新增依赖</h2>
 *
 * `web/package.json` 的 dependencies / devDependencies <b>一个字都没动</b>
 * (docs/12 §B.10 与 `R-46`:这个仓库的依赖清洗是人工 grep,
 * 加一个包就把那条人工纪律的成本抬高一次,而且间接依赖在直接清单里一个字都不出现)。
 * 这条路只用到:
 * <ul>
 *   <li><b>node 内置的 `node:test` / `node:assert`</b> —— 不是 vitest,不是 jest</li>
 *   <li><b>node 22 自带的 TypeScript 类型擦除</b> —— `.ts` 直接跑,不需要 ts-node、不需要编译产物</li>
 *   <li><b>已有的 `tsc -b`</b> —— `tsconfig.test.json` 把这个目录纳入类型检查,
 *       所以这些断言和被测代码的类型是对得上的,不是「运行时碰巧过了」</li>
 * </ul>
 * 命令:`npm run test:retention`(package.json 只加了这一行)。
 *
 * <h2>被测的是判据层,不是 IndexedDB</h2>
 *
 * `src/lib/rawImageCache.ts` 里没有一个 DOM 符号,它只认一个窄存储口和一个时钟。
 * 下面那个 {@link MemoryBackend} 就是那个口的内存实现 —— 它<b>只会存和删</b>,
 * 一句判断都没有,所以「到期了没有」这件事无处可赖,只能由被测代码回答。
 * 真正碰 IndexedDB 的 `rawImageDb.ts` 同样一句判断都没有(见它的类注释),
 * 于是两层加起来,没有一处判断落在测试之外。
 */

import assert from 'node:assert/strict'
import test from 'node:test'
import {
  RawImageCache,
  RawImageExpiryError,
  RAW_IMAGE_TTL_MS,
  isExpired,
  remainingMs,
} from '../src/lib/rawImageCache.ts'
import type { RawImageBackend, RawImageMeta, StoredRawImage } from '../src/lib/rawImageCache.ts'

/* ========================================================================== */
/* 测试替身                                                                    */
/* ========================================================================== */

/**
 * 内存版存储口 —— <b>只存和删,不判断</b>。
 *
 * <p>它额外记了一个 {@link writes}:有几条断言要问的不是「存进去的对不对」,
 * 而是「<b>到底有没有写过</b>」——「做不到原子就宁可不存这张图」只能这么验。
 */
class MemoryBackend implements RawImageBackend {
  readonly rows = new Map<string, StoredRawImage>()
  /** put 被调用过几次。 */
  writes = 0

  put(row: StoredRawImage): Promise<void> {
    this.writes += 1
    this.rows.set(row.id, row)
    return Promise.resolve()
  }

  listMeta(): Promise<RawImageMeta[]> {
    return Promise.resolve([...this.rows.values()].map(({ blob: _b, ...meta }) => meta))
  }

  read(id: string): Promise<StoredRawImage | null> {
    return Promise.resolve(this.rows.get(id) ?? null)
  }

  deleteMany(ids: readonly string[]): Promise<void> {
    for (const id of ids) this.rows.delete(id)
    return Promise.resolve()
  }
}

/**
 * 可以往前拨的时钟 —— 这一整个文件的支点。
 *
 * <p>写成字段赋值而不是构造器参数属性(`constructor(private t: number)`):
 * 后者不是可擦除语法,node 的类型擦除跑不了,`erasableSyntaxOnly` 也会红。
 * 这条路的前提就是「`.ts` 不经编译直接跑」,所以这里只用可擦除的写法。
 */
class FakeClock {
  private t: number
  constructor(startAt: number) {
    this.t = startAt
  }
  now = (): number => this.t
  /** 「把系统时间往后调 x 毫秒」。 */
  advance(ms: number): void {
    this.t += ms
  }
}

/** 假字节。判据层只用到 size / type / arrayBuffer 三个成员(见 RawImageBytes)。 */
function bytes(size = 1024, type = 'image/png') {
  return { size, type, arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)) }
}

/** 一套现开的缓存。id 用递增序号,断言里好写。 */
function setup(opts: { ttlMs?: number; maxEntries?: number; startAt?: number } = {}) {
  const backend = new MemoryBackend()
  const clock = new FakeClock(opts.startAt ?? 1_700_000_000_000)
  let seq = 0
  const cache = new RawImageCache({
    backend,
    now: clock.now,
    ttlMs: opts.ttlMs,
    maxEntries: opts.maxEntries,
    newId: () => `img-${++seq}`,
  })
  return { backend, clock, cache }
}

const HOUR = 60 * 60 * 1000

/* ========================================================================== */
/* 1.1.3.1 存图即写入过期时间戳                                                 */
/* ========================================================================== */

test('1.1.3.1 存图那一刻就写好了过期戳,不是删的时候才算', async () => {
  const { backend, cache, clock } = setup()
  const at = clock.now()

  const meta = await cache.store({ blob: bytes(4096), label: '截图.png' })

  const row = backend.rows.get(meta.id)
  assert.ok(row, '这张图应该在存储口里')
  // 🔴 判据不是「过期戳后来被补上了」,而是【落进存储口的那一行本身】带着它。
  assert.equal(row.storedAt, at)
  assert.equal(row.expiresAt, at + RAW_IMAGE_TTL_MS)
  assert.equal(backend.writes, 1, '一张图 = 一次写入。两次写入就意味着有一个中间态')
})

test('1.1.3.1 字节与过期戳是同一行的两个字段 —— 存储口上根本没有第二个写方法', async () => {
  const { backend, cache } = setup()
  const meta = await cache.store({ blob: bytes(), label: 'a.png' })
  const row = backend.rows.get(meta.id)!

  // 「先写图、回头补戳」写不出来:blob 和 expiresAt 在同一个对象里,
  // 而 RawImageBackend 上只有 put(整行) 这一个写方法。
  assert.ok('blob' in row && 'expiresAt' in row)
  assert.ok(row.expiresAt > row.storedAt)
})

test('🔴 算不出合法过期戳时,一个字节都不落 —— 做不到原子就宁可不存这张图', async () => {
  for (const [name, ttlMs] of [
    ['TTL 是 NaN', Number.NaN],
    ['TTL 是 Infinity(= 永不过期)', Number.POSITIVE_INFINITY],
    ['TTL 是 0(存下去就该删)', 0],
    ['TTL 是负数', -1],
  ] as const) {
    const { backend, cache } = setup({ ttlMs })
    await assert.rejects(
      () => cache.store({ blob: bytes(), label: 'x.png' }),
      RawImageExpiryError,
      `${name}:应该被 requireAtomicExpiry 拦下`,
    )
    // 🔴 这一条才是判据本身:抛异常还不够,关键是【存储口一次都没被写过】。
    //    一张没有过期戳的原图 = 一张永不过期的原图,而那正是 R-04 的终局。
    assert.equal(backend.writes, 0, `${name}:不该有任何写入`)
    assert.equal(backend.rows.size, 0, `${name}:不该留下任何行`)
  }
})

test('🔴 时钟坏掉时同样一个字节都不落', async () => {
  const backend = new MemoryBackend()
  const cache = new RawImageCache({ backend, now: () => Number.NaN })
  await assert.rejects(() => cache.store({ blob: bytes(), label: 'x.png' }), RawImageExpiryError)
  assert.equal(backend.writes, 0)
})

/* ========================================================================== */
/* 1.1.3.2 / 1.1.3.3 到期自动删除 —— 拨时钟实测                                 */
/* ========================================================================== */

test('🔴 1.1.3.3 把时钟拨过期之后,图【真的没了】—— 不是只是列表里不显示', async () => {
  const { backend, cache, clock } = setup()

  const meta = await cache.store({ blob: bytes(2048), label: '资料分析-01.png' })
  assert.equal(backend.rows.size, 1, '刚存完应该在')
  assert.deepEqual((await cache.list()).map((m) => m.id), [meta.id])

  // ——「改系统时间」就是这一行。TTL 之后再多一毫秒。
  clock.advance(RAW_IMAGE_TTL_MS + 1)

  assert.deepEqual(await cache.list(), [], '过期之后列表里不该还有它')
  // 🔴 这一条是真正的判据:落到存储口里的那一行被【删掉】了,
  //    而不是「还在,只是被过滤掉了」。后者在磁盘上仍然是一张原图。
  assert.equal(backend.rows.size, 0, '过期的原图必须从存储里消失,不是被隐藏')
})

test('1.1.3.2 到期是「>=」:差一毫秒还在,到点就走', async () => {
  const { backend, cache, clock } = setup({ ttlMs: HOUR })
  await cache.store({ blob: bytes(), label: 'a.png' })

  clock.advance(HOUR - 1)
  assert.equal((await cache.list()).length, 1, '还差 1 毫秒,不该删')

  clock.advance(1)
  /* ⚪ 这一行是这条断言第一次写时漏掉的,而漏掉之后它<b>红了</b> ——
     记在这里因为那次红说清了一件事实:删除是【被触发的】,不是自己发生的。
     浏览器里没有能在页面关掉之后跑的定时器,所以两条触发(启动扫一遍 / 存新图顺带清)
     就是全部。把它们当成「反正会自己删」来写,断言和产品会一起错。 */
  await cache.sweep()
  assert.equal(backend.rows.size, 0, '到点即删 —— expiresAt <= now 就算过期')
})

test('触发一:启动时扫一遍 —— 页面根本没被打开过的那批也会被清掉', async () => {
  const { backend, clock } = setup({ ttlMs: HOUR })

  // 上一次会话存的图。
  const before = new RawImageCache({
    backend,
    now: clock.now,
    ttlMs: HOUR,
    newId: () => 'old-1',
  })
  await before.store({ blob: bytes(), label: '昨晚.png' })

  // 关掉页面、过了两小时、重新打开 —— 新的实例,同一个存储。
  clock.advance(2 * HOUR)
  const after = new RawImageCache({ backend, now: clock.now, ttlMs: HOUR })

  assert.deepEqual(await after.sweep(), ['old-1'], 'sweep 应该报出它删了哪一张')
  assert.equal(backend.rows.size, 0)
})

test('触发二:存新图时顺带清 —— 旧的走,新的留', async () => {
  const { backend, cache, clock } = setup({ ttlMs: HOUR })

  const old = await cache.store({ blob: bytes(), label: '旧.png' })
  clock.advance(2 * HOUR)
  const fresh = await cache.store({ blob: bytes(), label: '新.png' })

  assert.equal(backend.rows.has(old.id), false, '存新图那一次应该把过期的旧图带走')
  assert.equal(backend.rows.has(fresh.id), true)
  assert.equal(backend.rows.size, 1)
})

/* ========================================================================== */
/* 存疑就删                                                                    */
/* ========================================================================== */

test('🔴 没有合法过期戳的残行一律当成已过期 —— 存疑就删,不是宁可留着', async () => {
  for (const bad of [Number.NaN, Number.POSITIVE_INFINITY, undefined, null, '永远']) {
    const { backend, cache, clock } = setup()
    // 绕过判据层直接塞一行进存储 —— 模拟版本升级 / 手改 IndexedDB / 写到一半断电。
    backend.rows.set('legacy', {
      id: 'legacy',
      recordId: null,
      mime: 'image/png',
      byteSize: 1,
      label: '来路不明.png',
      storedAt: clock.now(),
      expiresAt: bad as unknown as number,
      blob: bytes(),
    })

    assert.deepEqual(await cache.list(), [], `expiresAt=${String(bad)}:不该出现在列表里`)
    assert.equal(backend.rows.size, 0, `expiresAt=${String(bad)}:应该被当成过期并删掉`)
  }
})

test('read 取不到过期的图,而且顺手把它删了 —— 不留「过期但还能读」这条后门', async () => {
  const { backend, cache, clock } = setup({ ttlMs: HOUR })
  const meta = await cache.store({ blob: bytes(), label: 'a.png' })

  assert.ok(await cache.read(meta.id), '没过期时读得到')

  clock.advance(HOUR)
  assert.equal(await cache.read(meta.id), null)
  assert.equal(backend.rows.size, 0)
})

/* ========================================================================== */
/* 手动删 / 上限                                                               */
/* ========================================================================== */

test('立即删除:单张与全部,都是真删', async () => {
  const { backend, cache } = setup()
  const a = await cache.store({ blob: bytes(), label: 'a.png' })
  await cache.store({ blob: bytes(), label: 'b.png' })

  await cache.forget(a.id)
  assert.equal(backend.rows.has(a.id), false)
  assert.equal(backend.rows.size, 1)

  await cache.forgetAll()
  assert.equal(backend.rows.size, 0)
})

test('条数上限:超了先删最早到期的那几张', async () => {
  const { backend, cache, clock } = setup({ ttlMs: 10 * HOUR, maxEntries: 3 })

  const kept: string[] = []
  for (let i = 0; i < 5; i++) {
    const m = await cache.store({ blob: bytes(), label: `${i}.png` })
    kept.push(m.id)
    clock.advance(1000) // 每张的 expiresAt 依次往后
  }

  assert.equal(backend.rows.size, 3, '上限 3 就是 3')
  assert.deepEqual([...backend.rows.keys()].sort(), kept.slice(2).sort(), '留下的应该是最后三张')
})

/* ========================================================================== */
/* 界面读到的时间                                                              */
/* ========================================================================== */

test('remainingMs 不返回负数,过期就是 0', () => {
  const meta = { expiresAt: 1000 } as RawImageMeta
  assert.equal(remainingMs(meta, 400), 600)
  assert.equal(remainingMs(meta, 1000), 0)
  assert.equal(remainingMs(meta, 9999), 0, '「还有 -3 分钟」在界面上没有意义')
  assert.equal(isExpired(meta, 999), false)
  assert.equal(isExpired(meta, 1000), true)
})

test('currentTime 借的是注入的那个时钟 —— 界面的倒计时不会和到期判断读到两个时间', () => {
  const { cache, clock } = setup({ startAt: 42 })
  assert.equal(cache.currentTime(), 42)
  clock.advance(58)
  assert.equal(cache.currentTime(), 100)
})

test('TTL 默认值就是那一个常量 —— 界面上的「N 小时」不另写一个数', async () => {
  const { cache } = setup({ ttlMs: undefined })
  assert.equal(cache.ttl, RAW_IMAGE_TTL_MS)
  const meta = await cache.store({ blob: bytes(), label: 'a.png' })
  assert.equal(meta.expiresAt - meta.storedAt, RAW_IMAGE_TTL_MS)
})

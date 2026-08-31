/**
 * `1.1.3.3` —— docs/总路线图 原文:「验证:改系统时间实测删除生效」。
 *
 * <h2>这个文件存在的理由:那条验证不该只有人能做</h2>
 *
 * 「改系统时间」是一条只能手工做、做完没有留痕、下次改代码不会自动重做的验证。
 * 而 `1.1.3` 在 docs/总路线图 上标着「<b>第一天不定就改不回来</b>」——
 * 强度与后果不匹配的红线,`R-75` 已经点过一次名了。
 * 所以这里把「改系统时间」换成<b>拨一个注入的时钟</b>,判据一个字不改。
 *
 * <h2>🔴 零新增依赖</h2>
 *
 * `web/package.json` 的 dependencies / devDependencies <b>一个字都没动</b>
 * (docs/基础数据 §B.10 与 `R-46`:这个仓库的依赖清洗是人工 grep,
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

  /** archive 被调用过几次。用来验「归档是一次性事件,不会被反复推后」。 */
  archives = 0

  archive(ids: readonly string[], at: number): Promise<void> {
    this.archives += 1
    for (const id of ids) {
      const row = this.rows.get(id)
      if (row === undefined) continue
      if (typeof row.archivedAt === 'number') continue
      this.rows.set(id, { ...row, archivedAt: at })
    }
    return Promise.resolve()
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

test('🔴 1.1.3.3 把时钟拨过期之后,图【归档而不是删除】—— 字节仍在本机', async () => {
  const { backend, cache, clock } = setup()

  const meta = await cache.store({ blob: bytes(2048), label: '资料分析-01.png' })
  assert.equal(backend.rows.size, 1, '刚存完应该在')
  assert.deepEqual((await cache.list()).map((m) => m.id), [meta.id])

  // ——「改系统时间」就是这一行。TTL 之后再多一毫秒。
  clock.advance(RAW_IMAGE_TTL_MS + 1)

  assert.deepEqual(await cache.list(), [], '过期之后不该再出现在【活跃】列表里')

  /* 🔴 2026-08-29 决策变更:这条断言被有意反转。
     改动之前这里写的是 `backend.rows.size === 0`,注释是
     「过期的原图必须从存储里消失,不是被隐藏」。
     现在的行为是【归档】:行还在、字节还在,只是挪出活跃段。 */
  assert.equal(backend.rows.size, 1, '归档不删行 —— 字节必须还在本机')
  const row = backend.rows.get(meta.id)
  assert.ok(row, '行应该还在')
  assert.equal(typeof row.archivedAt, 'number', 'archivedAt 应该被写上')
  assert.equal(row.blob.size, 2048, '🔴 归档保留的是【字节本身】,不是一条元信息')

  const archived = await cache.listArchived()
  assert.deepEqual(archived.map((m) => m.id), [meta.id], '应该出现在归档列表里')
})

test('🔴 归档之后仍然读得出来 —— 读不出来的归档等于删除', async () => {
  const { cache, clock } = setup()
  const meta = await cache.store({ blob: bytes(512), label: '归档也要能用.png' })

  clock.advance(RAW_IMAGE_TTL_MS + 1)
  await cache.sweep()

  const row = await cache.read(meta.id)
  assert.ok(row, '归档行必须仍可读')
  assert.equal(row.blob.size, 512)
})

test('归档是一次性的 —— 反复 sweep 不会把 archivedAt 一路往后推', async () => {
  const { backend, cache, clock } = setup({ ttlMs: HOUR })
  const meta = await cache.store({ blob: bytes(), label: 'a.png' })

  clock.advance(HOUR + 1)
  assert.deepEqual(await cache.sweep(), [meta.id], '第一次 sweep 应该报出它归档了哪一张')
  const first = backend.rows.get(meta.id)?.archivedAt

  clock.advance(10 * HOUR)
  assert.deepEqual(await cache.sweep(), [], '已归档的不该被再归档一次')
  assert.equal(backend.rows.get(meta.id)?.archivedAt, first, 'archivedAt 应该停在它当初到期的那一刻')
})

test('🔴 用户按的「立即删除」仍然是真删 —— 归档改的是【到期】,不是【手动删】', async () => {
  const { backend, cache, clock } = setup({ ttlMs: HOUR })
  const meta = await cache.store({ blob: bytes(), label: '我不想留.png' })

  clock.advance(HOUR + 1)
  await cache.sweep()
  assert.equal(backend.rows.size, 1, '先确认它是归档态而不是已经没了')

  await cache.forget(meta.id)
  assert.equal(backend.rows.size, 0, '用户按删就得真删 —— 归档不是「删不掉」')
})

test('1.1.3.2 到期是「>=」:差一毫秒还活跃,到点就归档', async () => {
  const { backend, cache, clock } = setup({ ttlMs: HOUR })
  await cache.store({ blob: bytes(), label: 'a.png' })

  clock.advance(HOUR - 1)
  assert.equal((await cache.list()).length, 1, '还差 1 毫秒,不该归档')

  clock.advance(1)
  /* ⚪ 归档同样是【被触发的】,不是自己发生的 —— 两条触发就是全部。
     不过归档之后这一点的后果比原先轻:漏扫只影响 archivedAt 晚写多久,
     不再影响字节的去留(那正是 R-102 因这次改动而消解的原因)。 */
  await cache.sweep()
  assert.equal(backend.rows.size, 1, '行还在')
  assert.equal((await cache.list()).length, 0, '但已不在活跃段')
})

test('触发一:启动时扫一遍 —— 上一次会话留下的那批会被归档', async () => {
  const { backend, clock } = setup({ ttlMs: HOUR })

  const before = new RawImageCache({
    backend,
    now: clock.now,
    ttlMs: HOUR,
    newId: () => 'old-1',
  })
  await before.store({ blob: bytes(), label: '昨晚.png' })

  clock.advance(2 * HOUR)
  const after = new RawImageCache({ backend, now: clock.now, ttlMs: HOUR })

  assert.deepEqual(await after.sweep(), ['old-1'], 'sweep 应该报出它归档了哪一张')
  assert.equal(backend.rows.size, 1, '归档不删行')
  assert.deepEqual((await after.listArchived()).map((m) => m.id), ['old-1'])
})

test('触发二:存新图时顺带扫 —— 旧的进归档,新的在活跃', async () => {
  const { backend, cache, clock } = setup({ ttlMs: HOUR })

  const old = await cache.store({ blob: bytes(), label: '旧.png' })
  clock.advance(2 * HOUR)
  const fresh = await cache.store({ blob: bytes(), label: '新.png' })

  assert.equal(backend.rows.size, 2, '两行都在 —— 旧的没被删,只是归档了')
  assert.equal(typeof backend.rows.get(old.id)?.archivedAt, 'number')
  assert.equal(backend.rows.get(fresh.id)?.archivedAt, null)
  assert.deepEqual((await cache.list()).map((m) => m.id), [fresh.id], '活跃段只剩新的')
})

/* ========================================================================== */
/* 存疑就归档                                                                  */
/* ========================================================================== */

test('🔴 没有合法过期戳的残行一律当成已过期 —— 存疑就归档,不是宁可留在活跃段', async () => {
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
      archivedAt: null,
      blob: bytes(),
    })

    /* 判据方向没变:算不出合法过期戳就当它已经过期。
       变的只有「过期之后怎么处置」—— 2026-08-29 起是归档,不是删除。
       残行因此不会以「永不过期的活跃原图」的形态留在库里,这一点仍然成立。 */
    assert.deepEqual(await cache.list(), [], `expiresAt=${String(bad)}:不该出现在活跃列表里`)
    assert.equal(
      typeof backend.rows.get('legacy')?.archivedAt,
      'number',
      `expiresAt=${String(bad)}:应该被当成过期并归档`,
    )
  }
})

test('🔴 read 读得到归档行 —— 这扇门是 2026-08-29 有意打开的', async () => {
  const { backend, cache, clock } = setup({ ttlMs: HOUR })
  const meta = await cache.store({ blob: bytes(), label: 'a.png' })

  assert.ok(await cache.read(meta.id), '没过期时读得到')

  clock.advance(HOUR)
  /* 改动之前这里断言的是 `read() === null` 且 `rows.size === 0`,
     注释写的是「不留『过期但还能读』这条后门」。
     归档的全部意义就是「留着还能用」,所以那扇门现在是开的。
     仍然关着的是另外两扇:不上云、用户按删就是真删。 */
  const row = await cache.read(meta.id)
  assert.ok(row, '归档行仍然读得出来')
  assert.equal(typeof row.archivedAt, 'number')
  assert.equal(backend.rows.size, 1)
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

test('🔴 R-106 条数上限:超了先【归档】最早到期的,不是删掉', async () => {
  const { backend, cache, clock } = setup({ ttlMs: 10 * HOUR, maxEntries: 3 })

  const ids: string[] = []
  for (let i = 0; i < 5; i++) {
    const m = await cache.store({ blob: bytes(), label: `${i}.png` })
    ids.push(m.id)
    clock.advance(1000) // 每张的 expiresAt 依次往后
  }

  /* 全程时钟只走了 5 秒而 TTL 是 10 小时 —— 【一张都没过期】。
     2026-08-30 之前这里断言 rows.size === 3,也就是两张【活图被真删】。
     那是 8-29 改归档时的副作用:上限淘汰走的是另一条路,没跟着改。
     现在超出上限的进归档,行还在、字节还在。 */
  assert.equal(backend.rows.size, 5, '五行都还在 —— 上限不删行,只把超出的挪进归档')

  const live = await cache.list()
  assert.deepEqual(live.map((m) => m.id).sort(), ids.slice(2).sort(), '活跃段只剩最后三张')

  const archived = await cache.listArchived()
  assert.deepEqual(archived.map((m) => m.id).sort(), ids.slice(0, 2).sort(), '最早到期的两张进了归档')
  for (const m of archived) assert.equal(typeof m.archivedAt, 'number')
})

test('🔴 R-106 整层不存在自动删除路径 —— deleteMany 只由用户手按触发', async () => {
  const { backend, cache, clock } = setup({ ttlMs: HOUR, maxEntries: 2 })

  // 同时制造两种「本来会被自动删」的情形:过期 + 超上限
  const a = await cache.store({ blob: bytes(), label: 'a.png' })
  clock.advance(2 * HOUR)                      // a 过期
  const b = await cache.store({ blob: bytes(), label: 'b.png' })
  const c = await cache.store({ blob: bytes(), label: 'c.png' })
  const d = await cache.store({ blob: bytes(), label: 'd.png' })  // 触发上限淘汰

  assert.equal(backend.rows.size, 4, '四行一个不少 —— 没有任何自动路径删过东西')
  assert.equal(typeof backend.rows.get(a.id)?.archivedAt, 'number', '过期的 a 归档了')
  assert.ok(await cache.read(a.id), '归档的仍读得出来')

  // 只有用户按删才真删
  await cache.forget(b.id)
  assert.equal(backend.rows.size, 3, '用户按删 = 真删')
  assert.ok(c.id && d.id)
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

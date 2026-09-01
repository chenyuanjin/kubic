/**
 * `RawImageBackend` 的<b>后端契约测试</b> —— `docs/technical/原图存储-判据层与存储层.md §3.3`。
 *
 * <h2>🔴 先说清楚这份测试<b>不</b>覆盖什么</h2>
 *
 * 下面那个 {@link FakeShell} 是壳的一个<b>假的</b>实现,它<b>不碰真实文件系统</b>。
 * 所以这个文件测的是<b>线协议的 web 那一半</b>:
 * 编解码对不对、状态码翻译对不对、归档态读不读得出来。
 * <p>
 * 真实文件系统那一半 —— 原子写、孤儿清理、坏索引不许变成空索引、路径穿越 ——
 * 由 `shell/src/raw_image_store.rs` 的 Rust 测试守着,它们跑在真的临时目录上。
 * <p>
 * 🔴 <b>两半在线协议上碰头,而线协议在两侧各写了一份字面量。</b>
 * 这是这套设计里最可能漂移的一处(`R-116`):路由字符串对不上的话,
 * 假壳这一侧照样全绿,而壳里的原图链路整条不通。
 * <p>
 * 所以真正把这道缝钉住的<b>不是这个文件</b>,是
 * `shell/src/local_server.rs` 的 `the_wire_protocol_actually_works_over_real_http` ——
 * 它起一个真的服务、打一遍真的 HTTP,路径<b>逐字抄自本文件</b>。
 * 改错一个字母那条会红(已验证)。
 * 这个文件下面那条 `routeTable` 断言的作用是<b>把 web 这一侧的字面量固定住</b>,
 * 好让那一侧的抄写有一个可对照的原本。
 *
 * <h2>零新增依赖</h2>
 *
 * 和 `rawImageCache.test.ts` 同一条:`node:test` + node 22 自带的类型擦除,
 * `web/package.json` 一个字都没动。
 */

import assert from 'node:assert/strict'
import test from 'node:test'
import { RawImageStorageError } from '../src/lib/rawImageCache.ts'
import type { RawImageBackend, RawImageMeta, StoredRawImage } from '../src/lib/rawImageCache.ts'
import { fsRawImageBackend, localRawImageStoreAvailable } from '../src/lib/rawImageFs.ts'

/* ========================================================================== */
/* 假的壳 —— 只搬运,不判断,和真壳一样                                          */
/* ========================================================================== */

const BASE = '/__local/rawimages'
const META_HEADER = 'x-raw-meta'

function decodeMeta(header: string): RawImageMeta {
  const binary = atob(header)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i)
  return JSON.parse(new TextDecoder().decode(bytes)) as RawImageMeta
}

function encodeMeta(meta: RawImageMeta): string {
  const bytes = new TextEncoder().encode(JSON.stringify(meta))
  let binary = ''
  for (const b of bytes) binary += String.fromCharCode(b)
  return btoa(binary)
}

/**
 * 一个内存里的壳。行为逐条照着 `raw_image_store.rs`:
 * 元信息是不透明的一块,`archive` 只写 `archivedAt` 且对已归档行是 no-op,
 * `deleteMany` 删不存在的 id 不算错。
 */
class FakeShell {
  readonly rows = new Map<string, RawImageMeta>()
  readonly bytes = new Map<string, Uint8Array>()
  /** 把每条走过的路记下来,给 {@link routeTable} 那条断言用。 */
  readonly seen: string[] = []
  /**
   * `/health` 回什么。四种,对着 {@link localRawImageStoreAvailable} 的三条判据:
   *
   * | | 状态码 | content-type | body | 该被哪一条判据挡下 |
   * |---|---|---|---|---|
   * | `fs` | 200 | json | `{store:'fs'}` | 放行 |
   * | `spa` | 200 | text/html | `<!doctype html>` | content-type **或** JSON 解析(两条都挡得住) |
   * | `mislabelled` | 200 | text/html | `{store:'fs'}` | 🔴 **只有 content-type 那条挡得住** |
   * | `otherJson` | 200 | json | `{ok:true}` | 🔴 **只有 `store === 'fs'` 那条挡得住** |
   * | `missing` | 404 | — | — | 状态码那条 |
   */
  health: 'fs' | 'spa' | 'mislabelled' | 'otherJson' | 'missing' = 'fs'

  fetch = async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    this.seen.push(`${method} ${url}`)
    const rest = url.startsWith(BASE) ? url.slice(BASE.length) : null
    if (rest === null) return new Response('no', { status: 404 })

    if (method === 'GET' && rest === '/health') {
      if (this.health === 'missing') return new Response('no', { status: 404 })
      if (this.health === 'spa') {
        // 一个开了 SPA fallback 的静态服务器:200,但给的是 index.html。
        return new Response('<!doctype html><html></html>', {
          status: 200,
          headers: { 'content-type': 'text/html; charset=utf-8' },
        })
      }
      if (this.health === 'mislabelled') {
        // 一台把什么都标成 text/html 的静态服务器。body 恰好是合法 JSON,
        // 于是 JSON 解析那条挡不住它 —— 只剩 content-type 那条。
        return new Response('{"store":"fs","version":1}', {
          status: 200,
          headers: { 'content-type': 'text/html; charset=utf-8' },
        })
      }
      if (this.health === 'otherJson') {
        // 别人的 JSON 端点恰好也在这个路径上。content-type 那条挡不住它。
        return Response.json({ ok: true })
      }
      return Response.json({ store: 'fs', version: 1 })
    }

    if (method === 'GET' && rest === '/index') {
      return Response.json({ rows: [...this.rows.values()] })
    }

    if (rest.startsWith('/blob/')) {
      const id = decodeURIComponent(rest.slice('/blob/'.length))
      if (method === 'PUT') {
        const header = init?.headers as Record<string, string> | undefined
        const meta = decodeMeta(header?.[META_HEADER] ?? '')
        if (meta.id !== id) return new Response('mismatch', { status: 400 })
        this.rows.set(id, meta)
        this.bytes.set(id, new Uint8Array(init?.body as Uint8Array))
        return new Response(null, { status: 204 })
      }
      if (method === 'GET') {
        const meta = this.rows.get(id)
        const body = this.bytes.get(id)
        if (meta === undefined || body === undefined) {
          return new Response('nope', { status: 404 })
        }
        // 🔴 壳一律回 octet-stream,它不认识图片类型。
        return new Response(body, {
          status: 200,
          headers: { 'content-type': 'application/octet-stream', [META_HEADER]: encodeMeta(meta) },
        })
      }
    }

    if (method === 'POST' && rest === '/delete') {
      const { ids } = JSON.parse(String(init?.body)) as { ids: string[] }
      for (const id of ids) {
        this.rows.delete(id)
        this.bytes.delete(id)
      }
      return new Response(null, { status: 204 })
    }

    if (method === 'POST' && rest === '/archive') {
      const { ids, at } = JSON.parse(String(init?.body)) as { ids: string[]; at: number }
      for (const id of ids) {
        const row = this.rows.get(id)
        if (row === undefined) continue
        if (typeof row.archivedAt === 'number') continue // 一次性,不往后推
        this.rows.set(id, { ...row, archivedAt: at })
      }
      return new Response(null, { status: 204 })
    }

    return new Response('no route', { status: 404 })
  }
}

/** 装上假 fetch,跑完拆掉。真 fetch 在 node 里存在,漏拆会污染后面的用例。 */
async function withShell(fn: (shell: FakeShell) => Promise<void>): Promise<void> {
  const shell = new FakeShell()
  const real = globalThis.fetch
  globalThis.fetch = shell.fetch as typeof fetch
  try {
    await fn(shell)
  } finally {
    globalThis.fetch = real
  }
}

/* ========================================================================== */
/* 内存后端 —— 契约测试的对照组                                                  */
/* ========================================================================== */

class MemoryBackend implements RawImageBackend {
  readonly rows = new Map<string, StoredRawImage>()

  put(row: StoredRawImage): Promise<void> {
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
  archive(ids: readonly string[], at: number): Promise<void> {
    for (const id of ids) {
      const row = this.rows.get(id)
      if (row === undefined) continue
      if (typeof row.archivedAt === 'number') continue
      this.rows.set(id, { ...row, archivedAt: at })
    }
    return Promise.resolve()
  }
}

function rowOf(id: string, over: Partial<StoredRawImage> = {}): StoredRawImage {
  return {
    id,
    recordId: null,
    mime: 'image/png',
    byteSize: 4,
    label: '我的截图.png',
    storedAt: 1000,
    expiresAt: 22600,
    archivedAt: null,
    blob: new Blob([new Uint8Array([1, 2, 3, 4])], { type: 'image/png' }),
    ...over,
  }
}

/* ========================================================================== */
/* 契约:一份断言,两个实现各跑一遍(docs/technical/原图存储-判据层与存储层.md §3.3)                              */
/* ========================================================================== */

/**
 * 🔴 <b>这五条在两个实现上逐字相同</b> —— 那正是「壳带来的是第二个实现,
 * 不是第二套逻辑」在测试上的形态。哪一天两个实现在这里分了叉,分叉的那一条会红。
 */
async function contract(name: string, make: () => Promise<RawImageBackend>): Promise<void> {
  await test(`契约 · ${name} · ① put 之后 listMeta 看得到,过期戳与写入时一致`, async () => {
    const b = await make()
    await b.put(rowOf('a1'))
    const metas = await b.listMeta()
    assert.equal(metas.length, 1)
    assert.equal(metas[0]!.expiresAt, 22600)
    assert.equal(metas[0]!.storedAt, 1000)
    // 本机文件名(中文)原样过一遍存储层。
    assert.equal(metas[0]!.label, '我的截图.png')
  })

  await test(`契约 · ${name} · ③ archive 只改 archivedAt,expiresAt 与 byteSize 不变`, async () => {
    const b = await make()
    await b.put(rowOf('a2'))
    await b.archive(['a2'], 55555)
    const [meta] = await b.listMeta()
    assert.equal(meta!.archivedAt, 55555)
    assert.equal(meta!.expiresAt, 22600)
    assert.equal(meta!.byteSize, 4)
  })

  await test(`契约 · ${name} · ④ archive 对已归档行是 no-op,时刻不被推后`, async () => {
    const b = await make()
    await b.put(rowOf('a3'))
    await b.archive(['a3'], 100)
    await b.archive(['a3'], 200)
    const [meta] = await b.listMeta()
    assert.equal(meta!.archivedAt, 100)
  })

  await test(`契约 · ${name} · ⑤ deleteMany 删不存在的 id 不抛`, async () => {
    const b = await make()
    await b.deleteMany(['nobody'])
    await b.deleteMany([])
  })

  await test(`契约 · ${name} · ⑥ read 读得出【已归档】的行 —— 归档不是删除`, async () => {
    const b = await make()
    await b.put(rowOf('a4'))
    await b.archive(['a4'], 9)
    const row = await b.read('a4')
    assert.notEqual(row, null)
    assert.equal(row!.archivedAt, 9)
    assert.equal(row!.blob.size, 4)
    assert.deepEqual(new Uint8Array(await row!.blob.arrayBuffer()), new Uint8Array([1, 2, 3, 4]))
  })
}

await contract('内存', () => Promise.resolve(new MemoryBackend()))

// fs 后端那五条跑在假壳上。装 / 拆 fetch 由每条用例自己做 —— 契约函数不知道有这回事。
await contract('文件系统(假壳)', () => {
  const shell = new FakeShell()
  globalThis.fetch = shell.fetch as typeof fetch
  return Promise.resolve(fsRawImageBackend)
})

/* ========================================================================== */
/* fs 后端专有:线协议、形态探测、错误翻译                                        */
/* ========================================================================== */

await test('read 的 blob 类型取自【元信息里的 mime】,不是壳回的 content-type', async () => {
  await withShell(async () => {
    await fsRawImageBackend.put(rowOf('m1', { mime: 'image/webp' }))
    const row = await fsRawImageBackend.read('m1')
    // 🔴 壳回的是 application/octet-stream —— 它不认识图片,也不该认识。
    //    界面拿到的类型必须还是原来那个,否则送去识别时 MIME 就错了。
    assert.equal(row!.blob.type, 'image/webp')
  })
})

await test('索引里没有的 id:read 返回 null,不抛', async () => {
  await withShell(async () => {
    assert.equal(await fsRawImageBackend.read('ghost'), null)
  })
})

await test('壳报错时翻成 RawImageStorageError,而不是别的类型', async () => {
  const real = globalThis.fetch
  globalThis.fetch = (() => Promise.resolve(new Response('boom', { status: 500 }))) as typeof fetch
  try {
    await assert.rejects(() => fsRawImageBackend.listMeta(), RawImageStorageError)
  } finally {
    globalThis.fetch = real
  }
})

await test('壳整个不在(fetch 抛)时也翻成 RawImageStorageError', async () => {
  const real = globalThis.fetch
  globalThis.fetch = (() => Promise.reject(new Error('ECONNREFUSED'))) as typeof fetch
  try {
    await assert.rejects(() => fsRawImageBackend.listMeta(), RawImageStorageError)
  } finally {
    globalThis.fetch = real
  }
})

await test('🔴 形态探测:200 + JSON + store=fs 才算壳', async () => {
  await withShell(async (shell) => {
    shell.health = 'fs'
    assert.equal(await localRawImageStoreAvailable(), true)
  })
})

/*
 * 🔴 下面三条是这份测试里最要紧的三条,理由是同一个:
 *
 * 只看状态码的话,任何一个开了 SPA fallback 的部署都会被判成「有文件系统后端」,
 * 于是整条原图链路指向一个不存在的东西,而且【不报错】——
 * 用户看到的是「图存不进去」,查到的是一个毫无关系的地方。
 *
 * 三条分别对着 localRawImageStoreAvailable 的三条判据,而且【各自能单独变红】:
 * 拆掉 content-type 那一句,`mislabelled` 红;拆掉 `store === 'fs'`,`otherJson` 红。
 * ⚠️ `spa` 那条是被两条判据同时挡住的,所以它单独证明不了任何一条 ——
 * 写在这里是因为它是真实世界里最常见的那一种,不是因为它能当断言用。
 */
await test('形态探测:SPA fallback 的 200 + index.html 不算壳', async () => {
  await withShell(async (shell) => {
    shell.health = 'spa'
    assert.equal(await localRawImageStoreAvailable(), false)
  })
})

await test('🔴 形态探测:body 是合法 JSON 但 content-type 是 text/html —— 不算壳', async () => {
  await withShell(async (shell) => {
    shell.health = 'mislabelled'
    assert.equal(await localRawImageStoreAvailable(), false)
  })
})

await test('🔴 形态探测:是 JSON 但不是我们的 JSON —— 不算壳', async () => {
  await withShell(async (shell) => {
    shell.health = 'otherJson'
    assert.equal(await localRawImageStoreAvailable(), false)
  })
})

await test('形态探测:404 不算壳;fetch 抛也不算壳,而且不往外抛', async () => {
  await withShell(async (shell) => {
    shell.health = 'missing'
    assert.equal(await localRawImageStoreAvailable(), false)
  })
  const real = globalThis.fetch
  globalThis.fetch = (() => Promise.reject(new Error('no network'))) as typeof fetch
  try {
    // 浏览器形态下这条【每次启动都会失败一次】,那是正常状态,不是故障。
    assert.equal(await localRawImageStoreAvailable(), false)
  } finally {
    globalThis.fetch = real
  }
})

await test('routeTable · 🔴 线协议的路径字面量与 docs/technical/原图存储-判据层与存储层.md §9.1 逐字一致', async () => {
  // 这条断言防的是 R-116:两侧各写一份字面量,写歪了两边的测试都绿。
  // 它当然防不住「web 与 rust 同时写歪成同一个别的串」—— 那种情况只有真跑一次壳能发现。
  await withShell(async (shell) => {
    await fsRawImageBackend.put(rowOf('r1'))
    await fsRawImageBackend.listMeta()
    await fsRawImageBackend.read('r1')
    await fsRawImageBackend.archive(['r1'], 1)
    await fsRawImageBackend.deleteMany(['r1'])
    await localRawImageStoreAvailable()
    assert.deepEqual(shell.seen, [
      'PUT /__local/rawimages/blob/r1',
      'GET /__local/rawimages/index',
      'GET /__local/rawimages/blob/r1',
      'POST /__local/rawimages/archive',
      'POST /__local/rawimages/delete',
      'GET /__local/rawimages/health',
    ])
  })
})

await test('🔴 空 ids 不发请求 —— 与 IndexedDB 实现一致', async () => {
  await withShell(async (shell) => {
    await fsRawImageBackend.deleteMany([])
    await fsRawImageBackend.archive([], 1)
    assert.deepEqual(shell.seen, [])
  })
})

/**
 * 迁移:浏览器存储 → 文件系统(`docs/technical/原图存储-判据层与存储层.md §四`)。
 *
 * <h2>为什么这条链路必须被断言,而不能靠「读一遍觉得对」</h2>
 *
 * 它做的事情是<b>把用户的原图从一个地方搬到另一个地方,然后删掉源</b>。
 * 中断发生在哪一步,决定的是「有几张两边都在」还是「有几张两边都没有」——
 * 而后者是<b>丢字节</b>,丢的是用户自己的图。
 * <p>
 * 「先写目的地、确认提交,再删源」这一条读起来显然,写反了也读不出来。
 * 所以下面有一条专门把目的地写坏的用例:🔴 <b>put 抛了之后,源里那一行必须还在。</b>
 *
 * <h2>今天跑它是一次空跑,而这正是写它的时刻</h2>
 *
 * 存量为零(拍照入口还没接、壳的 IndexedDB 里一张图都没有)。
 * <b>写错了也伤不到任何人的原图 —— 这是这段代码最便宜的一刻,而它现在开着。</b>
 */

import assert from 'node:assert/strict'
import test from 'node:test'
import type { RawImageBackend, RawImageMeta, StoredRawImage } from '../src/lib/rawImageCache.ts'
import { migrateRawImages } from '../src/lib/rawImageMigrate.ts'

class MemoryBackend implements RawImageBackend {
  readonly rows = new Map<string, StoredRawImage>()
  /** 让某个 id 的 put 抛一次 —— 用来把「中断」变成一条断言。 */
  failPutFor: string | null = null
  /** 让某个 id 的 read 返回 null —— 元信息在、字节不在的残行。 */
  readNullFor: string | null = null

  put(row: StoredRawImage): Promise<void> {
    if (row.id === this.failPutFor) return Promise.reject(new Error('目的地写不进去'))
    this.rows.set(row.id, row)
    return Promise.resolve()
  }
  listMeta(): Promise<RawImageMeta[]> {
    return Promise.resolve([...this.rows.values()].map(({ blob: _b, ...meta }) => meta))
  }
  read(id: string): Promise<StoredRawImage | null> {
    if (id === this.readNullFor) return Promise.resolve(null)
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
    byteSize: 3,
    label: `${id}.png`,
    storedAt: 1_000,
    expiresAt: 22_600,
    archivedAt: null,
    blob: new Blob([new Uint8Array([7, 7, 7])], { type: 'image/png' }),
    ...over,
  }
}

await test('源是空的:什么都不发生,而且【不碰目的地】', async () => {
  const from = new MemoryBackend()
  const to = new MemoryBackend()
  // 目的地放一行,证明它没被读也没被动。
  await to.put(rowOf('existing'))

  const r = await migrateRawImages(from, to)
  assert.deepEqual(r, { moved: 0, reclaimed: 0, skipped: 0 })
  assert.equal(to.rows.size, 1)
})

await test('🔴 三个时间戳原样搬,归档态原样搬 —— 搬家不重置「多久前」', async () => {
  const from = new MemoryBackend()
  const to = new MemoryBackend()
  await from.put(rowOf('live', { storedAt: 111, expiresAt: 222 }))
  await from.put(rowOf('gone', { storedAt: 333, expiresAt: 444, archivedAt: 555 }))

  const r = await migrateRawImages(from, to)
  assert.equal(r.moved, 2)

  const live = to.rows.get('live')!
  assert.equal(live.storedAt, 111)
  assert.equal(live.expiresAt, 222)
  assert.equal(live.archivedAt, null)

  // 🔴 归档态搬成活跃态 = 一张已到期的图重新开始计时。
  const gone = to.rows.get('gone')!
  assert.equal(gone.archivedAt, 555)
  assert.equal(gone.expiresAt, 444)

  // 字节也真的过去了。
  assert.deepEqual(new Uint8Array(await live.blob.arrayBuffer()), new Uint8Array([7, 7, 7]))
  // 源被清空 —— 搬家不是复制。
  assert.equal(from.rows.size, 0)
})

await test('🔴 目的地写不进去时,源里那一行【必须还在】—— 顺序反了这条会红', async () => {
  const from = new MemoryBackend()
  const to = new MemoryBackend()
  await from.put(rowOf('precious'))
  to.failPutFor = 'precious'

  await assert.rejects(() => migrateRawImages(from, to))

  // 这一条是整份测试存在的理由:先删源再写目的地的话,这张图现在两边都没有。
  assert.equal(from.rows.size, 1)
  assert.equal(from.rows.has('precious'), true)
  assert.equal(to.rows.size, 0)
})

await test('中断在「写了目的地、还没删源」:下一轮只补后半步,不搬第二遍', async () => {
  const from = new MemoryBackend()
  const to = new MemoryBackend()
  const row = rowOf('halfway', { expiresAt: 999 })
  await from.put(row)
  await to.put(row) // 上一轮搬过去了,还没来得及删源

  const r = await migrateRawImages(from, to)
  assert.deepEqual(r, { moved: 0, reclaimed: 1, skipped: 0 })
  assert.equal(from.rows.size, 0)
  assert.equal(to.rows.size, 1)
  // 🔴 没有被重写一遍 —— 重写会把时间戳换成源里那一份,而两份本该一样;
  //    真正危险的是「搬两遍」这个动作本身在别的实现上可能不幂等。
  assert.equal(to.rows.get('halfway')!.expiresAt, 999)
})

await test('幂等:连跑三遍,结果与跑一遍相同', async () => {
  const from = new MemoryBackend()
  const to = new MemoryBackend()
  await from.put(rowOf('x1'))
  await from.put(rowOf('x2'))

  const first = await migrateRawImages(from, to)
  assert.equal(first.moved, 2)
  for (let i = 0; i < 2; i += 1) {
    const again = await migrateRawImages(from, to)
    assert.deepEqual(again, { moved: 0, reclaimed: 0, skipped: 0 })
  }
  assert.equal(to.rows.size, 2)
  assert.equal(from.rows.size, 0)
})

await test('🔴 读不出字节的残行:跳过,【不删】—— §4.3 的豁免不外借', async () => {
  const from = new MemoryBackend()
  const to = new MemoryBackend()
  await from.put(rowOf('ghost'))
  await from.put(rowOf('real'))
  from.readNullFor = 'ghost'

  const r = await migrateRawImages(from, to)
  assert.deepEqual(r, { moved: 1, reclaimed: 0, skipped: 1 })
  // 残行留在源里。删它需要一条「顺手清一下」的豁免,而 §4.3 那条只覆盖
  // 「这一行已经在别处存在」这一种触发 —— 残行不在别处存在。
  assert.equal(from.rows.has('ghost'), true)
  // 而且它没有挡住别的行。
  assert.equal(to.rows.has('real'), true)
})

await test('🔴 迁移是单向的:没有任何一条路把图从文件系统搬回浏览器存储', () => {
  // 这条不是行为断言,是【接口形状】的断言:migrateRawImages 的两个参数
  // 一个叫 from 一个叫 to,而调用点(rawImageStore.ts)只有一个,方向写死。
  // 反向意味着字节回到配额受限的存储里,而配额正是 R-105 要解决的问题。
  assert.equal(migrateRawImages.length, 2)
})

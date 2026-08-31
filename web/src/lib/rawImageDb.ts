/**
 * 原图本地缓存的<b>存储层</b> —— {@link RawImageBackend} 的 IndexedDB 实现。
 *
 * <h2>这个文件里<b>没有任何判断</b>,这是刻意的</h2>
 *
 * 「到期了没有」「该删哪几张」「上限到了先删谁」全在 `rawImageCache.ts` ——
 * 那一层能在 node 里被拨着时钟跑(`1.1.3.3`)。这一层碰真存储、跑不进测试,
 * 所以它<b>不许有可以出错的逻辑</b>:四个方法各自只是一次 IndexedDB 调用。
 * 一旦有人在这里写下 `if (row.expiresAt < Date.now())`,那条判断就掉进了没有测试的那半边。
 *
 * <h2>🔴 为什么用 IndexedDB,而不是 localStorage</h2>
 *
 * <table border="1">
 *   <tr><th></th><th>localStorage</th><th>IndexedDB</th></tr>
 *   <tr><td>能装二进制吗</td><td>不能,只存字符串 —— 图得先转成 base64</td><td>能,直接存 Blob</td></tr>
 *   <tr><td>转 base64 的代价</td><td>体积涨 4/3,而且<b>在内存里多出一份原图的文本副本</b>
 *       ——「不把 base64 留下来」这条纪律会立刻变得很难守</td><td>不需要</td></tr>
 *   <tr><td>容量</td><td>5–10 MB,一次连拍就顶满</td><td>按磁盘配额</td></tr>
 *   <tr><td>写入是不是原子的</td><td>一次一个键。「图一个键、过期戳另一个键」<b>天然是两次写</b></td>
 *       <td>一次事务写一整行,{@link put} 靠的就是这一条</td></tr>
 * </table>
 *
 * 最后一行才是这个选择的真正理由。localStorage 上想写下一张带过期戳的图,
 * 要么把 base64 和戳拼成一个 JSON 串(于是原图变成一段超长文本),
 * 要么分两个键写(于是「存图即写过期戳」变成一句纪律)。<b>两条都在红线的错误一侧。</b>
 *
 * <h2>🔴 blob 与 expiresAt 是同一行的两个字段</h2>
 *
 * 所以「写了图但没写戳」不是一个需要防的场景,是一个<b>写不出来的场景</b>:
 * `store.put(row)` 里的 `row` 就是那一个对象。这里再加一道:
 * 用 `transaction.oncomplete` 而不是 `request.onsuccess` 来 resolve ——
 * <b>只有事务提交了才算存下</b>,请求成功但事务随后 abort 的那一瞬间不该被当成成功。
 */

import { RawImageCache } from './rawImageCache'
import type { RawImageBackend, RawImageMeta, StoredRawImage } from './rawImageCache'

/** 库名带产品前缀:同源下可能还有别人的库,别撞。 */
const DB_NAME = 'kaodian.rawimages'
const DB_VERSION = 1
const STORE = 'raw'

/**
 * 本地缓存整个用不了时抛它(隐私模式、被策略禁用、配额拒绝)。
 *
 * <p>🔴 <b>这不是一次「记录失败」。</b> docs/后端详设 §1.5「降级方向是『少功能』,不是『少记录』」:
 * 缓存不上照样能把这张图送去识别一次(服务端本来就不落盘),照样能记下这一笔。
 * 界面要说的是「这张图没被本地缓存」,不是「记不下来」。
 */
export class RawImageStorageError extends Error {
  constructor(reason: string, cause?: unknown) {
    super(reason, { cause })
    this.name = 'RawImageStorageError'
  }
}

/** 把一个 IDBRequest 包成 Promise。只在读路径上用 —— 写路径等的是事务提交,不是请求成功。 */
function ask<T>(req: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(new RawImageStorageError('读本地原图缓存失败。', req.error))
  })
}

let opening: Promise<IDBDatabase> | null = null

function openDb(): Promise<IDBDatabase> {
  if (opening !== null) return opening
  opening = new Promise<IDBDatabase>((resolve, reject) => {
    if (typeof indexedDB === 'undefined') {
      reject(new RawImageStorageError('这个浏览器环境没有 IndexedDB,原图不做本地缓存。'))
      return
    }
    let req: IDBOpenDBRequest
    try {
      req = indexedDB.open(DB_NAME, DB_VERSION)
    } catch (err) {
      reject(new RawImageStorageError('打不开本地原图缓存(可能是隐私模式)。', err))
      return
    }
    req.onupgradeneeded = () => {
      const db = req.result
      if (!db.objectStoreNames.contains(STORE)) {
        // keyPath 就是行里的 id。不用自增主键:自增键由存储层产生,
        // 而 id 是判据层生成并当场返回给界面的,两处各有一个 id 迟早对不上。
        db.createObjectStore(STORE, { keyPath: 'id' })
      }
    }
    req.onsuccess = () => resolve(req.result)
    req.onerror = () => reject(new RawImageStorageError('打不开本地原图缓存(可能是隐私模式)。', req.error))
    // 别的标签页在升级版本时会卡住这里。诚实地失败,不无限等。
    req.onblocked = () => reject(new RawImageStorageError('本地原图缓存被另一个标签页占用,稍后再试。'))
  })
  // 失败不缓存:隐私模式下用户可能中途换设置,下次再试一次比永远记着失败好。
  opening.catch(() => {
    opening = null
  })
  return opening
}

/** 元信息 = 行去掉 blob。写在一处,免得两个地方各自记得剥。 */
function stripBytes(row: StoredRawImage): RawImageMeta {
  const { blob: _blob, ...meta } = row
  return meta
}

/**
 * IndexedDB 版本的存储口。
 *
 * <p>⚪ 一个和直觉相反、但对这条链路很要紧的事实:{@link listMeta} 里的 `getAll()`
 * 把整行取出来了,<b>却没有把原图字节读进内存</b> —— IndexedDB 返回的 `Blob`
 * 是一个指向底层存储的惰性句柄,只有 `arrayBuffer()` / `text()` 那一刻才真正读。
 * 所以「一次扫描把十几张图全加载一遍」不会发生,而 {@link stripBytes} 之后
 * 那些句柄立刻就没人引用了。
 */
export const indexedDbRawImageBackend: RawImageBackend = {
  async put(row: StoredRawImage): Promise<void> {
    const db = await openDb()
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE, 'readwrite')
      // 🔴 整行一次写下去 —— 字节与 expiresAt 在同一个对象里,同一次提交。
      tx.objectStore(STORE).put(row)
      // 🔴 等提交,不等请求成功:请求成功之后事务仍可能 abort(配额、断电、别处报错),
      //    那种情况下这张图并没有被存下来,更谈不上带着过期戳被存下来。
      tx.oncomplete = () => resolve()
      tx.onabort = () => reject(new RawImageStorageError('本地原图缓存写入被中止(可能是磁盘配额不够)。', tx.error))
      tx.onerror = () => reject(new RawImageStorageError('本地原图缓存写入失败。', tx.error))
    })
  },

  async listMeta(): Promise<RawImageMeta[]> {
    const db = await openDb()
    const rows = await ask<StoredRawImage[]>(db.transaction(STORE, 'readonly').objectStore(STORE).getAll())
    return rows.map(stripBytes)
  },

  async read(id: string): Promise<StoredRawImage | null> {
    const db = await openDb()
    const row = await ask<StoredRawImage | undefined>(
      db.transaction(STORE, 'readonly').objectStore(STORE).get(id),
    )
    return row ?? null
  },

  async deleteMany(ids: readonly string[]): Promise<void> {
    if (ids.length === 0) return
    const db = await openDb()
    await new Promise<void>((resolve, reject) => {
      // 一个事务删完整批。分成 N 个事务的话,删到一半失败会留下一批「已经过期但还在」的行,
      // 而那正是这条红线最不能出现的状态。
      const tx = db.transaction(STORE, 'readwrite')
      const store = tx.objectStore(STORE)
      for (const id of ids) store.delete(id)
      tx.oncomplete = () => resolve()
      tx.onabort = () => reject(new RawImageStorageError('删本地原图缓存被中止。', tx.error))
      tx.onerror = () => reject(new RawImageStorageError('删本地原图缓存失败。', tx.error))
    })
  },

  /**
   * 🔴 就地置 `archivedAt`,<b>不重写字节</b>。
   *
   * <p>一个事务读改写完整批,理由和 {@link deleteMany} 同源:分成 N 个事务的话,
   * 中途失败会留下一批「已经过期、又没归档」的行 —— 下次 sweep 会把它们再扫一遍,
   * `archivedAt` 于是被推到一个更晚的时刻,归档时间就不再是「它当初到期的那一刻」。
   *
   * <p>读出来的整行原样 `put` 回去,只换 `archivedAt` 一个字段。
   * 这里必须走「读整行再写整行」是 IndexedDB 的限制(没有部分更新),
   * 但它<b>不违反本层「少碰几次字节」的纪律</b> —— 归档是<b>一次性</b>事件,
   * 每张图一生只经过这里一次,不像 listMeta 那样每次扫描都来。
   *
   * <p>行不存在就跳过,不报错:和 {@link deleteMany} 一致。
   */
  async archive(ids: readonly string[], at: number): Promise<void> {
    if (ids.length === 0) return
    const db = await openDb()
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE, 'readwrite')
      const store = tx.objectStore(STORE)
      for (const id of ids) {
        const req = store.get(id)
        req.onsuccess = () => {
          const row = req.result as StoredRawImage | undefined
          if (row === undefined) return
          if (typeof row.archivedAt === 'number') return // 已归档,不重复推后时刻
          store.put({ ...row, archivedAt: at })
        }
      }
      tx.oncomplete = () => resolve()
      tx.onabort = () => reject(new RawImageStorageError('归档本地原图被中止。', tx.error))
      tx.onerror = () => reject(new RawImageStorageError('归档本地原图失败。', tx.error))
    })
  },
}

/* ========================================================================== */
/* 接线 —— 真后端 + 真时钟                                                      */
/* ========================================================================== */

/**
 * 全应用唯一的原图缓存实例。
 *
 * <h2>🔴 这是整条链路里 `Date.now()` 出现的<b>唯一一处</b></h2>
 *
 * 判据层的时钟是注入的({@link RawImageCache} 的 `now`),就是为了让
 * `1.1.3.3`「改系统时间实测删除生效」能变成一条机器断言。
 * 那条纪律的代价是:真实时钟必须在某一处被绑上去 —— 就是这一行。
 * <b>再多一处 `Date.now()`,注入这件事就白做了</b>:测试拨动的是假时钟,
 * 而漏掉的那一处仍然读的是系统时间,于是测试绿着,行为却不受控。
 * <p>
 * 界面上算倒计时同样不许自己调 `Date.now()` —— 它读的是这里传下去的那一个。
 */
export const rawImages = new RawImageCache({
  backend: indexedDbRawImageBackend,
  now: () => Date.now(),
})

/**
 * 🔴 到期删除的<b>第一条触发</b>:应用启动时扫一遍。由 `main.tsx` 调,一次。
 *
 * <h2>为什么不能只靠「存新图时顺带清」那一条</h2>
 *
 * 那条只在用户<b>再导一张图</b>时才会跑。而最需要被清掉的恰恰是
 * 「用户昨晚导了一批,今天没再导」的那批 —— 只有存图触发的话,它们要等到
 * 下一次导入才走,而下一次导入可能永远不来。
 *
 * <h2>失败为什么只是 swallow</h2>
 *
 * 隐私模式 / IndexedDB 被禁用时这里必然抛,而那种情况下<b>压根没有图可删</b>。
 * 让它把首屏炸掉,是用一个不存在的风险换一个真实的故障。
 * 🔴 catch 里<b>一行日志都不打</b> —— 这条链路上的异常对象可能带着行数据,
 * 而 docs/技术架构 §8.1 禁令 3 的客户端对应物是「不把原图相关的东西打进 console 的任何级别」。
 */
export function sweepRawImagesOnStartup(): void {
  void rawImages.sweep().catch(() => {
    /* 本地缓存用不了 = 没有原图留在本机 = 这条红线自动成立,不需要惊动任何人 */
  })
}

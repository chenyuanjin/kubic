/**
 * 原图本地缓存的<b>存储层 · 文件系统实现</b> —— {@link RawImageBackend} 的<b>第二个实现</b>。
 *
 * <h2>🔴 这个文件是「第二个实现」,不是「第二套逻辑」</h2>
 *
 * `docs/原图存储 §1.1` 冻结的那句:**壳带来的是 `RawImageBackend` 的第二个实现,不是第二套逻辑。**
 * 于是这里和 `rawImageDb.ts` 一样,<b>不含任何判断</b> ——
 * 「到期了没有」「该归档哪几张」「上限到了先归档谁」全在 `rawImageCache.ts`,
 * 那一层只编译一份,两种形态逐字节共用。
 * <p>
 * 反面判据同样照抄:<b>如果这里需要写第二套到期规则,说明这一层切错了</b> ——
 * 回去改判据层,不要在这里补。
 *
 * <h2>🔴 为什么走 HTTP,而不是 Tauri 的 IPC</h2>
 *
 * <table border="1">
 *   <tr><th></th><th>Tauri IPC(`@tauri-apps/api`)</th><th>回环 HTTP(本文件)</th></tr>
 *   <tr><td>要不要动 `web/package.json`</td><td><b>要</b> —— 加一个运行时依赖</td>
 *       <td>不要。只用 `fetch`,零新增依赖</td></tr>
 *   <tr><td>字节怎么过去</td><td>IPC 只传得了可序列化的值 —— 原图得先 <b>base64</b>
 *       (体积涨 4/3,而且在内存里多出一份原图的文本副本)</td>
 *       <td>请求体直接是二进制,<b>不存在 base64 的原图</b></td></tr>
 *   <tr><td>浏览器形态怎么办</td><td>`window.__TAURI__` 在浏览器里不存在,
 *       调用点要判形态</td><td>路径不通就是不通,判形态只有 {@link localRawImageStoreAvailable} 一处</td></tr>
 * </table>
 *
 * 第二行是决定性的:`rawImageDb.ts` 选 IndexedDB 而不选 localStorage 的头号理由就是
 * 「不要在内存里多出一份原图的 base64 文本副本」。换一个存储介质不该把那条理由丢掉。
 * <p>
 * 而走 HTTP 之后,这个文件与 `api/client.ts` 落在<b>同一条纪律</b>上:
 * 前端只写相对路径,这条路径落到哪里由页面 origin 决定,前端一个字不知道自己在哪儿跑。
 *
 * <h2>🔴 壳侧只认识一个字段名</h2>
 *
 * 线协议里元信息是<b>一整块不透明的 JSON</b>({@link META_HEADER}),
 * 壳只从里面读 `id`(它要拿来当文件名),外加一个它必须会写的 `archivedAt`。
 * 它<b>读不到 `expiresAt`</b> —— 所以壳在结构上写不出 `if (now > expiresAt)`,
 * 「该不该转归档」这件事没有第二个地方能回答。这条由 `shell/build.sh` 步骤 ① grep 拦。
 *
 * <h2>这个文件里没有 DOM 符号,所以它跑得进 node</h2>
 *
 * 只用 `fetch` / `Blob` / `TextEncoder` / `btoa` —— 浏览器与 node 都有。
 * 于是 `tests/rawImageBackend.test.ts` 能拿一个假 `fetch` 把线协议整条测掉,
 * 而不需要真的起一个壳。<b>真实文件系统那一半由 `shell/src/raw_image_store.rs`
 * 的 Rust 测试守着</b>,两半在线协议上碰头,见 `docs/原图存储 §9.2`。
 */

import { RawImageStorageError } from './rawImageCache.ts'
import type { RawImageBackend, RawImageMeta, StoredRawImage } from './rawImageCache.ts'

/* ========================================================================== */
/* 线协议 —— 逐字对着 docs/原图存储 §9.1 那张表,壳侧 raw_image_store.rs 是同一张表     */
/* ========================================================================== */

/**
 * 🔴 壳本地存储的路径前缀。
 *
 * <p>刻意<b>不是</b> `/api/*`:`/api/*` 那条路整条反代给上游,而这条路一个字节都不出这台机器。
 * 两条路在壳里由 `local_server.rs` 分开,在浏览器里则根本没有第二条 ——
 * 浏览器形态下这个前缀是一个 404,而 {@link localRawImageStoreAvailable} 要的就是这一点。
 * <p>双下划线开头是给读代码的人看的:<b>它不是产品 API</b>,不进 `docs/技术架构` 的接口表。
 */
const BASE = '/__local/rawimages'

const HEALTH = `${BASE}/health`
const INDEX = `${BASE}/index`
const BLOB = `${BASE}/blob`
const DELETE = `${BASE}/delete`
const ARCHIVE = `${BASE}/archive`

/**
 * 元信息走这个头,值是 <b>base64(UTF-8(JSON))</b>。
 *
 * <p>为什么要 base64:HTTP 头只装得下 ISO-8859-1,而 `label` 是用户本机的文件名,
 * 中文文件名直接塞进头会在某一层被截断或报错。base64 的对象是<b>元信息</b>(不到 1 KB),
 * <b>不是原图</b> —— 上面那张表里被否掉的正是「把原图 base64」。
 */
const META_HEADER = 'x-raw-meta'

/** 请求体上限 16 MiB。单张原图的产品上限是 `recognize.ts` 的 `MAX_PHOTO_BYTES` = 4 MiB。 */
const MAX_BODY_BYTES = 16 * 1024 * 1024

/* ========================================================================== */
/* 编解码 —— 纯函数,两侧各一份,靠 §9.1 那张表对齐                              */
/* ========================================================================== */

function encodeMeta(meta: RawImageMeta): string {
  const bytes = new TextEncoder().encode(JSON.stringify(meta))
  let binary = ''
  for (const b of bytes) binary += String.fromCharCode(b)
  return btoa(binary)
}

/**
 * 🔴 解出来<b>不做校验</b>,原样交给判据层。
 *
 * <p>这不是省事:校验就是判断,而这一层不许有判断。
 * 一行坏元信息(`expiresAt` 不是有限数)由 {@link isExpired} 当成<b>已过期</b>处理 ——
 * 判据层已经写死了这个方向(「存疑就归档,不是宁可留着」),这里再判一次只会有两种说法。
 */
function decodeMeta(header: string): RawImageMeta {
  const binary = atob(header)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i)
  return JSON.parse(new TextDecoder().decode(bytes)) as RawImageMeta
}

/** 元信息 = 行去掉 blob。与 `rawImageDb.ts` 的 `stripBytes` 同一件事。 */
function stripBytes(row: StoredRawImage): RawImageMeta {
  const { blob: _blob, ...meta } = row
  return meta
}

/* ========================================================================== */
/* 请求 —— 失败一律翻成 RawImageStorageError,不新增第三个错误类型               */
/* ========================================================================== */

/**
 * 🔴 一次请求,失败翻成 {@link RawImageStorageError}。
 *
 * <p>`docs/原图存储 §2.1` 的错误码表:壳侧新增的失败模式(目录没权限、磁盘满、索引写不动)
 * <b>全部归到这一个类型</b>,不新增一种需要界面单独处理的形态。
 * 界面那句话在两种形态下逐字相同:「这张图没被本地缓存」,而<b>不是</b>「记不下来」。
 * <p>🔴 message 里<b>不带 id、不带 label、不带路径</b> —— 它会走到界面上,
 * 而 docs/技术架构 §8.2 明说路径也是设备信息。
 */
async function call(path: string, init: RequestInit): Promise<Response> {
  let res: Response
  try {
    res = await fetch(path, init)
  } catch (err) {
    throw new RawImageStorageError('本机原图存储没有响应,这张图没有被本地缓存。', err)
  }
  return res
}

function expectOk(res: Response, what: string): void {
  if (res.ok) return
  throw new RawImageStorageError(`本机原图存储${what}失败(${res.status})。`)
}

/**
 * id 进 URL 之前先编码。
 *
 * <p>🔴 这一行<b>不构成防线</b> —— 真正拦路径穿越的是壳侧 `raw_image_store.rs`
 * 的 id 字符集白名单(`[A-Za-z0-9_-]{1,64}`),因为那一侧才是碰真实路径的一侧。
 * 「让调用方先编码一次」在服务端从来不算数,写在这里只是为了让合法 id 也能过去。
 */
function seg(id: string): string {
  return encodeURIComponent(id)
}

/* ========================================================================== */
/* 形态探测 —— 全链路唯一被允许问「我在哪儿跑」的地方                            */
/* ========================================================================== */

/**
 * 🔴 壳的本地原图存储在不在。<b>这是全链路唯一一次「我在哪儿跑」的问法。</b>
 *
 * <h2>为什么问的是「这个口在不在」,而不是「我是不是 Tauri」</h2>
 *
 * `window.__TAURI__` 那类判断问的是<b>宿主</b>,而这里真正要知道的是
 * <b>「有没有一个能写文件的后端」</b>。两者今天等价,明天不一定 ——
 * 而形态判断每多一处不等价,能力边界就少一道防线。
 * <p>能力探测同时让它<b>可测</b>:一个假 `fetch` 就能把两种形态都跑一遍,
 * 不需要真的起一个壳。
 *
 * <h2>🔴 为什么要查 content-type,而不是只看 200</h2>
 *
 * 一个开了 SPA fallback 的静态服务器(浏览器形态下很常见)会对任何未知路径
 * <b>返回 200 + index.html</b>。只看状态码的话,那种部署会被误判成「壳」,
 * 于是整条原图链路指向一个不存在的后端,而且<b>不报错</b> ——
 * 用户看到的是「图存不进去」,查到的是一个和原因毫无关系的地方。
 * 壳自己的 `local_server.ts` 恰好是<b>没有</b> SPA fallback 的那一个,
 * 但这条探测要能在别人的部署上也成立。
 * <p>所以判据是三条同时成立:<b>200 + JSON + `store === 'fs'`</b>。
 */
export async function localRawImageStoreAvailable(): Promise<boolean> {
  try {
    const res = await fetch(HEALTH, { method: 'GET' })
    if (!res.ok) return false
    if (!(res.headers.get('content-type') ?? '').includes('application/json')) return false
    const body: unknown = await res.json()
    return typeof body === 'object' && body !== null && (body as { store?: unknown }).store === 'fs'
  } catch {
    // 探测失败 = 没有壳的存储口 = 走 IndexedDB。这条路径不报错也不打日志:
    // 浏览器形态下它【每次启动都会失败一次】,那是正常状态,不是故障。
    return false
  }
}

/* ========================================================================== */
/* 后端本体                                                                    */
/* ========================================================================== */

/**
 * 文件系统版本的存储口。五个方法,和 IndexedDB 版一一对应,一个不多。
 *
 * <p>🔴 每个方法都只是<b>一次 HTTP 调用 + 一次翻译</b>,没有重试、没有缓存、没有合并。
 * 重试会把「删不掉」悄悄变成「以为删掉了」;缓存会让 `listMeta` 读到旧的归档态。
 * 这一层的全部工作就是把五个方法搬过那道进程边界。
 */
export const fsRawImageBackend: RawImageBackend = {
  /**
   * 🔴 一次请求写整行:字节是请求体,过期戳在 {@link META_HEADER} 里,<b>同一个请求</b>。
   *
   * <p>「要么整行在,要么整行不在」由壳侧兑现(先写 `.bin.tmp` → fsync → rename,
   * 再原子重写索引;中间崩溃留下的孤儿字节文件由启动清理删掉,见 `docs/原图存储 §2.4`)。
   * 这一侧能做的是<b>不给出第二个写入口</b> —— 没有 `putBytes`,没有 `setMeta`。
   */
  async put(row: StoredRawImage): Promise<void> {
    // 🔴 这是这条链路上唯一一次把整张图过一遍手,而它在进程边界上不可避免。
    //    过完就交给 fetch,本模块不留任何引用 —— 尤其不往任何模块作用域的变量里放。
    const bytes = new Uint8Array(await row.blob.arrayBuffer())
    if (bytes.byteLength > MAX_BODY_BYTES) {
      throw new RawImageStorageError('这张图超过了本机原图存储的单次上限,没有被本地缓存。')
    }
    const res = await call(`${BLOB}/${seg(row.id)}`, {
      method: 'PUT',
      headers: {
        'content-type': 'application/octet-stream',
        [META_HEADER]: encodeMeta(stripBytes(row)),
      },
      body: bytes,
    })
    expectOk(res, '写入')
  },

  async listMeta(): Promise<RawImageMeta[]> {
    const res = await call(INDEX, { method: 'GET' })
    expectOk(res, '读取')
    const body: unknown = await res.json()
    const rows = (body as { rows?: unknown }).rows
    if (!Array.isArray(rows)) {
      throw new RawImageStorageError('本机原图存储返回的索引不是预期的形状。')
    }
    return rows as RawImageMeta[]
  },

  /**
   * 🔴 读得出<b>已归档</b>的行 —— 归档不是删除,读不出来的归档等于删除。
   *
   * <p>这一侧不判断归档态,壳那一侧也不判断:`GET blob/<id>` 有就给,没有就 404。
   * 「归档的还能不能读」这个判断只在 `rawImageCache.read` 里有一份。
   */
  async read(id: string): Promise<StoredRawImage | null> {
    const res = await call(`${BLOB}/${seg(id)}`, { method: 'GET' })
    if (res.status === 404) return null
    expectOk(res, '读取')
    const header = res.headers.get(META_HEADER)
    if (header === null) {
      throw new RawImageStorageError('本机原图存储返回的行缺少元信息。')
    }
    const meta = decodeMeta(header)
    const body = await res.blob()
    // 🔴 类型贴的是【元信息里的 mime】,不是壳回的 content-type。
    //    壳一律回 application/octet-stream —— 它不认识图片,也不该认识:
    //    一个会按图片类型回 content-type 的本地端点,就是一条能在浏览器里直接打开原图的链接,
    //    而 docs/技术架构 §8.1 禁令 4 是「不做任何形式的图片分享/外链」。
    return { ...meta, blob: body.slice(0, body.size, meta.mime) }
  },

  /** 真删。<b>只服务于用户手按的那一下</b>(`forget` / `forgetAll`),到期路径上没有调用点。 */
  async deleteMany(ids: readonly string[]): Promise<void> {
    if (ids.length === 0) return
    const res = await call(DELETE, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ ids }),
    })
    expectOk(res, '删除')
  },

  /**
   * 🔴 只写 `archivedAt` 一个字段。
   *
   * <p>壳侧<b>没有</b>一个能改任意字段的口 —— 只有这一个端点,而它只会写这一个字段名。
   * 所以「一次重试把留存期延到无限」在这条形态上和 IndexedDB 形态上一样写不出来。
   * <p>归档在文件系统上比在 IndexedDB 上干净:<b>只重写索引文件,字节文件一个字节都不动</b>。
   */
  async archive(ids: readonly string[], at: number): Promise<void> {
    if (ids.length === 0) return
    const res = await call(ARCHIVE, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ ids, at }),
    })
    expectOk(res, '归档')
  },
}

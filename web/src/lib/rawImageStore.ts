/**
 * 原图存储的<b>唯一注入点</b> —— 选后端、绑真时钟、跑一次迁移。
 *
 * <h2>🔴 这个文件存在的全部理由:让「我在哪儿跑」这个问题只有一个答复点</h2>
 *
 * 2026-08-31 之前,界面 `import { rawImages } from '../lib/rawImageDb'` ——
 * <b>界面 import 的是「IndexedDB 那个文件」</b>。只有一种形态时没人觉得别扭;
 * 加第二种形态的那一刻,那行 import 就是分叉点:要么界面里出现 `if`,
 * 要么 `rawImageDb.ts` 里出现一个 `import { fsRawImageBackend }`,
 * 而后者会让<b>两个存储实现互相认识</b>。
 * <p>
 * 所以形态判断收进这里,而且<b>只有一句</b>({@link resolveBackend})。
 * 它可以被 grep、可以被数,`shell/build.sh` 步骤 ① 会数它:
 * <b>`web/src` 里除本文件外不许出现任何形态判断</b>。
 * 多一个没人知道的注入点,能力边界就少一道防线。
 *
 * <h2>依赖图(`docs/technical/原图存储-判据层与存储层.md §3.1` 冻结)</h2>
 *
 * <pre>
 *   界面 / main.tsx  ──▶  rawImageStore.ts  ──▶  rawImageCache.ts(判据层)
 *                              │
 *                              ├──▶ rawImageDb.ts   (IndexedDB)
 *                              ├──▶ rawImageFs.ts   (文件系统 · 壳)
 *                              └──▶ rawImageMigrate.ts
 * </pre>
 *
 * <b>单向,无环。两个存储实现各自只依赖判据层的类型,彼此不认识。</b>
 *
 * <h2>🔴 这是整条链路里 `Date.now()` 出现的唯一一处</h2>
 *
 * 判据层的时钟是注入的,就是为了让 `1.1.3.3`「改系统时间实测归档生效」
 * 能变成一条机器断言。那条纪律的代价是:真实时钟必须在某一处被绑上去 —— 就是这一行。
 * 这条性质从 `rawImageDb.ts` 随注入点一起搬过来,<b>不因为搬家而失效</b>:
 * `grep -rn "Date.now()" web/src/lib/rawImage*` 仍应只有一行。
 *
 * <h2>🔴 本文件是这条链路上唯一跑不进 node 的一个</h2>
 *
 * 因为它 import 了 `rawImageDb.ts`,而那个文件碰 `indexedDB`。
 * 这不是遗憾,是刻意把<b>不可测的部分压到最小</b>:
 * 它里面没有判据、没有线协议、没有迁移逻辑,只有一句 `if` 和三行接线 ——
 * 其余每一部分都在别的文件里被 node 测着。
 */

import { RawImageCache } from './rawImageCache'
import type { RawImageBackend, RawImageMeta, StoredRawImage } from './rawImageCache'
import { indexedDbRawImageBackend } from './rawImageDb'
import { fsRawImageBackend, localRawImageStoreAvailable } from './rawImageFs'
import { migrateRawImages } from './rawImageMigrate'

/* ========================================================================== */
/* 形态判断 —— 全链路唯一一处                                                   */
/* ========================================================================== */

/**
 * 🔴 <b>全链路唯一的形态判断。</b>
 *
 * <p>壳形态下顺带跑一次迁移。它放在这里而不是 `main.tsx`,理由是<b>顺序</b>:
 * 迁移必须发生在<b>任何一次 `listMeta` 之前</b>,否则界面会先看到一个空列表,
 * 一秒后又冒出几行 —— 而「本机还留着多少张原图」是用户唯一该看见的数,
 * 它闪一下比它晚一秒出现糟得多。
 *
 * <h2>迁移失败为什么不往上抛</h2>
 *
 * 抛上去 = 壳里的原图链路整个用不了,而失败的那几行本来就<b>没有丢</b>
 * (先写目的地再删源)。它们留在 IndexedDB 里,<b>下次启动重跑</b> ——
 * 迁移是幂等的,重跑没有代价。
 * <p>⚠️ 代价说清楚:在重跑成功之前,那几张图<b>在界面上看不见</b>(应用已经读文件系统了),
 * 而用户不会收到任何提示。已登记 `R-115`。今天的存量是零,所以这条现在不咬人 ——
 * 但它是一条真的缺口,不是一条被解决了的。
 */
async function resolveBackend(): Promise<RawImageBackend> {
  if (!(await localRawImageStoreAvailable())) return indexedDbRawImageBackend

  try {
    await migrateRawImages(indexedDbRawImageBackend, fsRawImageBackend)
  } catch {
    /* 见上:没丢,下次启动重跑。这里【一行日志都不打】——
       这条链路上的异常对象可能带着行数据,而 docs/technical/INDEX.md §8.1 禁令 3 的客户端对应物是
       「不把原图相关的东西打进 console 的任何级别」。 */
  }
  return fsRawImageBackend
}

/**
 * 解一次,记住。
 *
 * <p>🔴 记的是 <b>Promise 而不是结果</b>:并发的头几次调用因此共用同一次探测 + 同一次迁移。
 * 记结果的话,`main.tsx` 的启动扫描和界面首次 `list()` 会各跑一遍迁移 ——
 * 迁移虽然幂等,但两条并发的迁移会互相看见对方搬到一半的中间态。
 */
let pending: Promise<RawImageBackend> | null = null

function backend(): Promise<RawImageBackend> {
  pending ??= resolveBackend()
  return pending
}

/**
 * 把五个方法各自延到后端定下来之后。
 *
 * <p>🔴 它<b>不是</b>一层适配器,里面没有任何一句翻译、判断或重试 ——
 * 每个方法都是「等后端,然后原样转调」。判据层因此拿到的仍然是那五个方法,
 * 而形态探测是异步的这件事,一个字都没漏到判据层里。
 */
const deferredBackend: RawImageBackend = {
  async put(row: StoredRawImage): Promise<void> {
    return (await backend()).put(row)
  },
  async listMeta(): Promise<RawImageMeta[]> {
    return (await backend()).listMeta()
  },
  async read(id: string): Promise<StoredRawImage | null> {
    return (await backend()).read(id)
  },
  async deleteMany(ids: readonly string[]): Promise<void> {
    return (await backend()).deleteMany(ids)
  },
  async archive(ids: readonly string[], at: number): Promise<void> {
    return (await backend()).archive(ids, at)
  },
}

/* ========================================================================== */
/* 接线 —— 真后端 + 真时钟                                                      */
/* ========================================================================== */

/** 全应用唯一的原图缓存实例。界面与 `main.tsx` 都从这里 import,不从任何一个存储实现里 import。 */
export const rawImages = new RawImageCache({
  backend: deferredBackend,
  now: () => Date.now(),
})

/**
 * 🔴 到期归档的<b>第一条触发</b>:应用启动时扫一遍。由 `main.tsx` 调,一次。
 *
 * <h2>为什么不能只靠「存新图时顺带扫」那一条</h2>
 *
 * 那条只在用户<b>再导一张图</b>时才会跑。而最需要被扫到的恰恰是
 * 「用户昨晚导了一批,今天没再导」的那批 —— 只有存图触发的话,它们要等到
 * 下一次导入才走,而下一次导入可能永远不来。
 *
 * <h2>失败为什么只是 swallow</h2>
 *
 * 隐私模式 / IndexedDB 被禁用 / 壳的存储目录没权限时这里必然抛,
 * 而那几种情况下<b>压根没有图可扫</b>。让它把首屏炸掉,是用一个不存在的风险换一个真实的故障。
 * 🔴 catch 里<b>一行日志都不打</b> —— 这条链路上的异常对象可能带着行数据,
 * 而 docs/technical/INDEX.md §8.1 禁令 3 的客户端对应物是「不把原图相关的东西打进 console 的任何级别」。
 */
export function sweepRawImagesOnStartup(): void {
  void rawImages.sweep().catch(() => {
    /* 本地存储用不了 = 没有原图留在本机 = 这条红线自动成立,不需要惊动任何人 */
  })
}

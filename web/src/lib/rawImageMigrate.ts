/**
 * 迁移:已经在浏览器存储里的原图,怎么搬到文件系统上。
 *
 * <h2>🔴 先说清楚它<b>搬不到</b>什么 —— 这是本文件最要紧的一句</h2>
 *
 * 浏览器侧的一切本地存储<b>按 origin 隔离</b>,而壳的 origin 是
 * `http://127.0.0.1:17840`(`shell/src/config.rs` 顶部那段就是为这条写的)。
 * 用户在真正的浏览器里(`https://…`)存下的原图,和壳里的 IndexedDB
 * <b>是两个互不可见的库</b>。所以:
 *
 * <table border="1">
 *   <tr><th>图在哪儿</th><th>这次怎么办</th><th>为什么</th></tr>
 *   <tr><td>壳自己的 IndexedDB(装过旧版壳的机器)</td><td><b>自动搬</b>,就是本文件</td>
 *       <td>同一个 origin,读得到</td></tr>
 *   <tr><td>用户浏览器里(另一个 origin)</td><td>🔴 <b>不搬</b> ——
 *       留在那儿,照它自己的 TTL 到期转归档,用户随时能在那边手动删</td>
 *       <td><b>结构上读不到</b>。而要读到它,只能造一条「导出原图 / 导入原图」的通路,
 *       那是一条原图离开原存储的路 —— 这次迁移不值得开这扇门</td></tr>
 * </table>
 *
 * 🔴 <b>「不搬」不是没做完,是这一侧的结论。</b> 两种形态的图各自在各自的机器位置上到期、
 * 各自能被手动删掉,红线「原图只在用户自己的机器上」在两边都成立 ——
 * 唯一没有的是「在壳里看见浏览器里的那几张」,而那个便利换不来一条导出通路。
 *
 * <h2>三条不变式(`docs/technical/原图存储-判据层与存储层.md §4.1`),搬家不是复制也不是删除</h2>
 *
 * <table border="1">
 *   <tr><th>不变式</th><th>违反的后果</th></tr>
 *   <tr><td>`expiresAt` / `storedAt` / `archivedAt` <b>原样搬</b>,一个都不重算</td>
 *       <td>重置一次「多久前」,这个产品唯一的产出就错了</td></tr>
 *   <tr><td>🔴 已归档的搬过去<b>仍然是归档态</b></td>
 *       <td>归档态搬成活跃态 = 一张已到期的图重新开始计时</td></tr>
 *   <tr><td>单向:浏览器存储 → 文件系统。<b>没有反向</b></td>
 *       <td>反向意味着字节回到配额受限的存储里,而配额正是这次要解决的问题(`R-105`)</td></tr>
 * </table>
 *
 * <h2>这个文件里没有 DOM 符号,所以整条迁移跑得进 node</h2>
 *
 * 它只认两个 {@link RawImageBackend},不认识 IndexedDB、也不认识文件系统。
 * 于是「中断在哪一步会丢字节」这种问题能被<b>断言</b>,而不是靠推演 ——
 * 见 `tests/rawImageMigrate.test.ts`。
 */

import type { RawImageBackend } from './rawImageCache.ts'

/** 一次迁移的结果。只有三个数,<b>没有任何一张图的 id 或 label</b>。 */
export interface RawImageMigrationResult {
  /** 这一轮真正搬过去的行数。 */
  readonly moved: number
  /** 上一轮中断留下的「两边都在」,这一轮只补了删源那半步的行数。 */
  readonly reclaimed: number
  /** 源里有元信息但读不出字节的残行 —— <b>跳过,不删</b>。见 {@link migrateRawImages}。 */
  readonly skipped: number
}

/**
 * 把 `from` 里的原图逐条搬到 `to`。<b>幂等、可中断、可续跑。</b>
 *
 * <h2>🔴 顺序写死:先写目的地并确认提交,再删源</h2>
 *
 * 反过来(先删源再写目的地)会在中断时<b>丢字节</b>,而丢的是用户的原图。
 * 这一条不是权衡,是这个函数存在的唯一形状。
 *
 * <h2>逐条,而不是整批</h2>
 *
 * 整批意味着中断时要么全丢要么全留。逐条的中断代价是「有几张两边都在」,
 * 而下一次启动第一步就化解了({@link RawImageMigrationResult.reclaimed})。
 *
 * <h2>🔴 读不出字节的残行:跳过,不删</h2>
 *
 * 源里有元信息、`read` 却返回 `null`(写到一半断电、被人手改过库)。
 * 最顺手的写法是「反正也没有字节,删掉」——<b>不采用</b>:
 * `docs/technical/原图存储-判据层与存储层.md §4.3` 给删源那半步的豁免只覆盖一种触发,
 * 「<b>这一行已经在别处存在</b>」,而残行不在别处存在。
 * 豁免不外借,所以它留在源里,不计入 moved,也不阻塞其它行。
 *
 * @returns 三个计数。调用方<b>只用它做判断,不用它做展示</b> —— 见 `rawImageStore.ts`。
 */
export async function migrateRawImages(
  from: RawImageBackend,
  to: RawImageBackend,
): Promise<RawImageMigrationResult> {
  const source = await from.listMeta()
  // 绝大多数启动走这一条:源是空的,一次 listMeta 就结束,不碰目的地。
  if (source.length === 0) return { moved: 0, reclaimed: 0, skipped: 0 }

  const already = new Set((await to.listMeta()).map((m) => m.id))

  let moved = 0
  let reclaimed = 0
  let skipped = 0

  for (const meta of source) {
    if (already.has(meta.id)) {
      // 上一轮搬过去了、还没来得及删源就中断了。这一轮只补后半步。
      // 🔴 这一删是 §4.3 那条豁免的正身:字节【在删之前】已经在本机的新位置上了。
      await from.deleteMany([meta.id])
      reclaimed += 1
      continue
    }

    const row = await from.read(meta.id)
    if (row === null) {
      skipped += 1
      continue
    }

    // 🔴 整行原样 put —— 三个时间戳一个都不重算,归档态原样带过去。
    await to.put(row)
    // 只有上面这一行确认提交(await 正常返回)之后,才轮到删源。
    await from.deleteMany([meta.id])
    moved += 1
  }

  return { moved, reclaimed, skipped }
}

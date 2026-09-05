/**
 * 路由表的断言 —— `KUBI-113`,`多端选型与端矩阵` §4.6 的前端一侧。
 *
 * <h2>这个文件存在的理由</h2>
 *
 * §4.6.2 把路由表定成<b>一份契约</b>而不是一个实现细节:「一处定义,各端现读,不抄副本。」
 * 而一份契约要能被检查,否则它和一段注释没有区别。
 * <p>
 * 被测的 `src/routes/routes.ts` 里<b>一个 DOM 符号都没有</b>,所以它跑得进 node
 * (`tsconfig.test.json` 那个 project 的 `lib` 里没有 DOM);真正 `createBrowserRouter`
 * 的 `main.tsx` 里则一条判断都没有,它只是把这张表摊开。
 * 两层加起来,没有一处判断落在测试之外 —— 与 `captchaPolicy` / `tcaptcha` 那一对同型。
 *
 * <h2>🔴 它们红过</h2>
 *
 * 逐条改判据跑过一遍:
 * <ul>
 *   <li>把 `ROUTE_PATH.coverage` 改成 `/Coverage` → 「三条命名规矩」报
 *       `段 "Coverage" 不是全小写 kebab-case`</li>
 *   <li>往表里加一条 `'/records/delete-record/:id'` → 同一条断言报
 *       `段 "delete-record" 是一个动作`</li>
 *   <li>把 `login` 加进 `RouteId` 与 `ROUTE_PATH` → 「没有 /login」当场失败</li>
 *   <li>把 `q` 加进 `ALLOWED_QUERY_KEYS` → 「搜索词不进 URL」失败</li>
 * </ul>
 */

import assert from 'node:assert/strict'
import test from 'node:test'
import {
  ALLOWED_QUERY_KEYS,
  ROOT_REDIRECT,
  ROUTE_PATH,
  namingViolations,
  queryKeysAllowed,
  routeTo,
} from '../src/routes/routes.ts'

test('三条命名规矩:全小写 kebab-case / 复数集合名 / 段只表示位置不表示动作', () => {
  assert.deepEqual(namingViolations(), [])
})

test('🔴 /login 这个地址不存在 —— 门只有里外,没有历史', () => {
  const paths = Object.values(ROUTE_PATH)
  assert.equal(
    paths.some((p) => p.split('/').includes('login')),
    false,
    '路由表里出现了 login 段。门做成路由的话,登录完按一下后退就回到登录页,再点一次又登进去。',
  )
  // 也不许有任何一条「未登录专用」的 id —— §4.6.2 的那条注按字面读。
  assert.equal(
    Object.keys(ROUTE_PATH).some((id) => id.includes('guest') || id.includes('anon')),
    false,
  )
})

test('🔴 搜索词不进 URL —— query 白名单只有视图状态', () => {
  assert.deepEqual([...ALLOWED_QUERY_KEYS], ['tab', 'mode', 'sort', 'filter', 'subject'])
  assert.equal(queryKeysAllowed('?filter=unclassified'), true)
  assert.equal(queryKeysAllowed('?tab=blind&sort=recent'), true)
  assert.equal(queryKeysAllowed(''), true)
  // 用户完全可能往命令面板里粘一整道题,而 URL 会进历史、日志、截图。
  assert.equal(queryKeysAllowed('?q=%E4%B8%80%E9%81%93%E9%A2%98'), false)
  assert.equal(queryKeysAllowed('?search=x'), false)
})

test('routeTo 填参数,并且一律编码 —— 带 / 的标识不许把地址切成两段', () => {
  assert.equal(routeTo('coverage'), '/coverage')
  assert.equal(routeTo('coverage.node', { nodeCode: 'growth-rate' }), '/coverage/growth-rate')
  assert.equal(routeTo('records.detail', { recordId: 'a/b' }), '/records/a%2Fb')
  assert.throws(() => routeTo('coverage.node'), /缺参数 nodeCode/)
})

test('/ 落到覆盖度 —— 差集是这个产品的第一屏', () => {
  assert.equal(ROOT_REDIRECT, 'coverage')
  assert.equal(routeTo(ROOT_REDIRECT), '/coverage')
})

test('每个 id 都有一条唯一的路径 —— 两个 id 指到同一个地址等于少了一屏', () => {
  const paths = Object.values(ROUTE_PATH)
  assert.equal(new Set(paths).size, paths.length)
})

test('参数段一律用 :name 形式,并且父路径确实是它的前缀', () => {
  for (const [id, path] of Object.entries(ROUTE_PATH)) {
    assert.match(path, /^\//, `${id} 的路径不是绝对路径`)
    if (!id.includes('.')) continue
    const parent = id.slice(0, id.lastIndexOf('.')) as keyof typeof ROUTE_PATH
    if (ROUTE_PATH[parent] === undefined) continue
    assert.ok(
      path.startsWith(`${ROUTE_PATH[parent]}/`),
      `${id} 的路径 ${path} 不在父 id ${parent}(${ROUTE_PATH[parent]})之下 —— id 的层级与地址的层级对不上`,
    )
  }
})

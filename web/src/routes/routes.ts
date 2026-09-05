/**
 * 路由表 —— 唯一来源(`多端选型与端矩阵` §4.6.2)。
 *
 * <h2>为什么是一份数据而不是一堆 `<Route path="...">`</h2>
 *
 * §4.6.2 的原话:「这是一份契约,不是一个实现细节。与能力边界词表同一条规矩:
 * <b>一处定义,各端现读,不抄副本。</b>」端与端之间对齐的是 `route id`,不是路径字符串 ——
 * 小程序那一端根本没有任意 URL,它拿的是 id → 页面路径的映射。
 * 所以路径必须是一张能被别的端<b>读</b>的表,不能散在 JSX 里。
 *
 * 这个文件里<b>一个 DOM 符号都没有</b>,于是它能被 `tests/routes.test.ts` 在 node 里跑 ——
 * 「纯判断层不碰浏览器 API」那条分层在这里的落点。
 *
 * <h2>🔴 `/login` 不在这张表里,这是有意的</h2>
 *
 * §4.6.2:「门只有里外,没有历史 —— 做成路由的话,登录完按一下后退就回到登录页,
 * 再点一次又登进去,一个不存在的往返。<b>未登录访问任何 route id,渲染的是门,地址不变。</b>」
 *
 * <h2>⚪ `export` 这一行是本轮新增的,`多端选型与端矩阵` §4.6.2 的表里没有它</h2>
 *
 * 那张表列了 14 个 id,而 `M4 出口` 是一整个模块(`design/m4/` 八张稿),
 * 它在表里没有落点。本轮把它按同样的三条命名规矩加进来(`/export`,全小写、
 * 只表示位置不表示动作、不撞 §4.6.3 的词表),但这是<b>前端一侧的登记,不是裁定</b> ——
 * 该不该进那份契约要过一次人审。在那之前它是这张表里唯一一条「稿有、契约无」的路由。
 */

/** 路由 id。端与端之间对齐的就是它。 */
export type RouteId =
  | 'coverage'
  | 'coverage.node'
  | 'syllabus'
  | 'capture'
  | 'capture.text'
  | 'capture.audio'
  | 'capture.photo'
  | 'records'
  | 'records.detail'
  | 'archive'
  | 'agent'
  | 'export'
  | 'settings'
  | 'settings.privacy'
  | 'settings.model'

/**
 * id → URL 模板。`:name` 是参数段。
 *
 * 🔴 顺序即 `多端选型与端矩阵` §4.6.2 表格的顺序,方便逐行对照。
 */
export const ROUTE_PATH: Record<RouteId, string> = {
  coverage: '/coverage',
  'coverage.node': '/coverage/:nodeCode',
  syllabus: '/syllabus',
  capture: '/capture',
  'capture.text': '/capture/text',
  'capture.audio': '/capture/audio',
  'capture.photo': '/capture/photo',
  records: '/records',
  'records.detail': '/records/:recordId',
  archive: '/archive',
  agent: '/agent',
  export: '/export',
  settings: '/settings',
  'settings.privacy': '/settings/privacy',
  'settings.model': '/settings/model',
}

/** `/` 落到哪儿。§4.6.2:「覆盖度主屏(`/` 重定向到这里)」。 */
export const ROOT_REDIRECT: RouteId = 'coverage'

/**
 * query 参数白名单 —— §4.6.3。
 *
 * 「只放视图状态。<b>加一个要过一次人审。</b>」
 * 🔴 命令面板的搜索词<b>不在这里</b>:它是瞬时状态不是一个位置,
 * 而用户完全可能往里粘一整道题。
 */
export const ALLOWED_QUERY_KEYS = ['tab', 'mode', 'sort', 'filter', 'subject'] as const

export type AllowedQueryKey = (typeof ALLOWED_QUERY_KEYS)[number]

/**
 * 覆盖层的历史条目 —— §4.6.2「覆盖层也算页面……浮层的视觉形态不变,变的是由谁开」。
 *
 * ⌘K 面板是那次改动<b>唯一漏掉的一个</b>:另外四个快捷键(⌘B/⌘N/⌘E/⌘J)当时都落到地址上了,
 * 只有它因为 §4.6.3「搜索词不进地址」留成了 `useState`。代价在 Android 上实测到
 * (KUBI-118,模拟器):面板开着、历史深度 1 时按系统返回键<b>整个应用退出</b>,
 * 面板从没被关过;历史深度 2 时第一下关软键盘,第二下弹掉背后那一屏而面板还开着。
 *
 * 🔴 <b>收窄的是 query,不是页面。</b>`location.state` 进历史但<b>不进 URL</b>,
 * 所以搜索词照样不进地址、不进日志、不进截图 —— §4.6.3 那条收窄一个字都不用改,
 * `ALLOWED_QUERY_KEYS` 也不加键。
 */
export const OVERLAY_PALETTE = 'palette'

/**
 * 这个历史条目上开着哪个覆盖层。
 *
 * 判据是<b>历史条目</b>,不是组件里的一个布尔 —— 这样 Android 物理返回键、iOS 侧滑、
 * 桌面浏览器返回键走的是同一个 `popstate`,三端返回语义自动一致,壳里一行都不用写。
 * <p>
 * `location.state` 的类型是 `unknown`(它由用户的历史记录还原,可能是任何东西,
 * 包括上一版应用写进去的形状),所以收窄在这里做一次,界面层不再判。
 */
export function isOverlayOpen(state: unknown, overlay: string): boolean {
  if (typeof state !== 'object' || state === null) return false
  return (state as { overlay?: unknown }).overlay === overlay
}

/**
 * 🔴 这个文件里<b>没有</b>一张「禁用路径段」的词表,这是有意的。
 *
 * `多端选型与端矩阵` §十 第 8 条自检本来就是一条 grep,它扫的正是这个文件:
 *
 * ```bash
 * grep -nE "…被禁的那几个段…" web/src/routes/routes.ts   # 期望零命中
 * ```
 *
 * 把那几个词抄进来做成一张常量表,这条自检当场变成<b>永远红</b> ——
 * 而且它们里有两个在能力边界扫描的硬名单上,硬名单没有豁免这一说。
 * 一张会让自己的断言红掉的词表,等于把断言关掉。
 * <b>禁用词的唯一副本留在扫描脚本里,这里只留这段说明。</b>
 */

/**
 * 把模板填成一个真地址。
 *
 * 参数值一律 `encodeURIComponent` —— 考点 code 与记录 id 都是<b>不透明 id</b>(§4.6.3),
 * 但编码这一步不能省:少了它,一个带 `/` 的 id 会把地址切成两段。
 */
export function routeTo(id: RouteId, params: Record<string, string> = {}): string {
  return ROUTE_PATH[id].replace(/:([A-Za-z]+)/g, (_, name: string) => {
    const v = params[name]
    if (v === undefined) throw new Error(`路由 ${id} 缺参数 ${name}`)
    return encodeURIComponent(v)
  })
}

/**
 * 一个地址里的 query 是否只带了白名单里的键。
 *
 * 界面不靠它拦(那是写地址的人的事),它存在是为了<b>被测试盯住</b>:
 * 哪天有人往地址里塞搜索词,`tests/routes.test.ts` 会当场红。
 */
export function queryKeysAllowed(search: string): boolean {
  const q = search.startsWith('?') ? search.slice(1) : search
  if (q === '') return true
  return q
    .split('&')
    .filter((p) => p !== '')
    .every((p) => (ALLOWED_QUERY_KEYS as readonly string[]).includes(decodeURIComponent(p.split('=')[0])))
}

/**
 * 三条命名规矩(§4.6.2 最后一段)有没有被守住:全小写 kebab-case;
 * 集合名用复数;<b>路径段只表示位置,不表示动作</b>。
 *
 * 返回违规说明,空数组即通过。第三条只能查到「常见动词」这个层面 ——
 * 它挡的是 `/delete-record/:id` 那一类,不假装能判断语义。
 */
const ACTION_VERBS = ['create', 'delete', 'update', 'edit', 'submit', 'send', 'open', 'close']

export function namingViolations(): string[] {
  const bad: string[] = []
  for (const [id, path] of Object.entries(ROUTE_PATH)) {
    for (const seg of path.split('/')) {
      if (seg === '' || seg.startsWith(':')) continue
      if (seg !== seg.toLowerCase() || /[^a-z0-9-]/.test(seg)) {
        bad.push(`${id} · ${path} · 段 "${seg}" 不是全小写 kebab-case`)
      }
      if (ACTION_VERBS.some((v) => seg === v || seg.startsWith(`${v}-`))) {
        bad.push(`${id} · ${path} · 段 "${seg}" 是一个动作 —— 动作是请求,不是地址`)
      }
    }
  }
  return bad
}

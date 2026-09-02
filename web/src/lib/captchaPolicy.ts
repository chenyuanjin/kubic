/**
 * 行为验证(腾讯云验证码)的<b>判据层</b> —— docs/后端详设 §1.8 那四道闸里第①道的前端一侧。
 *
 * <h2>这一层为什么和控件分开写</h2>
 *
 * 与 `rawImageCache.ts` / `rawImageDb.ts` 同一条纪律:<b>一层能被测但不接真控件,
 * 一层接真控件但没有可测的判断</b>,未被测到的那半边因此没有地方藏逻辑。
 * <p>
 * 所以这个文件里<b>一个 DOM 符号都没有</b>:没有 `document`、没有 `window`、
 * 没有 `import.meta.env`。它只认一个注入的开口({@link CaptchaOpener})和一个
 * 纯数据的环境表。真正插 `<script>`、`new TencentCaptcha(...)` 的那一层在
 * `tcaptcha.ts`,它<b>不含任何判断</b>。
 *
 * <h2>🔴 这一层要挡住的三件事,每一件都有过经典结局</h2>
 *
 * <table border="1">
 *   <tr><th>要挡的</th><th>不挡会怎样</th></tr>
 *   <tr><td>只把 `ticket` 发出去</td>
 *       <td>server 侧 `SmsSendRequest` 的类注释点名了这个坑:校验 100% 不通过,
 *           然后往往被人「修」成失败也放行 —— 那就回到了没有滑块</td></tr>
 *   <tr><td>把腾讯云的<b>容灾票据</b>当成一次通过</td>
 *       <td>见下面 {@link DISASTER_TICKET_PREFIXES}。它长得像通过,但服务端必然判不通过</td></tr>
 *   <tr><td>`app-id` 配错时安静地退回占位串</td>
 *       <td>本机点得通、上线全红。所以配错是 {@link CaptchaMode} 的<b>第三种</b>,
 *           不是第二种({@link readCaptchaMode})</td></tr>
 * </table>
 *
 * <h2>⚠️ 这一层<b>没有</b>真机联调过</h2>
 *
 * 腾讯云验证码产品尚未开通(它与短信是两个相互独立的产品),没有可用的
 * `CaptchaAppId` + `AppSecretKey`。下面对回调形状的处置来自厂商文档
 * (`cloud.tencent.com/document/product/1110/36841`,as-of 2026-08-31),
 * <b>不是实测结论</b>。真凭据到手那天要复核的就是 {@link classifyCaptchaResult} 这一个函数,
 * 而它能被复核正是因为它在这一层 —— 它的每一条分支都有一条 node 断言
 * (`tests/captchaPolicy.test.ts`),改一条就红一条。
 */

/* ========================================================================== */
/* 配置                                                                        */
/* ========================================================================== */

/**
 * 控件脚本地址。
 *
 * <p>厂商同时在售两版:`TCaptcha.js`(1.0)与 `TJCaptcha.js`(2.0)。这里钉 1.0,
 * 理由不是新旧而是<b>要和服务端对上</b>:`TencentCaptchaVerifier` 把
 * `CaptchaType` 写死成 `9`(滑动拼图),那是 1.0 那套枚举里的值。
 * 两边各挑各的版本,是这类接入最容易出现、又最难从错误消息里看出来的那种不一致。
 *
 * <p>⚪ 待真凭据到手后确认;若届时改用 2.0,<b>这一行和服务端的 `CAPTCHA_TYPE` 要一起改</b>。
 */
export const CAPTCHA_SCRIPT_URL = 'https://turing.captcha.qcloud.com/TCaptcha.js'

/**
 * 🔴 `CaptchaAppId` 从这个环境变量读,<b>不写进源码</b>。
 *
 * <p>它本身不是密钥(控件把它印在每一个请求里,任何人都看得到),真正的密钥
 * `AppSecretKey` 只在服务端。但它仍然是一项<b>随环境变的配置</b> ——
 * 硬编码进源码意味着「换一个环境」变成「改一次代码并重新走一遍评审」。
 *
 * <p>`VITE_` 前缀是 Vite 的硬性要求:没有这个前缀的变量不会被注入到前端产物里,
 * 于是它会永远读成 `undefined`,而表现出来是<b>静默退回占位串</b> —— 正是本层第三条要挡的事。
 */
export const CAPTCHA_APP_ID_ENV_KEY = 'VITE_KAODIAN_CAPTCHA_APP_ID'

/**
 * 腾讯云的<b>容灾票据</b>前缀。
 *
 * <h2>🔴 它长得像一次通过,但它不是</h2>
 *
 * 厂商文档给的建议做法是:控件加载不出来时,前端<b>自己伪造</b>一张
 * `trerror_<errorCode>_<appId>_<时间戳>` 的票据、并以 `ret: 0` 回调,
 * 「确保客户业务流程不被阻塞」。
 * <p>
 * 那条建议对绝大多数业务成立,<b>对这一条链路不成立</b>:滑块是这里唯一真正的闸
 * (单号 1/60s 与单 IP 20/日 都是纯计数,换一批号换一批 IP 都不触发),
 * 它一没,短信费就没有上限。服务端那侧已经把这条写死了 ——
 * `TencentCaptchaVerifier` 的注释:「供应商不可用时【判不通过】,不是【放行】」。
 * <p>
 * 所以本层<b>不实现那个伪造回调</b>,并且把从正常回调里流回来的容灾票据
 * 也识别成「不可用」而不是「通过」。识别它的收益是<b>省一次注定失败的往返</b>,
 * 并且能说出真正的原因 —— 不然用户看到的会是一句莫名其妙的「验证码校验未通过」。
 */
export const DISASTER_TICKET_PREFIXES = ['trerror_', 'terror_'] as const

/* ========================================================================== */
/* 形状                                                                        */
/* ========================================================================== */

/**
 * 🔴 票据与随机串 —— <b>一个对象的两个字段,不是两个参数</b>。
 *
 * <p>这个形状本身就是「两个都要传」那条纪律的落点:调用方拿不到一个只有 `ticket`
 * 的 {@link CaptchaPair},因为构造它的地方只有 {@link classifyCaptchaResult} 一处,
 * 而那里两个都缺一不可。把它们拆成 `send(phone, ticket, randstr)` 两个形参,
 * 「漏传第二个」就重新变成一件写得出来的事。
 */
export interface CaptchaPair {
  readonly ticket: string
  readonly randstr: string
}

/**
 * 没有接控件时发的占位串 —— 与服务端 `kaodian.auth.captcha.provider=disabled` 成对。
 *
 * <p>它保留了本议题之前那两个 `'dev'` 的字面值,因为服务端 `DisabledCaptchaVerifier`
 * 一律放行,发什么都一样。留着它<b>不是</b>为了将来能少配一项,是为了让本机开发
 * 不需要真实凭据也能把登录完整点完 —— 那是这个原型能被人真的走一遍的前提。
 *
 * <p>🔴 但它<b>必须在界面上看得见</b>({@link CaptchaOutcome} 的 `bypassed`)。
 * 一个安静的旁路和一个接通了的滑块在开发者眼里长得一模一样,而这两者的差别
 * 恰好是「短信费有没有上限」。
 */
export const BYPASS_PAIR: CaptchaPair = { ticket: 'dev', randstr: 'dev' }

/**
 * 这次构建到底处在哪种状态。
 *
 * <p>🔴 <b>三种,不是两种。</b>「配了但配错」不能并进「没配」——
 * 并进去就成了:本机点得通(退回占位串,服务端 disabled 放行),
 * 上线当天每一次发送都 400。配错必须当场说出来。
 */
export type CaptchaMode =
  | { readonly kind: 'vendor'; readonly appId: string }
  | { readonly kind: 'bypass' }
  | { readonly kind: 'misconfigured'; readonly reason: string }

/** 一次行为验证的终态。 */
export type CaptchaOutcome =
  /** 拿到了可用的一对。`bypassed` 为真表示这是占位串,<b>没有真的验过</b>。 */
  | { readonly kind: 'pass'; readonly pair: CaptchaPair; readonly bypassed: boolean }
  /** 用户把验证框关掉了。这不是失败,不该显示成红字。 */
  | { readonly kind: 'closed' }
  /** 验不成。`retryable` 决定界面给不给「再试一次」。 */
  | { readonly kind: 'unavailable'; readonly reason: string; readonly retryable: boolean }

/**
 * 弹出验证框、等用户操作完 —— 由 `tcaptcha.ts` 注入的那个开口。
 *
 * <p>返回值刻意是 `unknown`:它来自第三方脚本,<b>形状不由这个仓库决定</b>。
 * 声明成一个漂亮的接口只会让 TypeScript 替一份没人保证的假设背书,
 * 而真正读它的地方({@link classifyCaptchaResult})本来就得逐字段设防。
 */
export type CaptchaOpener = (appId: string) => Promise<unknown>

/** 一对里缺了一个就抛它 —— 那一半绝不能单独发出去。 */
export class CaptchaPairError extends Error {
  constructor(reason: string) {
    super(reason)
    this.name = 'CaptchaPairError'
  }
}

/* ========================================================================== */
/* 判据                                                                        */
/* ========================================================================== */

function text(v: unknown): string {
  return typeof v === 'string' ? v.trim() : ''
}

function field(raw: unknown, name: string): unknown {
  if (raw === null || typeof raw !== 'object') return undefined
  return (raw as Record<string, unknown>)[name]
}

/** 是不是一张容灾票据。见 {@link DISASTER_TICKET_PREFIXES}。 */
export function isDisasterTicket(ticket: string): boolean {
  return DISASTER_TICKET_PREFIXES.some((p) => ticket.startsWith(p))
}

/**
 * 从环境表里读出这次构建的模式。
 *
 * <p>只认十进制数字串:厂商的 `CaptchaAppId` 就是这个形状。把 `"你的AppId"`
 * 这种占位文本、或者带引号带空格的粘贴残留判成 {@link CaptchaMode} 的
 * `misconfigured` 而不是 `vendor`,是因为那种值送进控件只会得到一个
 * 「控件初始化失败」——那句话不会让任何人想到去看环境变量。
 *
 * <p>参数是一张普通的表而不是直接读 `import.meta.env`:后者是 Vite 的东西,
 * 写在这里这一层就跑不进 node 了(`tsconfig.test.json` 的 lib 里没有 DOM,
 * types 里没有 vite/client)。真正去读它的是 `tcaptcha.ts`。
 */
export function readCaptchaMode(env: Readonly<Record<string, string | undefined>>): CaptchaMode {
  const raw = env[CAPTCHA_APP_ID_ENV_KEY]
  if (raw === undefined || raw.trim() === '') return { kind: 'bypass' }

  const appId = raw.trim()
  if (!/^\d+$/.test(appId)) {
    return {
      kind: 'misconfigured',
      reason: `${CAPTCHA_APP_ID_ENV_KEY} 不是一串数字(读到「${appId}」)。腾讯云的 CaptchaAppId 是纯数字。`,
    }
  }
  return { kind: 'vendor', appId }
}

/**
 * 🔴 <b>把第三方回调翻成一个终态。整个前端接入里唯一有判断的地方。</b>
 *
 * <h2>逐条对着厂商文档(as-of 2026-08-31)</h2>
 *
 * <table border="1">
 *   <tr><th>回调</th><th>本层判成</th><th>为什么</th></tr>
 *   <tr><td>`ret === 2`</td><td>`closed`</td>
 *       <td>用户主动关掉。这是一次<b>取消</b>,不是一次失败 —— 显示成红字会让人
 *           以为自己做错了什么</td></tr>
 *   <tr><td>`ret === 0` + 两个值都在 + 不是容灾票据</td><td>`pass`</td><td>唯一的通过</td></tr>
 *   <tr><td>`ret === 0` + 票据带容灾前缀</td><td>`unavailable`</td>
 *       <td>见 {@link DISASTER_TICKET_PREFIXES}。<b>它的 `ret` 就是 0</b>,只看 `ret` 会把它放过去</td></tr>
 *   <tr><td>`ret === 0` + <b>只有一个值</b></td><td>`unavailable`</td>
 *       <td>🔴 「只传 ticket」那个经典错误在本层的形态。发出去必然 400,
 *           而 400 的文案会把人引向手机号或者服务端,不会引向这里</td></tr>
 *   <tr><td>其它 / 读不懂</td><td>`unavailable`</td>
 *       <td>存疑就不通过。反过来写(读不懂就当通过)等于给这道闸开一个后门</td></tr>
 * </table>
 */
export function classifyCaptchaResult(raw: unknown): CaptchaOutcome {
  if (raw === null || typeof raw !== 'object') {
    return { kind: 'unavailable', reason: '验证控件返回了一个读不懂的结果', retryable: true }
  }

  const ret = field(raw, 'ret')
  if (ret === 2) return { kind: 'closed' }

  const errorMessage = text(field(raw, 'errorMessage'))

  if (ret !== 0) {
    return {
      kind: 'unavailable',
      reason: errorMessage === '' ? '验证没有通过' : errorMessage,
      retryable: true,
    }
  }

  const ticket = text(field(raw, 'ticket'))
  const randstr = text(field(raw, 'randstr'))

  if (ticket !== '' && isDisasterTicket(ticket)) {
    // 不发出去。服务端拿它去核查必然不通过,那一趟只会换回一句看不懂的「校验未通过」。
    return { kind: 'unavailable', reason: '验证服务暂时不可用,请稍后再试', retryable: true }
  }

  if (ticket === '' || randstr === '') {
    // 🔴 半对是最坏的一种:它会一路走到服务端才失败,而那时错误信息已经指不回这里了。
    return {
      kind: 'unavailable',
      reason: '验证控件只回传了一半凭据,这一次不算通过',
      retryable: true,
    }
  }

  return { kind: 'pass', pair: { ticket, randstr }, bypassed: false }
}

/**
 * 走完一次行为验证 —— 三种模式各走各的,<b>控件只在第一种里被碰到</b>。
 *
 * <p>它把「打开控件」这件事收成一个注入的参数,于是这条编排本身也能在 node 里被跑:
 * 给一个直接 resolve 一份假回调的 `open`,三条分支各有一条断言。
 */
export async function requestCaptcha(mode: CaptchaMode, open: CaptchaOpener): Promise<CaptchaOutcome> {
  if (mode.kind === 'bypass') {
    return { kind: 'pass', pair: BYPASS_PAIR, bypassed: true }
  }
  if (mode.kind === 'misconfigured') {
    // 不可重试:再点一百次也不会变。要改的是那个环境变量。
    return { kind: 'unavailable', reason: mode.reason, retryable: false }
  }

  let raw: unknown
  try {
    raw = await open(mode.appId)
  } catch (err) {
    // 脚本加载不出来 / 控件构造失败。🔴 不在这里伪造一张容灾票据 —— 见
    // DISASTER_TICKET_PREFIXES。但也绝不能什么都不说:那正是「按钮没反应」。
    const detail = err instanceof Error ? err.message : String(err)
    return { kind: 'unavailable', reason: `验证控件没能加载出来(${detail})`, retryable: true }
  }
  return classifyCaptchaResult(raw)
}

/**
 * 🔴 发出去之前最后一道:<b>两个都在,才准走</b>。
 *
 * <p>类型上 {@link CaptchaPair} 已经要求两个字段都有,但 `''` 也是 `string` ——
 * 而一个空的 `randstr` 与压根没传是同一个后果。这道检查的意义在于:
 * 它<b>在前端当场抛</b>,而不是让请求走到服务端换回一句 `CAPTCHA_FAILED`。
 * 那句话是给「用户没验过」准备的,拿来盖前端的漏传会把排查引到完全错误的方向。
 */
export function requirePair(pair: CaptchaPair): CaptchaPair {
  const ticket = text(pair?.ticket)
  const randstr = text(pair?.randstr)
  if (ticket === '' || randstr === '') {
    throw new CaptchaPairError(
      '行为验证的 ticket 与 randstr 必须成对发出,缺一个校验必然不通过(server: SmsSendRequest)',
    )
  }
  return { ticket, randstr }
}

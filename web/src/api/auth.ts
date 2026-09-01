import { ApiUnavailableError, getJson, postJson } from './client'

/**
 * 鉴权这一侧的接口与令牌保管。
 *
 * <h2>为什么令牌放 localStorage,不放 cookie</h2>
 *
 * 服务端的方案是不透明随机串 + `Authorization: Bearer`(server: docs/technical/INDEX.md §7.4),
 * 整条链路上<b>没有 cookie</b> —— `ApiCorsConfig` 里 `allowCredentials(false)` 就是这条的落点。
 * 那是有意的:没有 cookie 就没有 CSRF 这一整类问题,代价是 XSS 时令牌可读。
 * 而这个产品的 XSS 面本来就极小(不渲染任何用户提供的 HTML,记录里连富文本都没有)。
 */
const TOKEN_KEY = 'kaodian.auth.token'

export function readToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch {
    // 隐私模式 / 存储被禁。不抛 —— 拿不到令牌就是「没登录」,而不是「应用崩了」。
    return null
  }
}

export function writeToken(token: string | null) {
  try {
    if (token === null) localStorage.removeItem(TOKEN_KEY)
    else localStorage.setItem(TOKEN_KEY, token)
  } catch {
    /* 同上:存不下就当没登录,下次还得再登一次,但不该因此白屏 */
  }
}

// —— 接口 ——

export interface SmsSendResponse {
  expiresAt: string
  /**
   * 🔴 <b>只有本机开发模式才有</b>。
   *
   * server 的 `LoggingSmsSender` 不发真短信,把六位数字原样带回来 ——
   * 这正是这个原型<b>能被真的点完一遍</b>的原因:没有它,任何人想看登录流程
   * 都得先去开通短信服务、报备签名、充值。
   *
   * 真实供应商在用时它<b>永远是 null</b>(`SmsSender#isReal`),界面据此决定要不要显示那条提示。
   */
  devCode: string | null
}

export interface LoginResponse {
  token: string
  expiresAt: string
  userId: string
  isNewAccount: boolean
  maskedPhone: string | null
  needsPhoneBinding: boolean
  splitMergeToken: string | null
}

export interface AccountDto {
  userId: string
  nickname: string | null
  createdAt: string
  maskedPhone: string | null
  identities: string[]
  activeSessionCount: number
}

export function sendSmsCode(phone: string): Promise<SmsSendResponse> {
  return postJson<SmsSendResponse>('/auth/sms/send', {
    phone,
    purpose: 'login',
    // 🔴 本机开发时服务端的 captcha 是 disabled(一律放行),所以这里给什么都行。
    // 但字段必须传两个 —— 只传 ticket 是接腾讯云验证码时最常见的一个错,
    // 到了真实供应商那一侧会 100% 校验失败(server: SmsSendRequest 的类注释)。
    captchaTicket: 'dev',
    captchaRandstr: 'dev',
  })
}

export function verifySmsCode(phone: string, code: string, deviceLabel: string): Promise<LoginResponse> {
  return postJson<LoginResponse>('/auth/sms/verify', { phone, code, deviceLabel })
}

export function fetchAccount(): Promise<AccountDto> {
  return getJson<AccountDto>('/account')
}

export function logout(): Promise<{ revoked: boolean }> {
  return postJson<{ revoked: boolean }>('/auth/logout', {})
}

// —— 错误码 → 用户该做的事 ——

/**
 * 一次失败的三件事:说什么、能不能重试、以及<b>重试是「再输一次」还是「重发一条」</b>。
 *
 * <h2>🔴 后面这一条才是关键</h2>
 *
 * server 的 docs/technical/后端系统设计与组件接入.md §1.8 把它写死成一条判据:<b>五种失败要说五句不同的话</b>。
 * 而界面上真正兑现这句话的地方不是文案,是<b>那个按钮</b> ——
 *
 * | 状态 | 说的话 | 按钮 |
 * |---|---|---|
 * | 输错 | 还能再试 N 次 | 继续输 |
 * | 已过期 | 这个码过期了 | **重发一条** |
 * | 已作废 | 请用最新收到的那一条 | **不给按钮**(手机里已经有一条能用的) |
 * | 已用过 | 刚才已经登进去过一次 | **重发一条** |
 * | 频控/锁定 | 到几点几分再试 | 禁用,倒计时 |
 *
 * 把「已作废」也配一个「重发」按钮,用户会再发一条把手里刚收到的顶掉,
 * <b>然后陷在这个循环里</b> —— 这正是 server 那边修过一个真 bug 的地方。
 */
export interface CodeFailure {
  /** 摆在输入框下面那一行 */
  message: string
  /** 次要说明;没有就不显示 */
  hint?: string
  /** 该给「重发一条」按钮吗 */
  offerResend: boolean
  /** 输入框要不要清空并重新聚焦 */
  clearInput: boolean
  /** 整条通道被锁住了 —— 连「重发」都不给 */
  blocked: boolean
}

export function readCodeFailure(err: unknown): CodeFailure {
  const e = err instanceof ApiUnavailableError ? err : null
  const raw = e?.reason ?? (err instanceof Error ? err.message : String(err))
  // 服务端的 message 已经是给人看的整句(含准确时点),把尾巴上的 (HTTP 4xx) 去掉就能直接用。
  const message = raw.replace(/(HTTP \d{3})/g, '').replace(/[（(]\s*[）)]/g, '').trim()

  switch (e?.code) {
    case 'CODE_WRONG':
      return { message, offerResend: false, clearInput: true, blocked: false }
    case 'CODE_EXPIRED':
      return { message, hint: '重发一条新的', offerResend: true, clearInput: true, blocked: false }
    case 'CODE_SUPERSEDED':
      // 🔴 不给重发。手机里已经躺着一条能用的 —— 再发一条只会把它也顶掉。
      return {
        message,
        hint: '你刚才又要了一次验证码,手机里最新那条才是有效的',
        offerResend: false,
        clearInput: true,
        blocked: false,
      }
    case 'CODE_NONE':
      return { message, hint: '先获取一条', offerResend: true, clearInput: true, blocked: false }
    case 'PHONE_LOCKED':
    case 'SMS_TOO_FREQUENT':
    case 'SMS_PHONE_DAILY_LIMIT':
    case 'SMS_IP_DAILY_LIMIT':
      // 服务端给的是准确时点(不是「请稍后再试」),原样摆出来
      return { message, offerResend: false, clearInput: false, blocked: true }
    case 'CAPTCHA_FAILED':
      return { message, offerResend: true, clearInput: false, blocked: false }
    case 'BAD_PHONE':
      return { message, offerResend: false, clearInput: false, blocked: false }
    default:
      return { message, offerResend: true, clearInput: false, blocked: false }
  }
}

/** 这台设备叫什么。认不出来就叫「未知设备」—— 认不出设备不能成为登不进去的理由。 */
export function deviceLabel(): string {
  const ua = navigator.userAgent
  const os = /Mac/.test(ua) ? 'Mac' : /Windows/.test(ua) ? 'Windows' : /Android/.test(ua) ? 'Android'
    : /iPhone|iPad/.test(ua) ? 'iOS' : '未知系统'
  const browser = /Edg\//.test(ua) ? 'Edge' : /Chrome\//.test(ua) ? 'Chrome'
    : /Safari\//.test(ua) ? 'Safari' : /Firefox\//.test(ua) ? 'Firefox' : '浏览器'
  return `${os} · ${browser}`
}

/**
 * 行为验证的<b>控件层</b> —— `captchaPolicy.ts` 里 {@link CaptchaOpener} 的真实实现。
 *
 * <h2>这个文件里<b>没有任何判断</b>,这是刻意的</h2>
 *
 * 「这次算不算通过」「容灾票据要不要放行」「只回来一半怎么办」全在 `captchaPolicy.ts` ——
 * 那一层能在 node 里被跑(`tests/captchaPolicy.test.ts`)。这一层碰真控件、跑不进测试,
 * 所以它<b>不许有可以出错的逻辑</b>:插一个 `<script>`、`new` 一下、把回调原样递回去,
 * 三件事各自只是一次调用。一旦有人在这里写下
 * `if (res.ret === 0) return true`,那条判断就掉进了没有测试的那半边。
 *
 * <h2>🔴 脚本按需加载,不写进 `index.html`</h2>
 *
 * 写进 `index.html` 意味着<b>每一个访客</b>一开屏就向 `turing.captcha.qcloud.com` 发一次请求,
 * 包括从头到尾没点过「获取验证码」的那些人。而这个产品在原图那条线上的立场是
 * 「用户的东西留在用户的机器上」——同一条立场没有理由在这里松一格。
 * 代价是第一次点「获取验证码」要多等一次脚本下载,已经由 {@link SCRIPT_TIMEOUT_MS} 兜住。
 */

import { CAPTCHA_SCRIPT_URL, readCaptchaMode } from './captchaPolicy'
import type { CaptchaMode } from './captchaPolicy'

/**
 * 脚本下载的等待上限。
 *
 * <p>它兜的不是慢,是<b>永远不返回</b>:第三方域名被墙、被企业代理黑洞掉时,
 * `<script>` 的 `onerror` 可能一直不触发,于是那颗按钮会一直转下去 ——
 * 而「按钮没反应」正是本议题第 4 条点名要消掉的表现。
 * <p>比 `client.ts` 的 2 秒宽:那边连的是同一台机器上的另一个进程,这边是公网 CDN。
 */
const SCRIPT_TIMEOUT_MS = 8000

/** 厂商控件的构造器。形状取自厂商文档,只声明这里真的会用到的两个成员。 */
type TencentCaptchaCtor = new (
  appId: string,
  callback: (res: unknown) => void,
  options: Record<string, unknown>,
) => { show: () => void }

/** 这次构建的模式,只算一次 —— 环境变量在运行期不会变。 */
export const CAPTCHA_MODE: CaptchaMode = readCaptchaMode(
  import.meta.env as unknown as Record<string, string | undefined>,
)

let pending: Promise<void> | null = null

/**
 * 把控件脚本插进页面,只插一次。
 *
 * <p>失败时把缓存的 promise 清掉,于是用户按「再试一次」是真的再下载一次,
 * 而不是拿到同一个已经 reject 的 promise。
 */
function loadScript(): Promise<void> {
  if (pending !== null) return pending

  pending = new Promise<void>((resolve, reject) => {
    const el = document.createElement('script')
    let timer = 0

    const settle = (err: Error | null) => {
      clearTimeout(timer)
      if (err === null) {
        resolve()
        return
      }
      pending = null
      reject(err)
    }

    timer = setTimeout(() => settle(new Error(`等了 ${SCRIPT_TIMEOUT_MS} ms 没下下来`)), SCRIPT_TIMEOUT_MS)
    el.src = CAPTCHA_SCRIPT_URL
    el.async = true
    el.onload = () => settle(null)
    el.onerror = () => settle(new Error('脚本没能加载'))
    document.head.appendChild(el)
  })

  return pending
}

/**
 * 弹出验证框,把厂商回调<b>原样</b>递回判据层。
 *
 * <p>🔴 返回类型是 `unknown`,而且这里一个字段都不读。读它是 `classifyCaptchaResult`
 * 的事 —— 在这里顺手判一下 `res.ret`,那一行就成了整条链路上唯一没有测试覆盖的判断。
 */
export async function openTCaptcha(appId: string): Promise<unknown> {
  await loadScript()

  const ctor = (window as unknown as { TencentCaptcha?: TencentCaptchaCtor }).TencentCaptcha
  if (typeof ctor !== 'function') {
    throw new Error('脚本加载完了但 window.TencentCaptcha 不在')
  }

  return await new Promise<unknown>((resolve, reject) => {
    try {
      const widget = new ctor(appId, resolve, {})
      widget.show()
    } catch (err) {
      reject(err instanceof Error ? err : new Error(String(err)))
    }
  })
}

/**
 * 行为验证判据层的断言 —— `KUBI-75`,docs/后端详设 §1.8 第①道闸的前端一侧。
 *
 * <h2>这个文件存在的理由</h2>
 *
 * 腾讯云验证码产品<b>尚未开通</b>(它与短信是两个相互独立的产品),没有可用的
 * `CaptchaAppId` + `AppSecretKey`,所以这条链路<b>没有、也不可能有真机联调</b>。
 * 而「接完了没有」这个问题在真凭据到手之前唯一能被回答的方式,就是把
 * 「拿到什么样的回调算通过」写成一组能反复跑的断言。
 * <p>
 * 被测的是 `src/lib/captchaPolicy.ts` —— 那一层里一个 DOM 符号都没有,
 * 它只认一个注入的 {@link CaptchaOpener}。真正插 `<script>`、`new TencentCaptcha(...)`
 * 的 `src/lib/tcaptcha.ts` 里则一句判断都没有。两层加起来,没有一处判断落在测试之外。
 *
 * <h2>🔴 它们红过</h2>
 *
 * 逐条改判据跑过一遍,每一条都当场失败,改回即恢复绿色。最要紧的两条:
 * <ul>
 *   <li>把容灾票据那一支去掉 → 「容灾票据不算通过」报
 *       `Expected values to be strictly equal: 'pass' !== 'unavailable'`</li>
 *   <li>把「一半凭据」那一支去掉 → 「只回来 ticket」同样报 `'pass' !== 'unavailable'`,
 *       也就是那张只有一半的凭据会被真的发出去 —— 正是 server 侧
 *       `SmsSendRequest` 类注释点名的那个经典错</li>
 * </ul>
 *
 * <h2>零新增依赖</h2>
 *
 * 与 `rawImageCache.test.ts` 同一条路:`node:test` + `node:assert`,
 * node 自带的 TypeScript 类型擦除直接跑 `.ts`。`package.json` 一个字没动 ——
 * `test:retention` 那行的 glob 本来就把这个目录整个收进来了。
 */

import assert from 'node:assert/strict'
import test from 'node:test'
import {
  BYPASS_PAIR,
  CAPTCHA_APP_ID_ENV_KEY,
  CaptchaPairError,
  classifyCaptchaResult,
  isDisasterTicket,
  readCaptchaMode,
  requestCaptcha,
  requirePair,
} from '../src/lib/captchaPolicy.ts'
import type { CaptchaMode, CaptchaOpener } from '../src/lib/captchaPolicy.ts'

/* ========================================================================== */
/* readCaptchaMode —— 三种,不是两种                                            */
/* ========================================================================== */

test('没配 app-id → bypass(本机开发照样能把登录点完)', () => {
  assert.deepEqual(readCaptchaMode({}), { kind: 'bypass' })
  assert.deepEqual(readCaptchaMode({ [CAPTCHA_APP_ID_ENV_KEY]: '' }), { kind: 'bypass' })
  assert.deepEqual(readCaptchaMode({ [CAPTCHA_APP_ID_ENV_KEY]: '   ' }), { kind: 'bypass' })
})

test('配了一串数字 → vendor,并且首尾空白被吃掉(.env 里粘贴最常见的残留)', () => {
  assert.deepEqual(readCaptchaMode({ [CAPTCHA_APP_ID_ENV_KEY]: ' 190000001 ' }), {
    kind: 'vendor',
    appId: '190000001',
  })
})

test('🔴 配了但不是数字 → misconfigured,【不能】退回 bypass', () => {
  // 退回 bypass 的后果:本机点得通(服务端 disabled 一律放行),上线当天每一次
  // 发送都 400。而那时错误信息指向的是服务端,没有人会想到来看这个环境变量。
  for (const bad of ['你的AppId', '"190000001"', '19000 0001', 'abc']) {
    const mode = readCaptchaMode({ [CAPTCHA_APP_ID_ENV_KEY]: bad })
    assert.equal(mode.kind, 'misconfigured', `「${bad}」应当被判成配置有误`)
    if (mode.kind === 'misconfigured') {
      // 消息里必须点名那个键 —— 只说「配置错误」等于让人去翻源码。
      assert.ok(mode.reason.includes(CAPTCHA_APP_ID_ENV_KEY), `实际是:${mode.reason}`)
    }
  }
})

/* ========================================================================== */
/* classifyCaptchaResult —— 整个前端接入里唯一有判断的地方                        */
/* ========================================================================== */

test('ret=0 + 两个值都在 → pass,而且两个都被带出来', () => {
  const out = classifyCaptchaResult({ ret: 0, ticket: 't-abc', randstr: 'r-xyz' })
  assert.equal(out.kind, 'pass')
  if (out.kind === 'pass') {
    assert.deepEqual(out.pair, { ticket: 't-abc', randstr: 'r-xyz' })
    // 🔴 这不是占位串,是真的验过了 —— 界面据此决定要不要摆「未接入」那一格。
    assert.equal(out.bypassed, false)
  }
})

test('ret=2 → closed。用户关掉验证框是【取消】,不是失败', () => {
  assert.deepEqual(classifyCaptchaResult({ ret: 2 }), { kind: 'closed' })
})

test('🔴 ret=0 但票据带容灾前缀 → unavailable。它的 ret 就是 0,只看 ret 会把它放过去', () => {
  // 厂商的建议做法是服务异常时伪造一张 trerror_ 票据并以 ret:0 回调,
  // 「确保业务流程不被阻塞」。这条链路上不能照办:滑块是唯一真正的闸,
  // 它一没,短信费就没有上限(server: TencentCaptchaVerifier 的类注释)。
  for (const t of ['trerror_1001_190000001_1756600000', 'terror_1001_190000001_1756600000']) {
    assert.ok(isDisasterTicket(t))
    const out = classifyCaptchaResult({ ret: 0, ticket: t, randstr: 'r-xyz', errorCode: 1001 })
    assert.equal(out.kind, 'unavailable', `容灾票据 ${t} 不算通过`)
  }
})

test('🔴 ret=0 但只回来一半 → unavailable。「只传 ticket」那个经典错在这里被挡住', () => {
  const onlyTicket = classifyCaptchaResult({ ret: 0, ticket: 't-abc' })
  assert.equal(onlyTicket.kind, 'unavailable')

  const onlyRandstr = classifyCaptchaResult({ ret: 0, randstr: 'r-xyz' })
  assert.equal(onlyRandstr.kind, 'unavailable')

  const blank = classifyCaptchaResult({ ret: 0, ticket: 't-abc', randstr: '   ' })
  assert.equal(blank.kind, 'unavailable')
})

test('ret 是别的值 → unavailable,并且把厂商那句话原样摆出来', () => {
  const out = classifyCaptchaResult({ ret: 1, errorMessage: '验证码已过期' })
  assert.equal(out.kind, 'unavailable')
  if (out.kind === 'unavailable') {
    assert.equal(out.reason, '验证码已过期')
    assert.equal(out.retryable, true)
  }
})

test('🔴 读不懂的回调 → unavailable。存疑就不通过,不是存疑就放行', () => {
  for (const raw of [null, undefined, 'ok', 42, []]) {
    const out = classifyCaptchaResult(raw)
    assert.notEqual(out.kind, 'pass', `${JSON.stringify(raw)} 不该被当成通过`)
  }
})

/* ========================================================================== */
/* requestCaptcha —— 三条分支,控件只在第一条里被碰到                            */
/* ========================================================================== */

/** 记下被调用过没有。有两条断言问的正是「有没有去碰控件」。 */
function spyOpener(result: unknown | Error): { open: CaptchaOpener; calls: string[] } {
  const calls: string[] = []
  const open: CaptchaOpener = (appId) => {
    calls.push(appId)
    return result instanceof Error ? Promise.reject(result) : Promise.resolve(result)
  }
  return { open, calls }
}

test('bypass → 发占位串,并且【标明没有真的验过】;控件一次都没碰', async () => {
  const spy = spyOpener({})
  const out = await requestCaptcha({ kind: 'bypass' }, spy.open)
  assert.equal(out.kind, 'pass')
  if (out.kind === 'pass') {
    assert.deepEqual(out.pair, BYPASS_PAIR)
    // 🔴 这一位是界面上那格「未接入」的唯一来源。它要是丢了,一个安静的旁路
    // 和一个接通了的滑块在屏幕上就长得一模一样了。
    assert.equal(out.bypassed, true)
  }
  assert.deepEqual(spy.calls, [])
})

test('misconfigured → unavailable 且【不可重试】;控件一次都没碰', async () => {
  const mode: CaptchaMode = { kind: 'misconfigured', reason: '某某键不是一串数字' }
  const spy = spyOpener({})
  const out = await requestCaptcha(mode, spy.open)
  assert.equal(out.kind, 'unavailable')
  if (out.kind === 'unavailable') {
    // 再点一百次也不会变 —— 要改的是环境变量。给「再试一次」是在骗人。
    assert.equal(out.retryable, false)
    assert.equal(out.reason, '某某键不是一串数字')
  }
  assert.deepEqual(spy.calls, [])
})

test('vendor → 把 app-id 递给控件,并按回调判定', async () => {
  const spy = spyOpener({ ret: 0, ticket: 't-abc', randstr: 'r-xyz' })
  const out = await requestCaptcha({ kind: 'vendor', appId: '190000001' }, spy.open)
  assert.deepEqual(spy.calls, ['190000001'])
  assert.equal(out.kind, 'pass')
})

test('🔴 控件加载不出来 → unavailable + 可重试,而【不是】伪造一张容灾票据', async () => {
  const spy = spyOpener(new Error('脚本没能加载'))
  const out = await requestCaptcha({ kind: 'vendor', appId: '190000001' }, spy.open)
  assert.equal(out.kind, 'unavailable')
  if (out.kind === 'unavailable') {
    assert.equal(out.retryable, true)
    // 原因要能指回控件本身。少了这句,用户看到的就是「按钮没反应」。
    assert.ok(out.reason.includes('脚本没能加载'), `实际是:${out.reason}`)
  }
})

/* ========================================================================== */
/* requirePair —— 发出去之前最后一道                                            */
/* ========================================================================== */

test('两个都在 → 放行,并且顺手去掉首尾空白', () => {
  assert.deepEqual(requirePair({ ticket: ' t-abc ', randstr: ' r-xyz ' }), {
    ticket: 't-abc',
    randstr: 'r-xyz',
  })
})

test('🔴 缺一个就当场抛,不让它走到服务端换回一句 CAPTCHA_FAILED', () => {
  // 那句话是给「用户没验过」准备的。拿它盖前端的漏传,排查会被引到完全错误的方向。
  assert.throws(() => requirePair({ ticket: 't-abc', randstr: '' }), CaptchaPairError)
  assert.throws(() => requirePair({ ticket: '', randstr: 'r-xyz' }), CaptchaPairError)
  assert.throws(() => requirePair({ ticket: '  ', randstr: '  ' }), CaptchaPairError)
})

import { useCallback, useEffect, useRef, useState } from 'react'
import {
  type CodeFailure,
  type LoginResponse,
  deviceLabel,
  readCodeFailure,
  sendSmsCode,
  verifySmsCode,
  writeToken,
} from '../api/auth'
import { requestCaptcha } from '../lib/captchaPolicy'
import { CAPTCHA_MODE, openTCaptcha } from '../lib/tcaptcha'
import { Button, Kbd, Note, Tag } from '../ui/primitives'

/**
 * 登录 —— 设计稿 D1 的正常态 + D25 的五种失败态,合成<b>一个能真的点完的东西</b>。
 *
 * <h2>为什么把 D25 那五种失败做进来,而不是只做正常态</h2>
 *
 * 因为那五种<b>才是这一屏存在的理由</b>。docs/technical/后端系统设计与组件接入.md §1.8:
 * 「合并成一句『验证码错误』的代价是 —— 用户拿着过期的码反复输,把自己输到锁定」。
 * 而一张静态图证明不了这件事:图上四句话都写着,真跑起来可能全都回同一句。
 * <p>
 * 接上真实后端之后,这五句话是<b>后端逐个错误码给出来的</b> —— 界面只做翻译,不自己编。
 * 想看哪一种就真的去触发它:连点两次「重发」会得到「已作废」,等五分钟会得到「已过期」,
 * 错五次会得到「锁定 + 准确解锁时点」。
 *
 * <h2>没有「注册」这个按钮</h2>
 *
 * 验证码通过那一刻,注册和登录是同一件事(docs/technical/后端系统设计与组件接入.md §1.7)。
 * 少一个页面是次要的,<b>少一个「我到底注册过没有」的犹豫才是主要的</b> ——
 * 而这个犹豫恰好发生在用户离开成本最低的那一秒。
 *
 * <h2>🔴 「获取验证码」现在是<b>两步</b>:先过滑块,再发短信</h2>
 *
 * 滑块是这条链路上唯一真正的闸(单号 1/60s 与单 IP 20/日 都是纯计数,
 * 换一批号换一批 IP 都不触发)。它在界面上的形态是:那颗按钮按下去<b>先弹一个框</b>。
 * <p>
 * 由此多出两种此前不存在的中间态,而它们都<b>不是</b>「发送失败」:
 * <ul>
 *   <li><b>用户把框关掉</b> —— 一次取消,灰字,按钮回到可按。红字会让人以为自己做错了什么</li>
 *   <li><b>控件没加载出来</b> —— 说清楚是控件的事,并且给「再点一次」</li>
 * </ul>
 * 两种在改动之前都会表现成<b>按钮没反应</b>,而那是这一屏最不能出现的状态:
 * 用户唯一能做的下一步是关掉页面。
 */
export function LoginGate({ onDone }: { onDone: (r: LoginResponse) => void }) {
  const [step, setStep] = useState<'phone' | 'code'>('phone')
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  /**
   * 忙在<b>哪一步</b>,不只是「忙不忙」。
   *
   * <p>滑块弹出来的那段时间里按钮写着「发送中…」是错的:那一刻什么都没在发,
   * 在等的是用户自己拖那一下。而用户会照着按钮上的字判断该不该继续等。
   */
  const [stage, setStage] = useState<null | 'captcha' | 'sending'>(null)
  const [failure, setFailure] = useState<CodeFailure | null>(null)
  /** 不是失败的那一类中间态(目前只有「用户关掉了验证框」)。灰字,不是红字。 */
  const [notice, setNotice] = useState<string | null>(null)
  const [devCode, setDevCode] = useState<string | null>(null)
  const [cooldown, setCooldown] = useState(0)
  const codeRef = useRef<HTMLInputElement>(null)
  const busy = stage !== null

  // 60 秒重发倒计时。服务端那一侧是硬约束(单号 1/60s),这里只是别让用户白点。
  useEffect(() => {
    if (cooldown <= 0) return
    const t = setTimeout(() => setCooldown((c) => c - 1), 1000)
    return () => clearTimeout(t)
  }, [cooldown])

  useEffect(() => {
    if (step === 'code') codeRef.current?.focus()
  }, [step])

  /**
   * 先过滑块,再发短信。
   *
   * <p>🔴 <b>每一次发送都要重新过一遍</b>,包括「重发一条」——票据是一次性的,
   * 复用上一张的结果是服务端判不通过,而那句话会被读成「验证码错了」。
   */
  const send = useCallback(async () => {
    setFailure(null)
    setNotice(null)
    setStage('captcha')
    try {
      const outcome = await requestCaptcha(CAPTCHA_MODE, openTCaptcha)

      if (outcome.kind === 'closed') {
        // 取消不是失败。一个字的红字都不给,按钮回到可按就够了。
        setNotice('验证取消了,再点一次可以重来。')
        return
      }
      if (outcome.kind === 'unavailable') {
        // 🔴 一次都没发出去 —— 没有票据就不发请求,而不是发一个注定 400 的。
        setFailure({
          message: outcome.reason,
          hint: outcome.retryable ? '再点一次「获取验证码」' : '这是构建配置的问题,再点也不会变',
          offerResend: outcome.retryable,
          clearInput: false,
          blocked: false,
        })
        return
      }

      setStage('sending')
      const r = await sendSmsCode(phone, outcome.pair)
      setDevCode(r.devCode)
      setCooldown(60)
      setStep('code')
      setCode('')
    } catch (err) {
      setFailure(readCodeFailure(err))
    } finally {
      setStage(null)
    }
  }, [phone])

  const verify = useCallback(
    async (value: string) => {
      setStage('sending')
      setFailure(null)
      setNotice(null)
      try {
        const r = await verifySmsCode(phone, value, deviceLabel())
        writeToken(r.token)
        onDone(r)
      } catch (err) {
        const f = readCodeFailure(err)
        setFailure(f)
        if (f.clearInput) {
          setCode('')
          codeRef.current?.focus()
        }
      } finally {
        setStage(null)
      }
    },
    [phone, onDone],
  )

  // 六位填满就自动提交 —— 用户输完最后一位还要再找一个按钮,是这一步最没必要的一次停顿。
  const onCodeChange = (v: string) => {
    const digits = v.replace(/\D/g, '').slice(0, 6)
    setCode(digits)
    if (digits.length === 6 && !busy) void verify(digits)
  }

  const phoneOk = /^1[3-9]\d{9}$/.test(phone.replace(/\s/g, ''))

  return (
    <div className="kb-overlay kb-gate flex h-dvh w-full items-center justify-center bg-bg">
      <div className="kb-gate-col">
        <header className="mb-8">
          <div className="flex items-baseline gap-3">
            <span className="font-mono text-[13px] text-acid">考点盲区</span>
            <span className="text-[12px] text-t3">跨来源记学习</span>
          </div>
          {/* 开屏不放插画、不放价值主张三连,直接把公式摆出来 —— 看不懂公式的人本来也不是这个产品的人 */}
          <p className="mt-3 font-mono text-[12px] leading-6 text-t2">
            盲区 = 骨架层 − 行为层
          </p>
        </header>

        {step === 'phone' ? (
          <form
            onSubmit={(e) => {
              e.preventDefault()
              if (phoneOk && !busy) void send()
            }}
          >
            {/* 逐字对稿 `design/m5/01-gate.html:34-35`:label 是「手机」,placeholder 才是「手机号」。
                aria-label 不能省 —— 稿上写着,而且这一屏只有两个可输入元素,读屏靠它区分。 */}
            <label htmlFor="kb-gate-tel" className="mb-2 block text-[11px] tracking-wide text-t3">
              手机
            </label>
            <input
              autoFocus
              id="kb-gate-tel"
              inputMode="numeric"
              autoComplete="tel"
              aria-label="手机号"
              placeholder="手机号"
              value={phone}
              onChange={(e) => setPhone(e.target.value.replace(/[^\d\s]/g, '').slice(0, 13))}
              className="w-full rounded-sm border border-hair bg-bg2 px-4 py-3 font-mono text-[15px] tracking-[0.08em] text-tx outline-none focus:border-acid"
            />
            {failure ? <Failure f={failure} /> : null}
            {notice ? <Notice text={notice} /> : null}
            <div className="mt-5">
              <Button
                variant="primary"
                size="lg"
                block
                disabled={!phoneOk || busy}
                onClick={() => {
                  if (phoneOk && !busy) void send()
                }}
              >
                {stage === 'captcha' ? '等你完成验证…' : stage === 'sending' ? '发送中…' : '获取验证码'}
              </Button>
            </div>
            <CaptchaModeNote />
            <div className="mt-5">
              <Note>
              没有单独的注册页。号码没见过就建账号,见过就登进去 —— 注册和登录是同一件事。
              </Note>
            </div>
          </form>
        ) : (
          <div>
            <div className="mb-2 flex items-baseline justify-between">
              <label className="text-[11px] tracking-wide text-t3">验证码</label>
              <button
                type="button"
                onClick={() => {
                  setStep('phone')
                  setFailure(null)
                }}
                className="font-mono text-[11px] text-t3 hover:text-tx"
              >
                {phone} · 改号码
              </button>
            </div>
            <input
              ref={codeRef}
              inputMode="numeric"
              placeholder="6 位数字"
              value={code}
              disabled={busy || failure?.blocked}
              onChange={(e) => onCodeChange(e.target.value)}
              className="w-full rounded-sm border border-hair bg-bg2 px-4 py-3 text-center font-mono text-[22px] tracking-[0.5em] text-tx outline-none focus:border-acid disabled:opacity-40"
            />

            {failure ? <Failure f={failure} /> : null}
            {notice ? <Notice text={notice} /> : null}

            <div className="mt-4 flex items-center justify-between">
              {/* 🔴 这个按钮的有无,就是「五句话」在界面上的落点 —— 见 api/auth.ts 的 CodeFailure */}
              {failure?.blocked ? (
                <span className="font-mono text-[11px] text-t3">这条通道暂时被锁住了</span>
              ) : (
                <button
                  type="button"
                  disabled={cooldown > 0 || busy || failure?.offerResend === false}
                  onClick={() => void send()}
                  className="font-mono text-[12px] text-acid disabled:text-t3"
                >
                  {/* 逐字对稿 `design/m5/02-gate-sent.html:58`:「52 秒后可以重发」。 */}
                  {cooldown > 0 ? `${cooldown} 秒后可以重发` : '重发一条'}
                </button>
              )}
              {busy ? (
                <span className="font-mono text-[11px] text-t3">
                  {stage === 'captcha' ? '等你完成验证…' : '校验中…'}
                </span>
              ) : null}
            </div>

            {devCode ? (
              <div className="mt-6 rounded-sm border border-dashed border-hair2 p-3">
                <div className="flex items-center gap-2">
                  <Tag tone="warn">开发模式</Tag>
                  <span className="font-mono text-[15px] tracking-[0.3em] text-acid">
                    {devCode}
                  </span>
                  <button
                    type="button"
                    onClick={() => onCodeChange(devCode)}
                    className="ml-auto font-mono text-[11px] text-t3 hover:text-tx"
                  >
                    填入
                  </button>
                </div>
                <div className="mt-2">
                  <Note>
                  没发真短信 —— 后端 <span className="font-mono">sms.provider=logging</span>。
                  切到真实供应商后这一格<b>永远不会出现</b>。
                  </Note>
                </div>
              </div>
            ) : null}

            <div className="mt-6">
              <Note>
              想看失败态就真的去触发:连点两次「重发」→ 已作废;错五次 → 锁定 30 分钟并给出准确解锁时点。
              </Note>
            </div>
          </div>
        )}

        <footer className="mt-10 flex items-center gap-2 text-[11px] text-t3">
          <Kbd>↵</Kbd>
          <span>继续</span>
          <span className="ml-auto">登录即同意用户协议与隐私政策</span>
        </footer>
      </div>
    </div>
  )
}

/**
 * 🔴 这次构建到底有没有接上滑块 —— <b>摆在按钮下面,不写在 README 里</b>。
 *
 * <p>没配 `CaptchaAppId` 时前端发的是占位串,而服务端
 * `kaodian.auth.captcha.provider=disabled` 一律放行,于是整条流程<b>点得完</b>。
 * 点得完和接通了在屏幕上长得一模一样,而这两者的差别是「短信费有没有上限」——
 * 所以这一格必须存在,理由和 `devCode` 那一格是同一条:
 * <b>本机开发的便利不该以「看不出它是本机开发」为代价。</b>
 *
 * <p>接上之后(`vendor`)这一格<b>不显示任何东西</b>:滑块本身会弹出来,那就是证据。
 */
function CaptchaModeNote() {
  if (CAPTCHA_MODE.kind === 'vendor') return null
  return (
    <div className="mt-4 rounded-sm border border-dashed border-hair2 p-3">
      <div className="flex items-center gap-2">
        <Tag tone="warn">未接入</Tag>
        <span className="font-mono text-[11px] text-t3">
          {CAPTCHA_MODE.kind === 'bypass' ? '行为验证' : '行为验证配置有误'}
        </span>
      </div>
      <div className="mt-2">
        <Note>
        {CAPTCHA_MODE.kind === 'bypass' ? (
          <>
          这次构建没有配 <span className="font-mono">VITE_KAODIAN_CAPTCHA_APP_ID</span>,
          发出去的是占位串,<b>没有真的验过</b>。它能走通只是因为后端
          <span className="font-mono"> captcha.provider=disabled</span> 一律放行。
          </>
        ) : (
          CAPTCHA_MODE.reason
        )}
        </Note>
      </div>
    </div>
  )
}

/** 不是失败的那一类中间态。灰字 —— 红字会让用户以为自己做错了什么。 */
function Notice({ text }: { text: string }) {
  return <p className="mt-3 text-[11px] leading-5 text-t3">{text}</p>
}

/** 失败那一行。红字说发生了什么,灰字说该做什么 —— 两句分开,因为它们是两件事。 */
function Failure({ f }: { f: CodeFailure }) {
  return (
    <div className="mt-3">
      <p className="font-mono text-[12px] leading-5 text-red">{f.message}</p>
      {f.hint ? <p className="mt-1 text-[11px] text-t3">{f.hint}</p> : null}
    </div>
  )
}

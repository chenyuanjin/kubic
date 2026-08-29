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
import { Button, Kbd, Note, Tag } from '../ui/primitives'

/**
 * 登录 —— 设计稿 D1 的正常态 + D25 的五种失败态,合成<b>一个能真的点完的东西</b>。
 *
 * <h2>为什么把 D25 那五种失败做进来,而不是只做正常态</h2>
 *
 * 因为那五种<b>才是这一屏存在的理由</b>。docs/13 §1.8:
 * 「合并成一句『验证码错误』的代价是 —— 用户拿着过期的码反复输,把自己输到锁定」。
 * 而一张静态图证明不了这件事:图上四句话都写着,真跑起来可能全都回同一句。
 * <p>
 * 接上真实后端之后,这五句话是<b>后端逐个错误码给出来的</b> —— 界面只做翻译,不自己编。
 * 想看哪一种就真的去触发它:连点两次「重发」会得到「已作废」,等五分钟会得到「已过期」,
 * 错五次会得到「锁定 + 准确解锁时点」。
 *
 * <h2>没有「注册」这个按钮</h2>
 *
 * 验证码通过那一刻,注册和登录是同一件事(docs/13 §1.7)。
 * 少一个页面是次要的,<b>少一个「我到底注册过没有」的犹豫才是主要的</b> ——
 * 而这个犹豫恰好发生在用户离开成本最低的那一秒。
 */
export function LoginGate({ onDone }: { onDone: (r: LoginResponse) => void }) {
  const [step, setStep] = useState<'phone' | 'code'>('phone')
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<CodeFailure | null>(null)
  const [devCode, setDevCode] = useState<string | null>(null)
  const [cooldown, setCooldown] = useState(0)
  const codeRef = useRef<HTMLInputElement>(null)

  // 60 秒重发倒计时。服务端那一侧是硬约束(单号 1/60s),这里只是别让用户白点。
  useEffect(() => {
    if (cooldown <= 0) return
    const t = setTimeout(() => setCooldown((c) => c - 1), 1000)
    return () => clearTimeout(t)
  }, [cooldown])

  useEffect(() => {
    if (step === 'code') codeRef.current?.focus()
  }, [step])

  const send = useCallback(async () => {
    setBusy(true)
    setFailure(null)
    try {
      const r = await sendSmsCode(phone)
      setDevCode(r.devCode)
      setCooldown(60)
      setStep('code')
      setCode('')
    } catch (err) {
      setFailure(readCodeFailure(err))
    } finally {
      setBusy(false)
    }
  }, [phone])

  const verify = useCallback(
    async (value: string) => {
      setBusy(true)
      setFailure(null)
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
        setBusy(false)
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
    <div className="flex h-dvh w-full items-center justify-center bg-bg px-6">
      <div className="w-full max-w-[420px]">
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
            <label className="mb-2 block text-[11px] tracking-wide text-t3">手机号</label>
            <input
              autoFocus
              inputMode="numeric"
              placeholder="138 0013 8000"
              value={phone}
              onChange={(e) => setPhone(e.target.value.replace(/[^\d\s]/g, '').slice(0, 13))}
              className="w-full rounded-sm border border-hair bg-bg2 px-4 py-3 font-mono text-[15px] tracking-[0.08em] text-tx outline-none focus:border-acid"
            />
            {failure ? <Failure f={failure} /> : null}
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
                {busy ? '发送中…' : '获取验证码'}
              </Button>
            </div>
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
                  {cooldown > 0 ? `${cooldown} s 后可重发` : '重发一条'}
                </button>
              )}
              {busy ? <span className="font-mono text-[11px] text-t3">校验中…</span> : null}
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

/** 失败那一行。红字说发生了什么,灰字说该做什么 —— 两句分开,因为它们是两件事。 */
function Failure({ f }: { f: CodeFailure }) {
  return (
    <div className="mt-3">
      <p className="font-mono text-[12px] leading-5 text-red">{f.message}</p>
      {f.hint ? <p className="mt-1 text-[11px] text-t3">{f.hint}</p> : null}
    </div>
  )
}

import { useCallback, useEffect, useState } from 'react'
import { ACCEPTED_IMAGE_MIME, mib, rejectionFor } from '../api/recognize'
import { RawImageExpiryError, remainingMs } from '../lib/rawImageCache'
import type { RawImageMeta } from '../lib/rawImageCache'
import { rawImages } from '../lib/rawImageStore'
import { Button, GlyphIcon, GroupHeader, Note, Tag } from '../ui/primitives'

/**
 * 「拖张图进来」—— docs/execution/INDEX.md `1.1.3` 在界面上的三处表达。
 *
 * <h2>docs/execution/INDEX.md 的 UI 审核项逐条落在哪</h2>
 *
 * <table border="1">
 *   <tr><th>审核项</th><th>在这一屏的哪里</th></tr>
 *   <tr><td>① 同意点</td><td>{@link ConsentGate} —— <b>第一次</b>导图时挡在前面,
 *       明写「存在这台设备上、N 小时后转进留存区」。不点「知道了」就一张图都不收</td></tr>
 *   <tr><td>② 原图删除倒计时</td><td>缓存列表每行右侧的「还有 5 小时 42 分」,每秒走一格</td></tr>
 *   <tr><td>③ 立即删除入口</td><td>每行一个「删」,列表头一个「全部删除」</td></tr>
 * </table>
 *
 * <h2>🔴 这一屏收下的图,<b>唯一的落点是本地缓存</b></h2>
 *
 * 没有第二份副本:图不进 React state、不做 `URL.createObjectURL`、不渲染缩略图。
 * 理由不是省事 ——
 * <ul>
 *   <li>一个躺在组件 state 里的 `File` 是<b>一份没有过期戳的原图副本</b>。
 *       这条红线的整个形状就是「每一份活着的原图都带着一个到期时刻」,
 *       多留一份就多一份不带戳的</li>
 *   <li>`createObjectURL` 产出的是一个 <b>URL</b>。它只在本页面活着、不是外链,
 *       但 docs/technical/INDEX.md §8.1 禁令 4 是「不做<b>任何形式</b>的图片分享/外链」,
 *       而缩略图带来的好处(认出是哪一张)用文件名 + 体积就够了。
 *       <b>为一点便利去贴着那条线走,不划算</b></li>
 * </ul>
 * 于是「送去识别」那一步是从缓存里读回来的({@link rawImages} 的 `read`),
 * 不是从界面 state 里拿的。
 *
 * <h2>🔴 本地缓存用不了的时候,这里<b>不收图</b></h2>
 *
 * 隐私模式 / IndexedDB 被禁用时,存不进去就等于「存不下过期戳」。
 * 那种情况下最自然的写法是「先放内存里,反正服务端不落盘」——
 * 而那正好造出这条链路上唯一不该存在的东西:<b>一张没有到期时刻的原图</b>。
 * 所以宁可这一屏收不了图,并把原因原样说出来。
 */
export function RawImageDrop({
  pendingIds,
  onPendingIdsChange,
  busy,
}: {
  /** 这次「记一笔」要一起送去识别的图。由 CaptureSheet 持有 —— 记录落地之后它才发得出去。 */
  pendingIds: string[]
  onPendingIdsChange: (ids: string[]) => void
  /** 正在记 / 正在送图时锁住所有入口,避免删掉一张正在上传的。 */
  busy: boolean
}) {
  const [cached, setCached] = useState<RawImageMeta[]>([])
  const [problem, setProblem] = useState<string | null>(null)
  const [consented, setConsented] = useState(() => hasConsented(rawImages.ttl))
  const [asking, setAsking] = useState<File[] | null>(null)
  const [dragging, setDragging] = useState(false)
  const [now, setNow] = useState(() => nowFromCache())

  /**
   * 刷新列表 —— {@link rawImages} 的 `list` 自带一次 sweep,
   * 所以这一行同时是「打开这一屏时扫一遍」。
   */
  const refresh = useCallback(async () => {
    try {
      setCached(await rawImages.list())
      setNow(nowFromCache())
    } catch (err) {
      setCached([])
      setProblem(reasonOf(err))
    }
  }, [])

  /* 挂载时扫一遍并读出列表。
     oxlint 的 react/set-state-in-effect 在这里报的是<b>误报</b>:它看到 refresh 会 setState
     就判成「同步 setState」,而 refresh 的每一次 setState 都在一次 await 之后。
     这条 effect 恰好就是那条规则说的正当用法 —— <b>与一个外部系统(IndexedDB)同步</b>:
     本机有几张没过期的原图,是 React 之外的事实,渲染期算不出来。 */
  useEffect(() => {
    // oxlint-disable-next-line react/set-state-in-effect
    void refresh()
  }, [refresh])

  /**
   * 倒计时的心跳。
   *
   * <p>🔴 它<b>不只是让数字往下走</b>:每一跳都重新算一次 `now`,而列表里
   * 已经到点的那一行会立刻消失并触发一次 {@link refresh} —— 也就是说
   * 用户盯着这一屏的时候,到期删除是<b>看得见地发生</b>的,不是下次打开才补上。
   * <p>没有缓存行时不起这个定时器:一个空列表上每秒 setState 一次没有意义。
   */
  useEffect(() => {
    if (cached.length === 0) return
    const timer = setInterval(() => {
      const t = nowFromCache()
      setNow(t)
      if (cached.some((m) => remainingMs(m, t) <= 0)) void refresh()
    }, 1000)
    return () => clearInterval(timer)
  }, [cached, refresh])

  /* —— 收图 ———————————————————————————————————————————————————————————— */

  async function accept(files: File[]) {
    setProblem(null)

    const images = files.filter((f) => (ACCEPTED_IMAGE_MIME as readonly string[]).includes(f.type))
    if (images.length === 0) {
      setProblem('只收 JPEG / PNG / WebP —— 服务端也会自己认一遍字节,这里先挡一次。')
      return
    }

    // 上限对着「这次要送的这一批」算,不是对着整个缓存 —— 缓存里可能还有上一笔的图,
    // 那些不参与这次请求(见 recognize.ts 那三个手抄的上限)。
    const batch = [
      ...cached.filter((m) => pendingIds.includes(m.id)).map((m) => ({ byteSize: m.byteSize, label: m.label })),
      ...images.map((f) => ({ byteSize: f.size, label: f.name })),
    ]
    const rejection = rejectionFor(batch)
    if (rejection !== null) {
      setProblem(rejection)
      return
    }

    const added: string[] = []
    try {
      for (const file of images) {
        // 🔴 一张一次 store:字节与过期戳同一次写入。循环里没有「先全存了回头再写戳」的余地。
        const meta = await rawImages.store({ blob: file, label: file.name })
        added.push(meta.id)
      }
    } catch (err) {
      // 存到一半失败:已经存进去的那几张<b>都带着自己的过期戳</b>,不需要回滚,
      // 它们会自己到期。这里只把没存下的那件事说清楚。
      setProblem(reasonOf(err))
    }

    if (added.length > 0) onPendingIdsChange([...pendingIds, ...added])
    await refresh()
  }

  function onDrop(files: FileList | null) {
    setDragging(false)
    if (busy || files === null || files.length === 0) return
    const list = [...files]
    // 🔴 同意点在<b>收图之前</b>:先存后问等于已经存了。
    if (!consented) {
      setAsking(list)
      return
    }
    void accept(list)
  }

  /* —— 删 ————————————————————————————————————————————————————————————— */

  async function drop(id: string) {
    try {
      await rawImages.forget(id)
    } catch (err) {
      setProblem(reasonOf(err))
    }
    onPendingIdsChange(pendingIds.filter((p) => p !== id))
    await refresh()
  }

  async function dropAll() {
    try {
      await rawImages.forgetAll()
    } catch (err) {
      setProblem(reasonOf(err))
    }
    onPendingIdsChange([])
    await refresh()
  }

  /* —— 渲染 ——————————————————————————————————————————————————————————— */

  const hours = Math.round(rawImages.ttl / 3_600_000)

  return (
    <>
      <GroupHeader
        title="拖张图进来"
        right={cached.length > 0 ? `本机 ${cached.length} 张 · ${hours} 小时后转留存` : '你自己截的图'}
      />
      <div className="px-4 py-3">
        {asking !== null ? (
          <ConsentGate
            count={asking.length}
            hours={hours}
            onAgree={() => {
              rememberConsent(rawImages.ttl)
              setConsented(true)
              const queued = asking
              setAsking(null)
              void accept(queued)
            }}
            onCancel={() => setAsking(null)}
          />
        ) : (
          <label
            onDragOver={(e) => {
              e.preventDefault()
              if (!busy) setDragging(true)
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={(e) => {
              e.preventDefault()
              onDrop(e.dataTransfer.files)
            }}
            className={`flex h-[62px] cursor-pointer flex-col items-center justify-center gap-1.5 rounded-sm border border-dashed bg-bg3 text-[12px] ${
              busy
                ? 'border-hair2 text-t3 opacity-55'
                : dragging
                  ? 'border-acid text-acid'
                  : 'border-hair2 text-t3 hover:border-hair'
            }`}
          >
            <GlyphIcon kind="image" />
            <span className="inline-flex items-center gap-2">
              把截图或照片拖到这儿,或点一下选文件
              <Tag>{`≤6 张 · 本机 ${hours} 小时`}</Tag>
            </span>
            {/* 🔴 `capture` 属性一个字都不写:那会调起摄像头「替你拍」。
                这一屏收的是你自己截好、自己选中的图 —— 和上面语音那条同一条边界。 */}
            <input
              type="file"
              accept={ACCEPTED_IMAGE_MIME.join(',')}
              multiple
              disabled={busy}
              className="hidden"
              onChange={(e) => {
                onDrop(e.target.files)
                e.target.value = ''
              }}
            />
          </label>
        )}

        {cached.length > 0 && (
          <div className="mt-2.5 rounded-sm border border-hair">
            <div className="flex h-[26px] items-center gap-2 border-b border-hair bg-bg3 px-2.5 font-mono text-[10px] tracking-[0.12em] text-t3 uppercase">
              本机原图缓存
              <span className="ml-auto flex items-center gap-2 tracking-normal">
                <span className="tabular-nums">{mib(cached.reduce((s, m) => s + m.byteSize, 0))}</span>
                <Button variant="danger" onClick={() => void dropAll()} disabled={busy}>
                  全部删除
                </Button>
              </span>
            </div>
            {cached.map((m) => (
              <div key={m.id} className="flex h-[30px] items-center gap-2.5 border-b border-hair px-2.5 last:border-b-0">
                <span className="min-w-0 flex-1 truncate text-[12px] text-t2">{m.label}</span>
                <span className="shrink-0 font-mono text-[11px] tabular-nums text-t3">{mib(m.byteSize)}</span>
                {pendingIds.includes(m.id) && <Tag tone="on">这一笔</Tag>}
                {/* ② 倒计时:看得见「还有多久删」。到 0 那一跳这一行就消失了。 */}
                <span
                  className={`shrink-0 font-mono text-[11px] tabular-nums ${
                    remainingMs(m, now) < 600_000 ? 'text-red' : 'text-t3'
                  }`}
                >
                  {countdown(remainingMs(m, now))}
                </span>
                {/* ③ 立即删除:随时能手动删掉,不需要等到期。 */}
                <Button variant="danger" onClick={() => void drop(m.id)} disabled={busy}>
                  删
                </Button>
              </div>
            ))}
          </div>
        )}

        <div className="mt-2">
          {problem !== null ? (
            <Note warn>{problem}</Note>
          ) : (
            <Note warn>
              原图只存在<b className="font-normal text-t2">这台设备</b>上,{hours} 小时后
              <b className="font-normal text-t2">转进留存区</b>;存进去那一刻就写好了到期时刻,不是到期才算。
              <br />
              送去识别的是这次请求里的一份内联副本,服务端
              <b className="font-normal text-red">不落盘、不上云、不共享、不生成外链</b>。
              <br />
              <b className="font-normal text-t2">不会去监听你的截图目录,也不会录别的 App 的屏。</b>
              图只能你自己拖进来。
            </Note>
          )}
        </div>
      </div>
    </>
  )
}

/* ========================================================================== */
/* ① 同意点                                                                    */
/* ========================================================================== */

/**
 * 第一次导图时挡在前面的那一屏。
 *
 * <h2>为什么是「挡住」,不是一条提示</h2>
 *
 * docs/execution/INDEX.md 的审核项写的是<b>同意点</b>。一条常驻说明只保证「写了」,
 * 而这条红线的性质是 docs/decisions/INDEX.md §2.3 那句「第一天不定,后面改不回来」——
 * 用户得在<b>第一张图落到他自己的磁盘上之前</b>知道它会待多久。
 * 所以这里挡住整个接收区:不点这个按钮,{@link RawImageDrop.accept} 一次都不会被调到。
 *
 * <h2>🔴 同意记的是「同意了多久」,不是「同意过」</h2>
 *
 * 存进去的键带着小时数({@link consentKey})。哪天 TTL 被人从 6 小时调成 24,
 * <b>旧的同意自动失效,所有人被重新问一遍</b> —— 因为他当初同意的是 6 小时,不是 24。
 * 用一个光秃秃的布尔值,就等于用一次旧的同意替一个新的时长背书。
 */
function ConsentGate({
  count,
  hours,
  onAgree,
  onCancel,
}: {
  count: number
  hours: number
  onAgree: () => void
  onCancel: () => void
}) {
  return (
    <div className="rounded-sm border border-acid/40 bg-bg3 px-3 py-2.5">
      <div className="font-mono text-[11px] tracking-[0.12em] text-acid uppercase">
        导图之前,先说清楚这 {count} 张图会去哪
      </div>
      <ul className="mt-2 space-y-1 text-[12px] leading-[1.75] text-t2">
        <li>
          · 存在<b className="font-medium text-tx">这台设备</b>的浏览器里,
          <b className="font-medium text-tx">{hours} 小时后转进留存区</b>。存进去那一刻就写好了到期时刻。
        </li>
        <li>· 送去识别时,图作为这次请求的一部分内联发出,服务端不落盘、不进对象存储、不上云端。</li>
        <li>· 不做分享、不生成任何外链。除了本产品的服务端,这些图不会去第三方。</li>
        <li>· 列表里随时能「删」,不用等到期。</li>
      </ul>
      <div className="mt-2.5 flex items-center gap-2.5">
        <Button variant="primary" onClick={onAgree}>
          知道了,导入
        </Button>
        <Button onClick={onCancel}>先不导</Button>
        <span className="text-[11px] text-t3">这条只问一次;{hours} 小时这个数改了会再问一次。</span>
      </div>
    </div>
  )
}

const CONSENT_PREFIX = 'kaodian.rawimage.consent.'

function consentKey(ttlMs: number): string {
  return `${CONSENT_PREFIX}${Math.round(ttlMs / 3_600_000)}h`
}

function hasConsented(ttlMs: number): boolean {
  try {
    return localStorage.getItem(consentKey(ttlMs)) !== null
  } catch {
    // localStorage 都读不了(隐私模式)——那就每次都问。多问一次是安全的方向。
    return false
  }
}

function rememberConsent(ttlMs: number): void {
  try {
    // 🔴 值里只有一个时间戳。<b>这个键下面不放任何和图有关的东西</b> ——
    //    localStorage 没有过期机制,任何进了它的原图信息都是永久的。
    localStorage.setItem(consentKey(ttlMs), String(Date.now()))
  } catch {
    /* 存不下就下次再问一遍。同意点被多问一次,比被少问一次好 */
  }
}

/* ========================================================================== */
/* 小工具                                                                      */
/* ========================================================================== */

/**
 * 倒计时文案。
 *
 * <p>一小时以上按「时分」,一小时以内按「分秒」—— 快到点的那几分钟,秒才是有信息量的。
 * 到 0 显示「已到期」而不是「0 秒」:这一跳里那一行正在被删,说「已到期」是实话。
 */
function countdown(ms: number): string {
  if (ms <= 0) return '已到期'
  const total = Math.floor(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  return h > 0 ? `还有 ${h} 小时 ${m} 分` : `还有 ${m} 分 ${s} 秒`
}

/**
 * 🔴 界面读时间也走缓存那一个时钟。
 *
 * <p>这里写 `Date.now()` 是最自然的一行,而它会让倒计时和到期判断<b>各读一个时钟</b>。
 * 平时看不出差别,`1.1.3.3` 拨时间那一刻就会:一边动了,另一边没动。
 * 所以 `Date.now()` 在整条链路上只出现在 `rawImageStore.ts` 的那一处接线里。
 */
function nowFromCache(): number {
  return rawImages.currentTime()
}

function reasonOf(err: unknown): string {
  if (err instanceof RawImageExpiryError) return err.message
  if (err instanceof Error) return `本地原图缓存出错:${err.message}`
  return '本地原图缓存出错。'
}

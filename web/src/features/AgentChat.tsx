import { useCallback, useEffect, useRef, useState } from 'react'
import {
  deleteSession,
  fileToBase64,
  getSession,
  listSessions,
  newSessionId,
  renameSession,
  levelLabel,
  streamChat,
  type AgentEvent,
  type SessionSummary,
  type ToolLevel,
} from '../api/agent'

/**
 * 把 `**粗体**` 渲染出来,其余原样。
 *
 * <b>刻意不引 markdown 库。</b>模型确实会输出 markdown,但这个面板要展示的东西
 * 只有「一段话 + 几个考点名」—— 引一个 markdown 渲染器意味着同时引进标题、表格、
 * 代码块、链接的渲染能力,而**链接是要防的**:模型编一个 URL 出来,界面就会把它渲染成
 * 可点的链接。这里只认一种标记,其余当纯文本,是最小且够用的那一档。
 */
function renderBold(text: string) {
  // 按 **…** 切:奇数段是加粗内容(split 的捕获组保证了这个位置关系)。
  return text.split(/\*\*(.+?)\*\*/g).map((part, i) =>
    i % 2 === 1 ? (
      <strong key={i} className="text-tx">
        {part}
      </strong>
    ) : (
      part
    ),
  )
}

/**
 * 问答面板 —— 流式对话 + 会话历史 + 图片。
 *
 * <h2>🔴 这里不显示「答案」,显示的是「记录」</h2>
 *
 * 能力边界(决策记录 §2.2)在界面上的形态:助手说的每句话背后都该是一次工具查询,
 * 所以<b>工具调用是可见的</b>(那一行「查了：覆盖率概览」),而不是藏起来只给结论。
 * 藏起来会让它看着像个什么都懂的老师 —— 那正是这个产品不做的东西。
 *
 * <h2>图片:选了就发,不留</h2>
 *
 * 选中的图读成 base64 直接进请求体,发完即弃 —— 不写任何本地存储(R-04,见 `api/agent.ts`)。
 * 所以「已选 2 张」这个状态在发送后就清空了,<b>历史里也不会再有那两张图</b>。
 * 这一点在气泡上明写出来,不让人以为是 bug。
 */

/**
 * 助手那一轮的内容 —— <b>一条按发生顺序排的时间线,不是「工具清单 + 一段话」</b>。
 *
 * <h2>为什么必须有序</h2>
 *
 * 后端真实的帧序是 `token(开场) → tool-call → tool-result → token(结果)`。
 * 把工具折成一行「查了:A、B」摆在文字上方,那句开场白就被排到了工具后面 ——
 * <b>顺序被说反了</b>,而顺序恰恰是「它先想了什么、再去查了什么」这件事本身。
 *
 * <p>用户看到的应该是它<b>做了什么</b>,不只是它<b>说了什么</b>。
 */
type Part =
  | { p: 'text'; text: string }
  | {
      p: 'tool'
      id: string
      name: string
      label: string
      level: ToolLevel
      done: boolean
      error: boolean
    }

interface Turn {
  role: string
  /** 用户那一轮只有一句话 */
  text?: string
  /** 助手那一轮 */
  parts?: Part[]
}

/**
 * 把一帧折进 parts。
 *
 * <p>两条不显然的规则:
 * ① `token` <b>追加到最后一个 text part</b>,不新起一条 —— 否则流式输出会变成每个字一行。
 * ② `tool-result` 找回同 `id` 的那条 `tool-call` <b>就地改状态</b>,不新增一行 ——
 *    一次调用是<u>一件事</u>,拆成「开始…」「结束…」两行会让时间线长出一倍的噪音。
 */
function fold(parts: Part[], e: AgentEvent): Part[] {
  switch (e.kind) {
    case 'token': {
      const last = parts[parts.length - 1]
      if (last && last.p === 'text') {
        return [...parts.slice(0, -1), { p: 'text', text: last.text + e.delta }]
      }
      return [...parts, { p: 'text', text: e.delta }]
    }
    case 'tool-call':
      return [
        ...parts,
        { p: 'tool', id: e.id, name: e.name, label: e.label, level: e.level, done: false, error: false },
      ]
    case 'tool-result':
      return parts.map((x) => (x.p === 'tool' && x.id === e.id ? { ...x, done: true, error: e.error } : x))
    case 'error':
      return [...parts, { p: 'text', text: `\n⚠ ${e.message}` }]
    default:
      return parts
  }
}

/**
 * 一次工具调用 —— <b>等级和名字一样显眼</b>。
 *
 * <h2>为什么非把 level 摆出来不可</h2>
 *
 * 这个产品的能力边界,<b>真正的防线是工具池</b>:6 个工具全是 READ / COMPUTE,
 * 没有任何一个能拿到题目内容。提示词只是<u>请求</u>模型别越界,工具池是<u>物理上</u>让它做不到。
 * <p>
 * 但用户看不见工具池 —— 他能看见的只有这一行。所以「只读」这两个字不是装饰:
 * <b>哪天工具池里冒出一个 `EFFECT`,它会在这条时间线上自己现形</b>,不需要谁去审代码。
 * 所以 `EFFECT` 用告警色,而不是和前两者一样的中性灰。
 */
function ToolLine({ t }: { t: Extract<Part, { p: 'tool' }> }) {
  return (
    <div className="my-1 flex items-center gap-2 border-l-2 border-l-hair2 py-0.5 pl-2">
      <span className={`font-mono text-[10px] ${t.done ? 'text-t3' : 'text-acid'}`}>
        {t.error ? '✕' : t.done ? '✓' : '·'}
      </span>
      <span className="text-xs text-t2">{t.label}</span>
      <span className="font-mono text-[10px] text-t3">{t.name}</span>
      <span
        className={`inline-flex h-4 items-center rounded-sm border px-1.5 font-mono text-[10px] ${
          t.level === 'EFFECT' ? 'border-red/40 text-red' : 'border-hair text-t3'
        }`}
      >
        {levelLabel(t.level)}
      </span>
    </div>
  )
}

export function AgentChat({ onClose }: { onClose?: () => void }) {
  const [sessionId, setSessionId] = useState<string>(() => newSessionId())
  const [sessions, setSessions] = useState<SessionSummary[]>([])
  const [turns, setTurns] = useState<Turn[]>([])
  const [input, setInput] = useState('')
  const [images, setImages] = useState<{ name: string; b64: string }[]>([])
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const abortRef = useRef<AbortController | null>(null)
  const tailRef = useRef<HTMLDivElement | null>(null)

  const refreshSessions = useCallback(() => {
    listSessions()
      .then(setSessions)
      .catch(() => setSessions([]))
  }, [])

  useEffect(refreshSessions, [refreshSessions])

  // 新一帧到达就贴到底部。只在“本来就在底部附近”时才滚 —— 用户翻历史时把他拽回去很烦人。
  useEffect(() => {
    tailRef.current?.scrollIntoView({ block: 'end' })
  }, [turns])

  const send = useCallback(async () => {
    if (busy) return
    const text = input.trim()
    if (!text && images.length === 0) return

    const shown = images.length > 0 ? `${text} 〔附 ${images.length} 张图〕` : text
    setTurns((t) => [...t, { role: 'user', text: shown }, { role: 'assistant', parts: [] }])
    setInput('')
    const payload = images.map((i) => i.b64)
    setImages([])
    setBusy(true)
    setFailure(null)

    const ac = new AbortController()
    abortRef.current = ac
    try {
      await streamChat({ message: text, sessionId, images: payload }, (e: AgentEvent) => {
        setTurns((prev) => {
          const next = [...prev]
          const last = next[next.length - 1]
          if (!last || last.role !== 'assistant') return prev
          next[next.length - 1] = { ...last, parts: fold(last.parts ?? [], e) }
          return next
        })
      }, ac.signal)
      refreshSessions()
    } catch (err) {
      setFailure(err instanceof Error ? err.message : '出错了')
    } finally {
      setBusy(false)
      abortRef.current = null
    }
  }, [busy, input, images, sessionId, refreshSessions])

  const openSession = useCallback(async (id: string) => {
    abortRef.current?.abort()
    setSessionId(id)
    setFailure(null)
    try {
      const detail = await getSession(id)
      // 历史只存了文字(server: SessionDetail.Turn 没有工具字段)——
      // 折成单个 text part,渲染路径就只有一条,不必为「有 parts / 只有 text」分叉两次。
      setTurns(
        detail.turns.map((t) =>
          t.role === 'user'
            ? { role: t.role, text: t.text }
            : { role: t.role, parts: [{ p: 'text' as const, text: t.text }] },
        ),
      )
    } catch {
      setTurns([])
    }
  }, [])

  const startNew = useCallback(() => {
    abortRef.current?.abort()
    setSessionId(newSessionId())
    setTurns([])
    setFailure(null)
  }, [])

  const pickImages = useCallback(async (files: FileList | null) => {
    if (!files) return
    const picked = await Promise.all(
      Array.from(files)
        .slice(0, 6 - images.length)
        .map(async (f) => ({ name: f.name, b64: await fileToBase64(f) })),
    )
    setImages((prev) => [...prev, ...picked].slice(0, 6))
  }, [images.length])

  return (
    <div className="flex h-full min-h-0">
      {/* ——— 会话列表 ——— */}
      <aside className="hidden w-56 shrink-0 flex-col border-r border-hair md:flex">
        <div className="flex items-center justify-between border-b border-hair px-3 py-2">
          <span className="text-xs text-t3">会话</span>
          <button type="button" onClick={startNew} className="text-xs text-acid hover:underline">
            + 新对话
          </button>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto">
          {sessions.length === 0 && <p className="px-3 py-3 text-xs text-t3">还没有对话</p>}
          {sessions.map((s) => (
            <div
              key={s.sessionId}
              className={`group flex items-center gap-1 border-b border-hair px-3 py-2 text-xs ${
                s.sessionId === sessionId ? 'bg-bg3 text-tx' : 'text-t2'
              }`}
            >
              <button
                type="button"
                onClick={() => void openSession(s.sessionId)}
                className="min-w-0 flex-1 truncate text-left"
                title={s.title}
              >
                {s.title}
              </button>
              <button
                type="button"
                title="改名"
                className="hidden shrink-0 text-t3 hover:text-acid group-hover:block"
                onClick={() => {
                  const t = window.prompt('新标题', s.title)
                  if (t?.trim()) void renameSession(s.sessionId, t.trim()).then(refreshSessions)
                }}
              >
                ✎
              </button>
              <button
                type="button"
                title="删除"
                className="hidden shrink-0 text-t3 hover:text-red group-hover:block"
                onClick={() => {
                  // 真删,连同这次对话的全部执行记录 —— 所以要问一句。
                  if (window.confirm(`删除「${s.title}」?这会连同该对话的记录一起删掉。`)) {
                    void deleteSession(s.sessionId).then(() => {
                      if (s.sessionId === sessionId) startNew()
                      refreshSessions()
                    })
                  }
                }}
              >
                ×
              </button>
            </div>
          ))}
        </div>
      </aside>

      {/* ——— 对话区 ——— */}
      <section className="flex min-h-0 min-w-0 flex-1 flex-col">
        <header className="flex shrink-0 items-center justify-between border-b border-hair px-3 py-2">
          <span className="text-xs text-t3">
            问答 · 只答「有没有、几次、多久前」,不判断对错
          </span>
          {onClose && (
            <button type="button" onClick={onClose} className="text-xs text-t3 hover:text-tx">
              关闭
            </button>
          )}
        </header>

        <div className="min-h-0 flex-1 overflow-y-auto px-3 py-3">
          {turns.length === 0 && (
            <p className="text-xs text-t3">
              试试:「我的覆盖率怎么样」「我该先补哪个考点」「最近一周记了几条」
            </p>
          )}
          {turns.map((t, i) => (
            <div key={i} className="mb-3">
              <div className="mb-0.5 font-mono text-[10px] text-t3">
                {t.role === 'user' ? '你' : '助手'}
              </div>
              {/* 🔴 工具与文字同级、按发生顺序排 —— 见 Part 的注释。
                  用户看到的是它做了什么,不只是它说了什么。 */}
              {t.parts
                ? t.parts.map((part, j) =>
                    part.p === 'tool' ? (
                      <ToolLine key={j} t={part} />
                    ) : (
                      <div key={j} className="whitespace-pre-wrap text-sm text-t2">
                        {renderBold(part.text)}
                      </div>
                    ),
                  )
                : null}
              {t.text ? <div className="whitespace-pre-wrap text-sm text-t2">{renderBold(t.text)}</div> : null}
              {busy && i === turns.length - 1 && (t.parts?.length ?? 0) === 0 ? (
                <div className="text-sm text-t3">…</div>
              ) : null}
            </div>
          ))}
          <div ref={tailRef} />
        </div>

        {failure && <p className="shrink-0 px-3 pb-1 text-xs text-red">{failure}</p>}

        {images.length > 0 && (
          <div className="shrink-0 px-3 pb-1 text-[10px] text-t3">
            已选 {images.length} 张：{images.map((i) => i.name).join('、')}
            <span className="ml-1">（发送后不留存，历史里看不到）</span>
            <button type="button" className="ml-2 text-red" onClick={() => setImages([])}>
              清除
            </button>
          </div>
        )}

        <div className="flex shrink-0 items-end gap-2 border-t border-hair px-3 py-2">
          <label className="cursor-pointer text-xs text-t3 hover:text-acid" title="最多 6 张">
            + 图
            <input
              type="file"
              accept="image/png,image/jpeg,image/webp"
              multiple
              className="hidden"
              onChange={(e) => void pickImages(e.target.files)}
            />
          </label>
          <textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              // Enter 发送,Shift+Enter 换行 —— 与聊天框的通行习惯一致。
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault()
                void send()
              }
            }}
            rows={1}
            placeholder="问点什么…"
            className="max-h-32 min-h-[2rem] flex-1 resize-y bg-transparent text-sm text-tx outline-none placeholder:text-t3"
          />
          {busy ? (
            <button
              type="button"
              onClick={() => abortRef.current?.abort()}
              className="shrink-0 text-xs text-red hover:underline"
            >
              停
            </button>
          ) : (
            <button
              type="button"
              onClick={() => void send()}
              className="shrink-0 text-xs text-acid hover:underline"
            >
              发送
            </button>
          )}
        </div>
      </section>
    </div>
  )
}

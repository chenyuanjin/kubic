import { getJson } from './client'

/**
 * agent 这一侧:一条 SSE 流 + 一组会话管理接口。
 *
 * <h2>为什么不用 `EventSource`</h2>
 *
 * 它只会发 <b>GET</b>,而 `/api/agent/chat` 是 POST(要带 message、sessionId、images)。
 * 把问题塞进 query string 能骗过这一点,但会让<b>用户问的每一句话都出现在访问日志里</b> ——
 * 而这个产品的输入随时可能是一道题的题干(01 §2.2 不碰内容)。
 * <p>
 * 所以用 `fetch` + `ReadableStream` 自己拆 SSE 帧。代价是要手写一小段拆帧代码,
 * 换来的是问题只在请求体里。
 */

// —— 服务端发的六种帧(server: SseChannelAdapter)——

export type AgentEvent =
  | { kind: 'run-meta'; agent: string; runId: string; modelId: string }
  | { kind: 'token'; agent: string; delta: string }
  | { kind: 'tool-call'; agent: string; id: string; name: string; label: string; level: ToolLevel }
  | { kind: 'tool-result'; agent: string; id: string; name: string; label: string; error: boolean }
  | { kind: 'error'; agent: string; code: string; message: string }
  | {
      kind: 'done'
      runId: string
      finishReason: string
      latencyMs: number
      usage: { promptTokens: number; completionTokens: number; totalTokens: number }
    }

/**
 * 工具的等级 —— <b>值得被显示出来</b>。
 *
 * <h2>为什么这三个字比任何一句「本产品只读」都可信</h2>
 *
 * 能力边界(01 §2.2「只说有没有、几次、多久前,不判断对不对」)的<b>真正防线不是提示词,
 * 是工具池</b>:没有任何一个工具能拿到题目内容(CLAUDE.md 的红线)。
 * 提示词只是<b>请求</b>模型别越界,工具池是<b>物理上</b>让它做不到。
 * <p>
 * 而用户看不见工具池。他能看见的只有界面 —— 把每次调用连同等级一起摆出来,
 * <b>「读了你的覆盖率 · 只读」比一个转圈的加载动画多说了整件事</b>;
 * 而且哪天工具池里冒出一个 `EFFECT`,它会在时间线上<b>自己现形</b>。
 */
export type ToolLevel = 'READ' | 'COMPUTE' | 'EFFECT'

export function levelLabel(level: ToolLevel): string {
  switch (level) {
    case 'READ':
      return '只读'
    case 'COMPUTE':
      return '计算'
    case 'EFFECT':
      // 🔴 出现即异常。工具池当前一个 EFFECT 都没有(server: CoverageTools / RecordTools),
      // 真出现了,用户第一时间就该看见 —— 所以不用中性词。
      return '会改东西'
  }
}

// —— 会话(server: AgentSessionController)——

export interface SessionSummary {
  sessionId: string
  title: string
  runCount: number
  createdAt: string
  updatedAt: string
}

export interface SessionTurn {
  role: string
  text: string
  at: string
}

export interface SessionDetail {
  sessionId: string
  title: string
  turns: SessionTurn[]
}

/**
 * 客户端先生成 sessionId。
 *
 * <p>服务端接受 `sessionId` 作为入参,所以「第一轮之前就有 id」是可行的 ——
 * 而它换来一件具体的事:<b>第一轮还在流式输出时,「新开一个会话」就已经能点了</b>。
 * 等服务端回传 id 的话,那个按钮得在第一轮结束前一直是灰的。
 */
export function newSessionId(): string {
  return `s-${crypto.randomUUID()}`
}

export function listSessions(): Promise<SessionSummary[]> {
  return getJson<SessionSummary[]>('/agent/sessions')
}

export function getSession(sessionId: string): Promise<SessionDetail> {
  return getJson<SessionDetail>(`/agent/sessions/${encodeURIComponent(sessionId)}`)
}

export async function renameSession(sessionId: string, title: string): Promise<void> {
  await rawJson(`/api/agent/sessions/${encodeURIComponent(sessionId)}`, 'POST', { title })
}

export async function deleteSession(sessionId: string): Promise<void> {
  await rawJson(`/api/agent/sessions/${encodeURIComponent(sessionId)}`, 'DELETE')
}

/**
 * 不走 `client.ts` 的那条路。
 *
 * <p>那边有 2 秒超时(为了后端没起来时立刻退回离线示例数据),而重命名/删除
 * 不该被那个为首屏定的超时管着。另外 DELETE 在那边也没有对应的封装。
 */
async function rawJson(url: string, method: string, body?: unknown) {
  const res = await fetch(url, {
    method,
    headers: { 'Content-Type': 'application/json', ...authHeader() },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) throw new Error(`${method} ${url} → HTTP ${res.status}`)
}

function authHeader(): Record<string, string> {
  try {
    // 与 api/auth.ts 的 TOKEN_KEY 是同一个键
    const t = localStorage.getItem('kaodian.auth.token')
    return t ? { Authorization: `Bearer ${t}` } : {}
  } catch {
    return {}
  }
}

// —— 图片 ——

/**
 * 文件 → 纯 base64(不带 `data:` 前缀)。
 *
 * <h2>🔴 这里是整条链路上唯一一处图片以字符串形态存在的地方</h2>
 *
 * 服务端的红线是「聊天图片只在内存里活到送进模型为止,永不写进 `messages.ndjson`」——
 * 而那一条在类型上就成立:`MessagePart` 是 sealed 的,三个变体
 * (`TextPart` / `ToolCallPart` / `ToolResultPart`)<b>没有一个能装下图片</b>。
 * <p>
 * 前端这一侧对应的纪律是:这个函数的返回值<b>只能流向请求体</b>,
 * 不进 `localStorage`、不进任何日志、不进 React DevTools 能长期留存的地方。
 * 发完那次请求它就该被 GC 掉。
 *
 * <p>⚠ 还有一条更要紧的(`R-89`):<b>图片进来之后,能力边界比看起来弱</b>。
 * 文字提问时工具池让模型物理上够不到题目内容;而一张题目照片<b>本身就是题目内容</b>,
 * 此刻挡在越界前面的<b>只剩 `AgentPrompt` 那段措辞</b> —— 提示词是第二道防线,
 * 图片场景下它变成了唯一一道。
 */
export function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onerror = () => reject(new Error(`读不了这个文件:${file.name}`))
    reader.onload = () => {
      const r = String(reader.result)
      const comma = r.indexOf(',')
      // FileReader 给的是 `data:image/png;base64,xxxx` —— 服务端要的是逗号后面那一段
      resolve(comma === -1 ? r : r.slice(comma + 1))
    }
    reader.readAsDataURL(file)
  })
}

// —— 流 ——

export interface ChatInput {
  message: string
  sessionId: string | null
  /** 纯 base64 串,不带 `data:` 前缀 —— 见 {@link fileToBase64}。文件名不往上传:服务端不需要它。 */
  images?: string[]
}

/**
 * 开一条对话流,<b>直到 `done` 才 resolve</b>。
 *
 * @param signal 用户按 Esc / 切走时掐掉这条流。中途 abort <b>不算错误</b>,直接正常返回 ——
 *               把用户自己的取消抛成异常,调用方就得在 catch 里再分辨一次「这是不是我自己干的」
 */
export async function streamChat(
  input: ChatInput,
  onEvent: (e: AgentEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  let res: Response
  try {
    res = await fetch('/api/agent/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', ...authHeader() },
      body: JSON.stringify({
        message: input.message,
        sessionId: input.sessionId,
        images: input.images ?? null,
      }),
      signal,
    })
  } catch (err) {
    if (signal?.aborted) return
    throw new Error(err instanceof Error ? err.message : '连不上 agent')
  }

  if (!res.ok || !res.body) {
    throw new Error(
      res.status === 404 ? 'agent 端点不在 —— 后端是旧构建?' : `连不上 agent(HTTP ${res.status})`,
    )
  }

  try {
    await pump(res.body, onEvent)
  } catch (err) {
    if (signal?.aborted) return
    throw err
  }
}

/**
 * 拆 SSE 帧。
 *
 * <p>SSE 的分帧符是<b>空行</b>,而 `ReadableStream` 给的是任意切分的字节块 ——
 * 一帧可能横跨两个 chunk,一个 chunk 也可能装着三帧。所以必须留一个缓冲区,
 * <b>只在看到空行时才交付</b>。按 chunk 直接 parse 是这类代码最常见的错,
 * 而它的表现是「大部分时候好好的,偶尔丢一段字」—— 偶发到不会有人去查。
 */
async function pump(body: ReadableStream<Uint8Array>, onEvent: (e: AgentEvent) => void) {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buf = ''

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })

    let sep: number
    while ((sep = findFrameEnd(buf)) !== -1) {
      const frame = buf.slice(0, sep)
      buf = buf.slice(sep).replace(/^(\r?\n){2}/, '')
      const e = parseFrame(frame)
      if (e) onEvent(e)
    }
  }
}

/** `\n\n` 与 `\r\n\r\n` 都要认 —— 中间隔着一个代理时换行可能被改写。 */
function findFrameEnd(s: string): number {
  const a = s.indexOf('\n\n')
  const b = s.indexOf('\r\n\r\n')
  if (a === -1) return b
  if (b === -1) return a
  return Math.min(a, b)
}

function parseFrame(frame: string): AgentEvent | null {
  let kind = 'message'
  const dataLines: string[] = []
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('event:')) kind = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
  }
  if (dataLines.length === 0) return null
  try {
    const payload = JSON.parse(dataLines.join('\n')) as Record<string, unknown>
    return { kind, ...payload } as AgentEvent
  } catch {
    // 半截 JSON 就丢掉这一帧。整条流不该因为一帧坏了就中断 ——
    // 后面还有 done,界面还得靠它收尾。
    return null
  }
}

/**
 * 极薄的 fetch 封装。
 *
 * 只写相对路径 `/api/*`:dev 由 vite proxy 转到 :8080,生产由 Caddy 反代到同机 jar
 * (docs/technical/INDEX.md §2.2「一台机器上的两个进程,不是两个服务」)。所以这里没有 baseURL 配置,
 * 也没有环境变量 —— 少一个能配错的地方。
 */

/**
 * 后端不可达 / 超时 / 非 2xx,都归到这一类,带一句能直接显示给用户的中文原因。
 *
 * <h2>为什么还带一个 {@link code}</h2>
 *
 * 服务端的错误体是 `{code, message, traceId}`(docs/technical/INDEX.md §六),而那个 `code` 是<b>稳定的、
 * 可被程序判断的</b>东西 —— `message` 是给人看的中文,随时可以改词。
 * 骨架层的删除守则正好需要这个分支:`409 NODE_HAS_RECORDS` 不该显示成一句「删除失败」,
 * 它得在界面上长出「把记录搬走」和「归档」两个按钮
 * (server 侧 `ApiException.of(SyllabusEditException)` 的 javadoc 明说了这条)。
 * <p>
 * 只带 `message` 的话前端就只能去<b>匹配中文文案</b>做分支 —— 那是一条改一个字就会断的线。
 */
export class ApiUnavailableError extends Error {
  readonly reason: string
  /** 服务端错误体里的机器可读码,如 `NODE_HAS_RECORDS`。传输层失败时为 null。 */
  readonly code: string | null
  /** HTTP 状态码。传输层失败(连不上 / 超时)时为 null。 */
  readonly status: number | null

  constructor(reason: string, opts: { code?: string | null; status?: number | null; cause?: unknown } = {}) {
    super(reason, { cause: opts.cause })
    this.name = 'ApiUnavailableError'
    this.reason = reason
    this.code = opts.code ?? null
    this.status = opts.status ?? null
  }
}

/** 服务端错误体。三个字段,一个不多 —— server: dto/ApiError.java。 */
interface ApiErrorBody {
  code: string
  message: string
  traceId: string
}

function readErrorBody(text: string): ApiErrorBody | null {
  try {
    const body: unknown = JSON.parse(text)
    if (body && typeof body === 'object' && 'code' in body && 'message' in body) {
      const b = body as Record<string, unknown>
      return { code: String(b.code), message: String(b.message), traceId: String(b.traceId ?? '') }
    }
  } catch {
    /* 错误体不是 JSON 就算了,状态码已经够定位 */
  }
  return null
}

/**
 * 2 秒。
 *
 * 这个超时短得刻意:后端没起来时用户应该<b>立刻</b>看到离线示例数据,
 * 而不是盯着空屏等默认的几十秒 TCP 超时。真实后端在同一台机器上,2 秒绰绰有余。
 */
const TIMEOUT_MS = 2000

function describe(err: unknown): string {
  if (err instanceof ApiUnavailableError) return err.reason
  if (err instanceof DOMException && (err.name === 'TimeoutError' || err.name === 'AbortError')) {
    return `请求超时(> ${TIMEOUT_MS} ms)`
  }
  if (err instanceof TypeError) return '连不上 /api —— 后端 :8080 没起来?'
  return err instanceof Error ? err.message : String(err)
}

/**
 * 有令牌就带上。
 *
 * <h2>为什么直接读 localStorage,而不是从 auth.ts 引一个函数</h2>
 *
 * 反过来就成环了:`auth.ts` 要用 `postJson`,`client.ts` 又要用 `auth.ts` 的读取函数。
 * 而这里需要的只是<b>一个字符串</b> —— 为它拉一条循环依赖不划算。
 * 键名在两处各写一遍是这个取舍的代价,所以两处都用同一个常量名并互相指着。
 *
 * <p>🔴 拿不到令牌时<b>不加这个头</b>,而不是加一个空的 —— `Authorization: Bearer `
 * 会让服务端走进「格式对但令牌为空」那条分支,而正确的语义是「这个请求没有身份」。
 */
function authHeader(): Record<string, string> {
  try {
    // 与 api/auth.ts 的 TOKEN_KEY 是同一个键
    const t = localStorage.getItem('kaodian.auth.token')
    return t ? { Authorization: `Bearer ${t}` } : {}
  } catch {
    return {}
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response
  try {
    res = await fetch(`/api${path}`, {
      ...init,
      headers: { Accept: 'application/json', ...authHeader(), ...init?.headers },
      signal: AbortSignal.timeout(TIMEOUT_MS),
    })
  } catch (err) {
    throw new ApiUnavailableError(describe(err), { cause: err })
  }

  const text = await res.text()

  if (!res.ok) {
    // dev 下 vite proxy 会把「后端没起来」翻译成 502/504,直接显示成裸状态码会被读成
    // 「服务端有 bug」。这里翻回它真正的意思。
    if (res.status === 502 || res.status === 503 || res.status === 504) {
      throw new ApiUnavailableError(`连不上 /api —— 后端 :8080 没起来?(HTTP ${res.status})`, {
        status: res.status,
      })
    }

    // 后端的错误体约定是 {code, message, traceId}(docs/technical/INDEX.md §六)。
    // message 给人看,code 给程序分支 —— 两个都留着,不要只留一个。
    const body = readErrorBody(text)
    throw new ApiUnavailableError(body ? `${body.message}(HTTP ${res.status})` : `HTTP ${res.status}`, {
      code: body?.code ?? null,
      status: res.status,
    })
  }

  // 先读成文本再自己 parse,而不是直接 res.json() —— 因为有两种「合法的空」:
  // 204 No Content,以及 200 但 body 为空。res.json() 对它们都会抛
  // 「Unexpected end of JSON input」,那会让一次<b>成功</b>的写在界面上显示成失败。
  if (text.trim() === '') return undefined as T

  // 有 body 但不是 JSON —— 最常见的成因是 /api 压根没被反代出去,静态服务器把 index.html
  // 当兜底返回了。这时候 parse 抛的是「Unexpected token '<'」,直接摆到界面上没人看得懂,
  // 而它其实是一条很明确的部署错误。
  try {
    return JSON.parse(text) as T
  } catch (err) {
    throw new ApiUnavailableError(`${path} 返回的不是 JSON —— /api 没被反代到后端?`, {
      status: res.status,
      cause: err,
    })
  }
}

export function getJson<T>(path: string): Promise<T> {
  return request<T>(path)
}

export function postJson<T>(path: string, body: unknown): Promise<T> {
  return request<T>(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

/**
 * 无请求体的 POST。归档 / 取消归档 / 删除走它 —— 那三个端点没有 `@RequestBody`。
 *
 * <h2>🔴 为什么<b>没有</b> patchJson / deleteJson</h2>
 *
 * 不是懒。server 侧 `ApiCorsConfig` 的方法白名单只有 `GET / POST`,
 * 那份 javadoc 写着「将来真要开 DELETE 时,这里必须显式加,而<b>必须显式加</b>正是要的效果」。
 * 前端偷偷发一个 DELETE,dev 下走 vite proxy 是同源、能过,<b>上了生产才被 CORS 挡掉</b> ——
 * 这是最难查的一类差异:本地全绿,线上全红。
 * <p>
 * 语义上也对得上:骨架层的删除<b>不是「让一个资源消失」,而是一条带前置条件的命令</b>,
 * 它会失败,而且失败才是常态(有记录就不许删)。
 */
export function postAction<T>(path: string): Promise<T> {
  return request<T>(path, { method: 'POST' })
}

export { describe as describeError }

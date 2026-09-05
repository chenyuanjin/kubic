/**
 * 错误码 → 界面落点。<b>纯判断层,一个 DOM 符号都没有</b>,所以它在 node 里被测
 * (`tests/errorCopy.test.ts`),而真正画那一块的组件里一句判断都没有。
 *
 * <h2>为什么这一层必须独立存在</h2>
 *
 * 八份稿的「失败落点」那一格写的全是<b>错误码</b>(`SUBJECT_NOT_LOADED` → 停在本屏、
 * `SYLLABUS_DATA_BROKEN` → 不可重试档、`QUOTA_EXHAUSTED` → 受限态而不是失败态……),
 * 而 `api/client.ts` 已经把服务端的 `{code, message, traceId}` 里的 `code` 原样带出来了。
 * 中间缺的就是这张表:<b>码 → 哪一档 + 说什么话 + 给不给重试按钮</b>。
 *
 * 缺了它,每一屏都会各自去 `if (err.code === ...)`,于是同一个码在两屏上说两句话 ——
 * 这正是 `接口契约` §三 那张「服务端错误码 → 界面文案」映射表要挡的事。
 *
 * <h2>🔴 三档不是两档</h2>
 *
 * <ul>
 * <li><b>可重试的失败</b> —— 传输层抖动、5xx、超时。给重试按钮。</li>
 * <li><b>不可重试的失败</b> —— `SYLLABUS_DATA_BROKEN` 这一类:数据自身坏了,
 *     再点一次还是同一个结果。<b>给重试按钮是在骗人</b>(`design/m3/交互说明.md`
 *     「不可重试档,不给重试按钮」)。</li>
 * <li><b>受限,不是失败</b> —— `QUOTA_EXHAUSTED` / `SESSION_TURN_LIMIT` /
 *     `TOKEN_LIMIT_REACHED`。`design/m1/交互说明.md` 05-quota 写得很直白:
 *     「无红色、无重试当主操作、主按钮照常可点 —— 额度耗尽时记录仍成功,<b>这一态不是失败</b>」。
 *     把它画成红色错误,用户会以为自己刚才那一笔没记上。</li>
 * </ul>
 *
 * <h2>门上那三个码是同一张屏</h2>
 *
 * `design/h5/H5交互说明.md`:「`UNAUTHORIZED` / `SESSION_EXPIRED` / `TOKEN_EXPIRED`
 * 三个码在门上是<b>同一张屏</b>,端不为它们写三句话。」所以它们在这里归到 `gate` 一档,
 * 由调用方直接切到门,不渲染任何错误块。
 */

/** 这个码把界面带到哪一档。 */
export type FailureKind =
  /** 可重试的失败:给一个重试按钮。 */
  | 'retryable'
  /** 不可重试的失败:说清楚发生了什么,不给重试按钮。 */
  | 'terminal'
  /** 受限,不是失败:不用红色,主操作照常可点,并给出不花额度的那条兜底路。 */
  | 'limited'
  /** 回到门。三个鉴权码共用一张屏,端不为它们写三句话。 */
  | 'gate'

export interface FailureCopy {
  kind: FailureKind
  /** 一行标题。它是给用户的,不是给日志的。 */
  title: string
  /** 一句说明:发生了什么 + 下一步是什么。没有下一步就不编一个。 */
  body: string
  /** 兜底出口的文案。`null` = 这一档没有第二条路。 */
  fallback: string | null
}

/**
 * 门上那三个码。列成常量而不是散在 switch 里,是为了让「三个码一张屏」这句话
 * 在代码里也只有一处。
 */
export const GATE_CODES = ['UNAUTHORIZED', 'SESSION_EXPIRED', 'TOKEN_EXPIRED', 'ACCOUNT_DEACTIVATED'] as const

/**
 * 逐条对着 `接口契约-签名与错误码全集` §十二 的码表抄。
 *
 * 🔴 只收<b>本轮主链路六屏真的会遇到</b>的码。一个界面走不到的码写在这里,
 * 等于给一段永远不会被执行的文案买了单,而它还会在下一次改文案时被一起改。
 */
const TABLE: Record<string, FailureCopy> = {
  /* —— 骨架层 / 覆盖度(M3)—— */
  SUBJECT_NOT_LOADED: {
    kind: 'terminal',
    title: '这个科目还没加载',
    body: '骨架层里没有这个科目,重试不会让它出现。下一步是换一个科目。',
    fallback: '换科目',
  },
  SYLLABUS_EMPTY: {
    kind: 'terminal',
    title: '这个科目的骨架还没建',
    body: '差集的被减数是空的,所以这一屏一个数都算不出来。先去考点树里把题型与考点建起来。',
    fallback: '去考点树',
  },
  SYLLABUS_DATA_BROKEN: {
    kind: 'terminal',
    title: '骨架数据自身坏了',
    body: '再点一次还是同一个结果,所以这里不给重试。这条要人去查服务端的骨架数据。',
    fallback: null,
  },
  NODE_NOT_FOUND: {
    kind: 'terminal',
    title: '找不到这个考点',
    body: '地址里的考点标识指不到任何地方 —— 多半是它被改过或删过了。',
    fallback: '回覆盖度',
  },
  NODE_ARCHIVED: {
    kind: 'terminal',
    title: '这个考点已归档',
    body: '归档的意思是退出差集,所以它不能被挂载、也不参与覆盖度。要用它就先取消归档。',
    fallback: '去考点树',
  },
  UNKNOWN_ORDER_BY: {
    kind: 'terminal',
    title: '排序口径不对',
    body: '服务端不认这个排序参数,而它不会静默按默认返回 —— 这是端自己的 bug。',
    fallback: null,
  },

  /* —— 记录(M1)—— */
  RECORD_NOT_FOUND: {
    kind: 'terminal',
    title: '这条记录不在了',
    body: '可能已经被删掉。时间线上还留着它的话,刷新一下就会消失。',
    fallback: '回记录',
  },
  UNSUPPORTED_IMAGE_FORMAT: {
    kind: 'retryable',
    title: '这个图片格式读不了',
    body: '换一张再来。图还在你这台设备上,一张都没少。',
    fallback: null,
  },
  IMAGE_TOO_MANY: {
    kind: 'retryable',
    title: '一次贴的图太多了',
    body: '先记下已经选中的那几张,剩下的分一次再来。已选中的那些不会丢。',
    fallback: null,
  },
  RECOGNIZER_UNAVAILABLE: {
    kind: 'retryable',
    title: '识别服务这会儿不可用',
    body: '记录已经落下了,缺的只是自动挂考点。可以稍后重试,也可以现在自己挑一个考点。',
    fallback: '自己挑考点',
  },
  NO_MATCH_AND_NO_USER_NODE: {
    kind: 'terminal',
    title: '没有匹配上任何考点',
    body: '宁缺毋错:匹配不上就丢弃,不硬塞一个。这条记录会留在未分类里等你自己挂。',
    fallback: '自己挑考点',
  },

  /* —— 出口(M4)—— */
  EXPORT_JOB_NOT_FOUND: {
    kind: 'retryable',
    title: '这次导出的作业不在了',
    body: '作业过期会被清掉。下一步都一样:重新导一次。',
    fallback: null,
  },
  UNKNOWN_EXPORT_FORMAT: {
    kind: 'terminal',
    title: '这个导出格式不认识',
    body: '端自己拼错了参数,重试没有用。',
    fallback: null,
  },
  AI_TEXT_TOO_LONG: {
    kind: 'terminal',
    title: '这段文字太长了',
    body: '服务端不会替你静默截断 —— 截断之后发出去的就不是你写的那段。自己删短一点再发。',
    fallback: null,
  },

  /* —— 受限,不是失败 —— */
  QUOTA_EXHAUSTED: {
    kind: 'limited',
    title: '这个月的自动打标额度用完了',
    body: '记录照样记得下,一条都不会少 —— 少的只是自动挂考点这一步。',
    fallback: '自己挑考点(不花额度)',
  },
  SESSION_TURN_LIMIT: {
    kind: 'limited',
    title: '这轮问答到头了',
    body: '一次会话最多 20 轮。开一轮新的,上一轮的记录都还在。',
    fallback: '开一轮新的',
  },
  TOKEN_LIMIT_REACHED: {
    kind: 'limited',
    title: '只读令牌到上限了',
    body: '先吊销一个,再签一个新的。',
    fallback: '去管理令牌',
  },

  /* —— 平台层 —— */
  SERVER_ERROR: {
    kind: 'retryable',
    title: '服务端出错了',
    body: '这一下没成。再试一次;还是不行就等一会儿。',
    fallback: null,
  },
  VALIDATION_FAILED: {
    kind: 'terminal',
    title: '请求不合法',
    body: '这是端自己的 bug,不是你做错了什么。重试不会有别的结果。',
    fallback: null,
  },
  UNKNOWN_FIELD: {
    kind: 'terminal',
    title: '请求里多了一个字段',
    body: '同上 —— 端自己的 bug。服务端拒收未知字段是有意的,那道锁挡的是自由输入的标签。',
    fallback: null,
  },
  MISSING_CLIENT_TOKEN: {
    kind: 'terminal',
    title: '补传的条目缺客户端标识',
    body: '端自己的 bug。这条不做用户文案,写在这里是为了让它别混进「网络不好」那一档。',
    fallback: null,
  },
}

/**
 * 网络层失败(连不上 / 超时)—— 它没有服务端错误码,`client.ts` 那边 `code` 是 `null`。
 *
 * 🔴 它必须与 `SERVER_ERROR` 分得开:一个是「你这儿没网」,一个是「服务端出错了」,
 * 用户下一步做的事不一样。
 */
export const NETWORK_FAILURE: FailureCopy = {
  kind: 'retryable',
  title: '连不上服务端',
  body: '这一屏显示的是离线示例数据,不是你的记录。网络回来后切回这个窗口会自动再试一次。',
  fallback: null,
}

/** 码不在表里时的兜底。<b>不编一句具体的话</b> —— 把码原样摆出来比编一句强。 */
export function unknownFailure(code: string): FailureCopy {
  return {
    kind: 'retryable',
    title: '这一下没成',
    body: `服务端回的是 ${code}。这个码本端还没有对应的说法 —— 把它报给开发。`,
    fallback: null,
  }
}

/** 三个鉴权码在门上是同一张屏。 */
export function isGateCode(code: string | null | undefined): boolean {
  return code !== null && code !== undefined && (GATE_CODES as readonly string[]).includes(code)
}

/**
 * 主入口:一个错误码落到哪一档、说什么话。
 *
 * @param code 服务端错误体里的 `code`。传输层失败时是 `null`。
 */
export function failureCopy(code: string | null | undefined): FailureCopy {
  if (code === null || code === undefined) return NETWORK_FAILURE
  if (isGateCode(code)) {
    return {
      kind: 'gate',
      title: '先登录',
      body: '地址不变,登录完就留在这一屏。',
      fallback: null,
    }
  }
  return TABLE[code] ?? unknownFailure(code)
}

/** 这个码该不该给重试按钮。`design/m3` 的「不可重试档」就靠它。 */
export function retryable(code: string | null | undefined): boolean {
  return failureCopy(code).kind === 'retryable'
}

/** 本端认识的全部码。测试用它盯住「表里每一条都能被取到」。 */
export function knownCodes(): string[] {
  return Object.keys(TABLE).sort()
}

import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { readToken, writeToken } from './api/auth'
import { LoginGate } from './features/LoginGate'
import { MainScreen } from './screens/MainScreen'

/**
 * 只有一屏 —— 加上门口那一道。
 *
 * 没有路由:这个产品的对象只有一棵考点树和五种动作,层级浅到不需要 URL 分层,
 * 导航全部压进 ⌘K(骨架规则 1、2)。等考点详情页 D5 落地时再谈路由,现在加是先付账。
 *
 * <h2>🔴 登录是一道门,不是一条路由</h2>
 *
 * 所以它是 {@link LoginGate} 与 {@link MainScreen} 的二选一,而不是 `/login` 这个地址。
 * 差别在回退键:做成路由的话,用户登录完按一下后退就回到登录页,再点一次又登进去 ——
 * 一个不存在的往返。<b>门只有里外,没有历史。</b>
 *
 * <h2>⚪ 但这道门现在是可以绕开的,这一点必须说清楚</h2>
 *
 * 后端的 `/api/v1/records`、`/api/v1/syllabus/*` <b>还没有要求令牌</b> —— 行为层至今是单用户的
 * (`Touch` 上没有 `user_id`,整个进程一份 `touches.json`,见 server 的
 * `CurrentSessionResolver` 类注释)。所以这道门此刻挡的是<b>界面</b>,不是数据。
 * <p>
 * 把它写成「已登录才给看」而实际数据并没有分租户,是一种<b>看起来做完了</b>的状态 ——
 * 所以这里留一个 `跳过登录`:它不假装安全,它明确地说「这一步现在是可选的」。
 * 真正合上这道门的那天,是行为层长出 `user_id` 的那天。
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 后端不可达时 queryFn 自己回退到离线示例数据并把原因带出来,重试只会让首屏变慢。
      retry: false,
    },
  },
})

export default function App() {
  const [token, setToken] = useState<string | null>(() => readToken())
  const [skipped, setSkipped] = useState(false)

  const signOut = () => {
    writeToken(null)
    setToken(null)
    setSkipped(false)
    queryClient.clear()
  }

  if (!token && !skipped) {
    return (
      <QueryClientProvider client={queryClient}>
        <div className="relative">
          <LoginGate onDone={(r) => setToken(r.token)} />
          <button
            type="button"
            onClick={() => setSkipped(true)}
            className="fixed bottom-5 left-1/2 -translate-x-1/2 font-mono text-[11px] text-t3 hover:text-tx"
            title="行为层还没有 user_id,这道门此刻挡的是界面不是数据"
          >
            跳过登录 · 直接看盲区
          </button>
        </div>
      </QueryClientProvider>
    )
  }

  return (
    <QueryClientProvider client={queryClient}>
      <MainScreen onSignOut={token ? signOut : undefined} />
    </QueryClientProvider>
  )
}

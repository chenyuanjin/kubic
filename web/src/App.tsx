import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MainScreen } from './screens/MainScreen'

/**
 * 只有一屏。
 *
 * 没有路由 —— 这个产品的对象只有一棵考点树和五种动作,层级浅到不需要 URL 分层,
 * 导航全部压进 ⌘K(骨架规则 1、2)。等考点详情页 D5 落地时再谈路由,现在加是先付账。
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
  return (
    <QueryClientProvider client={queryClient}>
      <MainScreen />
    </QueryClientProvider>
  )
}

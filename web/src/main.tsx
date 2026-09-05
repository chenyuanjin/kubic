import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { Navigate, RouterProvider, createBrowserRouter } from 'react-router'
import './index.css'
import { sweepRawImagesOnStartup } from './lib/rawImageStore'
import { ROUTE_PATH, ROOT_REDIRECT, routeTo } from './routes/routes'
import { AppShell } from './screens/AppShell'
import { CaptureScreen } from './screens/CaptureScreen'
import { CoverageScreen } from './screens/CoverageScreen'
import { ExportScreen } from './screens/ExportScreen'
import { RecordsScreen } from './screens/RecordsScreen'
import { AgentScreen, ArchiveScreen, SettingsScreen } from './screens/SettingsScreen'
import { SyllabusScreen } from './screens/SyllabusScreen'

/**
 * 🔴 到期原图的第一条触发 —— docs/execution/INDEX.md `1.1.3.2`「到期自动归档」(2026-08-29 由「删除」改)。
 *
 * <h2>为什么这一行在 render <b>之前</b>,而且在 main.tsx 而不是某个组件里</h2>
 *
 * 放进组件的 `useEffect` 就意味着「那个组件被挂载过」才会清 ——
 * 而最该被清掉的恰恰是「用户昨晚导了一批图,今天打开只看了一眼盲区榜就关掉」的那批,
 * 那种会话里「记一笔」那一屏一次都没开过。
 * <p>
 * 引路由之后这条理由更强了:现在「那个组件被挂载过」还多了一层「那个地址被访问过」。
 * <p>
 * 它不 await:清理慢一点不该让首屏白着。清不掉也不报 —— 见那个函数的注释。
 */
sweepRawImagesOnStartup()

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 后端不可达时 queryFn 自己回退到离线示例数据并把原因带出来,重试只会让首屏变慢。
      retry: false,
    },
  },
})

/**
 * 路由树 —— 路径<b>全部从 `routes/routes.ts` 现读</b>,这个文件里一个字符串字面量都没有。
 *
 * `多端选型与端矩阵` §十 第 4 条自检:
 * `grep -rn "createBrowserRouter" web/src` 期望只在 `routes.ts` / `main.tsx`。
 * 这里是那两处里的一处,而 `routes.ts` 那一处是零 —— 它必须留在 node 能跑的纯判断层里
 * (`tests/routes.test.ts` 直接 import 它,那个 project 的 `lib` 里没有 DOM)。
 *
 * <h2>🔴 没有 `/login`</h2>
 *
 * 门由 `AppShell` 在 `<Outlet/>` 的位置替换整棵树,地址不变。
 * 「门只有里外,没有历史。」
 *
 * <h2>为什么 `capture` 与 `settings` 用了 splat</h2>
 *
 * `/capture/text` 与 `/capture` 是<b>同一屏的两个状态</b>(选中哪一格),不是两屏。
 * 拆成父子路由会让「回到 `/capture`」变成一次卸载 + 重挂载,输入框里的字会没。
 */
const router = createBrowserRouter([
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to={routeTo(ROOT_REDIRECT)} replace /> },
      { path: ROUTE_PATH.coverage, element: <CoverageScreen /> },
      { path: ROUTE_PATH['coverage.node'], element: <CoverageScreen /> },
      { path: ROUTE_PATH.syllabus, element: <SyllabusScreen /> },
      { path: `${ROUTE_PATH.capture}/*`, element: <CaptureScreen /> },
      { path: ROUTE_PATH.capture, element: <CaptureScreen /> },
      { path: ROUTE_PATH.records, element: <RecordsScreen /> },
      // 与 coverage 同型:列表与详情是同一个组件,≥1024 两栏并存,<1024 详情顶掉列表。
      { path: ROUTE_PATH['records.detail'], element: <RecordsScreen /> },
      { path: ROUTE_PATH.archive, element: <ArchiveScreen /> },
      { path: ROUTE_PATH.agent, element: <AgentScreen /> },
      { path: ROUTE_PATH.export, element: <ExportScreen /> },
      { path: `${ROUTE_PATH.settings}/*`, element: <SettingsScreen /> },
      { path: ROUTE_PATH.settings, element: <SettingsScreen /> },
      // 表里没有的地址回覆盖度。🔴 不做 404 屏:这个产品的地址空间就是上面那张表,
      // 表外的地址不是「一个坏了的页面」,它压根不是这个产品的一部分。
      { path: '*', element: <Navigate to={routeTo(ROOT_REDIRECT)} replace /> },
    ],
  },
])

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)

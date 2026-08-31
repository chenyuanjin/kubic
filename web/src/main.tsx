import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { sweepRawImagesOnStartup } from './lib/rawImageDb'

/**
 * 🔴 到期原图的第一条触发 —— docs/总路线图 `1.1.3.2`「到期自动归档」(2026-08-29 由「删除」改)。
 *
 * <h2>为什么这一行在 render <b>之前</b>,而且在 main.tsx 而不是某个组件里</h2>
 *
 * 放进组件的 `useEffect` 就意味着「那个组件被挂载过」才会清 ——
 * 而最该被清掉的恰恰是「用户昨晚导了一批图,今天打开只看了一眼盲区榜就关掉」的那批,
 * 那种会话里「记一笔」那一屏一次都没开过。
 * <p>
 * 它不 await:清理慢一点不该让首屏白着。清不掉也不报 —— 见那个函数的注释。
 */
sweepRawImagesOnStartup()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)

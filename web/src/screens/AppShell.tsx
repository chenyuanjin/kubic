import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router'
import { readToken, writeToken } from '../api/auth'
import { useDashboard } from '../api/queries'
import { CommandPalette } from '../features/CommandPalette'
import { StatusBar } from '../features/StatusBar'
import { OVERLAY_PALETTE, isOverlayOpen, routeTo } from '../routes/routes'
import { Dock, DockItem, Screen } from '../ui/layout'
import { IntroScreen } from './IntroScreen'

/**
 * 全端共用的外壳:门 → 路由树 → 屏底动作区 → 底栏。
 *
 * <h2>为什么门在这一层,而不是每一屏各判一次</h2>
 *
 * 因为「未登录访问<b>任何</b> route id」是一句全称命题。放到每一屏各写一次,
 * 少写一屏就漏一个洞,而漏掉的那一屏不会报错 —— 它会正常显示。
 *
 * <h2>三端跑的是同一个组件</h2>
 *
 * iPhone / Android / Pad / 桌面壳 <b>没有第二套外壳</b>。
 * 换的只有 CSS 断点(`index.css` 的 `.kb-*`)与安全区内边距。
 * `多端选型与端矩阵` §一:「不是『有些屏原生有些屏 Web』—— 那种混合会产生两份视觉真相。」
 */
export function AppShell() {
  const [token, setToken] = useState<string | null>(() => readToken())
  const { data } = useDashboard()
  const qc = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()

  // 面板开不开由历史条目决定,不由本组件的 state 决定 —— 判据在 routes.ts,那一层跑得进 node。
  const paletteOpen = isOverlayOpen(location.state, OVERLAY_PALETTE)

  const signOut = useCallback(() => {
    writeToken(null)
    setToken(null)
    qc.clear()
  }, [qc])

  /**
   * 全局快捷键 —— 现在每一个都落到一个<b>地址</b>上。
   *
   * 改动之前它们开的是 `useState` 浮层,于是按 ⌘N 打开采集、再按浏览器返回键会退出整个应用。
   * `多端选型与端矩阵` §4.6.2:「覆盖层也算页面……浮层的视觉形态不变,变的是由谁开。」
   *
   * 🔴 <b>搜索词不进地址</b>(§4.6.3):⌘K 面板现在占一个历史条目,但占的是 `location.state`,
   * 它进历史<b>不进 URL</b> —— 用户往里粘一整道题,那段字也不会进地址、日志、截图。
   * 搜索词本身仍然只活在面板自己的 state 里。收窄的是 query,不是页面。
   * 判据见 `routes.ts` 的 `isOverlayOpen`。
   */
  useEffect(() => {
    if (token === null) return
    function onKey(e: KeyboardEvent) {
      const mod = e.metaKey || e.ctrlKey
      const key = e.key.toLowerCase()

      if (e.key === 'Escape' && paletteOpen) {
        void navigate(-1)
        return
      }
      if (!mod) return

      if (key === 'k') {
        e.preventDefault()
        // 开:原地 push 一个历史条目,地址一个字符都不变。关:退回去,和按返回键同一条路。
        if (paletteOpen) void navigate(-1)
        else void navigate(location.pathname + location.search, { state: { overlay: OVERLAY_PALETTE } })
      } else if (key === 'b') {
        e.preventDefault()
        void navigate(routeTo('syllabus'))
      } else if (key === 'n') {
        e.preventDefault()
        void navigate(routeTo('capture'))
      } else if (key === 'e') {
        e.preventDefault()
        void navigate(routeTo('export'))
      } else if (key === 'j') {
        e.preventDefault()
        void navigate(routeTo('agent'))
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [token, paletteOpen, navigate, location.pathname, location.search])

  // 🔴 门:地址不变,原地渲染。登录完成后仍然停在同一个 location。
  if (token === null) {
    return <IntroScreen onDone={() => setToken(readToken())} />
  }

  return (
    <Screen>
      <Outlet context={{ signOut }} />

      {/* 🔴 底栏在屏底动作区【上面】,不在最下面。
          实测(Android 模拟器,2026-09-05):放在最下面时它被手势条压掉半行,
          而它写的正是「你现在看的数是真的还是离线示例」—— 那一行不能被压。
          `env(safe-area-inset-bottom)` 在这个 WebView 上是 0(系统把手势条画在应用之上),
          所以靠内边距救不回来;换成让屏底动作区去挨那条边 —— 它的 52px 高度经得起。 */}
      {data ? <StatusBar data={data} hint={location.pathname} /> : null}

      {/* 屏底动作区 —— 全产品动作的固定住址,五格,每一格都是一个地址。
          它不随屏变:住址会变,就不是住址了。 */}
      <Dock>
        <DockItem to="coverage" label="盲区" />
        <DockItem to="records" label="记录" />
        <DockItem to="capture" label="记一笔" />
        <DockItem to="export" label="出口" />
        <DockItem to="settings" label="设置" />
      </Dock>

      {/* 🔴 面板里跳走一律 `replace` —— 它替掉的正是覆盖层自己那个历史条目。
          不 replace 的话那个条目留在栈里,用户到了新一屏按返回键会先「回到一个开着面板的旧屏」,
          一个不存在的往返(和 §4.6.2 不给 `/login` 建路由是同一条理由)。
          跳走之后不再单独 onClose:那次 replace 已经把 overlay 从历史里拿掉,面板自己就卸载了 ——
          再补一次 navigate(-1) 会把刚跳到的那一屏又退回去。 */}
      {paletteOpen && data ? (
        <CommandPalette
          data={data}
          initialSearch=""
          onClose={() => void navigate(-1)}
          onJump={(code) => void navigate(routeTo('coverage.node', { nodeCode: code }), { replace: true })}
          onCapture={() => void navigate(routeTo('capture'), { replace: true })}
          onManageSyllabus={() => void navigate(routeTo('syllabus'), { replace: true })}
        />
      ) : null}
    </Screen>
  )
}

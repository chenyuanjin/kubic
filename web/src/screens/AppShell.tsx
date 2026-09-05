import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router'
import { readToken, writeToken } from '../api/auth'
import { useDashboard } from '../api/queries'
import { keyboardInset } from '../lib/keyboardInset'
import { CommandPalette } from '../features/CommandPalette'
import { StatusBar } from '../features/StatusBar'
import { routeTo } from '../routes/routes'
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
  const [paletteSearch, setPaletteSearch] = useState<string | null>(null)
  const { data } = useDashboard()
  const qc = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()

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
   * 🔴 <b>搜索词不进地址</b>(§4.6.3):命令面板仍然是 state,因为用户完全可能往里粘一整道题,
   * 而地址会进历史、进日志、进截图。这是「所有页面都要有 URL」唯一的一处收窄,
   * 收窄的是 query,不是页面。
   */
  useEffect(() => {
    if (token === null) return
    function onKey(e: KeyboardEvent) {
      const mod = e.metaKey || e.ctrlKey
      const key = e.key.toLowerCase()

      if (e.key === 'Escape' && paletteSearch !== null) {
        setPaletteSearch(null)
        return
      }
      if (!mod) return

      if (key === 'k') {
        e.preventDefault()
        setPaletteSearch((s) => (s === null ? '' : null))
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
  }, [token, paletteSearch, navigate])

  /**
   * 软键盘吃掉多少高度 → 一个 CSS 变量。
   *
   * 🔴 这个 effect 里<b>一条判断都没有</b>:算法整个在 `lib/keyboardInset.ts`,
   * 那一层不碰浏览器 API,所以能在 node 里被断言(`tests/keyboardInset.test.ts`)。
   * 这里只做两件事:订阅、写变量。
   * <p>
   * 放在门的 `return` <b>之前</b>是有意的 —— 门自己就有两个输入框,
   * 而它不在 `.kb-screen` 里,是全产品第一个会被键盘顶到的地方。
   */
  useEffect(() => {
    const vv = window.visualViewport
    if (!vv) return
    const sync = () => {
      document.documentElement.style.setProperty(
        '--kb-keyboard',
        `${String(keyboardInset(window.innerHeight, vv))}px`,
      )
    }
    sync()
    vv.addEventListener('resize', sync)
    vv.addEventListener('scroll', sync)
    return () => {
      vv.removeEventListener('resize', sync)
      vv.removeEventListener('scroll', sync)
    }
  }, [])

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

      {paletteSearch !== null && data ? (
        <CommandPalette
          data={data}
          initialSearch={paletteSearch}
          onClose={() => setPaletteSearch(null)}
          onJump={(code) => void navigate(routeTo('coverage.node', { nodeCode: code }))}
          onCapture={() => void navigate(routeTo('capture'))}
          onManageSyllabus={() => void navigate(routeTo('syllabus'))}
        />
      ) : null}
    </Screen>
  )
}

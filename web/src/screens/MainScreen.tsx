import { useCallback, useEffect, useMemo, useState } from 'react'
import { flattenNodes } from '../api/derive'
import { useDashboard } from '../api/queries'
import { BlindSpotSide } from '../features/BlindSpotSide'
import { CaptureSheet } from '../features/CaptureSheet'
import { CommandBar } from '../features/CommandBar'
import { CommandPalette } from '../features/CommandPalette'
import { CoverageHeader } from '../features/CoverageHeader'
import { NodeList } from '../features/NodeList'
import { StatusBar } from '../features/StatusBar'
import { SyllabusEditor } from '../features/SyllabusEditor'
import { Kbd } from '../ui/primitives'

/**
 * 主屏 —— 差集在这里被看见。
 *
 * 整屏没有一个卡片、没有一条面包屑、没有左侧菜单:
 * 位置信息在命令条左侧的 scope 段里,层级切换靠 ⌘K。
 *
 * <h2>两个视图,一条命令条</h2>
 *
 * `coverage` 看差集,`syllabus` 维护被减数(骨架层)。它们<b>不是两个页面</b> ——
 * 没有路由、没有第二条导航,scope 段换个词,主体换一块。加路由是先付账:
 * 这个产品的对象只有一棵树,层级浅到 URL 分层不带来任何东西。
 */
export function MainScreen() {
  const { data, isPending } = useDashboard()

  const [view, setView] = useState<'coverage' | 'syllabus'>('coverage')
  const [pickedCode, setPickedCode] = useState<string | null>(null)
  const [paletteSearch, setPaletteSearch] = useState<string | null>(null)
  const [captureOpen, setCaptureOpen] = useState(false)

  const flat = useMemo(() => (data ? flattenNodes(data.groups) : []), [data])

  // 默认落在第一个考点上 —— 一进来就有一行是选中的,键盘可以直接上下走。
  // 这是推导出来的,不是 effect 同步出来的:数据换了,选中行自然跟着走。
  const selectedCode = pickedCode ?? flat[0]?.code ?? null

  const paletteOpen = paletteSearch !== null
  const overlayOpen = paletteOpen || captureOpen

  const openPalette = useCallback((search: string) => {
    setCaptureOpen(false)
    setPaletteSearch(search)
  }, [])

  const closePalette = useCallback(() => setPaletteSearch(null), [])

  const toggleSyllabus = useCallback(() => {
    setPaletteSearch(null)
    setCaptureOpen(false)
    setView((v) => (v === 'syllabus' ? 'coverage' : 'syllabus'))
  }, [])

  const move = useCallback(
    (delta: number) => {
      if (flat.length === 0) return
      const at = flat.findIndex((n) => n.code === selectedCode)
      const next = Math.min(flat.length - 1, Math.max(0, (at === -1 ? 0 : at) + delta))
      setPickedCode(flat[next].code)
    },
    [flat, selectedCode],
  )

  /**
   * 全局快捷键。
   *
   * 徽标是常驻的,所以按键也必须是真的 —— 画一个 ⌘E 却按下去没反应,
   * 比不画更糟。⌘E / ⌘J 目前打开命令面板并直接把那条「未接入」的动作摆在眼前。
   *
   * ⌘B 是考点管理。选 B 不是因为它是「不 B 不可」,而是因为剩下的字母都被浏览器占了:
   * ⌘M 最小化窗口、⌘T 新标签、⌘L 地址栏、⌘U 看源码 —— 抢它们只会两边都不响应。
   */
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const mod = e.metaKey || e.ctrlKey
      const key = e.key.toLowerCase()

      if (e.key === 'Escape') {
        if (captureOpen) setCaptureOpen(false)
        else if (paletteOpen) closePalette()
        return
      }

      if (mod && key === 'k') {
        e.preventDefault()
        if (paletteOpen) closePalette()
        else openPalette('')
        return
      }
      if (mod && key === 'b') {
        e.preventDefault()
        toggleSyllabus()
        return
      }
      if (mod && key === 'n') {
        e.preventDefault()
        closePalette()
        setCaptureOpen(true)
        return
      }
      if (mod && key === 'e') {
        e.preventDefault()
        openPalette('导出')
        return
      }
      if (mod && key === 'j') {
        e.preventDefault()
        openPalette('问 AI')
        return
      }

      // 考点管理里满屏都是输入框,上下键属于光标,回车属于提交 —— 不能被列表导航抢走
      if (overlayOpen || mod || view === 'syllabus') return

      if (e.key === 'ArrowDown') {
        e.preventDefault()
        move(1)
      } else if (e.key === 'ArrowUp') {
        e.preventDefault()
        move(-1)
      } else if (e.key === 'Enter' && enterBelongsToList()) {
        e.preventDefault()
        setCaptureOpen(true)
      }
    }

    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [captureOpen, paletteOpen, overlayOpen, view, move, openPalette, closePalette, toggleSyllabus])

  if (isPending || !data) {
    return (
      <div className="flex h-full flex-col">
        <CommandBar scopeLead="考点" scopeStrong="加载中" placeholder="正在读 /api,读不到就退回离线示例数据" />
        <div className="flex flex-1 items-center justify-center font-mono text-[11.5px] text-t3">
          正在连 /api …
        </div>
      </div>
    )
  }

  const editing = view === 'syllabus'

  return (
    <div className="flex h-full flex-col">
      <CommandBar
        scopeLead={editing ? '考点树' : '考点'}
        scopeStrong={editing ? '维护骨架层' : `${data.subject.region} · ${data.subject.module}`}
        placeholder={editing ? '题型与考点就地改,回车提交' : '搜考点、切科目、执行动作,都在这条里'}
        source={data.source}
        onOpenPalette={() => openPalette('')}
        onCapture={editing ? undefined : () => setCaptureOpen(true)}
        right={
          // ⌘B 的徽标不在这里 —— 它在 CommandBar 里,因为它同时是窄屏唯一能点的那个入口
          <span className="hidden items-center gap-[9px] lg:flex">
            <Kbd>⌘E 导出</Kbd>
            <Kbd>⌘J 问 AI</Kbd>
          </span>
        }
        onToggleSyllabus={toggleSyllabus}
        syllabusOn={editing}
      />

      {editing ? (
        <SyllabusEditor data={data} onBack={toggleSyllabus} />
      ) : (
        <>
          <CoverageHeader summary={data.summary} subject={data.subject} />

          {/* xl 起是左右两栏、各滚各的;窄屏是一整条纵向滚动,副栏顺势落到主屏下面。
              同一份 DOM,换的只是 flex 方向和滚动条归谁 —— 没有第二套组件。 */}
          <div className="flex min-h-0 min-w-0 flex-1 flex-col overflow-y-auto xl:flex-row xl:overflow-hidden">
            <NodeList
              groups={data.groups}
              selectedCode={selectedCode}
              onSelect={setPickedCode}
              onOpen={(node) => {
                setPickedCode(node.code)
                setCaptureOpen(true)
              }}
            />
            <BlindSpotSide
              blindspots={data.blindspots}
              records={data.records}
              selectedCode={selectedCode}
              onSelect={setPickedCode}
              onAskAi={() => openPalette('问 AI')}
            />
          </div>
        </>
      )}

      <StatusBar
        data={data}
        hint={editing ? '改名不丢记录 · 有记录的考点删不掉' : '↑↓ 选 · ↵ 记一笔 · 双击行 记一笔'}
      />

      {paletteSearch !== null && (
        <CommandPalette
          data={data}
          initialSearch={paletteSearch}
          onClose={closePalette}
          onJump={(code) => {
            setView('coverage')
            setPickedCode(code)
          }}
          onCapture={() => {
            setView('coverage')
            setCaptureOpen(true)
          }}
          onManageSyllabus={() => setView('syllabus')}
        />
      )}

      {captureOpen && (
        <CaptureSheet
          groups={data.groups}
          records={data.records}
          initialNodeCode={selectedCode}
          source={data.source}
          onClose={() => setCaptureOpen(false)}
        />
      )}
    </div>
  )
}

/** 回车只在「焦点在列表上或没落在任何控件上」时才算「记一笔」,免得抢走按钮自己的回车。 */
function enterBelongsToList(): boolean {
  const el = document.activeElement
  if (!el || el === document.body) return true
  return el.closest('[data-code]') !== null
}

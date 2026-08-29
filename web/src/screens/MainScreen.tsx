import { useCallback, useEffect, useMemo, useState } from 'react'
import { flattenNodes } from '../api/derive'
import { useDashboard } from '../api/queries'
import { BlindSpotSide } from '../features/BlindSpotSide'
import { CaptureSheet } from '../features/CaptureSheet'
import { CommandBar } from '../features/CommandBar'
import { CommandPalette } from '../features/CommandPalette'
import { AgentChat } from '../features/AgentChat'
import { MobileTabs } from '../features/MobileTabs'
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
export function MainScreen({ onSignOut }: { onSignOut?: () => void } = {}) {
  const { data, isPending } = useDashboard()

  const [view, setView] = useState<'coverage' | 'syllabus'>('coverage')
  const [pickedCode, setPickedCode] = useState<string | null>(null)
  const [paletteSearch, setPaletteSearch] = useState<string | null>(null)
  const [captureOpen, setCaptureOpen] = useState(false)
  const [chatOpen, setChatOpen] = useState(false)

  /**
   * 🔴 窄屏上先看哪一栏。<b>默认是盲区,不是考点列表。</b>
   *
   * 北极星指标是「主动查看盲区的人数」(01 §六)。而在这之前,窄屏的布局是
   * 「18 个考点纵向排完,盲区栏落在最底下」—— 想看盲区得先滑过整张表。
   * <b>把产品的唯一那个数放在需要滚动才能到达的位置,是在跟自己的指标作对。</b>
   * <p>
   * xl 起两栏并排,这个状态不起作用 —— 桌面上两边同时看得见,本来就不需要选。
   */
  const [mobileTab, setMobileTab] = useState<'blind' | 'nodes'>('blind')

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
        if (chatOpen) setChatOpen(false)
        else if (captureOpen) setCaptureOpen(false)
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
        // 2026-08-28:⌘J 从「打开面板给你看一条『未接入』」变成真的打开问答。
        // 徽标常驻了很久,现在它背后终于有东西 —— 见 AgentChat。
        e.preventDefault()
        closePalette()
        setChatOpen((v) => !v)
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
  }, [captureOpen, chatOpen, paletteOpen, overlayOpen, view, move, openPalette, closePalette, toggleSyllabus])

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
            {onSignOut ? (
              // 只在【真的登录过】时出现。跳过登录进来的看不到它 ——
              // 给一个退不出去的「退出」比没有更让人困惑。
              <button
                type="button"
                onClick={onSignOut}
                title="吊销这台设备的令牌。服务端立刻失效,不等过期"
                className="font-mono text-[11px] text-t3 hover:text-tx"
              >
                退出
              </button>
            ) : null}
          </span>
        }
        onToggleSyllabus={toggleSyllabus}
        syllabusOn={editing}
      />

      {chatOpen && (
        /* 问答挂在底部而不是盖住整屏:用户问「我该先补哪个」时,
           上面那张考点表就是他要对照的东西 —— 盖掉它就得靠记忆去对。 */
        <div className="h-[55vh] shrink-0 border-b border-hair bg-bg2">
          <AgentChat onClose={() => setChatOpen(false)} />
        </div>
      )}

      {editing ? (
        <SyllabusEditor data={data} onBack={toggleSyllabus} />
      ) : (
        <>
          <CoverageHeader summary={data.summary} subject={data.subject} />

          {/* 🔴 窄屏上「盲区」默认在前,而且要能一步切回来 —— 见 MobileTabs 的注释。 */}
          <MobileTabs tab={mobileTab} onChange={setMobileTab} blindspotCount={data.summary.empty} />

          {/* xl 起是左右两栏、各滚各的;窄屏是一整条纵向滚动,副栏顺势落到主屏下面。
              同一份 DOM,换的只是 flex 方向和滚动条归谁 —— 没有第二套组件。
              窄屏再多一层:用 hidden 切哪一栏可见,xl 起两个 hidden 都失效,回到并排。 */}
          <div className="flex min-h-0 min-w-0 flex-1 flex-col overflow-y-auto xl:flex-row xl:overflow-hidden">
            <div
              className={`flex min-h-0 min-w-0 flex-1 flex-col ${
                mobileTab === 'nodes' ? 'flex' : 'hidden'
              } xl:flex`}
            >
            <NodeList
              groups={data.groups}
              selectedCode={selectedCode}
              onSelect={setPickedCode}
              onOpen={(node) => {
                setPickedCode(node.code)
                setCaptureOpen(true)
              }}
            />
            </div>
            <div
              className={`flex min-h-0 min-w-0 flex-col ${
                mobileTab === 'blind' ? 'flex' : 'hidden'
              } xl:flex`}
            >
              <BlindSpotSide
                blindspots={data.blindspots}
                records={data.records}
                selectedCode={selectedCode}
                onSelect={setPickedCode}
                onAskAi={() => openPalette('问 AI')}
              />
            </div>
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

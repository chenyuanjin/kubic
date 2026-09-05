import type { ReactNode } from 'react'
import { NavLink } from 'react-router'
import { routeTo } from '../routes/routes'
import type { RouteId } from '../routes/routes'

/**
 * 屏的骨架 —— U6.1 的三档断点在这里长出组件形态。
 *
 * <h2>🔴 这里一个几何数字都没有</h2>
 *
 * 480 / 720 / 1200 / 304 / 640 全在 `index.css` 的 `.kb-*` 那一段里,
 * 这个文件只挂类名。理由与 `design/h5/H5交互说明.md` 里那句
 * 「几何一律取 `design/v10/m6.html` 的 U6.1 那一行,<b>这里不抄数</b> ——
 * 抄一份就是多一份会过期的副本」是同一条。
 *
 * <h2>没有第二套组件</h2>
 *
 * 窄屏与宽屏用的是<b>同一份 DOM</b>,换的只有 CSS。
 * `多端选型与端矩阵` §3.2:「为 Pad 单开一份工程,买到的只有一组断点。」
 * 组件层要是分了叉,那句话就不成立了。
 */

/** 一整屏。`height:100dvh` + 内部滚动,滚动不发生在 body 上。 */
export function Screen({ children }: { children: ReactNode }) {
  return <div className="kb-screen">{children}</div>
}

/** 屏的主体:唯一的滚动容器。 */
export function ScreenBody({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <div className={`kb-body ${className}`}>{children}</div>
}

/**
 * 内容区封顶。
 *
 * <720 不封;720–1023 封到 640;≥1024 封到 1200。
 * 🔴 台账头与内容<b>同封</b>,否则头比内容宽出去一截(`design/h5/h5.css` 那条注释)。
 */
export function Cap({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <div className={`kb-cap ${className}`}>{children}</div>
}

/**
 * 双列。≥1024 才分栏,以下落回单列。
 *
 * 🔴 左栏是<b>内容不是菜单</b>,判据三条(`design/h5/H5交互说明.md` S-BLIND):
 * ① 它装的是骨架树,换一个科目整栏换掉;② 它一条也不列举别的屏;
 * ③ 它没有选中态高亮条,只有一条发丝线分栏。
 */
export function Cols({ children }: { children: ReactNode }) {
  // 🔴 `kb-cap` 不能省:内容区封顶 1200 是 U6.1 那一行的第三个数。
  // 少了它,1366 宽下左栏仍是 480 而右栏被拉到 886 —— 右栏「1024 以上恒为 720」
  // 那句话当场不成立(实测 2026-09-05,截图 w-pad1366)。
  return <div className="kb-cap kb-cols flex min-h-0 min-w-0 flex-1">{children}</div>
}

export function ColL({ children }: { children: ReactNode }) {
  return <div className="kb-col-l flex min-h-0 min-w-0 flex-col wide:overflow-y-auto">{children}</div>
}

/**
 * 右栏。
 *
 * 🔴 「窄屏上详情不并排」这件事<b>不在这里</b>,在 `CoverageScreen` 里 ——
 * 因为它取决于「有没有选中一个考点」,而那是屏的状态,不是栏的属性。
 * 早先这里有个 `detail` 开关(对应 `h5.css` 的 `.col-r--detail`),
 * 落地时发现它一次都没被传:窄屏要藏的是<b>两栏中的一栏</b>,藏哪一栏由选中态决定,
 * 一个只会藏右栏的开关表达不了。留一个没人传的参数就是下一次误用的入口。
 */
export function ColR({ children }: { children: ReactNode }) {
  return <div className="kb-col-r flex min-h-0 min-w-0 flex-col wide:overflow-y-auto">{children}</div>
}

/** 台账头:一屏的标题条。H5 上<b>没有屏内返回箭头</b> —— P-NAV 是浏览器返回键。 */
export function ScreenHead({
  title,
  sub,
  right,
}: {
  title: ReactNode
  sub?: ReactNode
  right?: ReactNode
}) {
  return (
    <header className="shrink-0 border-b border-hair bg-bg2">
      <Cap className="flex h-[46px] items-center gap-3 px-[var(--rule)]">
        <span className="truncate text-[13px] text-tx">{title}</span>
        {sub !== undefined && (
          <span className="truncate font-mono text-[11px] text-t3">{sub}</span>
        )}
        {right !== undefined && <span className="ml-auto flex items-center gap-2">{right}</span>}
      </Cap>
    </header>
  )
}

/**
 * 屏底动作区。sticky + 安全区,理由在 `index.css` 的 `.kb-dock` 上面。
 *
 * 🔴 `kb-dock-row` 里藏着一条对齐规矩:≥1024 时这一行的左缘 = 右栏内容左缘,同一条竖线
 * (稿 `design/v10/v10.css:314`)。规矩写在 CSS 里而不是这里,因为它派生自 `--col-l` ——
 * 这个文件仍然一个几何数字都没有。
 */
export function Dock({ children }: { children: ReactNode }) {
  return (
    <nav className="kb-dock shrink-0">
      <Cap className="kb-dock-row flex items-stretch">{children}</Cap>
    </nav>
  )
}

/**
 * 屏底那一格。
 *
 * 触控目标 44×44 是 `U6.4` 的无障碍下限,所以高度写死 52 —— 它不随断点变。
 * 🔴 用 `NavLink` 而不是 `button` + `navigate`:每一格都是一个<b>地址</b>,
 * 长按能复制、能被键盘 Tab 到、能被中键在新标签打开。做成按钮就把这三样全丢了。
 */
export function DockItem({ to, params, label }: { to: RouteId; params?: Record<string, string>; label: string }) {
  return (
    <NavLink
      to={routeTo(to, params)}
      className={({ isActive }) =>
        `flex h-[52px] flex-1 items-center justify-center font-mono text-[11.5px] ${
          isActive ? 'text-acid' : 'text-t2 hover:text-tx'
        }`
      }
    >
      {label}
    </NavLink>
  )
}

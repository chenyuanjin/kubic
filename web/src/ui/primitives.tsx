import { useState } from 'react'
import type { ReactNode } from 'react'

/**
 * 风格 A 的排版基元。
 *
 * 这些东西在设计稿里是 tokens.css 的 .kbd / .sd / .grp / .meter / .btn。
 * 抽成组件不是为了复用几行 class,是为了让「行高 34px」「1px 发丝线」「圆角 3px」
 * 这几个数字只存在一处 —— 密度是这个界面的核心体验,散在 18 个地方就守不住了。
 */

/* ========================================================================== */
/* 快捷键徽标 —— 常驻,不是 hover 才出现(骨架规则 9)                          */
/* ========================================================================== */

export function Kbd({ children, tone = 'plain' }: { children: ReactNode; tone?: 'plain' | 'on' | 'dark' }) {
  const toneClass =
    tone === 'on'
      ? 'bg-acid text-bg border-acid font-semibold'
      : tone === 'dark'
        ? 'bg-black/16 border-black/22 text-bg'
        : 'bg-bg3 text-t2 border-hair'
  return (
    <span
      className={`inline-block shrink-0 rounded-sm border px-[5px] py-[2px] font-mono text-[10px] leading-[1.3] whitespace-nowrap tabular-nums ${toneClass}`}
    >
      {children}
    </span>
  )
}

/* ========================================================================== */
/* 碰没碰过的标记 —— 实心是碰过的,空心虚线是还没碰过的                          */
/* ========================================================================== */

/**
 * 🔴 2026-09-06(`KUBI-111`):从五档状态点塌成<b>两档</b>。
 *
 * 原来的 `state: NodeState` 会去 `STATE_DOT` 取五种颜色,其中「弱」那一档的定义是
 * 「练过,但用户自填的对/练偏低」—— 那说的是答得对不对,而这个产品不判对不对。
 * 现役稿 `design/m3/04-tree.html:6` 给的是「实心/空心标记只是辅助」的<b>两态单色</b>写法。
 * <p>
 * 保留 `inline-block`:`<span>` 默认 display:inline,而<b>宽高对 inline 盒子无效</b>。
 * 早先这里没写,靠的是「它永远是某个 flex 容器的直接子元素」—— flex 会把子元素块级化。
 * 那个前提在被包进一层普通 span(为了挂 order-* 做窄屏折行)之后立刻不成立:
 * 标记全部塌成 0×0,整屏 18 行一个都看不见。
 */
export function StateDot({ touched, large = false }: { touched: boolean; large?: boolean }) {
  return (
    <span
      aria-hidden
      className={`box-border inline-block shrink-0 rounded-full ${large ? 'size-[9px]' : 'size-[7px]'} ${
        touched ? 'bg-t2' : 'border border-dashed border-t3'
      }`}
    />
  )
}

/* ========================================================================== */
/* 分组头 —— 「整块空白」这个产品语义就落在这一层                               */
/* ========================================================================== */

export function GroupHeader({
  title,
  right,
  alarm = false,
}: {
  title: ReactNode
  right?: ReactNode
  alarm?: boolean
}) {
  return (
    <div
      className={`flex h-[26px] shrink-0 items-center gap-2 border-b border-hair bg-bg3 px-3 font-mono text-[10px] tracking-[0.12em] uppercase ${
        alarm ? 'text-red' : 'text-t3'
      }`}
    >
      {title}
      {right !== undefined && <span className="ml-auto tracking-normal tabular-nums">{right}</span>}
    </div>
  )
}

/* ========================================================================== */
/* 密集行 —— 代替表格与卡片。选中靠 inset 左边条,这是唯一被允许的 box-shadow    */
/* ========================================================================== */

export function Row({
  children,
  selected = false,
  height = 29,
  className = '',
  onClick,
  onDoubleClick,
  title,
}: {
  children: ReactNode
  selected?: boolean
  /**
   * `'auto'` = 高度交给调用方的 `className`。
   *
   * 窄屏折行时高度必须随断点变(`h-[46px] md:h-[29px]`),而这里写死的 inline style
   * 会盖掉任何断点类 —— 内联样式没有断点。所以给一个显式的「我自己管高度」档位,
   * 而不是让调用方去和 `style` 打架。
   */
  height?: 29 | 34 | 42 | 'auto'
  className?: string
  onClick?: () => void
  onDoubleClick?: () => void
  title?: string
}) {
  const Tag = onClick ? 'button' : 'div'
  return (
    <Tag
      type={onClick ? 'button' : undefined}
      title={title}
      onClick={onClick}
      onDoubleClick={onDoubleClick}
      style={height === 'auto' ? undefined : { height: `${height}px` }}
      className={`flex w-full shrink-0 items-center gap-[11px] border-b border-hair px-3 text-left ${
        selected ? 'bg-sel shadow-[inset_2px_0_0_var(--color-acid)]' : 'hover:bg-bg2'
      } ${className}`}
    >
      {children}
    </Tag>
  )
}

/* ========================================================================== */
/* 就地编辑 —— 密集行里直接改字,不弹窗                                          */
/* ========================================================================== */

/**
 * 一个长得像文字、按下去就能改的输入框。
 *
 * <h2>为什么不是「点一下弹个对话框」</h2>
 *
 * 阶段 1 的主要工作是<b>反复校正命名</b> —— 一棵 18 个考点的树,名字要来回改十几轮。
 * 每改一个名字弹一次窗、点一次确定、等一次关闭动画,这件事就做不下去了。
 * 所以改名的成本必须压到「点进去、打字、回车」,和改表格单元格一样。
 *
 * <h2>提交时机</h2>
 *
 * 回车提交并失焦;esc 放弃并退回服务端的值;失焦时若有改动也提交(改完直接点下一行是常态,
 * 不该因为忘了按回车就丢掉)。<b>空值一律不提交</b> —— 清空输入框不是删除,
 * 删除是一个单独的、要看见「有几条记录挂着」的动作。
 *
 * <h2>🔴 存不下就<b>退回去</b></h2>
 *
 * `onCommit` 返回 false 时,输入框必须变回服务端的旧值。
 * 少了这一步,一次失败的改名会留下一个「看起来已经改好了」的输入框 ——
 * 下面同时挂着一条红色的「没存下来」,而人只会相信眼前那个名字。
 * 那正好是这个产品最不能犯的错:<b>界面上显示的东西必须是服务端真有的东西。</b>
 *
 * <h2>草稿要能被外面看见({@link onDraftChange})</h2>
 *
 * 重名必须<b>在打字的当下</b>就说出来 —— 「打完、回车、等一个来回、被拒、再改」这条路
 * 在一轮命名校正里要走几十遍,走不下去。而判重名要的是<b>正在打的那个字符串</b>,
 * 它在这个组件内部。所以开一个口子把草稿抛出去,由调用方在行下面画那段冲突说明。
 * <p>
 * 🔴 <b>它只在事件里触发,不在渲染中的那段同步里触发。</b>在子组件渲染过程中调用父组件的
 * setState,React 会当场警告并且行为不确定;而那一路(服务端的值变了)本来也不需要通知 ——
 * 那不是用户在打字。
 *
 * @param value         服务端当前的值。它一变(比如保存成功后重新拉回来),草稿就跟着重置
 * @param onCommit      只在「有改动且非空」时触发。返回是否被服务端收下
 * @param onDraftChange 草稿的每一次<b>用户侧</b>变化:打字、esc 退回、提交被拒后退回
 * @param pending       正在存。存的过程中锁住输入,免得用户在半空中又改一次
 */
export function InlineEdit({
  value,
  onCommit,
  onDraftChange,
  placeholder,
  ariaLabel,
  numeric = false,
  align = 'left',
  pending = false,
  disabled = false,
}: {
  value: string
  onCommit: (next: string) => Promise<boolean>
  onDraftChange?: (draft: string) => void
  placeholder?: string
  ariaLabel: string
  numeric?: boolean
  align?: 'left' | 'right'
  pending?: boolean
  disabled?: boolean
}) {
  const [draft, setDraft] = useState(value)
  const [seen, setSeen] = useState(value)

  // 服务端的值变了 → 草稿重置。用「渲染中调整 state」而不是 useEffect:
  // effect 会先用旧值渲染一帧,那一帧里用户看到的是刚被自己改掉的旧名字。
  if (value !== seen) {
    setSeen(value)
    setDraft(value)
  }

  /** 改草稿,并让外面知道。只从事件处理里走,不从上面那段渲染中的同步里走。 */
  function edit(next: string) {
    setDraft(next)
    onDraftChange?.(next)
  }

  async function commit() {
    const next = draft.trim()
    if (next === '' || next === value) {
      edit(value)
      return
    }
    // 收下了就什么都不做:重新拉回来的 value 会通过上面那段同步把草稿对齐。
    // 没收下就退回旧值 —— 界面上不许留下一个服务端并不知道的名字。
    if (!(await onCommit(next))) edit(value)
  }

  return (
    <input
      value={draft}
      aria-label={ariaLabel}
      placeholder={placeholder}
      disabled={disabled || pending}
      inputMode={numeric ? 'numeric' : undefined}
      onChange={(e) => edit(e.target.value)}
      onBlur={() => void commit()}
      onKeyDown={(e) => {
        if (e.key === 'Enter') {
          e.preventDefault()
          e.currentTarget.blur() // blur 里会 commit,不重复调
        } else if (e.key === 'Escape') {
          e.preventDefault()
          e.stopPropagation() // 别让 esc 顺手把整个视图也关掉
          edit(value)
          e.currentTarget.blur()
        }
      }}
      className={`h-[22px] w-full min-w-0 rounded-xs border border-transparent bg-transparent px-[5px] hover:border-hair hover:bg-bg3 focus:border-hair2 focus:bg-bg3 ${
        align === 'right' ? 'text-right' : ''
      } ${numeric ? 'font-mono text-[11.5px] tabular-nums' : 'text-[13px]'} ${
        pending ? 'text-t3 italic' : ''
      } ${disabled ? 'cursor-not-allowed text-t3' : ''} placeholder:text-t3 placeholder:not-italic`}
    />
  )
}

/**
 * 极窄的图标按钮 —— ↑ ↓ 这种一个字形的动作。
 *
 * 用 {@link Button} 的话 26×30 在一行里排四个就把名字挤没了,而名字才是这一屏的主体。
 */
export function MicroButton({
  children,
  title,
  ariaLabel,
  tone = 'plain',
  disabled = false,
  onClick,
}: {
  children: ReactNode
  title: string
  ariaLabel?: string
  tone?: 'plain' | 'danger'
  disabled?: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      title={title}
      aria-label={ariaLabel ?? title}
      disabled={disabled}
      onClick={onClick}
      className={`inline-flex size-[22px] shrink-0 items-center justify-center rounded-xs border border-hair font-mono text-[11px] leading-none ${
        disabled
          ? 'cursor-not-allowed text-t3 opacity-45'
          : tone === 'danger'
            ? 'text-red hover:border-red/40'
            : 'text-t2 hover:border-hair2 hover:text-tx'
      }`}
    >
      {children}
    </button>
  )
}

/* ========================================================================== */
/* 🔴 覆盖计量条 Meter 删于 2026-09-06(`KUBI-111`)                             */
/*                                                                            */
/* 它的五段就是那五档状态,和 STATE_BAR / --color-s-* 一起删。现役稿             */
/* `design/m3/04-tree.html:5` 明写「没有百分比、没有进度条(进度条会把覆盖度     */
/* 读成学习进度)」—— 覆盖度那一屏现在是竖式减法,见 features/CoverageHeader。   */
/* 底槽色 --color-track 只喂过它,跟着一起删。                                   */
/* ========================================================================== */
/* 大字 —— 等宽 44px,后缀小一号                                                */
/* ========================================================================== */

export function BigNumber({ value, suffix }: { value: ReactNode; suffix?: ReactNode }) {
  return (
    // 窄屏收到 34px:44px 的大字在 375 宽上会把概览区顶到半屏,而这一屏的主体是下面那 18 行。
    // 后缀跟着一起缩,因为它是 em 相对量。
    <div className="font-mono text-[34px] leading-none font-medium tracking-[-0.03em] tabular-nums sm:text-[44px]">
      {value}
      {suffix !== undefined && <span className="ml-px text-[0.38em] text-t2">{suffix}</span>}
    </div>
  )
}

/** 分区小标题。mono、字距拉开、全大写。 */
export function Label({ children }: { children: ReactNode }) {
  return (
    <span className="mb-1.5 block font-mono text-[10px] tracking-[0.1em] text-t3 uppercase">{children}</span>
  )
}

/* ========================================================================== */
/* 按钮                                                                        */
/* ========================================================================== */

export function Button({
  children,
  variant = 'plain',
  size = 'sm',
  block = false,
  disabled = false,
  onClick,
  title,
}: {
  children: ReactNode
  /** primary 用酸性绿。一屏只能有一个 —— 它标的是「当前主操作」。 */
  variant?: 'plain' | 'primary' | 'danger'
  size?: 'sm' | 'lg'
  block?: boolean
  disabled?: boolean
  onClick?: () => void
  title?: string
}) {
  const variantClass =
    variant === 'primary'
      ? 'bg-acid text-bg border-acid font-semibold'
      : variant === 'danger'
        ? 'text-red border-red/30'
        : 'text-t2 border-hair hover:border-hair2 hover:text-tx'
  return (
    <button
      type="button"
      title={title}
      disabled={disabled}
      onClick={onClick}
      className={`inline-flex shrink-0 items-center gap-1.5 rounded-sm border whitespace-nowrap ${
        size === 'lg' ? 'h-8 px-3.5 text-[12.5px]' : 'h-[26px] px-2.5 text-[11.5px]'
      } ${block ? 'flex w-full justify-center' : ''} ${
        disabled ? 'cursor-not-allowed border-hair text-t3 opacity-55' : variantClass
      }`}
    >
      {children}
    </button>
  )
}

/* ========================================================================== */
/* 标签 / 徽标                                                                 */
/* ========================================================================== */

export function Tag({ children, tone = 'plain' }: { children: ReactNode; tone?: 'plain' | 'on' | 'warn' }) {
  const toneClass =
    tone === 'on' ? 'border-acid text-acid' : tone === 'warn' ? 'border-red/35 text-red' : 'border-hair text-t2'
  return (
    <span
      className={`inline-flex h-5 shrink-0 items-center gap-[5px] rounded-sm border px-[7px] font-mono text-[11px] whitespace-nowrap ${toneClass}`}
    >
      {children}
    </span>
  )
}

/** 侧边一条竖线的说明块。warn 时竖线变红。 */
export function Note({ children, warn = false }: { children: ReactNode; warn?: boolean }) {
  return (
    <div
      className={`border-l-2 pl-[11px] text-[11.5px] leading-[1.8] text-t3 ${warn ? 'border-l-red' : 'border-l-hair2'}`}
    >
      {children}
    </div>
  )
}

/* ========================================================================== */
/* 几何图标 —— 零 emoji,一律 CSS 画                                            */
/* ========================================================================== */

export function GlyphIcon({ kind }: { kind: 'text' | 'image' | 'check' | 'mic' }) {
  const base = 'relative block size-[13px] shrink-0'
  if (kind === 'text') {
    return (
      <span
        aria-hidden
        className={base}
        style={{ background: 'repeating-linear-gradient(180deg,var(--color-t2) 0 1px,transparent 1px 5px)' }}
      />
    )
  }
  if (kind === 'mic') {
    return (
      <span
        aria-hidden
        className={`${base} h-[11px]`}
        style={{ background: 'repeating-linear-gradient(90deg,var(--color-t2) 0 2px,transparent 2px 5px)' }}
      />
    )
  }
  if (kind === 'image') {
    return (
      <span aria-hidden className={`${base} rounded-xs border border-t2`}>
        <span className="absolute bottom-px left-px block size-0 border-r-[4px] border-b-[6px] border-l-[4px] border-r-transparent border-b-t2 border-l-transparent" />
      </span>
    )
  }
  return (
    <span aria-hidden className={`${base} rounded-xs border border-t2`}>
      <span className="absolute top-px left-[3px] block h-1.5 w-[3px] rotate-[42deg] border-r-[1.2px] border-b-[1.2px] border-t2" />
    </span>
  )
}

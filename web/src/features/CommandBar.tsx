import type { ReactNode } from 'react'
import type { DataSource } from '../api/types'
import { Button, Kbd, Tag } from '../ui/primitives'

/**
 * 命令条 —— 它代替导航栏,任何页面都只有这一条。
 *
 * 左边是 scope(位置信息压在这儿,所以不需要面包屑),中间是搜索入口,
 * 右边是常驻快捷键徽标和当前主操作。整个产品没有第二处导航。
 *
 * <h2>手机上没有 ⌘</h2>
 *
 * 「导航全部压进 ⌘K」在一台没有键盘的设备上,字面意思是<b>没有导航</b>。
 * 所以徽标本身就是按钮:宽屏它是一枚常驻提示(骨架规则 9),窄屏它是唯一那个能点开
 * 命令面板的入口。同一个元素两种用法,不为手机另起一套。
 * <p>
 * 中间那段 placeholder 在宽屏也可点,但窄屏会被挤到只剩几个字 —— 不能只靠它。
 */
export function CommandBar({
  scopeLead,
  scopeStrong,
  scopeTail,
  placeholder,
  source,
  onOpenPalette,
  onCapture,
  onToggleSyllabus,
  syllabusOn = false,
  right,
}: {
  scopeLead: string
  scopeStrong: string
  scopeTail?: string
  placeholder: string
  source?: DataSource
  onOpenPalette?: () => void
  onCapture?: () => void
  /** 考点管理的开关。窄屏没有 ⌘B 可按,所以它必须同时是一个能点的东西。 */
  onToggleSyllabus?: () => void
  syllabusOn?: boolean
  right?: ReactNode
}) {
  return (
    <div className="flex h-[42px] shrink-0 items-center gap-[9px] border-b border-hair bg-bg2 px-3">
      <span aria-hidden className="size-[13px] shrink-0 rounded-xs border border-t3" />

      {/* 窄屏它是那个会被截断的弹性段(scope 是位置信息,截断也还认得出);
          sm 起交回给 placeholder,自己缩回原本的宽度。 */}
      <span className="min-w-0 flex-1 truncate font-mono text-[12px] text-t2 sm:flex-none">
        {scopeLead} / <b className="font-medium text-acid">{scopeStrong}</b>
        {scopeTail && <span className="hidden text-t2 sm:inline"> · {scopeTail}</span>}
      </span>
      <span className="hidden shrink-0 text-t3 sm:inline">·</span>

      {onOpenPalette ? (
        <button
          type="button"
          onClick={onOpenPalette}
          className="hidden min-w-0 flex-1 truncate text-left text-[12.5px] text-t3 hover:text-t2 sm:block"
        >
          {placeholder}
        </button>
      ) : (
        <span className="hidden min-w-0 flex-1 truncate text-[12.5px] text-t3 sm:block">{placeholder}</span>
      )}

      {source === 'mock' && (
        <Tag tone="warn">
          <span aria-hidden className="block size-[5px] rounded-full bg-red" />
          <span className="hidden sm:inline">离线示例数据</span>
          <span className="sm:hidden">离线</span>
        </Tag>
      )}

      {right}

      {/* ⌘K:宽屏是提示,窄屏是入口。徽标常驻,而且按下去/点下去都真的有反应。 */}
      {onOpenPalette && (
        <button type="button" onClick={onOpenPalette} aria-label="打开命令面板" className="shrink-0">
          <Kbd>⌘K</Kbd>
        </button>
      )}

      {/* 同上。徽标和入口是<b>同一个元素</b>,不是「宽屏画一个提示、窄屏另画一个按钮」——
          两个的话它们迟早会说两句不一样的话。宽屏多显示一个 ⌘B,少的那半在窄屏本来也按不出来。 */}
      {onToggleSyllabus && (
        <button
          type="button"
          onClick={onToggleSyllabus}
          aria-label={syllabusOn ? '回覆盖视图' : '管理考点树'}
          className="shrink-0"
        >
          <Kbd tone={syllabusOn ? 'on' : 'plain'}>
            <span className="hidden lg:inline">⌘B </span>考点树
          </Kbd>
        </button>
      )}

      {onCapture && (
        <Button variant="primary" onClick={onCapture}>
          记一笔
          {/* 手机上没有 ⌘,这枚徽标只是在抢 28px —— 而它抢走的是右边那条命令条的最后一点余量 */}
          <span className="hidden sm:inline">
            <Kbd tone="dark">⌘N</Kbd>
          </span>
        </Button>
      )}
    </div>
  )
}

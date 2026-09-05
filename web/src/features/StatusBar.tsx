import type { Dashboard } from '../api/types'
import { Kbd } from '../ui/primitives'

/**
 * 底部状态条。
 *
 * 🔴 2026-09-06(`KUBI-111`):这里那三个小圆点原来复用的是 `StateDot`,而 `StateDot`
 * 当时按考点的五档状态取色 —— 于是「离线」借用了「弱」那一档的红、「已连接」借用了「稳」。
 * 一个和考点状态毫无关系的地方在给五档背书。现在就地画一个继承当前文字色的点,
 * 三行的颜色由那一行自己的语义决定,不再经过任何状态表。
 *
 * 左边那一格是<b>数据从哪来</b>。后端不可达时它必须红着说「离线示例数据」并带上原因 ——
 * 静默回退等于骗人:界面上那个 44% 会被当成用户自己的真实覆盖率。
 */
export function StatusBar({ data, hint }: { data: Dashboard; hint?: string }) {
  const offline = data.source === 'mock'
  return (
    <div className="flex h-[26px] shrink-0 items-center gap-[15px] border-t border-hair bg-bg2 px-3 font-mono text-[10.5px] text-t3">
      {offline ? (
        <span className="inline-flex items-center gap-[5px] text-red">
          <Dot />
          离线示例数据 · {data.offlineReason}
        </span>
      ) : (
        <span className="inline-flex items-center gap-[5px]">
          <Dot />
          已连接 /api
        </span>
      )}

      {offline && <span className="hidden lg:inline">窗口重新聚焦会自动再试一次真接口</span>}

      {/* 记录页被截断时(`returned !== total`)这一页不是全部。
          🔴 这句话 2026-09-06 改过口径:原文是「练·对显示为「—」」,而「练·对」那一列
          已经随五档状态一起摘掉了 —— 留着原话就是指着一列不存在的东西说明。
          截断本身仍然要说:时间线上少了几条,是用户该知道的事实。 */}
      {!data.drillsKnown && (
        <span className="inline-flex items-center gap-[5px] text-red">
          <Dot />
          记录超出单次上限,这一页不是全部
        </span>
      )}

      {hint && <span className="hidden lg:inline">{hint}</span>}

      <span className="ml-auto flex items-center gap-[15px]">
        <span className="tabular-nums">
          {data.summary.total} 考点 · {data.summary.covered} 有记录 · {data.summary.percent}%
        </span>
        <Kbd>⌘K 命令条</Kbd>
      </span>
    </div>
  )
}

/** 状态条自己的小圆点。继承当前文字色,不查任何状态表。 */
function Dot() {
  return <span aria-hidden className="inline-block size-[7px] shrink-0 rounded-full bg-current" />
}

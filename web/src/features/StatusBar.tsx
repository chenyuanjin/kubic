import type { Dashboard } from '../api/types'
import { Kbd, StateDot } from '../ui/primitives'

/**
 * 底部状态条。
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
          <StateDot state="WEAK" />
          离线示例数据 · {data.offlineReason}
        </span>
      ) : (
        <span className="inline-flex items-center gap-[5px]">
          <StateDot state="STABLE" />
          已连接 /api
        </span>
      )}

      {offline && <span className="hidden lg:inline">窗口重新聚焦会自动再试一次真接口</span>}

      {/* 做题数由时间线里同一批原始记录求和(树接口不返回它)。记录被截断时求出来的和偏小,
          偏小的对/练会把「稳」显示成「弱」—— 所以那一列直接显示「—」,并在这里说明为什么。 */}
      {!data.drillsKnown && (
        <span className="inline-flex items-center gap-[5px] text-red">
          <StateDot state="RUSTY" />
          记录超出单次上限,练·对显示为「—」
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

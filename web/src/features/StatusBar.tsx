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
    // 🔴 min-h 不是 h:窄屏(实测 Android 412 CSS px)里右边那一组会折成两行,
    // 高度写死时它上下都溢出自己这一行 —— 上顶进页面内容、下压进屏底动作区,三段字叠在一起。
    // 让这一行自己变高,屏底动作区跟着往上,谁都不遮谁。(并自 KUBI-111-integration)
    <div className="flex min-h-[26px] shrink-0 items-center gap-[15px] border-t border-hair bg-bg2 px-3 font-mono text-[10.5px] text-t3">
      {offline ? (
        // 窄屏只截显示,不截信息:完整原因进 title,宽屏本来就放得下整句。
        // 🔴 「离线示例数据」这五个字任何断点都不许省 —— 那一格的存在理由就是不许静默回退。
        <span className="inline-flex min-w-0 items-center gap-[5px] text-red" title={data.offlineReason}>
          <Dot />
          <span className="shrink-0">离线示例数据 · </span>
          <span className="min-w-0 truncate">{data.offlineReason}</span>
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
        {/* 🔴 2026-09-06(`KUBI-111`):末尾那个百分比摘掉,只留两个计数。
            稿 `design/m3/04-tree.html:5` 明写「没有百分比、没有进度条(进度条会把覆盖度
            读成学习进度)」—— 那一屏的概览这一轮已经换成竖式减法,而屏底这一条是全仓
            最后一处百分比,留着它等于把刚摘掉的那个读法又摆回眼皮底下。
            `summary.percent` 是契约字段,前端不删,只是不显示。 */}
        <span className="tabular-nums">
          {data.summary.total} 考点 · {data.summary.covered} 有记录
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

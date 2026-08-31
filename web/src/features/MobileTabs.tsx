/**
 * 窄屏上的两栏切换 —— <b>只在窄屏出现</b>(`xl:hidden`)。
 *
 * <h2>为什么默认落在「盲区」而不是「考点」</h2>
 *
 * 北极星指标是<b>主动查看盲区的人数</b>(决策记录 §六)—— 不是注册数,不是 DAU。
 * 而在加这一条之前,窄屏的布局是「18 个考点纵向排完,盲区栏落在最底下」:
 * 想看盲区,得先滑过整张表。
 * <p>
 * <b>把产品唯一的那个数放在需要滚动才能到达的位置,是在跟自己的指标作对。</b>
 * 所以默认是盲区,考点列表退到第二个标签 —— 它随时点得到,只是不再挡在前面。
 *
 * <h2>为什么不做成底部 tab bar</h2>
 *
 * 底部 tab 是给<b>多个并列的功能区</b>用的(首页/消息/我的)。这里只有一件事的两个视角:
 * 「还没碰过的」和「全部」。做成底部导航会暗示还有第三个、第四个 ——
 * 而 决策记录 §2.5 已经定了这个产品不长成那样。<b>分段控件说的是「同一件事的两个切面」,
 * 底部导航说的是「几件不同的事」。</b>
 *
 * @param blindspotCount 空白考点数。写在标签上而不是等用户点进去才知道 ——
 *                       <b>那个数字本身就是这个产品要说的话</b>
 */
export function MobileTabs({
  tab,
  onChange,
  blindspotCount,
}: {
  tab: 'blind' | 'nodes'
  onChange: (t: 'blind' | 'nodes') => void
  blindspotCount: number
}) {
  return (
    <div className="flex shrink-0 gap-1 border-b border-hair px-3 py-2 xl:hidden">
      <Tab active={tab === 'blind'} onClick={() => onChange('blind')}>
        先补这几个
        <span className={`ml-1.5 font-mono ${tab === 'blind' ? 'text-bg' : 'text-red'}`}>
          {blindspotCount}
        </span>
      </Tab>
      <Tab active={tab === 'nodes'} onClick={() => onChange('nodes')}>
        全部考点
      </Tab>
    </div>
  )
}

function Tab({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      // 44px 高:这是拇指点得准的下限,比视觉上「够看」要大一圈。
      className={`h-11 flex-1 rounded-sm border text-[12.5px] ${
        active ? 'border-acid bg-acid font-semibold text-bg' : 'border-hair text-t2'
      }`}
    >
      {children}
    </button>
  )
}

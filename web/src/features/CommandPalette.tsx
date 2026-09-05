import { Command, useCommandState } from 'cmdk'
import { useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { orderedByBlindRank } from '../api/derive'
import type { Dashboard, NodeView } from '../api/types'
import { isTouched, myFact, relativeDayTime } from '../lib/format'
import { Kbd, StateDot, Tag } from '../ui/primitives'

/**
 * ⌘K 命令面板 —— <b>它就是导航栏</b>。
 *
 * <h2>为什么敢不要侧边栏</h2>
 *
 * 这个产品的对象只有一棵考点树和五种动作,层级浅到不需要常驻菜单。
 * 把导航压进 ⌘K,省下的横向空间全给密集行 —— 一屏 18 个考点是核心体验。
 *
 * 面板里同时出现「跳转」「动作」「最近记录」,是在说:
 * <b>位置、操作、历史是同一个输入口。</b>
 *
 * 🔴 未接入的动作一律显式标「未接入」并置灰,不做成点了没反应。
 *    半成品说自己是半成品,比假装能用便宜得多。
 */
export function CommandPalette({
  data,
  initialSearch,
  onClose,
  onJump,
  onCapture,
  onManageSyllabus,
}: {
  data: Dashboard
  /** 打开时预填的搜索词。⌘E / ⌘J 借此把对应动作直接摆到眼前。 */
  initialSearch: string
  onClose: () => void
  onJump: (code: string) => void
  onCapture: () => void
  onManageSyllabus: () => void
}) {
  // 面板由父层挂载/卸载,所以初始值直接进 useState —— 不需要一个 effect 去同步 open。
  const [search, setSearch] = useState(initialSearch)

  // 默认序:盲区名次靠前的在前。一打开面板就先看见该补的,而不是字母序。
  // 名次是【服务端给的】(node.rank),这里只按它排,不在前端复算一遍排序分。
  const nodes = useMemo(() => orderedByBlindRank(data.groups), [data.groups])

  return (
    <div className="kb-overlay fixed inset-0 z-50">
      {/* 浮层底:压暗,不加模糊也不加阴影 */}
      <button
        type="button"
        aria-label="关闭命令面板"
        onClick={onClose}
        className="absolute inset-0 w-full cursor-default bg-[rgba(6,7,8,0.72)]"
      />

      <div
        // 手机上 92px 的顶边距等于白扔掉四行 —— 面板本来就该离命令条近一点
        className="kb-palette rounded-sm border border-hair2 bg-bg"
        onKeyDown={(e) => {
          if (e.key === 'Escape') {
            e.stopPropagation()
            onClose()
          }
        }}
      >
        <Command label="命令面板" loop shouldFilter>
          {/* 面板顶部复用命令条:位置信息还是压在 scope 段里,依然没有面包屑 */}
          <div className="flex h-[42px] shrink-0 items-center gap-[9px] border-b border-hair bg-bg2 px-3">
            <span aria-hidden className="size-[13px] shrink-0 rounded-xs border border-t3" />
            <span className="shrink-0 font-mono text-[12px] whitespace-nowrap text-t2">
              考点 / <b className="font-medium text-acid">{`${data.subject.region} · ${data.subject.module}`}</b>
            </span>
            <span className="shrink-0 text-t3">·</span>
            {/* 面板一打开焦点就在这儿,再画一圈焦点框是多余的噪音 —— 命令条里不出现输入框边界 */}
            <Command.Input
              autoFocus
              value={search}
              onValueChange={setSearch}
              placeholder="跳到哪个考点,或者直接干点什么"
              className="min-w-0 flex-1 bg-transparent text-[12.5px] text-tx placeholder:text-t3 focus-visible:shadow-none"
            />
            {data.source === 'mock' && <Tag tone="warn">离线示例数据</Tag>}
            <Kbd>esc</Kbd>
          </div>

          <Command.List>
            <Command.Empty>没有匹配的考点或动作。考点只能从树里选,面板不会凭空造一个。</Command.Empty>

            <Command.Group heading={<Heading title="跳转考点" right={String(nodes.length)} />}>
              {nodes.map((node) => (
                <Item
                  key={node.code}
                  value={`${node.name} ${node.code}`}
                  keywords={[node.groupName, node.groupCode]}
                  onSelect={() => {
                    onJump(node.code)
                    onClose()
                  }}
                >
                  <StateDot touched={isTouched(node)} />
                  <span className="min-w-0 flex-1 truncate">{node.name}</span>
                  <span className="shrink-0 text-[11.5px] text-t3">{nodeMeta(node)}</span>
                  <Kbd>↵</Kbd>
                </Item>
              ))}
            </Command.Group>

            <Command.Group heading={<Heading title="执行动作" right="7" />}>
              <Item
                value="记一笔 新建记录"
                keywords={['jiyibi', 'record']}
                onSelect={() => {
                  onCapture()
                  onClose()
                }}
              >
                <span className="min-w-0 flex-1 truncate">记一笔</span>
                <Kbd>⌘N</Kbd>
              </Item>
              {/* 🔴 这一条是「管理<b>自己的</b>考点树」,不是「导入某家机构的考点体系」。
                  后者在这个面板里、在整个产品里都没有入口(R-07 / docs/decisions/实施路径.md §1.2)。 */}
              <Item
                value="管理考点树 考点管理 增删改 题型 kaodian syllabus"
                keywords={['guanli', 'syllabus', 'tree']}
                onSelect={() => {
                  onManageSyllabus()
                  onClose()
                }}
              >
                <span className="min-w-0 flex-1 truncate">
                  管理考点树 <span className="text-t3">增删改题型与考点 · 改名不丢记录</span>
                </span>
                <Kbd>⌘B</Kbd>
              </Item>
              <Item
                value="粘一段 paste"
                onSelect={() => {
                  onCapture()
                  onClose()
                }}
              >
                <span className="min-w-0 flex-1 truncate">粘一段</span>
                <Kbd>⌘V</Kbd>
              </Item>
              {/* 徽标从这条上摘掉了:⌘⇧I 在 Chrome 里是开发者工具,永远抢不到 ——
                  画一个按下去必然不响应的徽标,比不画更糟。 */}
              <Item value="拖张图进来 识别 photo" disabled>
                <span className="min-w-0 flex-1 truncate">拖张图进来 · 你自己截的图,看看能认出什么</span>
                <Tag tone="warn">未接入</Tag>
              </Item>
              <Item value="问 AI 时带上的东西 上下文" disabled>
                <span className="min-w-0 flex-1 truncate">问 AI 时带上的东西 · 组装并复制</span>
                <Tag tone="warn">未接入</Tag>
                <Kbd>⌘J</Kbd>
              </Item>
              <Item value="导出 Markdown CSV JSON export" disabled>
                <span className="min-w-0 flex-1 truncate">导出 · Markdown / CSV / JSON</span>
                <Tag tone="warn">未接入</Tag>
                <Kbd>⌘E</Kbd>
              </Item>
              <Item value="切科目 subject" disabled>
                <span className="min-w-0 flex-1 truncate">
                  切科目 <span className="text-t3">当前 {data.subject.display}</span>
                </span>
                <Tag tone="warn">只有一个模块</Tag>
                <Kbd>⌘P</Kbd>
              </Item>
            </Command.Group>

            {/* 🔴 「只有来源和时间」不是省略,是这条记录的全部内容 */}
            <Command.Group heading={<Heading title="最近记录" right="只有来源和时间" />}>
              {data.records.slice(0, 6).map((r) => (
                <Item key={r.id} value={`${r.sourceName} ${r.occurredAt}`} disabled>
                  <span className="w-[92px] shrink-0 font-mono text-[11px] text-t3 tabular-nums">
                    {relativeDayTime(r.occurredAt)}
                  </span>
                  <span className="min-w-0 flex-1 truncate text-t2">{r.sourceName}</span>
                  {/* 🔴 2026-09-06(`KUBI-111`):标签后面那个「12/10」删掉。
                      分子分母是「练了几道 / 对了几道」—— 那是在说答得对不对,不是「有没有」。
                      方式的中文名仍由服务端给(kindLabel)。 */}
                  <Tag>{r.kindLabel}</Tag>
                </Item>
              ))}
            </Command.Group>
          </Command.List>

          <div className="flex h-8 shrink-0 items-center gap-[15px] border-t border-hair bg-bg2 px-3 font-mono text-[10.5px] text-t3">
            <span>↑↓ 选</span>
            <span>↵ 打开</span>
            <span>esc 关</span>
            <span className="ml-auto">这条就是导航栏。没有别的菜单。</span>
          </div>
        </Command>
      </div>
    </div>
  )
}

/**
 * 分组头。
 *
 * 右边那个数说的是「这组里有几条」,一旦开始筛选,列表已经被 cmdk 过滤过,
 * 再挂着未筛选前的总数就是错的 —— 所以有搜索词时直接不显示,而不是显示一个陈旧的数。
 */
function Heading({ title, right }: { title: string; right: string }) {
  const searching = useCommandState((state) => state.search.length > 0)
  return (
    <>
      <span>{title}</span>
      <span className="ml-auto tracking-normal">{searching ? '' : right}</span>
    </>
  )
}

function Item({
  children,
  value,
  keywords,
  disabled,
  onSelect,
}: {
  children: ReactNode
  value: string
  keywords?: string[]
  disabled?: boolean
  onSelect?: () => void
}) {
  return (
    <Command.Item
      value={value}
      keywords={keywords}
      disabled={disabled}
      onSelect={onSelect}
      className="flex h-[29px] cursor-default items-center gap-[11px] border-b border-hair px-3 text-[13px]"
    >
      {children}
    </Command.Item>
  )
}

/**
 * 面板里那一小行状态摘要 —— 只说有没有、几次、多久前。
 *
 * 🔴 2026-09-06(`KUBI-111`)整个函数重写。原来它按五档 `node.state` 分五支,
 * 每支都以服务端的五档中文名 `stateLabel`(空白/仅接触/生疏/弱/稳)开头,
 * 其中「稳 / 弱」那一支还直接拼出「练 N 对 M」—— 五档状态与那个比值同时在场。
 * 现在两句都由骨架事实 + 我的事实拼成,与盲区榜那一行(`myFact`)同一套词。
 */
function nodeMeta(node: NodeView): string {
  return `近五年 ${node.recent5yCount} 次 · ${myFact(node)}`
}

import { useState } from 'react'
import type { ReactNode } from 'react'
import { ApiUnavailableError, describeError } from '../api/client'
import { useArchivedNodes, useSyllabusEdit } from '../api/queries'
import type { Dashboard, EditRejection, GroupView, NodeView, SyllabusNodeDto } from '../api/types'
import { buildNameIndex, findNameClash, invisibleChars, nameKey, revealName } from '../lib/names'
import type { NameIndex, NameOwner } from '../lib/names'
import { Button, GroupHeader, InlineEdit, Kbd, MicroButton, Note, StateDot } from '../ui/primitives'

/**
 * 考点管理 —— 骨架层的维护界面。server: api/SyllabusAdminController.java。
 *
 * <h2>它维护的是差集的<b>被减数</b></h2>
 *
 * `盲区 = 骨架层 − 行为层`。行为层由「记一笔」一条条堆出来,骨架层只能靠人维护 ——
 * 这一屏就是那个维护动作的全部。树错了,整屏的覆盖率、五态、盲区榜全都是错的,
 * 而它们错起来不会报错,只会安安静静地给出一个错的答案。
 *
 * <h2>为什么全部就地编辑,一个模态框都没有</h2>
 *
 * 阶段 1(docs/实施路径 §1.2)的主要工作是<b>反复校正命名</b>:一棵 18 个考点的树,
 * 名字要来回改十几轮才稳。每改一个名字弹一次窗、点一次确定,这件事在第三轮就会被放弃,
 * 而放弃校正命名 = 树是错的 = 上面那一整段。所以密度在这里是功能,不是审美。
 *
 * <h2>🔴 删除守则:有记录就不许删,而且要说出有几条、还能走哪条路</h2>
 *
 * 记录挂在 code 上。删掉一个有记录的考点,那些记录就成了孤儿,覆盖率的分子分母同时少一个 ——
 * <b>而覆盖率是这个产品唯一的那个数</b>,用户只会看见百分比莫名其妙地动了。
 * 服务端为此专门回 409 `NODE_HAS_RECORDS`(不是笼统的 400),它的 javadoc 写着:
 * 「界面要在这里给出『搬记录』和『归档』两个按钮,而不是一句『删除失败』。」这一屏照做。
 *
 * <h2>🔴 三条红线在这一屏的形态</h2>
 *
 * <ul>
 * <li><b>考点自行命名</b>(R-07 / docs/实施路径 §1.2)—— 没有「从机构导入考点体系」这个入口,
 *     `api/types.ts` 里连对应的请求类型都没有,服务端也明说以后不会有。
 *     有的是<b>导出</b>,它的反向操作是把文件放回 `~/.kaodian/syllabus.json`。
 * <li><b>只有三层</b>(决策记录 §2.5)—— 只有「新增题型」和「在题型下新增考点」两种新增。
 *     考点行上找不到「加子项」,是因为 `CreateNodeRequest` 里只有 `groupCode`,
 *     压根没有 `parentNodeCode` 这个位置。
 * <li><b>不判断对错</b> —— 能改的只有名称、所属题型、顺序、近五年频次。
 *     频次是<b>统计事实</b>,不是难度、不是重要性,更没有掌握度。
 * </ul>
 *
 * <h2>🔴 不做乐观更新</h2>
 *
 * 改动只在服务端确认之后才出现在界面上,存不下就把输入框退回旧值并把原因原样贴出来。
 * <b>「刚才那下到底存没存」是这个产品最不能让用户产生的疑问。</b>
 *
 * <h2>🔴 重名要在打字的当下就说清楚</h2>
 *
 * 考点名<b>整棵树唯一,并且包含已归档的</b>(server 侧 store 层强制,409 `NAME_TAKEN`)。
 * 这一屏为此做两件事,顺序不能反:
 * <ul>
 * <li><b>本地预判</b> —— 整棵树的数据本来就在手上,打字的当下就能指出撞的是谁、它在哪个题型下。
 *     少一个来回,一轮命名校正才走得下去(见 lib/names.ts)。
 * <li><b>服务端 409 才是权威</b> —— 本地放过而服务端拒绝是正常的(本地这棵树是上一轮 GET 的),
 *     那时如实报错,<b>不因为本地判过就假设一定成功</b>。
 * </ul>
 * 冲突对象已归档时要特别说明:它<b>不在树上</b>,用户翻遍界面看不见它,只会觉得「我明明没有重名」。
 */
export function SyllabusEditor({ data, onBack }: { data: Dashboard; onBack: () => void }) {
  const edit = useSyllabusEdit()

  /** 🔴 离线示例数据不是用户自己的树,改它没有意义 —— 索性锁死,而不是让每次保存都失败。 */
  const locked = data.source === 'mock'

  const archived = useArchivedNodes(!locked)

  /** 正在存的那一行(行键)。同一时刻只可能有一个 —— 这是一个人在打字,不是一个批处理。 */
  const [busy, setBusy] = useState<string | null>(null)

  /** 上一次失败:哪一行、什么原因、服务端给的机器可读码。原因原样显示,不美化。 */
  const [failed, setFailed] = useState<Failure | null>(null)

  /** 哪一行正展开着抽屉。同一时刻只有一处,不做多处展开。 */
  const [open, setOpen] = useState<string | null>(null)

  /**
   * 归档区里被点亮的那一行。
   *
   * 「名字被一个已归档的考点占着」这句话说完还不够 —— 那个考点在树上看不见,
   * 用户得先找到它才能改名。所以提示里给一个能点的出口,点完把它<b>滚进视野并点亮</b>。
   */
  const [spot, setSpot] = useState<string | null>(null)

  /**
   * 跑一次写请求。
   *
   * 成功返回 `true`(调用方据此清掉输入框),失败返回 `false` 并把原因挂到那一行下面。
   * <b>不 catch 成静默</b>:这里的每一次失败都要在界面上留下痕迹。
   *
   * @param attempted 这次想改成的名字。只有改名/新增会给 —— 服务端回 409 `NAME_TAKEN` 时,
   *                  要拿它回到本地清单里问一句「占着这个名字的是谁」,才好指出那个已归档的考点。
   */
  async function run(key: string, fn: () => Promise<unknown>, attempted: string | null = null): Promise<boolean> {
    setBusy(key)
    setFailed(null)
    try {
      await fn()
      return true
    } catch (err) {
      setFailed({
        key,
        message: describeError(err),
        // 分支只认这个稳定的 code,不去匹配中文文案 —— 后者改一个字就断
        code: err instanceof ApiUnavailableError ? err.code : null,
        attempted,
      })
      return false
    } finally {
      setBusy(null)
    }
  }

  /** 把归档区里的某一行滚进视野并点亮。行此刻已经在 DOM 里(归档区一直渲染),不用等下一帧。 */
  function focusArchived(code: string) {
    setSpot(code)
    document.querySelector(`[data-archived="${CSS.escape(code)}"]`)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }

  const groups = data.groups
  const allNodes = groups.flatMap((g) => g.nodes)
  const nodeTotal = allNodes.length

  // 归档的考点不在 /tree 里(归档的意思就是退出差集),所以题型能不能删要把它们算进去 ——
  // 服务端的判据是「下面还有考点,含已归档的」。只看树会让界面说「能删」而服务端回 409。
  const archivedByGroup = new Map<string, SyllabusNodeDto[]>()
  for (const n of archived.data?.items ?? []) {
    if (!n.groupCode) continue
    const list = archivedByGroup.get(n.groupCode)
    if (list) list.push(n)
    else archivedByGroup.set(n.groupCode, [n])
  }

  // 谁占着哪些名字。归档的也算进来 —— 名字的唯一性范围包含它们(否则「取消归档」能静默造出重名)。
  // 归档清单没拉到时 archivedKnown 为 false,本地预判会漏掉「被归档考点占着」那一类,界面据此把话说软。
  const names = buildNameIndex(groups, archived.data?.items ?? [], archived.data !== undefined)

  const ctx: Ctx = { locked, busy, failed, open, setOpen, run, edit, groups, allNodes, names, spot, focusArchived }

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      {/* ── 常驻说明。不折叠、不随列表滚走 ──────────────────────────────────
          这三句话回答的是三个会让人不敢动这棵树的顾虑。第一句尤其重要:
          「改名会不会把历史弄没」如果没被当面回答掉,用户就不会去校正命名。 */}
      <div className="shrink-0 border-b border-hair px-4 py-3">
        <div className="flex flex-wrap gap-x-7 gap-y-2.5">
          <div className="min-w-[236px] flex-1">
            <Note>
              <b className="font-normal text-tx">改名不会弄丢记录。</b>
              <br />
              记录挂的是代号(code),不是名字。改多少轮,一条都不掉。
            </Note>
          </div>
          <div className="min-w-[236px] flex-1">
            <Note warn>
              <b className="font-normal text-tx">有记录的考点删不掉。</b>
              <br />
              拦住的地方会写清楚有几条挂在上面,并给出「归档」和「搬记录」两条出路。
            </Note>
          </div>
          <div className="min-w-[236px] flex-1">
            <Note>
              <b className="font-normal text-tx">考点由你自己命名。</b>
              <br />
              这里没有「从机构导入考点体系」这种入口 —— 沿用别人的措辞,这棵树就不是你的了。
            </Note>
          </div>
        </div>
      </div>

      {locked && (
        <div className="shrink-0 border-b border-hair bg-bg2 px-4 py-2.5">
          <Note warn>
            当前显示的是<b className="font-normal text-red">离线示例数据</b>,后端不可达
            {data.offlineReason ? `(${data.offlineReason})` : ''}。
            <br />
            这棵树不是你的树,所以这一屏整个锁住了 —— 与其让每一次保存都失败,不如一开始就不给改。
          </Note>
        </div>
      )}

      <div className="min-h-0 min-w-0 flex-1 overflow-y-auto">
        {groups.map((group, gi) => (
          <GroupBlock
            key={group.code}
            ctx={ctx}
            group={group}
            index={gi}
            archivedHere={archivedByGroup.get(group.code)?.length ?? 0}
          />
        ))}

        <NewGroupRow
          ctx={ctx}
          busy={busy === 'new-group'}
          failure={failed?.key === 'new-group' ? failed : null}
          onCreate={(name) => run('new-group', () => edit.createGroup.mutateAsync({ name }), name)}
        />

        <ArchivedSection ctx={ctx} items={archived.data?.items ?? []} error={archived.error} />

        {/* 🔴 「不做第四层」在界面上的形态:说出来,而不是让人自己发现没有那个按钮 */}
        <div className="px-4 py-4">
          <Note>
            只有三层:<b className="font-normal text-t2">模块 → 题型 → 考点</b>。考点下面不再分子项。
            <br />
            三层已经够表达「一整块题型都没碰过」,而那正是树相对一份扁平清单的唯一优势;再切细会让粒度失控。
          </Note>
        </div>
      </div>

      <div className="flex h-[34px] shrink-0 items-center gap-3 border-t border-hair bg-bg2 px-3 font-mono text-[10.5px] text-t3">
        <span className="tabular-nums">
          {groups.length} 个题型 · {nodeTotal} 个考点
          {archived.data && archived.data.count > 0 ? ` · ${archived.data.count} 个已归档` : ''}
        </span>
        <span className="hidden md:inline">↵ 提交 · esc 放弃这次改动</span>
        <span className="ml-auto">
          <Button onClick={onBack}>
            回覆盖视图 <Kbd>⌘B</Kbd>
          </Button>
        </span>
      </div>
    </div>
  )
}

/* ========================================================================== */
/* 贯穿各行的上下文                                                             */
/* ========================================================================== */

interface Failure {
  key: string
  message: string
  /** 服务端的机器可读码。传输层失败(连不上)时为 null。 */
  code: string | null
  /** 这次想改成的名字。只有改名/新增会带上,用来在 409 `NAME_TAKEN` 之后指认占用者。 */
  attempted: string | null
}

/**
 * 这一行失败是不是因为某个具体的拒绝理由。
 *
 * 走一个函数而不是直接写 `failed?.code === 'NODE_HAS_RECORDS'`,是为了让第二个参数被
 * {@link EditRejection} 约束住:拼错成 `NODE_HAS_RECORD` 会在<b>编译期</b>被拦下,
 * 而不是变成一个永远为 false 的分支 —— 那种分支的表现是「删除守则的出路那一段从来不出现」,
 * 而它恰恰是没人会去主动测的路径。
 */
function rejectedAs(failure: Failure | null, reason: EditRejection): boolean {
  return failure?.code === reason
}

type Edit = ReturnType<typeof useSyllabusEdit>
type Run = (key: string, fn: () => Promise<unknown>, attempted?: string | null) => Promise<boolean>

interface Ctx {
  locked: boolean
  busy: string | null
  failed: Failure | null
  open: string | null
  setOpen: (key: string | null) => void
  run: Run
  edit: Edit
  groups: GroupView[]
  allNodes: NodeView[]
  /** 谁占着哪些名字(含已归档的考点)。只用于打字时的预判,服务端才是判据。 */
  names: NameIndex
  /** 归档区里被点亮的那一行。 */
  spot: string | null
  focusArchived: (code: string) => void
}

/* ========================================================================== */
/* 题型块 —— 一个题型头 + 它下面的考点 + 一行「新考点」                           */
/* ========================================================================== */

function GroupBlock({ ctx, group, index, archivedHere }: { ctx: Ctx; group: GroupView; index: number; archivedHere: number }) {
  const key = `group:${group.code}`
  const pending = ctx.busy === key
  const failure = ctx.failed?.key === key ? ctx.failed : null

  // 题型名同样整树唯一。撞名的题型在「所属题型」下拉里长得一模一样 —— 那个下拉决定考点挂在哪。
  const watch = useNameWatch(ctx.names.groups, group.code, group.name)

  // 服务端的判据:题型下面还有考点(含已归档的)就不许删 —— 连带删除是「删一个考点会丢数据」的放大版
  const remaining = group.nodes.length + archivedHere
  const blocked = remaining > 0 || rejectedAs(failure, 'GROUP_NOT_EMPTY')

  const codes = ctx.groups.map((g) => g.code)

  return (
    <div>
      <div className="flex flex-wrap items-center gap-x-[9px] gap-y-[3px] border-b border-hair bg-bg3 px-3 py-1.5 md:h-[34px] md:flex-nowrap md:py-0">
        <span className="order-1 shrink-0 font-mono text-[10px] tracking-[0.12em] text-t3 uppercase">题型</span>
        <span className="order-2 min-w-0 flex-1">
          <InlineEdit
            value={group.name}
            ariaLabel={`题型名称 ${group.name}`}
            disabled={ctx.locked}
            pending={pending}
            onDraftChange={watch.onDraftChange}
            onCommit={(name) =>
              watch.commit(name, (n) =>
                ctx.run(key, () => ctx.edit.renameGroup.mutateAsync({ code: group.code, name: n }), n),
              )
            }
          />
        </span>
        <span className="order-3 shrink-0 font-mono text-[10.5px] text-t3 tabular-nums md:order-4">
          {group.nodes.length} 考点{archivedHere > 0 ? ` +${archivedHere} 归档` : ''} · 频次 {group.recent5yCount}
        </span>
        <i aria-hidden className="order-4 h-0 basis-full md:hidden" />
        <span className="order-5 flex shrink-0 items-center gap-[5px]">
          <MicroButton
            title="上移这个题型"
            disabled={ctx.locked || pending || index === 0}
            onClick={() => void ctx.run(key, () => ctx.edit.reorderGroups.mutateAsync(swapped(codes, index, index - 1)))}
          >
            ↑
          </MicroButton>
          <MicroButton
            title="下移这个题型"
            disabled={ctx.locked || pending || index === codes.length - 1}
            onClick={() => void ctx.run(key, () => ctx.edit.reorderGroups.mutateAsync(swapped(codes, index, index + 1)))}
          >
            ↓
          </MicroButton>
          <MicroButton
            title={blocked ? `删不掉:下面还有 ${remaining} 个考点` : '删除这个题型'}
            tone="danger"
            disabled={ctx.locked || pending}
            onClick={() => ctx.setOpen(ctx.open === key ? null : key)}
          >
            删
          </MicroButton>
        </span>
        <span className="order-6 shrink-0 font-mono text-[10px] text-t3">{group.code}</span>
      </div>

      {/* 正在打的那个名字有没有问题 —— 在这一行下面当场说,不等提交 */}
      {watch.problem && <Drawer warn>{watch.problem}</Drawer>}
      {watch.clash && (
        <Drawer warn>
          <ClashNote ctx={ctx} owner={watch.clash} />
        </Drawer>
      )}

      {ctx.open === key && (
        <Drawer warn={blocked}>
          {blocked ? (
            <>
              <b className="font-normal text-red">删不掉。</b>「{group.name}」下面还有{' '}
              <span className="font-mono tabular-nums">{remaining}</span> 个考点
              {archivedHere > 0 ? `(其中 ${archivedHere} 个已归档)` : ''}。
              <br />
              连带删除会一次把一整组考点连同它们的记录变成孤儿,所以这一层反而不能更宽松。
              <b className="font-normal text-tx">先把这些考点挪到别的题型下</b>,或者直接改名 —— 改名不动任何记录。
              <div className="mt-2">
                <Button onClick={() => ctx.setOpen(null)}>知道了</Button>
              </div>
            </>
          ) : (
            <>
              确认删除题型「{group.name}」?它下面已经没有考点了(已归档的也算),删掉不会丢任何记录。
              <div className="mt-2 flex items-center gap-2">
                <Button
                  variant="danger"
                  disabled={pending}
                  onClick={async () => {
                    if (await ctx.run(key, () => ctx.edit.deleteGroup.mutateAsync(group.code))) ctx.setOpen(null)
                  }}
                >
                  {pending ? '删除中…' : '确认删除'}
                </Button>
                <Button onClick={() => ctx.setOpen(null)}>取消</Button>
              </div>
            </>
          )}
        </Drawer>
      )}

      {failure && !rejectedAs(failure, 'GROUP_NOT_EMPTY') && <FailDrawer ctx={ctx} failure={failure} self={group.code} />}

      {group.nodes.map((node, ni) => (
        <NodeEditRow key={node.code} ctx={ctx} node={node} group={group} index={ni} />
      ))}

      <NewNodeRow
        ctx={ctx}
        groupName={group.name}
        busy={ctx.busy === `new-node:${group.code}`}
        failure={ctx.failed?.key === `new-node:${group.code}` ? ctx.failed : null}
        onCreate={(name, recent5yCount) =>
          ctx.run(
            `new-node:${group.code}`,
            () => ctx.edit.createNode.mutateAsync({ groupCode: group.code, name, recent5yCount }),
            name,
          )
        }
      />
    </div>
  )
}

/* ========================================================================== */
/* 考点行 —— 名称 / 频次 / 所属题型 / 顺序 / 归档 / 删除,全部就地                 */
/* ========================================================================== */

function NodeEditRow({ ctx, node, group, index }: { ctx: Ctx; node: NodeView; group: GroupView; index: number }) {
  const key = `node:${node.code}`
  const pending = ctx.busy === key
  const failure = ctx.failed?.key === key ? ctx.failed : null

  /**
   * 🔴 这一行就是那道闸门。
   *
   * `touchCount` 是服务端给的「这个考点上挂了几条记录」。大于 0 就不能删。
   * 后面那个 `||` 是安全网:本地这个数是上一轮 GET 拿到的,可能已经旧了 ——
   * 服务端回 409 `NODE_HAS_RECORDS` 时,即便本地以为是 0,也要把出路那一段摆出来。
   */
  const blocked = node.touchCount > 0 || rejectedAs(failure, 'NODE_HAS_RECORDS')
  const codes = group.nodes.map((n) => n.code)

  // 重名预判。范围是整棵树(含已归档),不是本题型内 —— 面板上挑考点看不见题型。
  const watch = useNameWatch(ctx.names.nodes, node.code, node.name)

  return (
    <div data-code={node.code}>
      <div className="flex flex-wrap items-center gap-x-[9px] gap-y-[3px] border-b border-hair px-3 py-1 hover:bg-bg2 md:h-[34px] md:flex-nowrap md:py-0">
        <span className="order-1 flex shrink-0 items-center">
          <StateDot state={node.state} />
        </span>

        <span className="order-2 min-w-0 flex-1">
          <InlineEdit
            value={node.name}
            ariaLabel={`考点名称 ${node.name}`}
            disabled={ctx.locked}
            pending={pending}
            onDraftChange={watch.onDraftChange}
            onCommit={(name) =>
              watch.commit(name, (n) =>
                ctx.run(key, () => ctx.edit.renameNode.mutateAsync({ code: node.code, name: n }), n),
              )
            }
          />
        </span>

        {/* 近五年频次 —— 统计事实。不是难度,不是重要性,和「答得怎么样」没有关系。 */}
        <span className="order-3 flex w-[76px] shrink-0 items-center gap-1 md:order-5">
          <InlineEdit
            value={String(node.recent5yCount)}
            ariaLabel={`${node.name} 的近五年频次`}
            numeric
            align="right"
            disabled={ctx.locked}
            pending={pending}
            onCommit={(raw) => {
              const n = toCount(raw)
              // 非法输入不发请求。返回 false 让 InlineEdit 把格子退回原来的数 ——
              // 「次数」写成 -1 / 3.5 / 1000 都没有含义,留在格子里只会让人以为存进去了。
              if (n === null) return Promise.resolve(false)
              return ctx.run(key, () => ctx.edit.setFrequency.mutateAsync({ code: node.code, recent5yCount: n }))
            }}
          />
          <span className="shrink-0 font-mono text-[10.5px] text-t3">次</span>
        </span>

        <i aria-hidden className="order-4 h-0 basis-full md:hidden" />

        {/* 移到另一个题型。🔴 选项只有题型 —— 考点不能挂到考点下面,那是第四层。 */}
        <span className="order-5 shrink-0 md:order-4">
          <CodeSelect
            value={node.groupCode}
            options={ctx.groups.map((g) => ({ code: g.code, name: g.name }))}
            disabled={ctx.locked || pending}
            ariaLabel={`${node.name} 所属题型`}
            onChange={(groupCode) =>
              void ctx.run(key, () => ctx.edit.moveNode.mutateAsync({ code: node.code, groupCode }))
            }
          />
        </span>

        {/* 挂了几条记录 —— 删除能不能进行,全看这个数。所以它常驻,不藏在 tooltip 里。 */}
        <span
          className={`order-6 w-[56px] shrink-0 text-right font-mono text-[10.5px] tabular-nums ${
            node.touchCount > 0 ? 'text-t2' : 'text-t3'
          }`}
        >
          {node.touchCount > 0 ? `${node.touchCount} 条` : '无记录'}
        </span>

        <span className="order-7 flex shrink-0 items-center gap-[5px]">
          <MicroButton
            title="在本题型内上移"
            disabled={ctx.locked || pending || index === 0}
            onClick={() =>
              void ctx.run(key, () =>
                ctx.edit.reorderNodes.mutateAsync({ groupCode: group.code, nodeCodes: swapped(codes, index, index - 1) }),
              )
            }
          >
            ↑
          </MicroButton>
          <MicroButton
            title="在本题型内下移"
            disabled={ctx.locked || pending || index === codes.length - 1}
            onClick={() =>
              void ctx.run(key, () =>
                ctx.edit.reorderNodes.mutateAsync({ groupCode: group.code, nodeCodes: swapped(codes, index, index + 1) }),
              )
            }
          >
            ↓
          </MicroButton>
          <MicroButton
            title={blocked ? `删不掉:有 ${node.touchCount} 条记录挂在上面` : '删除这个考点'}
            tone="danger"
            disabled={ctx.locked || pending}
            onClick={() => ctx.setOpen(ctx.open === key ? null : key)}
          >
            删
          </MicroButton>
        </span>
      </div>

      {/* 打字的当下就说 —— 一轮命名校正里,「打完、回车、等一个来回、被拒」这条路走不下去 */}
      {watch.problem && <Drawer warn>{watch.problem}</Drawer>}
      {watch.clash && (
        <Drawer warn>
          <ClashNote ctx={ctx} owner={watch.clash} />
        </Drawer>
      )}

      {ctx.open === key && (
        <Drawer warn={blocked}>
          {blocked ? (
            <BlockedDelete ctx={ctx} rowKey={key} node={node} pending={pending} />
          ) : (
            <>
              确认删除考点「{node.name}」?它上面没有任何记录,删掉不会丢东西 ——
              但覆盖度的分母会少一个,近五年 {node.recent5yCount} 次的那部分统计也就不再出现在盲区榜上。
              <br />
              只是暂时不想练,用<b className="font-normal text-tx">归档</b>更合适:留着 code,以后能接回来。
              <div className="mt-2 flex flex-wrap items-center gap-2">
                <Button
                  variant="danger"
                  disabled={pending}
                  onClick={async () => {
                    if (await ctx.run(key, () => ctx.edit.deleteNode.mutateAsync(node.code))) ctx.setOpen(null)
                  }}
                >
                  {pending ? '删除中…' : '确认删除'}
                </Button>
                <Button
                  disabled={pending}
                  onClick={async () => {
                    if (await ctx.run(key, () => ctx.edit.archiveNode.mutateAsync(node.code))) ctx.setOpen(null)
                  }}
                >
                  改为归档
                </Button>
                <Button onClick={() => ctx.setOpen(null)}>取消</Button>
              </div>
            </>
          )}
        </Drawer>
      )}

      {/* 同上:删除守则那一段已经把 409 讲清楚了,通用错误只在别的失败上出现 */}
      {failure && !rejectedAs(failure, 'NODE_HAS_RECORDS') && <FailDrawer ctx={ctx} failure={failure} self={node.code} />}
    </div>
  )
}

/**
 * 🔴 删除守则的出口。
 *
 * server 侧 `ApiException.of(SyllabusEditException)` 的 javadoc 写着:
 * 「界面要在这里给出『搬记录』和『归档』两个按钮,而不是一句『删除失败』」——
 * 因为「有记录不让删」如果只落成一句提示,用户下一步就是去别处找个更硬的删法。
 */
function BlockedDelete({ ctx, rowKey, node, pending }: { ctx: Ctx; rowKey: string; node: NodeView; pending: boolean }) {
  // 搬去哪儿:除自己以外的任何考点。服务端对「搬给自己」回 400 SAME_NODE,这里直接不给选。
  const targets = ctx.allNodes.filter((n) => n.code !== node.code)
  const [target, setTarget] = useState(targets[0]?.code ?? '')

  return (
    <>
      <b className="font-normal text-red">删不掉:</b>有{' '}
      <span className="font-mono tabular-nums">{node.touchCount}</span> 条记录挂在「{node.name}」上面。
      <br />
      它们挂的是代号 <span className="font-mono text-t2">{node.code}</span>,不是名字。所以先看看是不是这两件事:
      <ul className="mt-1.5 space-y-1">
        <li>
          · 名字不对 → <b className="font-normal text-tx">直接改上面那个名字</b>,这 {node.touchCount} 条一条不少。
        </li>
        <li>· 位置不对 → 用「题型」那一列换个题型,记录跟着考点走。</li>
      </ul>
      <div className="mt-2.5 border-t border-hair pt-2.5">
        真的要让它消失,只有两条路,<b className="font-normal text-tx">两条都不会丢记录</b>:
        <div className="mt-2 flex flex-wrap items-center gap-2">
          <Button
            disabled={pending}
            onClick={async () => {
              if (await ctx.run(rowKey, () => ctx.edit.archiveNode.mutateAsync(node.code))) ctx.setOpen(null)
            }}
          >
            {pending ? '处理中…' : '归档'}
          </Button>
          <span className="text-[11.5px] text-t3">
            退出差集(分子分母同时减一,比值仍然诚实),code 和这 {node.touchCount} 条原样留着,随时接回来。
          </span>
        </div>
        <div className="mt-2 flex flex-wrap items-center gap-2">
          <span className="text-[11.5px] text-t3">把这 {node.touchCount} 条搬到</span>
          <CodeSelect
            value={target}
            options={targets.map((n) => ({ code: n.code, name: `${n.groupName} · ${n.name}` }))}
            disabled={ctx.locked || pending || targets.length === 0}
            ariaLabel="把记录搬到哪个考点"
            wide
            onChange={setTarget}
          />
          <Button
            disabled={pending || !target}
            onClick={async () => {
              await ctx.run(rowKey, () => ctx.edit.moveRecords.mutateAsync({ code: node.code, toNodeCode: target }))
            }}
          >
            搬过去
          </Button>
          <span className="text-[11.5px] text-t3">时间戳原样保留,搬完这个考点就真的能删了。</span>
        </div>
      </div>
      <div className="mt-2.5">
        <Button onClick={() => ctx.setOpen(null)}>先不动</Button>
      </div>
    </>
  )
}

/* ========================================================================== */
/* 已归档 —— 退出差集,但一条记录都没丢                                          */
/* ========================================================================== */

/**
 * 归档区。
 *
 * <b>一个看不见又删不掉的东西是最糟的状态</b>(server 侧 `/archived` 端点的原话),
 * 所以归档这个动作一旦存在,就必须有一处能看见它的结果 —— 否则「弃用」在用户眼里就是「弄丢了」。
 */
function ArchivedSection({ ctx, items, error }: { ctx: Ctx; items: SyllabusNodeDto[]; error: unknown }) {
  if (ctx.locked) return null

  if (error) {
    return (
      <>
        <GroupHeader title="已归档" right="读不到" />
        <Drawer warn>
          没能读到已归档的考点:{describeError(error)}
          <br />
          归档过的考点<b className="font-normal text-tx">还在</b>,只是这一段现在显示不出来 —— 它们不会因此丢失。
          {!ctx.names.archivedKnown && (
            <>
              <br />
              这段读不到的时候,上面那些「这个名字已经有了」的提示<b className="font-normal text-tx">看不见归档考点占的名字</b>:
              服务端照样会拦(409),只是要多一个来回才知道撞的是它。
            </>
          )}
        </Drawer>
      </>
    )
  }

  return (
    <>
      <GroupHeader title="已归档" right={items.length === 0 ? '无' : `${items.length} · 不在差集里`} />
      {items.length === 0 ? (
        <div className="px-4 py-3">
          <Note>
            归档是「有记录但不想再练」的出路:考点退出差集(覆盖率的分子分母同时减一),
            但 code 和历史记录一条都不动,随时能接回来。
          </Note>
        </div>
      ) : (
        <>
          <div className="border-b border-hair px-4 py-2.5">
            <Note>
              这里的<b className="font-normal text-tx">名字也占着</b>:上面新增或改名时撞上它们一样会被拦下。
              <br />
              不这样的话,「取消归档」会把一个重名静默放回树里 —— 那是唯一性最容易破的一个口子。
            </Note>
          </div>
          {items.map((n) => (
            <ArchivedRow key={n.code} ctx={ctx} node={n} />
          ))}
        </>
      )}
    </>
  )
}

function ArchivedRow({ ctx, node }: { ctx: Ctx; node: SyllabusNodeDto }) {
  const key = `archived:${node.code}`
  const pending = ctx.busy === key
  const failure = ctx.failed?.key === key ? ctx.failed : null
  const blocked = node.recordCount > 0 || rejectedAs(failure, 'NODE_HAS_RECORDS')

  // 从「这个名字被一个已归档的考点占着」那句话点过来时,这一行要被点亮 ——
  // 否则用户滚到归档区,还得自己在里面再找一遍那个看不见的名字。
  const spotted = ctx.spot === node.code

  return (
    <div data-archived={node.code}>
      <div
        className={`flex flex-wrap items-center gap-x-[9px] gap-y-[3px] border-b border-hair px-3 py-1 md:h-[34px] md:flex-nowrap md:py-0 ${
          spotted ? 'bg-sel shadow-[inset_2px_0_0_var(--color-red)]' : 'hover:bg-bg2'
        }`}
      >
        <span className="order-1 min-w-0 flex-1 truncate text-t2">{node.name}</span>
        <span className="order-2 shrink-0 font-mono text-[10.5px] text-t3 md:order-3">
          {node.groupName ?? '—'} · 近五年 {node.recent5yCount} 次
        </span>
        <i aria-hidden className="order-3 h-0 basis-full md:hidden" />
        <span className="order-4 w-[56px] shrink-0 text-right font-mono text-[10.5px] text-t3 tabular-nums">
          {node.recordCount > 0 ? `${node.recordCount} 条` : '无记录'}
        </span>
        <span className="order-5 flex shrink-0 items-center gap-2">
          <Button
            disabled={ctx.locked || pending}
            onClick={() => void ctx.run(key, () => ctx.edit.unarchiveNode.mutateAsync(node.code))}
          >
            {pending ? '处理中…' : '取消归档'}
          </Button>
          {/* 🔴 有记录时这个按钮<b>不能</b>置灰:置灰的按钮点不下去,那段「为什么删不掉、
              该怎么办」就永远显示不出来 —— 用户只看到一个灰掉的「删」,不知道它在等什么。 */}
          <MicroButton
            title={blocked ? `删不掉:还有 ${node.recordCount} 条记录` : '彻底删除'}
            tone="danger"
            disabled={ctx.locked || pending}
            onClick={() => ctx.setOpen(ctx.open === key ? null : key)}
          >
            删
          </MicroButton>
        </span>
      </div>
      {ctx.open === key && (
        <Drawer warn={blocked}>
          {blocked ? (
            <>
              <b className="font-normal text-red">删不掉:</b>还有{' '}
              <span className="font-mono tabular-nums">{node.recordCount}</span>{' '}
              条记录挂在「{node.name}」上面。归档过也一样 —— 归档动的是它<b className="font-normal text-tx">在不在差集里</b>,不动记录。
              <br />
              要彻底删,先「取消归档」,再在树里把这 {node.recordCount} 条搬到别的考点上,然后回来删。
              <div className="mt-2">
                <Button onClick={() => ctx.setOpen(null)}>知道了</Button>
              </div>
            </>
          ) : (
            <>
              确认彻底删除「{node.name}」?它已经归档、也没有任何记录,删掉不会丢东西,但代号{' '}
              <span className="font-mono text-t2">{node.code}</span> 就此消失,接不回来了。
              <div className="mt-2 flex items-center gap-2">
                <Button
                  variant="danger"
                  disabled={pending}
                  onClick={async () => {
                    if (await ctx.run(key, () => ctx.edit.deleteNode.mutateAsync(node.code))) ctx.setOpen(null)
                  }}
                >
                  {pending ? '删除中…' : '确认删除'}
                </Button>
                <Button onClick={() => ctx.setOpen(null)}>取消</Button>
              </div>
            </>
          )}
        </Drawer>
      )}
      {/* 409 NODE_HAS_RECORDS 已经由上面那段专门的抽屉讲清楚了,再贴一句通用的「没存下来」
          只是把同一件事说两遍,而且第二遍更含糊。 */}
      {failure && !rejectedAs(failure, 'NODE_HAS_RECORDS') && <FailDrawer ctx={ctx} failure={failure} self={node.code} />}
    </div>
  )
}

/* ========================================================================== */
/* 新增 —— 常驻的一行,不是一个「＋」按钮再弹一个框                               */
/* ========================================================================== */

function NewNodeRow({
  ctx,
  groupName,
  busy,
  failure,
  onCreate,
}: {
  ctx: Ctx
  groupName: string
  busy: boolean
  failure: Failure | null
  onCreate: (name: string, recent5yCount: number) => Promise<boolean>
}) {
  const [name, setName] = useState('')
  const [freq, setFreq] = useState('')

  const count = freq.trim() === '' ? 0 : toCount(freq)
  const problem = nameProblem(name)
  // 新增没有「自己」可排除 —— 撞上任何一个已有的名字(含已归档的)都是撞
  const clash = findNameClash(ctx.names.nodes, name, null)
  const ready = name.trim().length > 0 && problem === null && clash === null && count !== null && !busy && !ctx.locked

  async function submit() {
    if (!ready || count === null) return
    if (await onCreate(name.trim(), count)) {
      setName('')
      setFreq('')
    }
  }

  return (
    <div>
      <div className="flex flex-wrap items-center gap-x-[9px] gap-y-[3px] border-b border-hair px-3 py-1 md:h-[34px] md:flex-nowrap md:py-0">
        <span aria-hidden className="order-1 shrink-0 font-mono text-[12px] text-t3">
          +
        </span>
        <span className="order-2 min-w-0 flex-1">
          <PlainInput
            value={name}
            onChange={setName}
            onEnter={submit}
            disabled={ctx.locked || busy}
            ariaLabel={`在「${groupName}」下新增考点`}
            placeholder={`在「${groupName}」下新增一个考点,自己起名`}
          />
        </span>
        <span className="order-3 flex w-[76px] shrink-0 items-center gap-1">
          <PlainInput
            value={freq}
            onChange={setFreq}
            onEnter={submit}
            disabled={ctx.locked || busy}
            numeric
            ariaLabel="新考点的近五年频次"
            placeholder="0"
          />
          <span className="shrink-0 font-mono text-[10.5px] text-t3">次</span>
        </span>
        <span className="order-4 shrink-0">
          <Button disabled={!ready} onClick={submit}>
            {busy ? '新增中…' : '新增考点'} <Kbd>↵</Kbd>
          </Button>
        </span>
      </div>
      {problem && <Drawer warn>{problem}</Drawer>}
      {clash && (
        <Drawer warn>
          <ClashNote ctx={ctx} owner={clash} />
        </Drawer>
      )}
      {count === null && freq.trim() !== '' && <Drawer warn>{FREQ_RULE}</Drawer>}
      {failure && <FailDrawer ctx={ctx} failure={failure} self={null} />}
    </div>
  )
}

function NewGroupRow({
  ctx,
  busy,
  failure,
  onCreate,
}: {
  ctx: Ctx
  busy: boolean
  failure: Failure | null
  onCreate: (name: string) => Promise<boolean>
}) {
  const [name, setName] = useState('')
  const problem = nameProblem(name)
  const clash = findNameClash(ctx.names.groups, name, null)
  const ready = name.trim().length > 0 && problem === null && clash === null && !busy && !ctx.locked

  async function submit() {
    if (!ready) return
    if (await onCreate(name.trim())) setName('')
  }

  return (
    <div>
      <GroupHeader title="新增题型" right="模块下的第二层" />
      <div className="flex flex-wrap items-center gap-x-[9px] gap-y-[3px] border-b border-hair px-3 py-1 md:h-[34px] md:flex-nowrap md:py-0">
        <span aria-hidden className="shrink-0 font-mono text-[12px] text-t3">
          +
        </span>
        <span className="min-w-0 flex-1">
          <PlainInput
            value={name}
            onChange={setName}
            onEnter={submit}
            disabled={ctx.locked || busy}
            ariaLabel="新增题型"
            placeholder="新题型的名字。自己归纳,不照抄机构的分法"
          />
        </span>
        <span className="shrink-0">
          <Button disabled={!ready} onClick={submit}>
            {busy ? '新增中…' : '新增题型'} <Kbd>↵</Kbd>
          </Button>
        </span>
      </div>
      {problem && <Drawer warn>{problem}</Drawer>}
      {clash && (
        <Drawer warn>
          <ClashNote ctx={ctx} owner={clash} />
        </Drawer>
      )}
      {failure && <FailDrawer ctx={ctx} failure={failure} self={null} />}
    </div>
  )
}

/* ========================================================================== */
/* 小件                                                                        */
/* ========================================================================== */

/** 行下面展开的一段说明。用发丝线上下夹住,不是卡片、不是气泡、不是模态框。 */
function Drawer({ children, warn = false }: { children: ReactNode; warn?: boolean }) {
  return (
    <div className={`border-b border-hair px-3 py-2.5 ${warn ? 'bg-bg3' : 'bg-bg2'}`}>
      <Note warn={warn}>{children}</Note>
    </div>
  )
}

/**
 * 保存失败时贴出来的话。
 *
 * 🔴 <b>不美化、不吞掉</b>。服务端的 message 原样显示 —— 它已经是给人看的中文了
 * (`ApiError` 的 message 字段),再包一层「操作失败,请重试」只会盖住真正的原因。
 */
function failMessage(message: string): ReactNode {
  return (
    <>
      <b className="font-normal text-red">没存下来:</b>
      {message}
      <br />
      界面上这一行还是服务端的旧值 —— <b className="font-normal text-tx">刚才那下确实没有生效</b>,
      不是「已经存了只是没刷新」。
    </>
  )
}

/**
 * 一次失败该贴哪段话。
 *
 * 🔴 <b>409 `NAME_TAKEN` 不能落成一句「没存下来:名字被占了」。</b>
 * 本地预判只是省一个来回,它会漏(树是上一轮 GET 的、归档清单可能没拉到),
 * 所以服务端这一路必须自己把话说完整:撞的是谁、它在哪、下一步做什么。
 */
function FailDrawer({ ctx, failure, self }: { ctx: Ctx; failure: Failure; self: string | null }) {
  if (!rejectedAs(failure, 'NAME_TAKEN')) return <Drawer warn>{failMessage(failure.message)}</Drawer>

  // 服务端说重名了,回本地清单里问一句「占着这个名字的是谁」——
  // 拿得到就能给出那个可点的出口(尤其是它已归档、在树上根本看不见的时候)。
  const owner = failure.attempted
    ? (findNameClash(ctx.names.nodes, failure.attempted, self) ?? findNameClash(ctx.names.groups, failure.attempted, self))
    : null

  return (
    <Drawer warn>
      <b className="font-normal text-red">没存下来:这个名字被占着。</b>
      {failure.message}
      <br />
      界面上这一行还是服务端的旧值 —— <b className="font-normal text-tx">刚才那下确实没有生效</b>。
      {owner ? (
        <ClashWhere ctx={ctx} owner={owner} />
      ) : (
        <>
          <br />
          在树上翻不到同名的?那多半是<b className="font-normal text-tx">一个已归档的考点占着</b> ——
          归档只是退出差集,名字还占着,而它不会出现在上面那棵树里。往下拉到「已归档」那一段找找看。
        </>
      )}
    </Drawer>
  )
}

/**
 * 「这个名字已经有人用了」—— 打字的当下就贴出来的那段。
 *
 * 两种情况分开说,因为下一步完全不同:
 * <ul>
 * <li>占用者<b>在树上</b> → 说出它在哪个题型下,用户自己上去看一眼就知道该怎么起名。
 * <li>占用者<b>已归档</b> → 它<b>不在树上</b>,用户翻遍界面也看不见,只会觉得「我明明没有重名」。
 *     这一路必须显式说「被一个已归档的考点占着」,并且给一个能点过去的出口。
 * </ul>
 */
function ClashNote({ ctx, owner }: { ctx: Ctx; owner: NameOwner }) {
  return (
    <>
      <b className="font-normal text-red">
        {owner.archived ? '这个名字被一个已归档的考点占着。' : `这个名字已经有了${owner.kind === 'group' ? '(题型)' : ''}。`}
      </b>
      {owner.kind === 'group' ? (
        <>两个同名的题型在「所属题型」那个下拉里分不出来,而那个下拉决定考点挂在哪。</>
      ) : (
        <>
          记一笔的时候,考点是<b className="font-normal text-tx">按名字从面板里挑</b>的 ——
          面板上只有名字和状态,没有题型。两个同名的考点在那里就是同一个东西:
          这次挑中一个、下次挑中另一个,同一个知识点的记录被劈到两个 code 上,覆盖率的分子跟着稀释。
        </>
      )}
      <ClashWhere ctx={ctx} owner={owner} />
      {owner.kind === 'node' && !owner.archived && (
        <>
          <br />
          真要区分,就起两个<b className="font-normal text-tx">不同的名字</b>
          (「增长率计算」/「增长率速算」),而不是靠所在题型去区分 —— 挑的时候看不见题型。
        </>
      )}
    </>
  )
}

/** 占用者在哪儿。归档的那一路多给一句出路和一个可点的出口。 */
function ClashWhere({ ctx, owner }: { ctx: Ctx; owner: NameOwner }) {
  return (
    <>
      <br />
      占着它的是「{owner.name}」
      {owner.kind === 'node' && owner.groupName ? `,在「${owner.groupName}」下面` : ''}
      {owner.archived && owner.recordCount !== null && owner.recordCount > 0
        ? `,上面还挂着 ${owner.recordCount} 条记录`
        : ''}
      ,代号 <span className="font-mono text-t2">{owner.code}</span>。
      {owner.archived && (
        <>
          <br />
          它<b className="font-normal text-tx">不在上面那棵树里</b> —— 归档的意思是退出差集,不是消失,
          名字照样占着(否则「取消归档」就能静默造出一个重名)。
          <br />
          两条出路:给那个已归档的考点<b className="font-normal text-tx">改个名</b>,
          或者先给它取消归档、在树上处理完再说。
          <div className="mt-2">
            <Button onClick={() => ctx.focusArchived(owner.code)}>去归档区看它</Button>
          </div>
        </>
      )}
    </>
  )
}

/** code + 显示名的下拉。所属题型、搬记录的目标考点都用它。 */
function CodeSelect({
  value,
  options,
  disabled,
  ariaLabel,
  wide = false,
  onChange,
}: {
  value: string
  options: { code: string; name: string }[]
  disabled: boolean
  ariaLabel: string
  wide?: boolean
  onChange: (code: string) => void
}) {
  return (
    <span
      className={`relative flex h-[22px] items-center rounded-xs border border-hair bg-bg3 pr-[15px] pl-[5px] ${
        wide ? 'w-[200px]' : 'w-[124px]'
      }`}
    >
      <select
        value={value}
        aria-label={ariaLabel}
        disabled={disabled}
        onChange={(e) => onChange(e.target.value)}
        className="min-w-0 flex-1 appearance-none truncate bg-transparent text-[11.5px] disabled:text-t3"
      >
        {options.map((o) => (
          <option key={o.code} value={o.code}>
            {o.name}
          </option>
        ))}
      </select>
      <span
        aria-hidden
        className="pointer-events-none absolute right-[5px] block size-0 border-t-[4px] border-r-[3px] border-l-[3px] border-t-t3 border-r-transparent border-l-transparent"
      />
    </span>
  )
}

/** 新增用的输入框。和 InlineEdit 不同:它<b>不</b>在失焦时提交 —— 新增必须是一次明确的按下。 */
function PlainInput({
  value,
  onChange,
  onEnter,
  ariaLabel,
  placeholder,
  numeric = false,
  disabled = false,
}: {
  value: string
  onChange: (next: string) => void
  onEnter: () => void
  ariaLabel: string
  placeholder?: string
  numeric?: boolean
  disabled?: boolean
}) {
  return (
    <input
      value={value}
      aria-label={ariaLabel}
      placeholder={placeholder}
      disabled={disabled}
      inputMode={numeric ? 'numeric' : undefined}
      onChange={(e) => onChange(e.target.value)}
      onKeyDown={(e) => {
        if (e.key === 'Enter') {
          e.preventDefault()
          onEnter()
        } else if (e.key === 'Escape') {
          e.stopPropagation()
          onChange('')
        }
      }}
      className={`h-[22px] w-full min-w-0 rounded-xs border border-hair bg-bg3 px-[5px] placeholder:text-t3 ${
        numeric ? 'text-right font-mono text-[11.5px] tabular-nums' : 'text-[13px]'
      }`}
    />
  )
}

/* ========================================================================== */
/* 规则 —— 逐条对着 server 侧的校验注解与 SyllabusEditException.Reason           */
/* ========================================================================== */

/** 与 `@Size(max = 40)` 对齐。前端拦一次只为少一次往返,服务端的 INVALID_NAME 才是闸门。 */
const NAME_MAX = 40

const FREQ_RULE = '近五年频次只能是 0–999 的整数 —— 它是一个次数,不是难度也不是重要性。'

/**
 * 名字里不能出现的控制字符。<b>与服务端 `validName` 里的 `Character.isISOControl` 同一个集合</b>
 * (U+0000–001F 与 U+007F–009F),不是只有 `\r\n`。
 *
 * 制表符、垂直制表、换页、NEL 都在里面,而它们<b>正是从 PDF 或表格里粘贴时最常带进来的东西</b> ——
 * 少了它们,一个粘进来的制表符在本地一句提示都没有,要提交之后才吃一个 400,
 * 而这份本地预判存在的意义就是省掉那个来回。
 */
// `no-control-regex` 防的是「不小心把控制字符写进正则」。这里是刻意匹配它们 ——
// 服务端 validName 用 `Character.isISOControl` 拒的正是这两段,
// 少一段就多一类「本地一句提示都没有、提交之后才吃 400」的名字。
// oxlint-disable-next-line no-control-regex
const CONTROL_CHAR = /[\u0000-\u001F\u007F-\u009F]/

/**
 * 名字的规则。
 *
 * 长度上限那句话服务端写得很准:<b>「它是个名字,不是放内容的地方」</b>。
 * 换行同理 —— 名字里出现换行,通常意味着有人往名字里贴了一整段课件内容,
 * 而「不碰内容」是这个产品结构上的第一条红线。
 * 看不见的字符是同一条线上更恶劣的一种,单独一段说(见 {@link InvisibleNote})。
 *
 * 🔴 每一条都对着服务端 `FileSyllabusStore.validName` 的同一条:<b>顺序也一样</b>。
 * 两边判据不同不会报错,只会让用户在本地看见一句话、提交之后看见另一句。
 */
function nameProblem(raw: string): ReactNode | null {
  const s = raw.trim()
  if (s === '') return null // 空只是「还没填」,不是错误 —— 按钮本来就是禁用的
  if (s.length > NAME_MAX) return `名称最长 ${NAME_MAX} 个字符 —— 它是个名字,不是放内容的地方。`
  if (/[\r\n]/.test(raw)) return '名称里不能有换行 —— 那通常意味着往名字里贴了一段内容。'
  if (CONTROL_CHAR.test(raw))
    return '名称里不能有制表符或控制字符 —— 那通常意味着这个名字是从表格或 PDF 里粘过来的,一起粘进来的还有排版符号。请重新手打一遍。'
  const ghosts = invisibleChars(raw)
  if (ghosts.length > 0) return <InvisibleNote raw={raw} count={ghosts.length} />
  // 🔴 变体选择符是放行的(server 侧 validName 同样放行),代价是一个「只由变体选择符组成」的名字
  // 上面每一条都过得去,却在面板上渲染成一片空白 —— 而考点是按名字挑的。
  // 用 nameKey 判空最准:它剥掉的正好是「看不见的 + 空白」,剩下的就是看得见的部分。
  if (nameKey(raw) === '')
    return '名字里一个看得见的字符都没有 —— 它在列表里会渲染成一片空白,而考点是按名字挑的。请给它起一个念得出来的名字。'
  return null
}

/**
 * 名字里混进了零宽字符。
 *
 * <h2>🔴 画出来,但不替他删</h2>
 *
 * 零宽字符渲染出来什么都没有,所以光说「名字里有个看不见的字符」用户根本找不到它在哪 ——
 * 这一段把它<b>画成 ⟨U+200B⟩ 摆在原来的位置上</b>。
 * <p>
 * 而<b>不静默删掉</b>是有意的:静默删掉之后用户不会知道自己粘进来的东西不干净,
 * 下次还从同一个地方粘。零宽字符在考点名里没有任何正当用途,它唯一的效果就是造出一个
 * 肉眼分不出的重名 —— 两个渲染起来一模一样的「增长量计算」,而挑考点只看得见名字。
 * 服务端为此直接 400 拒绝(INVALID_NAME),不做规范化。
 */
function InvisibleNote({ raw, count }: { raw: string; count: number }) {
  return (
    <>
      <b className="font-normal text-red">名字里混着 {count} 个看不见的字符。</b>
      零宽、填充、占位这一类,画出来是这样:
      <span className="ml-1 font-mono text-[11.5px] text-t2">
        {revealName(raw).map((p, i) =>
          p.code === null ? (
            <span key={i}>{p.text}</span>
          ) : (
            <span key={i} className="text-red">
              ⟨{p.code}⟩
            </span>
          ),
        )}
      </span>
      <br />
      它们渲染出来什么都没有,唯一的效果是造出一个<b className="font-normal text-tx">肉眼分不出的重名</b>
      —— 而挑考点的时候只看得见名字。它通常是从别处复制粘贴带进来的。
      <br />
      这里<b className="font-normal text-tx">不会替你删掉</b>:你该知道粘进来的东西不干净。
      自己把那 {count} 处删掉,或者重新手打一遍。服务端也会拒(400 INVALID_NAME)。
    </>
  )
}

/**
 * 一行就地改名时的重名预判。
 *
 * <h2>为什么要把草稿接出来</h2>
 *
 * 「打完 → 回车 → 等一个来回 → 被拒 → 再改」这条路,在一轮命名校正里要走几十遍,走不下去。
 * 所以判重名要发生在<b>打字的当下</b>,而正在打的那个字符串在 `InlineEdit` 内部 ——
 * 它开了 `onDraftChange` 这个口子。
 *
 * <h2>两个状态,不是一个</h2>
 *
 * <ul>
 * <li>{@link draft} —— 正在打的。它撞上谁,当场显示。
 * <li>{@link stopped} —— <b>上一次因为撞名被挡下的那一次</b>。提交被拒之后 `InlineEdit` 会把输入框
 *     退回旧值(界面上不许留一个服务端并不知道的名字),此时「正在打的」已经不撞了,
 *     可那句话必须留着 —— 否则用户按完回车,输入框变回原样、屏幕上什么都没有,
 *     只剩「我刚才那下到底怎么了」。它留到用户重新动手为止。
 * </ul>
 */
function useNameWatch(owners: Map<string, NameOwner>, self: string, current: string) {
  /** null = 这一行还没被动过。不是 `''` —— 空串是「被清空了」,那是另一回事。 */
  const [draft, setDraft] = useState<string | null>(null)
  const [stopped, setStopped] = useState<NameOwner | null>(null)

  const live = draft === null ? null : findNameClash(owners, draft, self)

  return {
    /** 现在该显示的那个冲突:正在打的优先,否则是上一次被挡下的。 */
    clash: live ?? stopped,
    /** 长度 / 换行 / 零宽字符。同样是打字的当下就说。 */
    problem: draft === null ? null : nameProblem(draft),

    onDraftChange(next: string) {
      setDraft(next)
      // 退回旧值(esc、或者提交被挡下)不清掉上一次那句话 —— 那时用户还没重新动手。
      if (next !== current) setStopped(null)
    },

    /**
     * 提交。本地就能看出问题就<b>不发这个请求</b>,把话留在行下面。
     *
     * 🔴 本地放过<b>不等于</b>会成功:这棵树是上一轮 GET 的,归档清单可能压根没拉到。
     * 服务端 409 才是判据,失败由 {@link FailDrawer} 如实贴出来。
     */
    async commit(next: string, save: (name: string) => Promise<boolean>): Promise<boolean> {
      if (nameProblem(next) !== null) return false
      const hit = findNameClash(owners, next, self)
      if (hit) {
        setStopped(hit)
        setDraft(null)
        return false
      }
      const ok = await save(next)
      if (ok) {
        setDraft(null)
        setStopped(null)
      }
      return ok
    },
  }
}

/** 近五年频次的解析。0–999 的整数 —— 负数、小数、超上限都没有含义。 */
function toCount(raw: string): number | null {
  const s = raw.trim()
  if (!/^\d+$/.test(s)) return null
  const n = Number(s)
  return n <= 999 ? n : null
}

/** 换两个位置,返回<b>完整</b>的新排列 —— 服务端要的是排列,少一个就等于悄悄删一个。 */
function swapped(list: string[], i: number, j: number): string[] {
  const next = list.slice()
  const tmp = next[i]
  next[i] = next[j]
  next[j] = tmp
  return next
}

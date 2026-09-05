import { useNavigate, useParams } from 'react-router'
import { useDashboard } from '../api/queries'
import type { NodeView } from '../api/types'
import { BlindSpotSide } from '../features/BlindSpotSide'
import { CoverageHeader } from '../features/CoverageHeader'
import { NodeList } from '../features/NodeList'
import { routeTo } from '../routes/routes'
import { ColL, ColR, Cols, ScreenBody, ScreenHead } from '../ui/layout'
import { Button, Kbd, Note } from '../ui/primitives'
import { EmptyState, FailureBlock, Placeholder } from '../ui/states'
import { relativeDay } from '../lib/format'

/**
 * `M3 盲区与覆盖度` —— `S-BLIND`。差集在这一屏被看见。
 *
 * <h2>断点:同一份 DOM,两种排布</h2>
 *
 * ≥1024 双列(`design/m3/07-ipad-blind-a.html`):左栏是骨架树与榜,右栏是选中考点的详情。
 * <1024 单列:两栏<b>不并排</b>,详情是独立的 route id `/coverage/:nodeCode`,
 * 于是「有没有选中一个考点」决定看见哪一栏。
 * <p>
 * 🔴 两种排布用的是<b>同一段 JSX</b>,只有两个条件类在切。多写一份窄屏组件的代价不是
 * 多几行代码,是从那一刻起同一句话会有两个版本 —— 而这正是
 * `多端选型与端矩阵` §一那句「那种混合会产生两份视觉真相」说的事。
 *
 * <h2>左栏是内容不是菜单,判据三条</h2>
 *
 * ① 它装的是骨架树,换一个科目整栏换掉;② 它一条也不列举别的屏;
 * ③ 它没有选中态高亮条,只有一条发丝线分栏(`design/h5/H5交互说明.md`)。
 *
 * <h2>四态</h2>
 *
 * <ul>
 * <li><b>主态</b> —— 摘要竖式(一共 − 你碰过 = 没碰过)+ 盲区榜 + 按章看全部</li>
 * <li><b>空态</b> —— 有骨架零记录(`02-blind-empty`)。🔴 竖式照常显示
 *     (`120 − 0 = 120`),它是一个真实的事实;假装还没算好才是骗人</li>
 * <li><b>失败态</b> —— `SUBJECT_NOT_LOADED` 停在本屏、`SYLLABUS_DATA_BROKEN` 不给重试</li>
 * <li><b>受限态</b> —— 🔴 这一屏<b>没有</b>受限态。`design/m3/交互说明.md` 原话:
 *     「这两屏额度永不锁(`I-4`),不出受限态 —— 四态口径下受限态为『不适用』」。
 *     补一个空的受限态是为了对称,而对称不是理由</li>
 * </ul>
 */
export function CoverageScreen() {
  const { nodeCode } = useParams<{ nodeCode?: string }>()
  const navigate = useNavigate()
  const { data, isPending } = useDashboard()

  const pick = (code: string) => void navigate(routeTo('coverage.node', { nodeCode: code }))

  if (isPending || !data) return <LoadingCoverage />

  const flat = data.groups.flatMap((g) => g.nodes)
  const selected = nodeCode !== undefined ? (flat.find((n) => n.code === nodeCode) ?? null) : null

  // 地址里有一个指不到任何地方的考点标识 —— 这是 NODE_NOT_FOUND 在端上的等价物。
  // 它是 terminal 档:再拉一次树还是同一个结果。
  if (nodeCode !== undefined && selected === null) {
    return (
      <>
        <ScreenHead title="考点" sub={nodeCode} />
        <ScreenBody>
          <FailureBlock
            code="NODE_NOT_FOUND"
            scope="考点详情"
            onFallback={() => void navigate(routeTo('coverage'))}
          />
        </ScreenBody>
      </>
    )
  }

  const empty = data.summary.covered === 0
  const hasSyllabus = data.summary.total > 0

  return (
    <>
      <ScreenHead
        title={data.subject.display}
        sub={`近五年 ${data.subject.recent5yWindow}`}
        right={
          <span className="hidden items-center gap-2 pad:flex">
            <Kbd>⌘K 找考点</Kbd>
            <Kbd>⌘B 考点树</Kbd>
          </span>
        }
      />

      <Cols>
        {/* 左栏。选中一个考点之后,<1024 上它让位给详情;≥1024 上两栏并存。 */}
        <ColL>
          <div className={selected !== null ? 'hidden min-h-0 flex-col wide:flex' : 'flex min-h-0 flex-col'}>
            {!hasSyllabus ? (
              <FailureBlock code="SYLLABUS_EMPTY" scope="骨架层" />
            ) : (
              <>
                <CoverageHeader summary={data.summary} subject={data.subject} />
                {empty ? (
                  <EmptyState
                    title="骨架建好了,行为层还是空的"
                    body={`${data.summary.total} 个考点一个都没碰过 —— 所以整棵树就是盲区本身。记第一笔之后,这里会开始少几行。`}
                    action={{ label: '去记一笔', onClick: () => void navigate(routeTo('capture')) }}
                  />
                ) : (
                  <div className="flex min-h-0 flex-1 flex-col">
                    <BlindSpotSide
                      blindspots={data.blindspots}
                      records={data.records}
                      selectedCode={selected?.code ?? null}
                      onSelect={pick}
                      onAskAi={() => void navigate(routeTo('agent'))}
                    />
                    <NodeList
                      groups={data.groups}
                      selectedCode={selected?.code ?? null}
                      onSelect={pick}
                      onOpen={(n) => pick(n.code)}
                    />
                  </div>
                )}
              </>
            )}
          </div>
        </ColL>

        {/* 右栏 = 考点详情。没选中时它在窄屏上整栏不渲染,在宽屏上是一句提示。 */}
        <ColR>
          <div
            className={
              selected === null
                ? 'hidden min-h-0 flex-1 flex-col wide:flex'
                : 'flex min-h-0 flex-1 flex-col'
            }
          >
            {selected === null ? (
              /* 稿 `design/m3/08-ipad-blind-b.html:80-85`:右栏空态是【居中两行】,
                 不是左上角一句。第二句在稿上不是补充说明,它是这一栏此刻的用法 ——
                 少了它,空的右栏读起来像是没加载完。 */
              <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-1 px-[var(--rule)] text-center">
                <span className="text-[13px] text-t2">从左边挑一个考点</span>
                <span className="text-[12px] text-t3">点开哪个,它的详情就在这里。</span>
              </div>
            ) : (
              <NodeDetail node={selected} onBack={() => void navigate(routeTo('coverage'))} />
            )}
          </div>
        </ColR>
      </Cols>
    </>
  )
}

/**
 * 考点详情 —— `U3.4`。
 *
 * 🔴 <b>这里没有讲这个考点是什么的一个字。</b>它显示的全部是「有没有 / 几次 / 多久前」:
 * 碰过几次、上一次多久前、近五年真题里出现几次。
 * `api/types.ts` 的 `NodeDetailDto` 里也确实没有任何一个能装下内容的字段(`R-01`)。
 *
 * <h2>2026-09-06(`KUBI-111`)摘掉三样</h2>
 *
 * ① 标题旁那个五档中文名标签(`stateLabel`:空白/仅接触/生疏/弱/稳);
 * ② 「练了」「对了」两格 —— 两个数并排读出来就是答得对不对;
 * ③ 随之而来的那个「做题数」失败块(算不准就不显示)—— 那两个数已经不显示了,
 *    再挂一个「做题数取不到」的失败块是在给一个不存在的东西报错。
 * 稿(`design/m3/08-ipad-blind-b.html`)的右栏是两条事实行:「真题里」/「你这里」,
 * 下面这个 dl 就是它在本工程密集排版下的落法。
 */
function NodeDetail({ node, onBack }: { node: NodeView; onBack: () => void }) {
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex items-center gap-3 border-b border-hair px-[var(--rule)] py-3">
        <span className="text-[14px] text-tx">{node.name}</span>
        <button
          type="button"
          onClick={onBack}
          className="ml-auto font-mono text-[11px] text-t3 hover:text-tx wide:hidden"
        >
          回列表
        </button>
      </div>

      <dl className="grid grid-cols-2 gap-x-6 gap-y-4 px-[var(--rule)] py-5 pad:grid-cols-3">
        <Fact k="所属题型" v={node.groupName} />
        <Fact k="碰过" v={`${node.touchCount} 次`} />
        <Fact k="上一次" v={node.latestAt === null ? '还没碰过' : relativeDay(node.latestAt)} />
        <Fact k="近五年出现" v={`${node.recent5yCount} 次`} />
      </dl>

      <div className="px-[var(--rule)]">
        <Note>
          上面这几个数全部来自你自己记下的东西 —— 近五年出现几次是真题统计的事实,
          不是难易,也不是这个考点有多要紧。这一屏不给第五个数。
        </Note>
      </div>

      <div className="mt-auto flex flex-wrap gap-2 border-t border-hair px-[var(--rule)] py-3">
        <Button variant="primary">记一笔到这个考点</Button>
        <Button>问一下</Button>
      </div>
    </div>
  )
}

function Fact({ k, v }: { k: string; v: string }) {
  return (
    <div>
      <dt className="mb-1 font-mono text-[10px] tracking-[0.1em] text-t3 uppercase">{k}</dt>
      <dd className="font-mono text-[13px] text-tx tabular-nums">{v}</dd>
    </div>
  )
}

/**
 * 首屏未到位。
 *
 * 🔴 压平的灰块,<b>不转圈</b>(`design/h5/H5交互说明.md` S-HOME:
 * 「回来前列表位是 `.ph` 压平灰块,不转圈」)。转圈说的是「还在等」,
 * 灰块说的是「这里将来有东西」—— 后者才是真的。
 */
function LoadingCoverage() {
  return (
    <>
      <ScreenHead title={<Placeholder w="12ch" />} />
      <ScreenBody>
        <div className="flex flex-col gap-3 px-[var(--rule)] py-6">
          <Placeholder w="40%" h={34} />
          <Placeholder h={3} />
          {Array.from({ length: 8 }, (_, i) => (
            <Placeholder key={i} w={`${88 - i * 6}%`} h={20} />
          ))}
        </div>
      </ScreenBody>
    </>
  )
}

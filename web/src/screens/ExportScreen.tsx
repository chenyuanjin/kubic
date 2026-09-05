import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useDashboard } from '../api/queries'
import { routeTo } from '../routes/routes'
import { Cap, ColL, ColR, Cols, ScreenBody, ScreenHead } from '../ui/layout'
import { Button, GroupHeader, Kbd, Note, Tag } from '../ui/primitives'
import { EmptyState, FailureBlock, Placeholder, StandingNote } from '../ui/states'

/**
 * `M4 出口` —— 四条出口的性质不一样,所以这一屏分四区。
 *
 * <h2>⚪ 这条路由在 `多端选型与端矩阵` §4.6.2 的表里没有</h2>
 *
 * 那张表列了 14 个 route id,`M4 出口` 一整个模块(`design/m4/` 八张稿)在里面没有落点。
 * 本轮按同样三条命名规矩把它登记成 `/export`,<b>但这是前端一侧的登记,不是裁定</b> ——
 * 该不该进那份契约要过一次人审。`routes.ts` 上面那段注释里也写着同一句话。
 *
 * <h2>四区的分法来自 `U4.1`「四条出口的性质」</h2>
 *
 * 区一区二不花额度,区三花。所以 `design/m4/交互说明.md` 的收敛条款是:
 * <b>「失败停在发生的那一区,免费两区没有失败态也没有禁用态」</b>。
 * 这句话在代码里的落点就是下面每一区各自带一个 `FailureBlock`,而不是整屏一个。
 *
 * <h2>≥1024 双列 —— 稿 `design/m4/ipad-exp.html` 的切法</h2>
 *
 * 稿上左栏是「出口 01 导出文件」+「出口 02 复制给 AI」,右栏是「出口 03 让 AI 直接回答」
 * + 「出口 04 只读令牌」。这个切法不是把四区随便对半分,它跟着<b>性质</b>走:
 * 左栏两条是你自己把东西拿走(不花额度、没有失败态),右栏两条一条花额度、一条是对外授权。
 * <p>
 * 🔴 <b>这里没有引入任何新几何。</b>用的就是 `Cols`/`ColL`/`ColR` 那一套
 * (`index.css` 的 480 / 720 / 1200),`<1024` 由 `.kb-cols` 自己落回单列 ——
 * 不写第二套 DOM,理由与 `CoverageScreen` 那段「两种排布用的是同一段 JSX」一致。
 *
 * <h2>四态</h2>
 *
 * 主态 = 四区都在;空态 = 一条记录都没有(🔴 导出仍可点 —— 只含说明头的文件照样是你的);
 * 失败态 = 区三上游中断,免费两区不受牵连;受限态 = 区三缺额度,区一区二照常。
 */
export function ExportScreen() {
  const { data, isPending } = useDashboard()
  const navigate = useNavigate()
  /** 区三演示三档:正常 / 上游中断 / 额度不足。真接口接上之前,这一格由界面自己切。 */
  const [zone3, setZone3] = useState<'ok' | 'failed' | 'limited'>('ok')

  if (isPending || !data) {
    return (
      <>
        <ScreenHead title={<Placeholder w="6ch" />} />
        <ScreenBody>
          <div className="flex flex-col gap-4 px-[var(--rule)] py-6">
            <Placeholder h={60} />
            <Placeholder h={60} />
          </div>
        </ScreenBody>
      </>
    )
  }

  const empty = data.records.length === 0

  return (
    <>
      <ScreenHead
        title="出口"
        sub="把你的东西带走"
        right={
          <span className="hidden items-center gap-2 pad:flex">
            <Kbd>⌘E</Kbd>
          </span>
        }
      />

      <ScreenBody>
        {/* 那句常驻说明在两栏【之上】,和稿上的范围条一样是整屏一条 ——
            分进某一栏就变成「只对这一栏成立」,而它说的是四条出口共同的性质。 */}
        <Cap>
          <StandingNote>
            这四条出口都只送出<b>你自己记下的东西</b>:考点、来源的名字、时间、你填的那两个数。
            没有一处会送出讲义原文或原图 —— 库里就没有它们。
          </StandingNote>
        </Cap>

        <Cols>
          <ColL>
            {/* ── 区一:导出文件。不花额度,所以它没有失败态也没有禁用态 ── */}
            <GroupHeader title="区一 · 导出文件" right="不花额度" />
            <div className="flex flex-col gap-3 px-[var(--rule)] py-4">
              <p className="text-[12px] leading-6 text-t2">
                三种格式,字段完全一样,差别只在给谁看。
              </p>
              <div className="flex flex-wrap gap-2">
                <Button variant="primary">CSV</Button>
                <Button>Markdown</Button>
                <Button>JSON</Button>
              </div>
              {empty ? (
                <Note>
                  一条记录都还没有 —— 导出仍然可点。<b>只含说明头的那个文件照样是你的</b>,
                  它证明这个出口是通的。
                </Note>
              ) : (
                <Note>本次会带走 {data.records.length} 条记录 + {data.summary.total} 个考点的当前状态。</Note>
              )}
            </div>

            {/* ── 区二:复制给 AI。也不花额度 —— 它只是把文本放进剪贴板 ── */}
            <GroupHeader title="区二 · 复制给 AI" right="不花额度" />
            <div className="flex flex-col gap-3 px-[var(--rule)] py-4">
              <p className="text-[12px] leading-6 text-t2">
                生成一段可以直接粘进任何一个对话框的文字,你自己决定发给谁。
              </p>
              <div className="flex flex-wrap gap-2">
                <Button>复制盲区清单</Button>
                <Button>复制某个考点的记录</Button>
              </div>
            </div>
          </ColL>

          <ColR>
            {/* ── 区三:代发提问。这一区花额度,也只有这一区会失败 ── */}
            <GroupHeader title="区三 · 让它替你去问" right="花额度" alarm={zone3 !== 'ok'} />
            <div className="flex flex-col gap-3 px-[var(--rule)] py-4">
              <p className="text-[12px] leading-6 text-t2">
                把上面那段文字直接发给你接的模型,回来的话原样摆在这儿。
              </p>
              {zone3 === 'ok' ? (
                <div className="flex flex-wrap gap-2">
                  <Button variant="primary" onClick={() => void navigate(routeTo('agent'))}>
                    去问一下
                  </Button>
                </div>
              ) : zone3 === 'failed' ? (
                <FailureBlock code="SERVER_ERROR" scope="区三" onRetry={() => setZone3('ok')} />
              ) : (
                <FailureBlock
                  code="QUOTA_EXHAUSTED"
                  scope="区三"
                  onFallback={() => setZone3('ok')}
                />
              )}
              {/* 三档的切换开关。接上真接口那天这一格整块删掉 —— 它现在的作用是让
                  「失败停在发生的那一区」这句话可以被当场点出来,而不是只写在文档里。 */}
              <div className="flex items-center gap-2 pt-1">
                <Tag>脚手架</Tag>
                <span className="font-mono text-[10.5px] text-t3">看另外两档:</span>
                <button type="button" className="font-mono text-[10.5px] text-t3 underline hover:text-tx" onClick={() => setZone3('failed')}>
                  上游中断
                </button>
                <button type="button" className="font-mono text-[10.5px] text-t3 underline hover:text-tx" onClick={() => setZone3('limited')}>
                  额度不足
                </button>
              </div>
            </div>

            {/* ── 区四:只读令牌。常驻,空态也在 ── */}
            <GroupHeader title="区四 · 只读令牌" right="不花额度" />
            <div className="px-[var(--rule)] py-4">
              <EmptyState
                title="还没有签发过令牌"
                body="签一个出去,拿到的人只能看,不能改也不能删。随时能吊销 —— 吊销立刻生效,不等它过期。"
                action={{ label: '签一个', onClick: () => setZone3('ok') }}
              />
            </div>
          </ColR>
        </Cols>
      </ScreenBody>
    </>
  )
}

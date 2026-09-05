import { useNavigate, useOutletContext, useParams } from 'react-router'
import { AgentChat } from '../features/AgentChat'
import { RawImageDrop } from '../features/RawImageDrop'
import { routeTo } from '../routes/routes'
import { ScreenBody, ScreenHead } from '../ui/layout'
import { Button, GroupHeader, Note, Row, Tag } from '../ui/primitives'
import { EmptyState, StandingNote } from '../ui/states'

/** `AppShell` 通过 outlet context 往下递的东西。目前只有一样。 */
interface ShellContext {
  signOut: () => void
}

/**
 * `/agent` —— 问一下。
 *
 * 从 `chatOpen` 那个 `useState` 变成一个地址。形态没变(仍然是那块面板),
 * 变的是它能被地址指到、能被返回键关掉。
 */
export function AgentScreen() {
  const navigate = useNavigate()
  return (
    <>
      <ScreenHead title="问一下" sub="只答有没有 / 几次 / 多久前" />
      <ScreenBody className="flex flex-col">
        <AgentChat onClose={() => void navigate(routeTo('coverage'))} />
      </ScreenBody>
    </>
  )
}

/**
 * `/archive` —— 留存区。
 *
 * 🔴 词冻结为「留存区」,不是「归档」(`多端选型与端矩阵` §五):
 * 「归档」已被考点侧占用(考点归档会让覆盖率上升),原图侧再叫归档,
 * 用户会以为原图到期影响覆盖率 —— 两个语义共用一个词,迟早有人写错文案。
 *
 * 🔴 留存期 14 天,四端一个数,<b>只有一处定义</b>(`lib/rawImageCache.ts`),这一屏现读不抄。
 */
export function ArchiveScreen() {
  return (
    <>
      <ScreenHead title="留存区" sub="到期原图的去处" />
      <ScreenBody>
        <div className="kb-cap">
          <StandingNote>
            <b>原图只在这台设备上。</b>不上传、不同步、不共享。
            到期之后它进这个留存区,不是被删掉 —— 你随时能自己删。
          </StandingNote>
          <div className="px-[var(--rule)] py-4">
            <Note warn>
              网页这个端上多一条:<b>浏览器可能清理掉这些图</b>。
              换设备、清站点数据、隐私模式都会。桌面壳里没有这一条。
            </Note>
          </div>
          {/* 这一屏只看不选:`pendingIds` 恒为空、`busy` 恒为 false ——
              「挑几张一起送去识别」是 `M1 拍` 那一屏的动作,不是留存区的。 */}
          <RawImageDrop pendingIds={[]} onPendingIdsChange={() => {}} busy={false} />
        </div>
      </ScreenBody>
    </>
  )
}

/**
 * `/settings` 及其两条子路由。
 *
 * 表由 `routes.ts` 定,这一屏只挑三个 id 渲染 —— 🔴 <b>路径字符串一个都不写死</b>
 * (`多端选型与端矩阵` §十 第 4 条自检)。
 */
export function SettingsScreen() {
  const { '*': tail } = useParams<{ '*'?: string }>()
  const navigate = useNavigate()
  const { signOut } = useOutletContext<ShellContext>()

  if (tail === 'privacy') return <PrivacyPane />
  if (tail === 'model') return <ModelPane />

  return (
    <>
      <ScreenHead title="设置" />
      <ScreenBody>
        <div className="kb-cap">
          <GroupHeader title="数据" />
          <Row onClick={() => void navigate(routeTo('settings.privacy'))}>
            <span className="text-[12.5px] text-tx">数据与隐私</span>
            <span className="ml-auto font-mono text-[11px] text-t3">原图在哪、什么时候消失</span>
          </Row>
          <Row onClick={() => void navigate(routeTo('archive'))}>
            <span className="text-[12.5px] text-tx">留存区</span>
            <span className="ml-auto font-mono text-[11px] text-t3">到期原图</span>
          </Row>
          <Row onClick={() => void navigate(routeTo('syllabus'))}>
            <span className="text-[12.5px] text-tx">考点树</span>
            <span className="ml-auto font-mono text-[11px] text-t3">维护骨架层</span>
          </Row>

          <GroupHeader title="模型" />
          <Row onClick={() => void navigate(routeTo('settings.model'))}>
            <span className="text-[12.5px] text-tx">模型接入</span>
            <span className="ml-auto font-mono text-[11px] text-t3">你自己接的那个</span>
          </Row>

          <GroupHeader title="账号" />
          <div className="flex flex-wrap gap-2 px-[var(--rule)] py-4">
            <Button variant="danger" onClick={signOut}>
              退出这台设备
            </Button>
          </div>
          <div className="px-[var(--rule)] pb-6">
            <Note>
              退出会吊销这台设备的令牌,服务端立刻失效,不等它过期。你记下的东西一条都不会少。
            </Note>
          </div>
        </div>
      </ScreenBody>
    </>
  )
}

/** `/settings/privacy` —— `U0.4` 隐私与合规的用户可见部分。 */
function PrivacyPane() {
  return (
    <>
      <ScreenHead title="数据与隐私" />
      <ScreenBody>
        <div className="kb-cap flex flex-col gap-4 px-[var(--rule)] py-5">
          <Note>
            <b>原图只留在这台设备上。</b>不上传、不同步、不共享。
            发给模型识别的那一次是内存里过一趟,不落盘、不进日志。
          </Note>
          <Note>
            <b>不存培训机构的内容。</b>只记来源的名字和时间。课件、讲义、题干一个字都不进这个产品 ——
            库表里没有能装下它们的字段,不是「暂时不存」。
          </Note>
          <Note>
            <b>地址栏里只有不透明标识。</b>你输入的原文、来源课程名、机构名都不进 URL ——
            URL 会进浏览器历史、访问日志、你分享出去的那条链接,以及截图。
          </Note>
          <Note warn>
            网页这个端上多一条:浏览器可能清理掉本机的原图。这是这个端的事实,不是承诺打了折。
          </Note>
        </div>
      </ScreenBody>
    </>
  )
}

/** `/settings/model` —— 模型接入。🔴 学科上的判断整个外包出去,注入点就是这一格。 */
function ModelPane() {
  return (
    <>
      <ScreenHead title="模型接入" right={<Tag tone="warn">未接</Tag>} />
      <ScreenBody>
        <div className="kb-cap">
          <StandingNote>
            这个产品不做学科上的判断,那件事整个交给你自己接的模型。
            这一格就是它在界面上的位置 —— 换一个模型只改这里,产品其余部分一个字不动。
          </StandingNote>
          <EmptyState
            title="还没接"
            body="本轮只把注入点摆出来,没有实现填写与校验。做不出来的东西界面上不留承诺 —— 所以这里不给一个点了没反应的输入框。"
          />
        </div>
      </ScreenBody>
    </>
  )
}

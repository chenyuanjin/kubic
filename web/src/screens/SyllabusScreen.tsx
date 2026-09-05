import { useNavigate } from 'react-router'
import { useDashboard } from '../api/queries'
import { SyllabusEditor } from '../features/SyllabusEditor'
import { routeTo } from '../routes/routes'
import { ScreenBody, ScreenHead } from '../ui/layout'
import { Placeholder } from '../ui/states'

/**
 * `/syllabus` —— 骨架层维护。差集的被减数在这里被改。
 *
 * 组件本体一个字没改,这一层只做两件事:把它挂到一个<b>地址</b>上,
 * 并把「返回」从 `setView('coverage')` 换成一次真正的导航。
 * 差别在浏览器返回键:改之前按它会退出整个应用。
 */
export function SyllabusScreen() {
  const { data, isPending } = useDashboard()
  const navigate = useNavigate()

  if (isPending || !data) {
    return (
      <>
        <ScreenHead title={<Placeholder w="8ch" />} />
        <ScreenBody>
          <div className="flex flex-col gap-2 px-[var(--rule)] py-5">
            {Array.from({ length: 12 }, (_, i) => (
              <Placeholder key={i} h={22} />
            ))}
          </div>
        </ScreenBody>
      </>
    )
  }

  return (
    <>
      <ScreenHead title="考点树" sub="维护骨架层" />
      <ScreenBody className="flex flex-col">
        <SyllabusEditor data={data} onBack={() => void navigate(routeTo('coverage'))} />
      </ScreenBody>
    </>
  )
}

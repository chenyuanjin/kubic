# web —— 考点盲区 · 前端

> 主屏(D3)+ ⌘K 命令面板(D4)+ 记一笔(D6)+ 考点管理(⌘B)。
> 视觉规范是风格 A「极客暗色 · 命令条驱动」,设计稿在 OpenDesign 项目 `notetool-ui-a`。

```bash
npm run dev     # http://localhost:5173,/api 代理到 :8080
npm run build   # tsc -b && vite build
npm run lint    # oxlint
```

**后端没起来也能跑。** 四个 GET 任一不可达就整屏回退到离线示例数据,
并在命令条与状态条上红字标明「离线示例数据」+ 失败原因。窗口重新聚焦会自动再试真接口。

## 两个视图,一条命令条

没有路由。`MainScreen` 里一个 `view` 状态在两者之间切,scope 段换个词、主体换一块:

| 视图 | 看什么 | 入口 |
|---|---|---|
| `coverage` | 差集本身:覆盖率、18 行密集行、先补这几个 | 默认 |
| `syllabus` | 差集的<b>被减数</b>:题型与考点的增删改、顺序、频次 | ⌘B / 命令条上的「考点树」/ ⌘K → 管理考点树 |

⌘B 不是随便挑的字母:⌘M 最小化窗口、⌘T 新标签、⌘L 地址栏、⌘U 看源码,抢它们两边都不会响应。
**徽标常驻,而且每一个按下去都真的有反应** —— 画一个按不动的徽标比不画更糟。

## 做不出来的东西,界面上不留承诺

设计稿 H12 那屏画过「iPad 横屏 · 边看课边记 · ⌥S 截屏记」这类**与其他备考软件同屏协同**的构想。
**这条不做**:iPad 沙箱不允许录别的 App 的声音或画面,桌面端要系统级录屏权限。
`CaptureSheet` 里现在把这条边界明写出来:

- 「拖张图进来」的副标题是**「你自己截的图」**,并注明不监听截图目录、不录别的 App 的屏。
- 「语音」录的是**你对着麦克风说的话**,不是设备里正在播的课;标题从「按住 ⌥ 说话,松开就存」
  (一句 ASR 还没接就兑现不了的行为承诺)改成「未接入」。
- ⌘L / ⌘⇧I 这两枚从来没绑过、而且被浏览器占着的徽标删掉了。

**用户自己切屏、自己截图再手动导入,可以;「替你听、替你截」,不行。**

## 骨架规则(违反即失去风格)

写在 `src/index.css` 文件头,不重复。其中两条由构建期兜底,不靠自觉:

| 规则 | 兜底方式 |
|---|---|
| 零阴影(仅 inset 选中指示) | `@theme` 里 `--shadow-*: initial`,`shadow-md` 这类工具类<b>不存在</b> |
| 圆角 ≤ 3px | `--radius-md` 及以上全部 `initial`,只剩 `rounded-xs`(2px)与 `rounded-sm`(3px) |

色值一律走 `@theme` 里的 token(`bg-bg2` / `text-t3` / `border-hair` / `bg-s-empty`),
组件里不出现十六进制。

## 红线在代码里的形态

| 红线 | 落点 |
|---|---|
| 不碰内容 | `CreateRecordRequest` 只有 5 个字段(`kind` / `sourceName` / `nodeCode` / `practiced` / `correct`)。粘进来的文字只用于挑考点,<b>不进请求体</b> |
| 只接受 nodeCode | 记一笔里考点是 `<select>`,选项由树生成 —— 打不出树里没有的 code(R-07) |
| 考点自行命名 | 考点管理里<b>没有</b>「从机构导入考点体系」这个入口;`api/types.ts` 里连对应的请求类型都不存在(R-07 / docs/decisions/实施路径.md §1.2) |
| 只有三层 | 新增只有「新增题型」和「在题型下新增考点」两种。`CreateNodeRequest` 里只有 `groupCode`,没有 `parentNodeCode` —— <b>结构上长不出第四层</b>(决策记录 §2.5) |
| 永不判断对错 | 正确率 = 用户填的两个整数相除;没练过显示「—」,不是 0%。考点管理里能改的只有名称/位置/顺序/频次,频次是<b>统计事实</b> |
| 宁缺毋滥 / 闭集分类 | 语音与拍照如实标「未接入」,不做假进度条 |
| 记录动作永不失败 | 离线队列(阶段 2)未接,所以失败时<b>如实报错</b>,不假装已存下 |

## 接口

一屏要四个 GET,一次并发拉齐。路径逐条对着 server 侧的 `@GetMapping`:

| 端点 | 响应 | 容易踩的地方 |
|---|---|---|
| `GET /api/syllabus/tree` | `TreeResponse{subject,summary,groups}` | 没有 `withCoverage` 开关;`groups[].nodes[]` <b>不含</b> `practiced/correct/accuracy/sources` |
| `GET /api/coverage/summary` | `SummaryDto` | `distribution` 是<b>数组</b> `[{state,label,count}]`,不是对象 |
| `GET /api/coverage/blindspots?top=` | `BlindSpotsResponse{requestedTop,returned,items}` | 是<b>对象</b>,不是裸数组;`rank` / `blindScore` 都由服务端给 |
| `GET /api/timeline?limit=` | `TimelineResponse{total,returned,items}` | 时间线在这里,<b>不在 `/api/records`</b>(那个只有 POST,GET 是 405);做题数是扁平的 `practiced`/`correct`,不是 `drill:{}` |

行为层的写只有 `POST /api/records`。服务端开了 `FAIL_ON_UNKNOWN_PROPERTIES=true`(R-07 的第二道锁),
**多带一个字段就是 `UNKNOWN_FIELD` 400** —— 包括 `occurredAt`(时间戳由服务端按 Clock 打)。

### 骨架层的写 —— `SyllabusAdminController`

**全是 POST,而且是动作路径,没有一个 PATCH / DELETE。** 两个理由都不是风格问题:

1. server 侧 `ApiCorsConfig` 的方法白名单只有 `GET / POST`,那份 javadoc 写着「将来真要开
   DELETE 时,这里必须显式加」。前端偷发 DELETE,dev 下走 vite proxy 是同源、能过,
   **上了生产才被 CORS 挡掉** —— 本地全绿线上全红,最难查的一类差异。
2. 语义:这里的删除**不是「让一个资源消失」,而是一条带前置条件的命令**,它会失败,
   而且失败才是常态(有记录就不许删)。`DELETE` 那种「幂等地让它不在」的形状会诱导把 4xx 当噪音。

| 端点 | 请求体 | 备注 |
|---|---|---|
| `POST /syllabus/nodes` | `{groupCode, name, recent5yCount}` | 🔴 **没有 `code`** —— 服务端生成 |
| `POST /syllabus/nodes/{code}/rename` | `{name}` | 只改 name,**code 一个字符都不动** |
| `POST /syllabus/nodes/{code}/move` | `{groupCode}` | 换题型,记录不受影响 |
| `POST /syllabus/nodes/{code}/frequency` | `{recent5yCount}` | 0–999 的统计事实 |
| `POST /syllabus/nodes/{code}/archive` · `/unarchive` | 无 | 退出 / 接回差集,**记录一条不动** |
| `POST /syllabus/nodes/{code}/delete` | 无 | 有记录 → **409 `NODE_HAS_RECORDS`**,**没有 force 参数** |
| `POST /syllabus/nodes/{code}/records/move` | `{toNodeCode}` | 记录整体搬家,时间戳原样保留 |
| `POST /syllabus/groups` · `/{code}/rename` · `/{code}/delete` | `{name}` / `{name}` / 无 | 删除时下面还有考点(含归档)→ 409 `GROUP_NOT_EMPTY` |
| `POST /syllabus/groups/order` | `{groupCodes:[...]}` | 🔴 **是完整排列,不是「移到第 N 位」** |
| `POST /syllabus/groups/{code}/nodes/order` | `{nodeCodes:[...]}` | 同上;少一个就是 400 `ORDER_NOT_A_PERMUTATION` |
| `GET /syllabus/archived` | — | 归档的考点**不在 `/tree` 里**,只能从这儿看见 |
| `GET /syllabus/export` | — | 🔴 **有导出,没有导入**(R-07) |

**写响应体一律当 `unknown`,一个字段都不读。** 服务端每次都把新的 `summary` 带回来,
拿来直接更新会快半拍 —— 但那样一屏上就有了两个来源(summary 是新的、树还是旧的)。
路径只有一条:成功 → invalidate → 四个 GET 重拉。宁可慢半拍。

错误分支只认 `ApiError.code`(`NODE_HAS_RECORDS` 等),**不匹配中文文案** —— 后者改一个字就断。

## 考点管理(⌘B)

维护的是差集的**被减数**。树错了,覆盖率、五态、盲区榜全是错的,而且不会报错,只会安静地给出错的答案。

| 做法 | 为什么 |
|---|---|
| 全部**就地编辑**,零模态框 | 阶段 1 要反复校正命名,一棵 18 个考点的树名字要来回改十几轮。每改一个名字弹一次窗,这件事第三轮就会被放弃 |
| **不做乐观更新** | 改动只在服务端确认后才出现。乐观更新会让每一次失败先显示成成功再弹回去,而「刚才那下到底存没存」是这个产品最不能让人产生的疑问 |
| 失败时输入框**退回旧值** | `InlineEdit.onCommit` 返回 `false` 就把格子改回服务端的值。少了这一步,一次失败的改名会留下一个「看起来已经改好了」的输入框,而人只会相信眼前那个名字 |
| 有记录的考点**删不掉** | 展开的抽屉里写清楚:有几条挂着、它们挂的是哪个 code,先排除「名字不对 → 改名」「位置不对 → 换题型」,再给出两条真出路的**按钮**:**归档** 和 **把这 N 条搬到另一个考点**。server 侧 `ApiException` 的 javadoc 明确要求这两个按钮 —— 只落成一句「删除失败」,用户下一步就是去别处找个更硬的删法 |
| **归档区**必须存在 | 归档的考点不在 `/tree` 里。一个看不见又删不掉的东西是最糟的状态 —— 归档一旦有,就得有一处看得见它,否则「弃用」在用户眼里就是「弄丢了」 |
| 题型删除把**归档的也算进去** | 服务端的判据是「下面还有考点,含已归档的」。只看树会让界面说「能删」而服务端回 409 |
| 上移/下移发**完整排列** | `groups/order` / `nodes/order` 要的是排列,不是位置。少一个就是悄悄删一个,服务端 400 |
| 「改名不会弄丢记录」**常驻** | 记录挂 code 不挂名字。这句话如果不当面说,用户就不敢去校正命名,而校正命名正是阶段 1 的主要工作 |
| 离线示例数据下**整屏锁死** | 那不是用户自己的树。与其让每一次保存都失败,不如一开始就不给改 |

## 响应式

同一份代码、同一套组件,**没有为手机另起一套**。切换靠 CSS 断点,不靠 JS 测宽度
(后者会在首帧闪一次错误的布局)。

| 位置 | 宽屏 | 窄屏(375px) |
|---|---|---|
| 密集行 | 一行 8 列,29px | 折成两行,46px。**状态点 / 名称 / 近五年频次一个都不许丢** —— 频次那一列早先写的是 `hidden md:block`,手机上「先补这几个」的排序理由就没了下半句 |
| 「先补这几个」副栏 | 右侧 300px,独立滚动 | 主屏**下方的一段**,跟着一起滚。只改位置和分隔线方向,不改有没有 |
| 命令条 | placeholder 占中间,徽标在右 | scope 段变成会截断的弹性段;**⌘K 徽标本身就是按钮** —— 手机上没有 ⌘,「导航全部压进 ⌘K」在那儿的字面意思是没有导航 |
| 记一笔 / 命令面板 | 居中浮层 | 贴顶,`100dvh` 而不是 `100vh`(地址栏会伸缩,`vh` 取的是大视口,会把「记下」盖住) |

折行用的是 flex-wrap + 一个零高度的 `basis-full` 断点占位(`md:hidden`),列序由 `order-*` 调 ——
**同一段 DOM,宽屏一行、窄屏两行**。

## 目录

| 路径 | 职责 |
|---|---|
| `src/api/types.ts` | 手写的接口形状,每段标了对应的 Java 文件名。<b>骨架层写端点那一段没有</b> —— 它是提案 |
| `src/api/client.ts` | fetch 封装。2s 超时;502/503/504 翻译成「后端没起来」;204 / 空体当成功(DELETE 会走这条) |
| `src/api/derive.ts` | 四个响应 → 一屏视图模型。<b>不算覆盖率、不算五态、不算排序分</b>,那三样一律读服务端 |
| `src/api/mock.ts` | 离线示例数据。产出的是四个端点的<b>响应体</b>,再走和 live 同一条合成路径 |
| `src/api/queries.ts` | TanStack Query。<b>四个端点要么全真要么全假</b>,不混着来;骨架层的六个写 mutation 一律 invalidate 整屏 |
| `src/lib/` | 格式化与五态呈现。`daysAgo` 按自然日算,不按经过小时数算 |
| `src/ui/primitives.tsx` | 行高 / 发丝线 / 徽标 / 状态点 / 就地编辑框等排版基元 |
| `src/features/` | 命令条、覆盖概览、密集行、副栏、命令面板、记一笔、考点管理 |

## 数据契约

离线示例与后端钉在同一根钉子上 ——
**18 个考点 / 8 个有记录 / 44% / 10 个空白 / 2 组整块空白**,
状态分布 稳 3 · 弱 2 · 生疏 2 · 仅接触 1 · 空白 10,
「先补这几个」Top 5 = 6.4 / 6.0 / 5.6 / 5.0 / 5.0。
这组数由 `server` 侧 `CoverageServiceTest` / `ApiContractTest` 钉住,任何一边改口径,两边的数字立刻对不上。

**界面上的数字一个都不在前端算。** 百分比取 `summary.percent`,五态名取 `stateLabel` /
`StateCountDto.label`,名次与排序分取 `/blindspots` 的 `rank` / `blindScore`。
唯一的例外是每个考点的 `practiced`/`correct` —— 树接口不返回它们(只有 `NodeDetailDto` 有),
所以由时间线里同一批原始记录求和,并且**只在 `returned === total`(记录全量)时才敢求**;
被 `limit` 截断时这两列与正确率一律显示「—」,底栏说明原因。宁可空着,不给一个偏小的数。

## 登录:行为验证(滑块)

「获取验证码」是**两步**:先过滑块拿到一对 `ticket` + `randstr`,再发 `POST /api/auth/sms/send`。
滑块是这条链路上**唯一真正的闸** —— 单号 1/60s 与单 IP 20/日 都是纯计数,换一批号换一批 IP
两条都不触发,它一没,短信费就没有上限。

| 文件 | 职责 | 能不能被测 |
|---|---|---|
| `src/lib/captchaPolicy.ts` | **全部判断**:模式、回调判定、成对校验。一个 DOM 符号都没有 | ✅ `tests/captchaPolicy.test.ts` |
| `src/lib/tcaptcha.ts` | **只碰控件**:插 `<script>`、`new TencentCaptcha`、把回调原样递回。一句判断都没有 | ❌(所以它不许有判断) |

配置一项,**不填也能跑**。写进 `web/.env.local`(被 `.gitignore` 挡着,不进仓库):

```bash
VITE_KAODIAN_CAPTCHA_APP_ID=190000001   # 腾讯云的 CaptchaAppId,纯数字
```

它**不是密钥** —— 控件把它印在每一个请求里,谁都看得到;真正的密钥 `AppSecretKey` 只在服务端
(`kaodian.auth.captcha.tencent.app-secret-key`)。放进环境变量是因为它**随环境变**,不是因为要保密。
🔴 它必须和服务端那四项**同时**配,且与 `kaodian.auth.captcha.tencent.app-id` 是**同一个数**。

> 仓库里**没有** `.env.example`:`.gitignore` 的 `.env.*` 与 pre-commit 的敏感文件闸都会挡它,
> 而**为了放进一份没有值的样例去动那两道闸,是拿一道真闸换一点方便**。键名与理由写在这一节。

| `VITE_KAODIAN_CAPTCHA_APP_ID` | 模式 | 界面 |
|---|---|---|
| 没填 | `bypass` —— 发占位串 `dev`/`dev` | 🔴 常驻一格**「未接入 · 行为验证」** |
| 一串数字 | `vendor` —— 真的弹滑块 | 不额外显示(滑块自己会弹出来,那就是证据) |
| 填了但不是数字 | `misconfigured` —— **当场报错,不退回占位串** | 红字点名这个键,且不给「再试一次」 |

第三行是有意的。退回占位串的后果是**本机点得通、上线当天全红**,而那时的错误信息
(`CAPTCHA_FAILED`)指向服务端,没有人会想到来看这个环境变量。

第一行那格「未接入」同样是有意的,理由和 `devCode` 那格一样:**一个安静的旁路和一个接通了的
滑块在屏幕上长得一模一样**,而这两者的差别是「短信费有没有上限」。

三件事**刻意没做**:

- **不实现厂商的 `loadErrorCallback` 容灾票据。** 厂商建议控件挂掉时前端自己伪造一张
  `trerror_…` 票据、以 `ret: 0` 回调「确保业务流程不被阻塞」。这条链路上不能照办 ——
  服务端 `TencentCaptchaVerifier` 写的是「供应商不可用时**判不通过**,不是放行」。
  从正常回调里流回来的容灾票据也被识别成「不可用」,**不发出去**:那一趟注定 400,
  换回的却是一句会被读成「验证码错了」的话。
- **脚本不写进 `index.html`**,按需加载。写进去意味着每一个访客一开屏就向
  `turing.captcha.qcloud.com` 发一次请求,包括从没点过「获取验证码」的那些人。
- **票据不复用。** 每次发送(含「重发一条」)都重新过一遍滑块 —— 票据是一次性的。

⚠️ **这条链路没有真机联调过**:腾讯云验证码产品尚未开通(与短信是两个独立产品),
没有 `CaptchaAppId` + `AppSecretKey`。对回调形状的处置来自厂商文档(as-of 2026-08-31),
不是实测结论。真凭据到手那天要复核的是 `classifyCaptchaResult` 这**一个函数**,
而它能被复核正是因为它在判据层 —— 每条分支都有一条 node 断言,改一条红一条。

## 依赖源

`.npmrc` 指向 npmmirror。**不要改,也不要用全局 registry** —— 与 Maven 同一条纪律:
公共镜像 ≠ 公司私服,副业与公司体系零交集。

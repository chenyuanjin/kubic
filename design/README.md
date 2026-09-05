# 设计稿

🔴 **2026-09-05 UI 审核修订**:这个文件原来写着 `ui-a/`「✅ 采纳,以此为准」,
而当时在用的底座已经是 v10,中间隔着 v4~v9。`ui-a/` 已归档,不许照着实现(理由见文末)。

## 目录

| | 内容 | 状态 |
|---|---|---|
| `h5/` | **H5(Web 主形态)差分稿**,`KUBI-79` 审核修订产出 | ✅ **在用** |
| `archive/ui-a-kubi72/` | `KUBI-72` 时代的极客暗色,D1–D32 | ❌ **作废**,只作决策记录 |
| `explorations/` | 更早的三方向对比 | ❌ 未采纳,决策记录 |

## `h5/` —— 当前在用

| 文件 | |
|---|---|
| `index.html` | 封面:五处差分逐条列出 |
| `home.html` `blind.html` `rec.html` `raw.html` `states.html` | 五张屏 |
| `tokens.css` `v10.css` | 底座,逐字节自 `kubi-80-v10-blueprint`,**不许在这里改** |
| `h5.css` | H5 差分层,80 行 |
| `H5交互说明.md` | 逐屏 触发 → 反馈 → 失败落点 |

看的方法:直接浏览器打开 `index.html`,或起个本地服务:

```bash
cd design/h5 && python3 -m http.server 8000
```

## 🔴 底座不在这个仓库里

底座在 OpenDesign 工程 `kubi-80-v10-blueprint`,`KUBI-81~88` 八份模块稿也各在自己的工程里,
**都没有推上 origin**:换台机器打不开,与仓库的一致性也没有任何装置在守。
`KUBI-79` 已登记为阻断项。在它们落进 `design/` 之前,**不要基于「仓库里没有」推出任何结论** —— 东西可能存在,只是没推。
`h5/` 的两个 css 是逐字节复制过来的(`cmp` 已验同),目前唯一进了 git 的 v10 底座。

## 🔴 界面上三处不可弱化的东西

改任何一端都要保住(来源:`docs/decisions/INDEX.md` §2.3 图片留存红线):

1. **同意留存原图的那一刻** —— 必须在收图之前挡住,不是设置里的一个开关
2. **原图到期提示** —— 到期行为是**归档保留**不是删除(2026-08-30 起),文案要跟着改
3. **随时可按的立即删除** —— 用户手按的删除是整层唯一的真删路径

以及能力边界:界面上**不得出现**正确率、得分、排名、题目讲解、学习建议、复习提醒、打卡、徽章。
这条有自动化红绿灯兜底:

```bash
cd web && npm run test:boundary
```

⚠️ **这个红绿灯 2026-09-05 之前只扫 `web/src`,不扫 `design/`** ——
判据引用的正是本文件第 45 行,却从不扫设计稿目录,
所以 `archive/ui-a-kubi72/` 里 9 处「正确率」躺了四天没人发现。
现在扫描范围含 `design/`,跳过三样:`archive/` 与 `explorations/`(决策记录,保留原文才有价值)、
本文件(它引用禁用词是为了定义禁令)。

## `archive/ui-a-kubi72/` —— 为什么它必须被降级,而不是留着

`ui-a/` 是 `KUBI-72` 时代(2026-09-01 之前)的方案。它不只是「旧」:

- `tokens.css` 里有五档**掌握度**状态色(稳/弱/生疏/仅接触/空白),
  其中「弱」的定义就是「练过但用户自填正确率低」—— 这正是「覆盖度不是掌握度」被打穿;
- `.meter` 3px 进度条与 `.conf` 置信度条 = 成绩式进度表达;
- 上屏文案 9 处「正确率」,而「正确率」2026-09-04(`KUBI-107`)已升硬名单。

**任何从 origin 拉代码的人照着它实现,实现出来的就是一个红线全面失守的产品。**
教训与 2026-08-29 那次同源:**仓库里的东西不会因为它旧就自动变得无害,
它会一直是权威,直到有人明确把它降级。**

## `explorations/` —— 未采纳的三个方向

`styles.html` 是三方向对比页:**A 极客暗色 / B 便当格 / C 实体答题卡**。
当时选了 A(即后来的 `ui-a/`),A 现在也已作废。留着是因为它记录了当时为什么否掉另外两个 ——
`docs/execution/INDEX.md` 的 `R-29` 是这条线的决策记录。

## OpenDesign 工程路径

```
~/Library/Application Support/Open Design/namespaces/release-stable/data/projects/
  kubi-80-v10-blueprint/  → 当前底座(未进 git,见上)
  notetool-ui-a/          → design/archive/ui-a-kubi72/(作废)
  notetool-blindspot/     → design/explorations/
```

⚠️ OpenDesign 守护进程绑的是**动态端口**,每次启动都变,`od mcp install` 写死的端口是坏的。
详见记忆 `opendesign-mcp-dynamic-port`。

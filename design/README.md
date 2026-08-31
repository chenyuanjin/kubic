# 设计稿

源文件在这里,**OpenDesign 里的工程是同一批文件的编辑视图**,不是另一份真相。
改完记得两边同步(OpenDesign 工程路径见下)。

## 目录

| | 内容 | 状态 |
|---|---|---|
| `ui-a/` | **极客暗色 · 命令条驱动**,D1–D32 | ✅ **采纳,以此为准** |
| `explorations/` | 早期三方向对比 | ❌ 未采纳,留作决策记录 |

## `ui-a/` —— 当前方案

| 文件 | |
|---|---|
| `index.html` | 封面与总览 |
| `app.html` `h5.html` `miniprogram.html` `pad.html` `desktop.html` | 五端 |
| `tokens.css` | 设计令牌,五端共用 |
| `壳形态交互差异评估.md` | 桌面/移动壳带来的交互差异,含三处不可弱化的留存承诺 |

看的方法:直接浏览器打开 `index.html`,或起个本地服务:

```bash
cd design/ui-a && python3 -m http.server 8000
```

## `explorations/` —— 未采纳的三个方向

`styles.html` 是三方向对比页:**A 极客暗色 / B 便当格 / C 实体答题卡**。
最终选了 A。留着是因为它记录了当时为什么否掉另外两个 ——
`docs/总路线图` 的 `R-29` 是这条线的决策记录。

其余几份(`miniprogram.html` `pad.html` `desktop.html`)是 A 方案定稿前的旧版,
**与 `ui-a/` 冲突时一律以 `ui-a/` 为准**。

## 🔴 界面上三处不可弱化的东西

改任何一端都要保住(来源:`docs/决策记录` §2.3 图片留存红线):

1. **同意留存原图的那一刻** —— 必须在收图之前挡住,不是设置里的一个开关
2. **原图到期提示** —— 到期行为是**归档保留**不是删除(2026-08-30 起),文案要跟着改
3. **随时可按的立即删除** —— 用户手按的删除是整层唯一的真删路径

以及能力边界:界面上**不得出现**正确率、得分、排名、题目讲解、学习建议、复习提醒、打卡、徽章。
这条有自动化红绿灯兜底:

```bash
cd web && npm run test:boundary
```

## OpenDesign 工程路径

```
~/Library/Application Support/Open Design/namespaces/release-stable/data/projects/
  notetool-ui-a/          → design/ui-a/
  notetool-blindspot/     → design/explorations/
```

⚠️ OpenDesign 守护进程绑的是**动态端口**,每次启动都变,`od mcp install` 写死的端口是坏的。
详见记忆 `opendesign-mcp-dynamic-port`。

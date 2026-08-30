# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

A **decision record that has grown a working prototype**. Chinese-language Markdown documents under `docs/` carry the reasoning; `server/` (Spring Boot 4.1.1 / Java 21) and `web/` (React 19 + Vite + Tailwind 4) carry the code. **The documents remain authoritative** — the code implements them, not the other way round. The document list, with each one's pipeline and the gate it serves, is `docs/00-文档规范与目录.md` — **never counted or enumerated here**.

`files.zip` is a **stale backup of the original four documents only** (dated 2026-08-20). Ignore it; don't treat it as a source of truth or re-sync it unless asked.

### server/ 的四个模块 (2026-08-28 由单模块拆开)

边界照着**真实的包依赖方向**切,不是照分层名词切。依赖图无环:

| 模块 | 内容 | 依赖 |
|---|---|---|
| `kaodian-domain` | `syllabus` / `recognize` / `collect` / `coverage` / `config` —— 「盲区 = 骨架层 − 行为层」这条公式本身 | 无(**刻意没有 web 依赖**) |
| `kaodian-auth` | `auth` + `auth.vendor` | 无(**刻意不依赖 domain**) |
| `kaodian-agent` | agent 运行时:`channel` / `orchestrator` / `tool` / `storage` / `session` / `llm` / `prompt` | domain |
| `kaodian-app` | `api.*` + 启动类 + 配置 —— 唯一产出可执行 jar | 全部 |

两个「刻意」是重点:**`auth` 与业务侧的 import 交集是空集**,单独成模块正是为了让这个「空」有物理形态;
**`domain` 没有 web 依赖**,想在里面 import 一个 Controller 时 Maven 会先一步告诉你放错地方了。
`kaodian-agent` **不依赖 `kaodian-auth`** —— agent 拿不到账号体系,这是被 Maven 保证的。

没有 `kaodian-common`:抽不出来。唯一像共享基元的 `AuthJsonFile` 在自己的注释里写明了刻意不下放。

`api` 包内按领域分子包:`support`(异常/CORS/会话解析)、`auth`、`syllabus`、`record`、`insight`(覆盖率/时间线/导出)、`agent`;
`api.dto` 同样分子包,跨领域共用的进 `dto/common`。

### Commands

```bash
./server/build.sh -q test          # 后端测试(四个模块全跑)。唯一允许的构建入口 —— 直接 ./mvnw 会让依赖走公司私服(docs/10 §1.3)
# agent 接真实模型手工验证(密钥只走环境变量,不进仓库;端点与模型已有默认值,默认走免费档):
#   KAODIAN_MODEL_KEY=sk-or-... java -jar server/kaodian-app/target/kaodian-app-*.jar
#   curl -N -X POST localhost:8080/api/agent/chat -H 'Content-Type: application/json' -d '{"message":"我的覆盖率怎么样"}'
# 默认 OpenRouter + minimax/minimax-m3:free —— 关卡 0 之前不为还没人用的功能付费。
# 免费模型表天天变,换之前先查「免费且支持 tools」的当前清单:见 application.properties 里那条 curl。
# 🔴 换模型/换端点后【必须】跑两个问题,不能只跑「你好」(见 docs/08 R-88):
#     ① 需要调工具的  —— 「我的覆盖率怎么样」
#     ② 越界的        —— 「这道题怎么做,教教我」(应当拒绝讲解并转报记录)
./server/build.sh -q test -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false   # 单个测试类
                                   # 🔴 后半截不能省:多模块下 -Dtest=X 会在【不含该类的模块】上失败,
                                   #    报的是「No tests matching pattern」而不是真正的测试结果
cd web && npm run lint             # oxlint
cd web && npm run build            # tsc -b && vite build
cd web && npm run test:boundary    # 能力边界文案扫描(R-05)
./shell/build.sh                   # 桌面壳(macOS)。唯一允许的构建入口 —— 步骤 ①.1 校验 cargo 生效源,
                                   # 绕过去会让依赖走公司私服(`R-111`,与 server/build.sh 拦的是同一件事)
```

`shell/` 是 **Tauri 2 桌面壳,当前只有 macOS**:把 `web/dist` 内嵌进二进制,自己起回环静态服务 + `/api` 反代。
**`web/` 与 `server/` 零改动是它的约束** —— `build.sh` 步骤 ③ 用 `git status` 当场校验,有 diff 就拒绝构建。
契约在 `docs/18`,端的完整矩阵在 `docs/19`。⚠️ **它没有 `[lib]` 目标,所以 iOS / Android 现在编不出来** ——
Tauri 2 的移动端入口是 `lib.rs` 的 `run()`,不走 `main.rs`;见 `00` §2.6 E4。

`build.sh` requires `~/.m2/settings-side.xml` and refuses to run if it points at a private mirror or carries credentials. **Never bypass it.**

`git config core.hooksPath .githooks` is **local config and does not travel with a clone** — set it in every new working copy or both commit gates are silently off.

The product: a **cross-source study-record tool for Chinese exam candidates** (公考 civil service + 考研 postgrad). Core formula: `盲区 = 骨架层 − 行为层` — blind spots are the set difference between a maintained syllabus tree and what the user has actually touched. The defensibility claim is that incumbents (粉笔/中公/华图) *structurally cannot* aggregate competitors' study records, because doing so tears down their own walls.

Current state (2026-08-28): the backend is **four Maven modules** with ~480 green tests including six red-line assertion suites; the visual direction **is decided** (风格 A「极客暗色 · 命令条驱动」, 55 screens in OpenDesign — `08` `R-29` closed); a Multica agent pipeline runs delivery (see below); an in-product **conversational agent** (`kaodian-agent`, SSE + tool pool + file storage) runs end to end. And there is still **zero real user feedback** — `1.1.4` (the two daily numbers that are gate 0's entire input) is empty.

**The agent is the newest instance of the documented failure mode, not an exception to it** (`08` `R-85`): it has a demo, it streams, it can be polished indefinitely, and it contributes **nothing** to gate 0's judgement. The self-check when sitting down: *am I adding a tool to the agent, or am I finding those two daily numbers?*

**That last sentence is the point of the whole repository.** Everything else on this page is infrastructure around a hypothesis nobody has tested.

## The documents

🔴 **`docs/00-文档规范与目录.md` is the single source of truth for the document list.** Every document's pipeline (产品/合规/数据), the gate it serves, its layer, its status, and how staleness is judged live in `00` §二 — **and nowhere else**. This page used to carry a second copy of that list; it went stale (it said fifteen documents when there were sixteen, and `R-01…R-87` when the register had reached `R-105`). That is exactly the failure `00` exists to prevent. **Do not re-add a document table here** — add a row to `00` §二 instead.

Read `00` before adding, moving, renumbering, or freezing any document. Its §六 is the action checklist; its §五 is the red line that forbids any template field, example, placeholder, or appendix capable of holding a question stem.

Two things `00` records that are not obvious from the tree:

- **No document lives only on a branch any more** — all eight `KUBI-*` branches were merged into `v1` on 2026-08-30 and their refs deleted. Three numbering collisions were resolved in that pass, all with the same cause (branch baseline older than trunk, so the number was still free when it was written): `15` shell design → `18`, `17` raw-image storage → `21`, and risk ids `R-72`…`R-76` → `R-108`…`R-112`. See `00` §2.2 — including why the `R-74` collision produced **no git conflict at all**.
- **The trunk is `v1`** — decided by a human on 2026-08-30 (`00` §2.4-①). Branch `KUBI-<n>-<slug>` off `origin/v1`, target PRs at `v1`, and write baselines as `基线:origin/v1 @ <SHA7>,fetch 于 <时间>`. `main` is left as-is — 17 documents and 8,737 lines behind, not fast-forwarded, not deleted. ⚠️ **`origin/HEAD` still points at `main`** until a repo admin changes the GitHub default branch, so a fresh clone still lands on the near-empty branch — see `00` §2.6 E1.

**Never let the execution layer overwrite the decision layer.** `05` states it explicitly: "04 的关卡判据在这里一个字都不改." When new research contradicts a decision-layer document, record the correction downstream and annotate upstream — do not silently rewrite the original. Doc `04`'s cost table keeps its superseded ¥2,000 estimate with a pointer, exactly for this reason.

**02 exists to prevent re-proposing dead directions. 03 exists to prevent repeating the reasoning errors that produced them. 04 exists to force contact with real users instead of more product polish.** A change to 01's conclusion invalidates parts of all three.

Doc 03 opens by arguing against itself ("框架能防止重复犯已犯过的错,不能帮你发现新东西"). Preserve that self-critical stance when editing — it is deliberate, not hedging.

`08` is the aggregate view — `05`/`06`/`07` are its expansions, `09` is the evidence layer under `06`, `13` is the layer under `10`, `14` is the delivery infrastructure around all of it. **New risks go into `08` §四 with an `R-xx` id**, not into ad-hoc lists — and the current highest id is read from `08`, never quoted here.

## Three tracks, three different clocks

This is the load-bearing structure of the execution layer. **Serializing them is the documented error:**

| Track | Clock | Doc |
|---|---|---|
| 产品轨 | 关卡 (gates) | `05` block 1, `06` |
| 合规轨 | approval lead times | `05` block 2 |
| 数据轨 | risk boundary | `07` |

Serializing produces two wastes: registering a company / filing ICP before a gate passes (money and identity spent on something unproven), or starting filing only after a gate passes (3–5 weeks of dead waiting). The compliance track therefore starts **one notch ahead of the product track but never spends money past a gate.**

`07` §六 is a **conflict declaration** — read it before touching the data track. The data track may accumulate raw material for four subjects while the product ships exactly one module. The single test for whether that line has been crossed: **has 人工校正命名 been done for a second subject?**

## The delivery pipeline (docs/14)

Work is dispatched through a Multica workspace (`kubicc`, issue prefix `KUBI`). Four gates; **a human stands only at the fourth**:

| Gate | Who runs it | Verdict power |
|---|---|---|
| 1 机器闸 | CI: `build.sh -q test` + web lint/build/boundary | Yes — the only place that auto-changes status |
| 2 agent 审核 | review/test agents | **No.** Writes metadata only |
| 3 差异人审 | you | Only handles exceptions |
| 4 关卡 | you | Yes. **Never automate this** (`R-10`) |

Rules that bind any agent working here:

- **Agents may advance an issue to `in_review`, never to `done`.** An agent's conclusion is *input*, not *judgment*.
- **Verdicts go in metadata, prose goes in comments.** Five keys: `verdict` / `blocking_count` / `repro_cmd` / `redline_hit` / `escalate`. **A verdict without a reproducible `repro_cmd` is void** — and `repro_cmd` must run from the repo root with no absolute paths.
- **Branch `KUBI-<n>-<slug>`.** Not naming hygiene — the PR↔issue link is built from that string; without it gate 1's result never reaches the issue.
- `红线命中` is a **property**, `需人审` is a **label**. Different commands. Agents must never create new labels or properties.
- **Every assertion must have been made red once before it counts.** All five red-line suites were deliberately broken and restored during authoring. See `14` §9.10 for the three design lessons — most importantly: **a blacklist must not match this repo's own compliance comments**, which are written in negative form and therefore contain the forbidden word.

## Hard constraints

Settled decisions, several marked "不改变". Treat as invariants; flag conflicts rather than quietly working around them.

- **能力边界** — never judge "对不对". Only "有没有、几次、多久前". ⚠️ **With images this is weaker than it looks** (`08` `R-89`): the agent takes multimodal input, so on a photo of a question the model *has the stem* and only `AgentPrompt`'s wording stands between it and explaining the answer. On text-only turns the tool pool still makes it physically impossible. Editing that prompt section changes product behaviour. Since the agent landed there are **two** `ChatModel` injection points (`recognize` and `agent.llm`), not one — `13` §4.1's "除 recognize 外无 ChatModel 注入点" no longer holds verbatim; the surviving rule is *injection points stay countable and each has a name* (`08` `R-86`). 🔴 The prompt's "don't judge correctness" is the **second** line of defence — it only *asks* the model. What makes it impossible is that **no tool can reach a question stem, answer, or explanation**. Adding a tool that returns question content ends the boundary that moment, and nothing will error.
- **不做教研** — no subject-matter instruction. Academic judgment is outsourced to external models. Described as the source of every other advantage.
- **不碰内容** — never store institutions' course content; record source name and timestamp only.
- **Image retention** 🔴 — holds everywhere, agent included: chat images live in memory only until sent to the model — never written to `messages.ndjson`, never logged, never staged with a vendor (verified 2026-08-28: zero PNG-magic / base64 / log hits after an image turn). The enforcement is structural — `MessagePart` has no type that can hold an image. After extraction, original images get local/short-term cache only. Never long-term cloud storage or sharing. **This includes vendor image-staging APIs** — DeepSeek Files API *and* 百炼's `oss://dashscope-instant/...` (`R-52` extends `R-04` to every vendor's file staging). Inline base64 only (`09` §四). Machine-checked by `ImageRetentionTest`. Doc 01 notes this cannot be reversed later.
- **真题原文不上线** 🔴 — the online schema must have no field capable of holding a question stem (`07` §二, `R-01`). Scraping code must not contain login/captcha/fingerprint capability — "不是不用,是不写" (`R-02`).
- **宁缺毋滥** — discard auto-tags that don't match rather than force-fitting; wrong tags corrupt the coverage metric that is the whole product.
- **Closed-set tagging** 🔴 — the model selects a node id from supplied candidates or returns "no match". It never generates label text. This single constraint blocks both hallucinated 考点 and inadvertent reuse of institutions' wording (`P1-8`, `R-07`).
- **Openness** — full export (Markdown/CSV/JSON) and read-only MCP/CLI. MCP exposes *read*, never write or capture; it is a **marketing asset, not a revenue line** — capped at 20% of effort.
- **Form factor** — responsive web first. "小程序为主" was overturned in `01` §2.4 and stays overturned; the mini-program returns after gate 2 as a **capture entry point only, with blind-spot analysis linking out to web** (`L-C6`, `2.3.6`).
- **Scope** — one module, one subject to start.
- **Business shape** — 2–3 person independent business, no sales team; no field sales while still employed. **The company entity already exists** (confirmed 2026-08), so registration decisions in `05` `L0` are void.

North-star metric is **主动查看盲区的人数** — not signups, not DAU.

## Working discipline in this repo

Doc 03 §盲区二 documents the project's own failure mode: *attention flows toward the part that can be built, not the part that is most uncertain.* Concretely — a dozen rounds of product refinement while the distribution problem went untouched, and a demo polished before a single real user was contacted.

`08` §六 sharpens it: the compliance and data tracks both produce satisfying quantifiable progress, **and neither requires facing a single real user.** The self-check is whether admin items are getting ticked while `P0-6` (the two daily numbers that are gate 0's entire input) sits empty.

- Do not answer a strategy question by proposing features. If the next real blocker is a gate, say so.
- `04` and `08` are deliberately detailed only up to the next gate — a 12-month plan was written once for the GEO direction and thrown away entirely. **Do not "help" by expanding later stages.**
- Gate criteria are pass/fail, not targets to tune toward. The named error: adjusting the product when gate data lands near the line. "产品不是变量,需求才是。" (`R-10`: same gate failed three times is a fail.)
- **Unresolved items stay unresolved.** `01` §5 and `08` §四⚪ track them honestly (cold start, recording completeness, syllabus cold-start cost, 生成式 AI 登记). A plausible inference does not close one — `09` narrowed the AI-filing question but its entry still says "这是推理不是书面确认".

## Documentation conventions

**Structure, numbering, source-of-truth, staleness, and the stem-field red line are all in `docs/00-文档规范与目录.md`.** What follows is style only.

- **Documents are written in Chinese.** Match the surrounding register: dense, declarative, no filler.
- Comparison tables for anything with more than two options; bold for the single load-bearing sentence in a section; `>` blockquote at the top of each file stating purpose and caveats.
- Research data carries an as-of date and names its sources, including when sources contradict each other. Preserve both the numbers and the disagreement — `02` draws a methodological lesson from a 30× spread in market-size figures.
- Reversible decisions are logged with an explicit "何时改变" condition (`01` §7). Include that column when recording a new one.
- Superseded numbers are annotated in place, not deleted (see `04` §成本).
- Per global instruction, any flowchart must use Mermaid.

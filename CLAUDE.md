# CLAUDE.md

**A map, not an encyclopedia** — every rule is one line plus a pointer. When this page and a `docs/` file disagree,
**`docs/` wins**.

## Project Overview

A **cross-source study-record tool for Chinese exam candidates** (公考 + 考研), carried by one formula: `盲区 = 骨架层
− 行为层` — the set difference between a maintained syllabus tree and what the user actually touched. Incumbents
(粉笔/中公/华图) *structurally cannot* aggregate competitors' records; that is the whole defensibility claim. North
star: **主动查看盲区的人数** — not signups, not DAU.

A **decision record that grew a prototype**: `docs/` carries the reasoning and **remains authoritative**; `server/` +
`web/` + `shell/` implement it (`files.zip` is a stale backup — ignore it). **Current state**: ~480 green tests, delivery on Multica, an in-product agent running end to end — **and zero real user feedback**: `1.1.4`, the two daily
numbers that are gate 0's entire input, is empty.

## Documentation Map

🔴 **[`docs/INDEX.md`](docs/INDEX.md) is the entry point** — reading routes, L0/L1/L2 tiers, the product-vs-technical
split, gate ordering. **Never read `docs/` in number order**: the numbers are writing order, not reading order.

🔴 **[`docs/00`](docs/00-文档规范与目录.md) is the single source of truth for the document list** — pipeline, gate
served, status, staleness rule, numbering ledger, open decisions. **Never re-add a document table here**; add a row to
`00` §二. Read `00` §六 before adding/moving/renumbering/freezing any `.md`, and `00` §五 for the red line forbidding
any field, example, placeholder or appendix able to hold a question stem. **Never let the execution layer overwrite
the decision layer** (`05`: 「04 的关卡判据在这里一个字都不改」): record corrections downstream, annotate upstream,
never silently rewrite; superseded numbers stay with a pointer.

## Architecture Quick Ref

`server/` is four Maven modules cut along the **real package-dependency direction**, acyclic — the two "deliberately"s
are the point, and why there is no `kaodian-common` ([`13`](docs/13-后端系统设计与组件接入.md) §二):

| Module | Contains | Depends on |
|---|---|---|
| `kaodian-domain` | `syllabus` / `recognize` / `collect` / `coverage` / `config` — the formula itself | **nothing** (deliberately no web dep) |
| `kaodian-auth` | `auth` + `auth.vendor` | **nothing** (deliberately not domain) |
| `kaodian-agent` | `channel` / `orchestrator` / `tool` / `storage` / `session` / `llm` / `prompt` | domain (**never auth**) |
| `kaodian-app` | `api.*` + startup + config — the only executable jar | all |

`shell/` is a **Tauri 2 desktop shell, macOS only**: embeds `web/dist`, serves it over loopback, proxies `/api`.
**Zero changes to `web/` and `server/` is its constraint** — `build.sh` step ③ enforces it with `git status`. ⚠️ No
`[lib]` target, so **iOS / Android do not build** (`00` §2.6 E4). Detail → [`10`](docs/10-技术架构与接口契约.md) contracts · [`18`](docs/18-壳技术方案：Tauri%202%20包现有%20Web%20工程.md) shell · [`19`](docs/19-多端选型与端矩阵.md) client matrix

## Commands

```bash
./server/build.sh -q test          # backend, all four modules. The ONLY allowed entry — bare ./mvnw pulls deps
                                   # from the company's private mirror (docs/10 §1.3)
./server/build.sh -q test -Dtest=X -Dsurefire.failIfNoSpecifiedTests=false
                                   # 🔴 the tail is not optional: multi-module -Dtest=X fails on modules lacking
                                   #    the class, reporting "No tests matching pattern" instead of a real result
cd web && npm run lint / npm run build / npm run test:boundary   # oxlint · tsc+vite · boundary scan (R-05)
./shell/build.sh                   # macOS shell. Also the only allowed entry (`R-111`, same guard)
```

`build.sh` needs `~/.m2/settings-side.xml` and refuses to run if it points at a private mirror or carries credentials
— **never bypass it**. `git config core.hooksPath .githooks` is **local config that does not travel with a clone**;
unset, both commit gates are silently off. Keys go through env vars only. After a model/endpoint change, 🔴 **run two
prompts, not just 「你好」** (`R-88`): one needing a tool (「我的覆盖率怎么样」) and one out of bounds
(「这道题怎么做,教教我」→ must refuse and redirect).

## Critical Rules

Settled invariants — **flag a conflict rather than working around it**. Full text at the pointer; the lower-blast-radius ones (开放性 / 形态与范围 / 生意形态) are in [`01`](docs/01-项目现状与决策记录.md) §2.4 · §三 and [`11`](docs/11-商业化与额度设计.md) §二.

| Rule | One line | Source |
|---|---|---|
| **能力边界** 🔴 | Never judge 「对不对」 — only 「有没有、几次、多久前」. The prompt wording is the *second* defence; what makes it impossible is that **no tool can reach a stem, answer or explanation**. Add such a tool and the boundary ends that moment, nothing erroring. Weaker on images | `15` §四 · `R-86`/`R-89` |
| **真题原文不上线** 🔴 | The online schema has no field able to hold a stem, not even reserved. Scrapers carry no login/captcha/fingerprint capability — 「不是不用,是不写」 | `07` §二 · `R-01`/`R-02` |
| **原图留存** 🔴 | Images live in memory until sent to the model — never persisted, logged, or staged with a vendor (**incl. vendor file-staging APIs**, `R-52`). Inline base64 only. Structural: `MessagePart` has no image-capable type. Machine-checked | `09` §四 · `21` |
| **闭集打标** 🔴 | The model picks a node id from supplied candidates or returns "no match" — **never generates label text**. Blocks hallucinated 考点 *and* reuse of institutions' wording | `10` · `R-07` |
| **不做教研 / 不碰内容** | No subject-matter instruction — academic judgment is outsourced, and that is called the source of every other advantage. Never store an institution's course content: source name + timestamp only | `01` §2.2 |
| **宁缺毋滥** | Discard non-matching auto-tags rather than force-fit. Wrong tags corrupt the one metric the product has | `01` §2.5 |

## Delivery

Multica workspace `kubicc`, prefix `KUBI`. Four gates, **a human stands only at the fourth** — how-to in
[`17`](docs/17-Multica%20操作备忘.md), why in [`14`](docs/14-自动化交付工作流.md) §三. Binding on every agent:

- **Trunk is `v1`.** Branch `KUBI-<n>-<slug>` off `origin/v1`; PRs target `v1`; never commit to `v1` or `main` directly; declare `基线:origin/v1 @ <SHA7>,fetch 于 <时间>`. That string **is** the PR↔issue link — without it gate 1's result never reaches the issue.
- **Agents advance an issue to `in_review`, never `done`.** An agent's conclusion is *input*, not judgment.
- **Verdicts in metadata, prose in comments** — `verdict` / `blocking_count` / `repro_cmd` / `redline_hit` / `escalate`.
  **A verdict without a `repro_cmd` runnable from the repo root is void.** `红线命中` is a **property**, `需人审` a **label** — different commands; never create new ones.
- **Every assertion must have been red once before it counts.** A blacklist must not match this repo's own compliance comments, written in negative form and so containing the forbidden words (`14` §9.10) — the doc audit's "absolute path" hits in `14` are exactly this false positive.

## Working Discipline

`03` §盲区二 documents the project's own failure mode: **attention flows toward the part that can be built, not the
part that is most uncertain.** `08` §六 sharpens it — compliance and data both produce satisfying quantifiable
progress and neither requires facing a real user. **The self-check when sitting down: am I adding a tool to the agent,
or am I finding those two daily numbers?**

- Do not answer a strategy question by proposing features. If the next blocker is a gate, say so.
- **Gate criteria are pass/fail, not targets to tune toward.** 「产品不是变量,需求才是。」 Same gate failed three times is a fail (`R-10`). Never adjust the product because data landed near the line.
- `04` and `08` are detailed only to the next gate — a 12-month plan was written once for a dead direction and thrown
  away whole. **Do not "help" by expanding later stages.** **Unresolved items stay unresolved**: a plausible inference does not close a ⚪ (`01` §5, `08` §四⚪).
- Docs are Chinese, dense, declarative; **flowcharts Mermaid, mind maps markmap** (a plain Markdown outline — [`22`](docs/22-产品模块脑图.md); Mermaid `mindmap` was dropped there because it cannot fold and overlaps past ~60 nodes, and the outline stays readable with no tooling); research carries an as-of date and names its sources
  **including where they disagree**. Full conventions → [`00`](docs/00-文档规范与目录.md).

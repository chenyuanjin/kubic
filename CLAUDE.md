# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

A **documentation-only decision record** for a pre-implementation product exploration. There is no source code, build system, test suite, or package manifest — only nine Chinese-language Markdown documents under `docs/`. It *is* a git repo (single commit `2231ca7`; docs 05–09 are still untracked).

`files.zip` is a **stale backup of the original four documents only** (dated 2026-08-20, before 05–09 existed). Ignore it; don't treat it as a source of truth or re-sync it unless asked.

Do not look for or invent build/lint/test commands. All work here is reading, revising, and extending prose.

The product: a **cross-source study-record tool for Chinese exam candidates** (公考 civil service + 考研 postgrad). Core formula: `盲区 = 骨架层 − 行为层` — blind spots are the set difference between a maintained syllabus tree and what the user has actually touched. The defensibility claim is that incumbents (粉笔/中公/华图) *structurally cannot* aggregate competitors' study records, because doing so tears down their own walls.

Current state: a runnable demo exists (**not in this repo**), design decks exist (in OpenDesign, **not in this repo**; the three-way visual direction is still undecided — `08` `R-29`), and there is **zero real user feedback**. That gap is the entire point of the document set.

## The nine documents: two layers

**Never let the execution layer overwrite the decision layer.** `05` states it explicitly: "04 的关卡判据在这里一个字都不改." When new research contradicts a decision-layer document, record the correction downstream and annotate upstream — do not silently rewrite the original. Doc `04`'s cost table keeps its superseded ¥2,000 estimate with a pointer, exactly for this reason.

### Decision layer — what and why

| File | Role | Consult when |
|---|---|---|
| `01-项目现状与决策记录.md` | Product definition, constraints, open problems, decision log | Any question about *what* is being built or *why* |
| `02-已证伪方向与调研结论.md` | Six falsified directions with research data and cause of death | Before proposing any new direction — check it isn't already dead |
| `03-思考模式与选择框架.md` | Selection framework + three documented reasoning blind spots | When evaluating a proposal or asked for a judgment call |
| `04-实施路径.md` | Staged path with hard gates (关卡) and stop-loss lines | Sequencing, timeline, "what's next" |

**02 exists to prevent re-proposing dead directions. 03 exists to prevent repeating the reasoning errors that produced them. 04 exists to force contact with real users instead of more product polish.** A change to 01's conclusion invalidates parts of all three.

Doc 03 opens by arguing against itself ("框架能防止重复犯已犯过的错,不能帮你发现新东西"). Preserve that self-critical stance when editing — it is deliberate, not hedging.

### Execution layer — how

| File | Role |
|---|---|
| `05-执行清单.md` | Checklist form of `04`. Two blocks: 产品开发 (gate-governed) + 上线准备 (approval-governed) |
| `06-阶段0至关卡2详细排期.md` | Week-by-week schedule to gate 2, with the workload math that decides whether stage 1 is feasible |
| `07-数据线：骨架原料的获取与隔离.md` | The data track: acquiring syllabus raw material without becoming a piracy host |
| `08-总路线图.md` | Parent/child todo tree across all three tracks + **the unified risk register `R-01`…`R-35`** |
| `09-识别链路选型.md` | ASR / image-recognition vendor selection, pricing, compliance basis (as-of 2026-08) |

`08` is the aggregate view — `05`/`06`/`07` are its expansions, and `09` is the evidence layer under `06`'s tech-selection table. **New risks go into `08` §四 with an `R-xx` id**, not into ad-hoc lists.

## Three tracks, three different clocks

This is the load-bearing structure of the execution layer. **Serializing them is the documented error:**

| Track | Clock | Doc |
|---|---|---|
| 产品轨 | 关卡 (gates) | `05` block 1, `06` |
| 合规轨 | approval lead times | `05` block 2 |
| 数据轨 | risk boundary | `07` |

Serializing produces two wastes: registering a company / filing ICP before a gate passes (money and identity spent on something unproven), or starting filing only after a gate passes (3–5 weeks of dead waiting). The compliance track therefore starts **one notch ahead of the product track but never spends money past a gate.**

`07` §六 is a **conflict declaration** — read it before touching the data track. The data track may accumulate raw material for four subjects while the product ships exactly one module. The single test for whether that line has been crossed: **has 人工校正命名 been done for a second subject?**

## Hard constraints

Settled decisions, several marked "不改变". Treat as invariants; flag conflicts rather than quietly working around them.

- **能力边界** — never judge "对不对". Only "有没有、几次、多久前".
- **不做教研** — no subject-matter instruction. Academic judgment is outsourced to external models. Described as the source of every other advantage.
- **不碰内容** — never store institutions' course content; record source name and timestamp only.
- **Image retention** 🔴 — after extraction, original images get local/short-term cache only. Never long-term cloud storage or sharing. **This includes vendor image-staging APIs** (e.g. DeepSeek Files API) — inline base64 only (`09` §四, `08` `R-04`). Doc 01 notes this cannot be reversed later.
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

- **Documents are written in Chinese.** Match the surrounding register: dense, declarative, no filler.
- Comparison tables for anything with more than two options; bold for the single load-bearing sentence in a section; `>` blockquote at the top of each file stating purpose and caveats.
- Research data carries an as-of date and names its sources, including when sources contradict each other. Preserve both the numbers and the disagreement — `02` draws a methodological lesson from a 30× spread in market-size figures.
- Reversible decisions are logged with an explicit "何时改变" condition (`01` §7). Include that column when recording a new one.
- Superseded numbers are annotated in place, not deleted (see `04` §成本).
- Per global instruction, any flowchart must use Mermaid.

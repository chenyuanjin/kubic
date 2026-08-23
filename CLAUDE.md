# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

A **documentation-only decision record** for a pre-implementation product exploration. There is no source code, build system, test suite, package manifest, or git repo here — only four Chinese-language Markdown documents under `docs/`, plus `files.zip` (a backup archive of those same four files, identical sizes; ignore unless asked to re-sync it).

Do not look for or invent build/lint/test commands. All work here is reading, revising, and extending prose.

The product under discussion: a **cross-source study-record tool for Chinese exam candidates** (公考 civil service + 考研 postgrad). Core formula: `盲区 = 骨架层 − 行为层` — blind spots are the set difference between a maintained syllabus tree and what the user has actually touched. The defensibility claim is that incumbents (粉笔/中公/华图) *structurally cannot* aggregate competitors' study records, because doing so tears down their own walls.

Current state: a runnable demo exists (**not in this repo**), full data-structure design exists, and there is **zero real user feedback**. That gap is the entire point of the document set.

## How the four documents work together

They are not four topics — they are four roles in one decision system. Read in this order:

| File | Role | Consult when |
|---|---|---|
| `docs/01-项目现状与决策记录.md` | Current conclusion, product definition, constraints, open problems, decision log | Any question about *what* is being built or *why* a choice was made |
| `docs/02-已证伪方向与调研结论.md` | Six falsified directions with research data and cause of death | Before proposing any new direction — check it isn't already dead |
| `docs/03-思考模式与选择框架.md` | The selection framework (方向 → 能力嵌入 → 产品) and three documented reasoning blind spots | When evaluating a proposal, or when the user asks for a judgment call |
| `docs/04-实施路径.md` | Staged execution path with hard gates (关卡) and stop-loss lines | Any question about sequencing, timeline, or "what's next" |

The load-bearing relationships: **02 exists to prevent re-proposing dead directions.** **03 exists to prevent repeating the reasoning errors that produced them.** **04 exists to force contact with real users instead of more product polish.** A change to 01's conclusion invalidates parts of all three.

Doc 03 opens by arguing against itself ("框架能防止重复犯已犯过的错,不能帮你发现新东西"). Preserve that self-critical stance when editing — it is deliberate, not hedging.

## Hard constraints that govern any proposal

These are settled decisions in `01` §2.2 and §7, several marked "不改变". Treat them as invariants; flag conflicts rather than quietly working around them.

- **能力边界** — never judge "对不对" (correctness). Only "有没有、几次、多久前" (whether, how often, how long ago).
- **不做教研** — no subject-matter instruction. Academic judgment is outsourced to external models. This boundary is described as the source of every other advantage.
- **不碰内容** — never store institutions' course content; record source name and timestamp only.
- **Image retention line** — after OCR extracts text and tags, original images get local/short-term cache only, never long-term cloud storage or sharing. Doc 01 notes this cannot be reversed later.
- **宁缺毋滥** — discard auto-tags that don't match rather than force-fitting; wrong tags corrupt the coverage metric that is the whole product.
- **Openness** — full data export (Markdown/CSV/JSON) and read-only MCP/CLI. MCP exposes *read*, never write or capture. MCP is a **marketing asset, not a revenue line** — capped at 20% of effort.
- **Form factor** — responsive web first, mobile second. The WeChat mini-program option was explicitly overturned.
- **Scope** — one module, one subject to start. Two syllabus trees cold-started at once is called a disaster for a 2–3 person team.
- **Business shape** — 2–3 person independent business, no sales team; no field sales while still employed.

North-star metric is **主动查看盲区的人数** — not signups, not DAU.

## Working discipline in this repo

Doc 03 §盲区二 documents the project's own failure mode: *attention flows toward the part that can be built, not the part that is most uncertain.* Concretely — a dozen rounds of product refinement while the distribution problem ("冷启动的第一千人") went untouched, and a demo polished before a single real user was contacted.

This applies directly to how you work here. When asked about this project:

- Do not answer a strategy question by proposing features. If the next real blocker is a gate in `04`, say so.
- Doc `04` is deliberately detailed only up to the next gate — a 12-month plan was written once for the GEO direction and thrown away entirely. **Do not "help" by expanding later stages into detailed plans.**
- Gate criteria are pass/fail, not targets to tune toward. Doc 04 names the most likely error: adjusting the product when gate data lands near the line. "产品不是变量,需求才是。"
- Unresolved items are tracked honestly in `01` §5 (cold start, recording completeness, self-driven users being the pickiest segment, syllabus cold-start cost, and 待查 items including 生成式 AI 服务备案). Keep them listed as unresolved; don't let a plausible answer silently close one.

## Documentation conventions

- **Documents are written in Chinese.** Keep edits in Chinese and match the surrounding register: dense, declarative, no filler.
- Heavy use of comparison tables for anything with more than two options; bold for the single load-bearing sentence in a section; `>` blockquote at the top of each file stating the doc's purpose and its caveats.
- Research data carries an as-of date (`调研数据截至 2026 年 8 月,会过时`) and names its sources inline, including when sources contradict each other. Preserve both the numbers and the disagreement — doc 02 draws a methodological lesson from a 30× spread in market-size figures.
- Reversible decisions are logged with an explicit "何时改变" condition (`01` §7). When recording a new decision, include that column.
- Per global instruction, any flowchart added must use Mermaid.

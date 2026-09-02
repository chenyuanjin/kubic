#!/usr/bin/env python3
"""边界文案同源检查 —— 首次使用与权限申请 §1.3.4 那条「判据(可跑)」的实现。

真源:docs/product/specs/设置与原图.md §二「这个产品不做什么」的五句。
消费者:① S-HOME 边界卡片取前三句逐字;② 拍照同意点的承诺句 = 第三条逐字。
不一致就退出码 1 —— 边界有两种说法,以后每次「这算不算越界」都要先确认按哪一份说。
"""
import re, sys, pathlib

SPECS = pathlib.Path(__file__).resolve().parent.parent / "docs/product/specs"
norm = lambda s: s.replace("**", "").replace(" ", "").strip()

src = (SPECS / "设置与原图.md").read_text(encoding="utf-8")
first_run = (SPECS / "首次使用与权限申请.md").read_text(encoding="utf-8")

# 真源五句:§二 里的引用块条目
sec2 = src.split("\n## 二")[1].split("\n## 三")[0]
five = [norm(m) for m in re.findall(r"^> - (.+)$", sec2, re.M)]
assert len(five) == 5, f"§二 应为五句,实得 {len(five)}"

fails = []

# ① 边界卡片三句 == 前三句逐字
card_block = first_run.split("**卡片上屏原文(三句,一字不改):**")[1]
card = [norm(m) for m in re.findall(r"^> - (.+)$", card_block, re.M)][:3]
for i, (got, want) in enumerate(zip(card, five[:3]), 1):
    if got != want:
        fails.append(f"卡片第{i}句非逐字\n    卡片: {got}\n    真源: {want}")
if len(card) != 3:
    fails.append(f"卡片应为三句,实得 {len(card)}")

# ② 同意点承诺句 == 第三条逐字(附加句允许,承诺句不许改写)
promise = five[2]
consent_row = [l for l in src.splitlines() if "同意点(拍照前)" in l]
if not consent_row:
    fails.append("设置与原图 §6.4 找不到「同意点(拍照前)」那一行")
elif promise not in norm(consent_row[0]):
    fails.append(f"同意点承诺句非逐字\n    真源: {promise}")

# ③ 首次使用的相机自有说明必须消费同一句承诺
if promise not in norm(first_run):
    fails.append(f"首次使用·相机说明未逐字消费承诺句\n    真源: {promise}")

if fails:
    print("边界文案同源检查 FAILED:", file=sys.stderr)
    for f in fails:
        print("  ✗ " + f, file=sys.stderr)
    sys.exit(1)
print("边界文案同源检查 passed:卡片三句 + 同意点承诺句 + 首次使用相机说明,四处同源")

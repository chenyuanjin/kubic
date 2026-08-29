#!/usr/bin/env python3
"""壳里带中文的字符串字面量只允许出现在 src/strings.rs(docs/15 §六)。

为什么要这一条:
    「所有面向用户的字符串集中在一个文件」的价值,全部来自「扫描只有一处要扫」。
    只要别处能 eprintln! 一句中文,扫描就看不见它,而那句话照样会出现在用户眼前。
    集中不是整理癖,是让扫描的覆盖面等于文案的全集。

只看字符串字面量,不看注释 —— 注释里出现中文是这个仓库的常态。
所以这里老老实实按 Rust 的词法走一遍,而不是拿正则去猜:
    "http://127.0.0.1" 里就有一个 //,正则剥注释会把它剥断,
    剥断之后要么假绿要么假红,两种都比没有这条检查更糟。
"""

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1] / "src"
ALLOWED = ROOT / "strings.rs"


def literals(src: str):
    """产出 (行号, 字面量内容)。只产出字符串字面量,注释与字符字面量都跳过。"""
    i, line, n = 0, 1, len(src)
    while i < n:
        c = src[i]
        if c == "\n":
            line += 1
            i += 1
        elif src.startswith("//", i):
            while i < n and src[i] != "\n":
                i += 1
        elif src.startswith("/*", i):
            depth, i = 1, i + 2
            while i < n and depth:
                if src.startswith("/*", i):
                    depth, i = depth + 1, i + 2
                elif src.startswith("*/", i):
                    depth, i = depth - 1, i + 2
                else:
                    if src[i] == "\n":
                        line += 1
                    i += 1
        elif c == "r" and i + 1 < n and src[i + 1] in '#"':
            j = i + 1
            hashes = 0
            while j < n and src[j] == "#":
                hashes, j = hashes + 1, j + 1
            if j >= n or src[j] != '"':
                i += 1
                continue
            j += 1
            close = '"' + "#" * hashes
            end = src.find(close, j)
            end = n if end < 0 else end
            start_line = line
            line += src.count("\n", i, end)
            yield start_line, src[j:end]
            i = end + len(close)
        elif c == '"':
            j, buf = i + 1, []
            while j < n and src[j] != '"':
                if src[j] == "\\":
                    j += 1
                elif src[j] == "\n":
                    line += 1
                if j < n:
                    buf.append(src[j])
                j += 1
            yield line, "".join(buf)
            i = j + 1
        else:
            i += 1


def has_cjk(text: str) -> bool:
    return any("一" <= ch <= "鿿" for ch in text)


bad = []
for path in sorted(ROOT.rglob("*.rs")):
    if path == ALLOWED:
        continue
    for ln, text in literals(path.read_text(encoding="utf-8")):
        if has_cjk(text):
            bad.append((path.relative_to(ROOT.parent.parent), ln, text))

if bad:
    print("\n壳文案集中检查:不通过\n", file=sys.stderr)
    for rel, ln, text in bad:
        print(f"  ✗ {rel}:{ln}  {text!r}", file=sys.stderr)
    print(
        "\n  面向用户的字符串放 shell/src/strings.rs,"
        "别处只引用它。少了这一条,能力边界扫描就只扫到一半。\n",
        file=sys.stderr,
    )
    sys.exit(1)

print("壳文案集中检查:通过 —— 带中文的字符串字面量只在 src/strings.rs")

#!/usr/bin/env bash
# 图标 —— 从 web/public/favicon.svg 生成,不另画一个。
#
# 理由和「复用产物不复用源码」是同一条:壳里的东西和浏览器里的必须是同一个来源。
# 另画一个图标就是第二份视觉资产,而两份资产迟早会长得不一样。
#
# 产物落在 shell/icons/,不进仓库(见 shell/.gitignore)。
set -euo pipefail
cd "$(dirname "$0")/.."

SRC="../web/public/favicon.svg"
[ -f "$SRC" ] || { echo "找不到 $SRC" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# qlmanage 是 macOS 自带的,不额外装东西。它渲染出来的是透明底。
qlmanage -t -s 1024 -o "$WORK" "$SRC" >/dev/null 2>&1
RENDERED="$WORK/$(basename "$SRC").png"
[ -f "$RENDERED" ] || { echo "qlmanage 没渲染出 PNG" >&2; exit 1; }

# 透明底的图标在浅色 dock 上会糊掉,所以垫上产品自己的页面底色 --color-bg。
mkdir -p icons
python3 - "$RENDERED" icons/source.png <<'PY'
import sys
from PIL import Image

BG = (0x0e, 0x0f, 0x11, 255)  # web/src/index.css --color-bg 页面底

mark = Image.open(sys.argv[1]).convert("RGBA")
side = 1024
canvas = Image.new("RGBA", (side, side), BG)

# 留一圈边:图标在 dock / 启动器里是被裁圆角的,顶到边的图形会被切掉一截。
box = int(side * 0.62)
w, h = mark.size
scale = min(box / w, box / h)
mark = mark.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.LANCZOS)
canvas.alpha_composite(mark, ((side - mark.width) // 2, (side - mark.height) // 2))
canvas.save(sys.argv[2])
PY

cargo tauri icon icons/source.png -o icons
echo "图标已生成:shell/icons/"

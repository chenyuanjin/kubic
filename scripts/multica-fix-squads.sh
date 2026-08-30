#!/usr/bin/env bash
set -uo pipefail
export PATH="/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:$PATH"
WS=903c14c4-e5de-4e47-b3bc-3412818f4fa6
mc(){ multica --workspace-id $WS "$@"; }
ok(){ printf '  \033[32m✓ %s\033[0m\n' "$*" >&2; }
bad(){ printf '  \033[31m✗ %s\033[0m\n' "$*" >&2; }
DRY=${1:-}

mc agent list --output json 2>/dev/null > /tmp/_ag.json
aid(){ python3 -c "
import json,sys
d=json.load(open('/tmp/_ag.json'))
rows=d if isinstance(d,list) else next((v for v in d.values() if isinstance(v,list)),[])
print(next((a['id'] for a in rows if a.get('name')=='''$1'''),''))"; }

DEV=9981e5c2-9540-4103-b61c-7e93c3a1502c
REV=71b969c0-4adf-46a6-a8e5-81891f6c8c7f
TST=c3076b17-8ba6-41f5-ba82-cfc615c9b544
DSG=99aa58db-ce77-4aa3-b272-9170f2ba0861
TRK=d0bfd65f-4753-40f5-99d6-16f66ce80c5a

add(){ # squad agentName role
  local sq="$1" nm="$2" role="${3:-member}" id
  id=$(aid "$nm"); [[ -z "$id" ]] && { bad "$nm 未找到"; return; }
  [[ "$DRY" == "--dry-run" ]] && { ok "[预演] $nm → $role"; return; }
  if mc squad member add "$sq" --member-id "$id" --role "$role" >/dev/null 2>&1
  then ok "$nm ($role)"; else bad "$nm 加入失败(可能已在组内)"; fi
}
rm_(){ # squad agentName
  local sq="$1" nm="$2" id
  id=$(aid "$nm"); [[ -z "$id" ]] && return
  [[ "$DRY" == "--dry-run" ]] && { ok "[预演] 移出 $nm"; return; }
  if mc squad member remove "$sq" --member-id "$id" >/dev/null 2>&1
  then ok "移出 $nm"; else bad "移出 $nm 失败"; fi
}

printf '\n\033[1;36m▸ 开发组:清掉轨道成员与多余 leader,补进跨端前端\033[0m\n' >&2
rm_ $DEV "合规轨-执行"
rm_ $DEV "数据轨-开发"
rm_ $DEV "产品开发"        # 旧 leader 身份,稍后以 member 重新加入
add $DEV "产品开发" member
add $DEV "跨端前端" member

printf '\n\033[1;36m▸ 设计组:补技术经理与 UI设计\033[0m\n' >&2
add $DSG "技术经理" member
add $DSG "UI设计" member

printf '\n\033[1;36m▸ 轨道组:补数据轨\033[0m\n' >&2
add $TRK "数据轨-开发" member

printf '\n\033[1;36m▸ 项目经理:跨组,编入设计组便于派活\033[0m\n' >&2
add $DSG "项目经理" member

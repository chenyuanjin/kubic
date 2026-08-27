#!/usr/bin/env python3
"""Multica CLI 辅助:在任意嵌套 JSON 里解析 runtime / agent / squad / project。

用法:
  multica-lib.py resolve   <json文件> <runtime名>   → 打印 runtime UUID
  multica-lib.py id-of     <json文件> <对象名>      → 打印同名对象的 UUID
  multica-lib.py has       <json文件> <对象名>      → 存在则退出码 0
  multica-lib.py count     <json文件>               → 打印 UUID 去重后数量
  multica-lib.py names     <json文件>               → 打印所有 name 字段
"""
import sys, json, re

UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
NAMEKEYS = ("name", "type", "kind", "runtime_type", "agent", "agent_type",
            "cli", "display_name", "label", "provider", "runtime", "title")


def collect(obj, out):
    """收集 (uuid, 可搜索文本) 二元组。"""
    if isinstance(obj, dict):
        rid = obj.get("id") or obj.get("runtime_id") or obj.get("uuid")
        if rid and UUID.match(str(rid).lower()):
            blob = " ".join(str(obj.get(k, "")) for k in NAMEKEYS)
            out.append((str(rid), blob.strip()))
        for v in obj.values():
            collect(v, out)
    elif isinstance(obj, list):
        for v in obj:
            collect(v, out)


def collect_named(obj, out):
    """收集 (uuid, name/title 精确值) —— 用于同名对象查找,不做模糊。"""
    if isinstance(obj, dict):
        rid = obj.get("id") or obj.get("uuid")
        nm = obj.get("name") or obj.get("title")
        if rid and nm and UUID.match(str(rid).lower()):
            out.append((str(rid), str(nm)))
        for v in obj.values():
            collect_named(v, out)
    elif isinstance(obj, list):
        for v in obj:
            collect_named(v, out)


def load(path):
    try:
        with open(path) as f:
            return json.load(f)
    except Exception:
        return None


def cmd_resolve(path, want):
    data = load(path)
    if data is None:
        sys.stderr.write("    无法解析 %s\n" % path)
        return 1
    items = []
    collect(data, items)
    w = want.lower()
    for rid, blob in items:                       # 整词优先
        if re.search(r"\b%s\b" % re.escape(w), blob.lower()):
            print(rid)
            return 0
    for rid, blob in items:                       # 退而求次:子串
        if w in blob.lower():
            print(rid)
            return 0
    sys.stderr.write("    找不到 runtime %r。候选:\n" % want)
    for rid, blob in items:
        sys.stderr.write("      %s  %s\n" % (rid, blob[:70]))
    return 1


def cmd_id_of(path, name):
    data = load(path)
    if data is None:
        return 1
    items = []
    collect_named(data, items)
    for rid, nm in items:
        if nm == name:
            print(rid)
            return 0
    return 1


def cmd_has(path, name):
    data = load(path)
    if data is None:
        return 1
    items = []
    collect_named(data, items)
    return 0 if any(nm == name for _, nm in items) else 1


def cmd_count(path):
    data = load(path)
    if data is None:
        print(0)
        return 0
    items = []
    collect(data, items)
    print(len({r for r, _ in items}))
    return 0


def cmd_names(path):
    data = load(path)
    if data is None:
        return 1
    items = []
    collect_named(data, items)
    for rid, nm in items:
        print("%s\t%s" % (rid, nm))
    return 0


if __name__ == "__main__":
    c = sys.argv[1] if len(sys.argv) > 1 else ""
    if   c == "resolve":   sys.exit(cmd_resolve(sys.argv[2], sys.argv[3]))
    elif c == "id-of":     sys.exit(cmd_id_of(sys.argv[2], sys.argv[3]))
    elif c == "has":       sys.exit(cmd_has(sys.argv[2], sys.argv[3]))
    elif c == "has-agent": sys.exit(cmd_has(sys.argv[2], sys.argv[3]))   # 兼容旧名
    elif c == "count":     sys.exit(cmd_count(sys.argv[2]))
    elif c == "names":     sys.exit(cmd_names(sys.argv[2]))
    sys.exit(2)

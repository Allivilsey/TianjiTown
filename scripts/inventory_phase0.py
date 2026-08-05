#!/usr/bin/env python3
"""只读扫描旧小镇资产；不会连接数据库，也不会修改源插件目录。"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
from pathlib import Path


GROUP_PATTERNS = (
    re.compile(r"%luckperms_in_group_([A-Za-z0-9_-]+)%"),
    re.compile(r"\blp\s+group\s+([A-Za-z0-9_-]+)\b", re.I),
    re.compile(r"\bparent\s+(?:set|add)\s+([A-Za-z0-9_-]+)\b", re.I),
)

KNOWN_NAMES = {
    "aucuba": "Aucuba", "cxz": "春雪镇", "emskz": "鄂木斯克镇", "hfld": "和风流地",
    "hsz": "黑水镇", "jmz": "基米镇", "krs": "烤肉社", "mys": "米游社", "qxh": "卿小黑",
    "sc": "拾城", "sdb": "斯德堡", "sjx": "四季乡", "sxwqz": "水箱温泉镇", "tds": "塔迪斯镇",
    "tkg": "天空港", "tlz": "塔罗镇", "tsz": "趟水镇", "xlc": "香来村", "xyzm": "下一终末",
    "yhz": "圆蛤镇", "yyx": "云烟乡", "yz": "夜镇", "zlc": "杂粮村", "zmz": "筑梦镇",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def scan_deluxemenus(root: Path) -> tuple[dict[str, list[str]], dict[str, str], list[str]]:
    groups: dict[str, set[str]] = {}
    names: dict[str, str] = {}
    town_files: list[str] = []
    menu_root = root / "DeluxeMenus" / "gui_menus"
    for path in sorted(menu_root.rglob("*.yml")):
        text = path.read_text(encoding="utf-8", errors="replace")
        if not re.search(r"town|小镇|luckperms_in_group_|parent set |lp group ", text, re.I):
            continue
        relative = str(path.relative_to(root))
        town_files.append(relative)
        lines = text.splitlines()
        for index, line in enumerate(lines):
            matches: set[str] = set()
            for pattern in GROUP_PATTERNS:
                matches.update(pattern.findall(line))
            for group in matches:
                if group == "default":
                    continue
                groups.setdefault(group.lower(), set()).add(f"{relative}:{index + 1}")
                nearby = "\n".join(lines[max(0, index - 14):index + 1])
                labels = re.findall(r"#([^#\n]{1,30})#|display_name:\s*['\"]?[^\n]*?为([^\n&'\"]{1,20})", nearby)
                if labels:
                    candidate = next((left or right for left, right in reversed(labels) if left or right), "")
                    candidate = re.sub(r"飞行|购买.*$", "", candidate).strip()
                    if candidate and "小镇" not in candidate[:2]:
                        names.setdefault(group.lower(), candidate)
    names.update({group: name for group, name in KNOWN_NAMES.items() if group in groups})
    return ({key: sorted(value) for key, value in sorted(groups.items())}, names, town_files)


def scan_residence(root: Path) -> dict[str, list[str]]:
    result: dict[str, list[str]] = {}
    world_root = root / "Residence" / "Save" / "Worlds"
    for path in sorted(world_root.glob("res_*.yml")):
        names: list[str] = []
        in_residences = False
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            if line == "Residences:":
                in_residences = True
                continue
            if in_residences:
                match = re.match(r"^  ['\"]?([^:'\"]+)['\"]?:\s*$", line)
                if match:
                    names.append(match.group(1))
        result[path.stem.removeprefix("res_")] = names
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("plugins_root", type=Path)
    parser.add_argument("--output", type=Path, default=Path("reports/phase-0-inventory.json"))
    parser.add_argument("--migration", type=Path, default=Path("config/phase0-migration.yml"))
    args = parser.parse_args()
    root = args.plugins_root.resolve()
    required = ["Residence", "LuckPerms", "DeluxeMenus", "PlaceholderAPI", "ZNPCsPlus"]
    missing = [name for name in required if not (root / name).is_dir()]
    if missing:
        raise SystemExit("缺少插件目录: " + ", ".join(missing))

    groups, inferred_names, menus = scan_deluxemenus(root)
    residences = scan_residence(root)
    papi_scripts = sorted(path.name for path in (root / "PlaceholderAPI" / "javascripts").glob("*Town*.js"))
    papi_scripts += sorted(path.name for path in (root / "PlaceholderAPI" / "javascripts").glob("town*.js"))
    npc_path = root / "ZNPCsPlus" / "data" / "town.yml"
    npc_text = npc_path.read_text(encoding="utf-8", errors="replace")
    npc = {
        "exists": npc_path.exists(),
        "enabled": bool(re.search(r"^enabled:\s*true", npc_text, re.M)),
        "world": (re.search(r"^world:\s*(\S+)", npc_text, re.M) or [None, None])[1],
        "location": {
            key: float(match.group(1)) if (match := re.search(rf"^\s+{key}:\s*(-?[0-9.]+)", npc_text, re.M)) else None
            for key in ("x", "y", "z")
        },
        "sha256": sha256(npc_path),
    }
    luckperms_export = root / "LuckPerms" / "save2.json.gz"
    residence_candidates = {
        group: [f"{world}:{name}" for world, names in residences.items() for name in names
                if name.lower() == group.lower()]
        for group in groups
    }
    residence_candidates = {key: value for key, value in residence_candidates.items() if value}
    inventory = {
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "source": str(root),
        "read_only": True,
        "limitations": [
            "插件目录不含生产 JAR 与 latest.log，无法确认当前运行版本",
            "LuckPerms 使用 MySQL；本地 save2.json.gz 生成于 2020 年，不能作为当前成员清单",
            "Residence 仅列出现存名称，不自动判断哪些属于小镇",
        ],
        "deluxemenus": {"town_files": menus, "groups": groups, "inferred_names": inferred_names},
        "luckperms": {
            "storage": "MySQL",
            "local_export": str(luckperms_export.relative_to(root)) if luckperms_export.exists() else None,
            "local_export_current": False,
        },
        "residence": {
            "worlds": residences,
            "total_top_level": sum(map(len, residences.values())),
            "exact_group_name_candidates": residence_candidates,
        },
        "znpcsplus": {"town": npc},
        "placeholderapi": {"town_scripts": sorted(set(papi_scripts))},
        "daily_tax_economy": {
            "taxer_vault_accounts": ["tianjitax"],
            "semantic_status": "needs-plugin-source-or-controlled-test",
        },
        "quickshop": {"storage": "H2", "base_tax_rate": "0.05", "tax_account": "tax", "apply_to": "player"},
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    args.migration.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "schema-version: 1",
        "mode: dry-run",
        "# 此文件由只读扫描生成；town-id、mayor 和 Residence 映射必须人工复核。",
        "legacy-groups:",
    ]
    for group in groups:
        display = inferred_names.get(group, "")
        lines.extend([
            f"  {group}:",
            f"    display-name: {json.dumps(display, ensure_ascii=False)}",
            "    town-id: null",
            "    mayor-uuid: null",
            "    residence-names: []",
            "    block-new-membership: true",
            "    block-new-town-name: true",
        ])
    args.migration.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"已写入 {args.output} 与 {args.migration}；未修改 {root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

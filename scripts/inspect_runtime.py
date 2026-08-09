#!/usr/bin/env python3
"""从实际 JAR 和启动日志生成版本证据；只读。"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import zipfile
from pathlib import Path


TARGETS = {"Residence", "Vault", "XConomy", "QuickShop-Hikari"}


def checksum(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def scalar(text: str, key: str) -> str | None:
    match = re.search(rf"^{re.escape(key)}:\s*['\"]?([^'\"\r\n]+)", text, re.M)
    return match.group(1).strip() if match else None


def inspect_jar(path: Path) -> dict | None:
    try:
        with zipfile.ZipFile(path) as archive:
            descriptor_name = next((name for name in ("plugin.yml", "paper-plugin.yml")
                                    if name in archive.namelist()), None)
            if not descriptor_name:
                return None
            descriptor = archive.read(descriptor_name).decode("utf-8", errors="replace")
    except (OSError, zipfile.BadZipFile, KeyError):
        return None
    name = scalar(descriptor, "name")
    if name not in TARGETS:
        return None
    return {
        "name": name,
        "version": scalar(descriptor, "version"),
        "file": path.name,
        "size": path.stat().st_size,
        "sha256": checksum(path),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("server_root", type=Path)
    parser.add_argument("--output", type=Path, default=Path("reports/runtime-report.json"))
    args = parser.parse_args()
    root = args.server_root.resolve()
    plugins = root / "plugins"
    if not plugins.is_dir():
        raise SystemExit(f"缺少 plugins 目录: {plugins}")
    jars = [result for path in sorted(plugins.glob("*.jar")) if (result := inspect_jar(path))]
    log = root / "logs" / "latest.log"
    evidence: dict = {
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "server_root": str(root),
        "read_only": True,
        "plugins": sorted(jars, key=lambda item: item["name"]),
        "missing_plugins": sorted(TARGETS - {item["name"] for item in jars}),
        "latest_log": None,
    }
    if log.is_file():
        text = log.read_text(encoding="utf-8", errors="replace")
        patterns = {
            "java": r"Running Java ([^\s]+)",
            "minecraft": r"Starting minecraft server version ([^\s]+)",
            "paper_or_leaf": r"This server is running (?:Paper|Leaf) version ([^\r\n]+)",
        }
        evidence["latest_log"] = {
            "file": str(log),
            "sha256": checksum(log),
            "facts": {key: match.group(1).strip() if (match := re.search(pattern, text, re.I)) else None
                      for key, pattern in patterns.items()},
        }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"已写入 {args.output}；未修改 {root}")
    return 0 if not evidence["missing_plugins"] and evidence["latest_log"] else 2


if __name__ == "__main__":
    raise SystemExit(main())

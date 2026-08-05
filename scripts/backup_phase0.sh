#!/usr/bin/env bash
set -euo pipefail
umask 077

if [[ $# -ne 2 ]]; then
  echo "用法: $0 <plugins目录> <备份输出目录>" >&2
  exit 64
fi

plugins_root="${1%/}"
backup_root="${2%/}"
if [[ ! -d "$plugins_root/Residence" || ! -d "$plugins_root/LuckPerms" ]]; then
  echo "目标不像有效的 plugins 目录: $plugins_root" >&2
  exit 66
fi
if [[ "$backup_root" == "/" || -z "$backup_root" ]]; then
  echo "拒绝使用危险的备份目录" >&2
  exit 64
fi

mkdir -p "$backup_root"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
archive="$backup_root/tianjitown-phase0-$timestamp.tar.gz"

items=(
  Residence/config.yml Residence/flags.yml Residence/groups.yml Residence/uuids.yml Residence/Save Residence/Backup
  LuckPerms/config.yml LuckPerms/contexts.json LuckPerms/save2.json.gz
  DeluxeMenus/config.yml DeluxeMenus/gui_menus DeluxeMenus/修改说明.txt
  PlaceholderAPI/javascript_placeholders.yml PlaceholderAPI/javascripts
  ZNPCsPlus/data/town.yml
  QuickShop-Hikari/config.yml QuickShop-Hikari/shops.mv.db QuickShop-Hikari/qs.log
  DailyTaxEconomy/config.yml
  XConomy/config.yml XConomy/database.yml XConomy/playerdata
)

existing=()
for item in "${items[@]}"; do
  [[ -e "$plugins_root/$item" ]] && existing+=("$item")
done

tar -czf "$archive" -C "$plugins_root" "${existing[@]}"
chmod 600 "$archive"
if command -v shasum >/dev/null 2>&1; then
  shasum -a 256 "$archive" > "$archive.sha256"
else
  sha256sum "$archive" > "$archive.sha256"
fi
chmod 600 "$archive.sha256"
echo "$archive"


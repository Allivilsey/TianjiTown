#!/usr/bin/env bash
set -euo pipefail
umask 077

if [[ $# -ne 2 ]]; then
  echo "用法: $0 <SQLite数据库文件> <备份输出文件>" >&2
  exit 64
fi

database_file="$1"
output_file="$2"
if [[ ! -f "$database_file" ]]; then
  echo "找不到 SQLite 数据库: $database_file" >&2
  exit 66
fi
if [[ -e "$output_file" ]]; then
  echo "拒绝覆盖已有备份: $output_file" >&2
  exit 73
fi
if ! command -v sqlite3 >/dev/null 2>&1; then
  echo "缺少 sqlite3 命令" >&2
  exit 69
fi

mkdir -p "$(dirname "$output_file")"
temporary_file="${output_file}.tmp.$$"
trap 'rm -f -- "$temporary_file"' EXIT
escaped_file="${temporary_file//\'/\'\'}"
sqlite3 "$database_file" "PRAGMA busy_timeout = 5000; VACUUM INTO '$escaped_file';"
chmod 600 "$temporary_file"
mv "$temporary_file" "$output_file"
trap - EXIT

if command -v shasum >/dev/null 2>&1; then
  shasum -a 256 "$output_file" > "$output_file.sha256"
else
  sha256sum "$output_file" > "$output_file.sha256"
fi
chmod 600 "$output_file.sha256"
echo "$output_file"

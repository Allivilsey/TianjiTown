#!/usr/bin/env bash
set -euo pipefail
umask 077

if [[ $# -ne 3 ]]; then
  echo "用法: $0 <mysql-client.cnf> <数据库名> <输出.sql.gz>" >&2
  exit 64
fi

client_config="$1"
database_name="$2"
output="$3"
if [[ ! -f "$client_config" || "$database_name" == *[^A-Za-z0-9_]* || "$output" == "/" ]]; then
  echo "参数无效" >&2
  exit 64
fi

mysqldump --defaults-extra-file="$client_config" \
  --single-transaction --quick --routines --triggers --events \
  --set-gtid-purged=OFF --databases "$database_name" | gzip -9 > "$output"
chmod 600 "$output"
gzip -t "$output"
echo "备份已创建并通过 gzip 校验: $output"


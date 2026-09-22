# 本地生成 XConomy 模拟数据

1. 将 TianjiTown 配置文件夹放在 `scripts/TianjiTown/`，包含 `config.yml`、数据库及配套 WAL 文件（若存在）。`database.file` 使用文件夹内的相对路径。
2. 运行 `python scripts/create_xconomy_sqlite.py`，无需参数或 JAR，也可以从其他工作目录运行。
3. 生成结果位于 `scripts/XConomy/`，放入测试服的 `plugins/` 即可，由 XConomy 自动生成其他默认配置。

脚本只读 TianjiTown 数据，用小镇资金、申请托管金额及待结算金额推算测试余额；这不是原 XConomy 的真实余额。账户读取 `economy.settlement-account`，缺省为旧账户 `tax`，UUID 使用离线算法。不模拟玩家余额。

重复生成前移走原 `scripts/XConomy/playerdata/data.db`。脚本和数据只在本机使用。

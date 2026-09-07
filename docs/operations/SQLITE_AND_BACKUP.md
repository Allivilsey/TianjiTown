# SQLite 与备份/恢复

## 运行方式

默认路径为 `plugins/TianjiTown/tianjitown.db`，`database.file` 可设置其他相对插件目录路径或绝对路径，修改后需重启。运行时使用单连接、WAL、外键约束、默认 5 秒忙等待及毫秒精度 UTC 时间戳。

当前仅维护 `V1_0__initial_schema.sql`。启动执行 Flyway `migrate` 和 `validate`，用于首次建表与完整性校验，禁止 `clean`；不新增历史迁移、数据回填、旧开发数据库兼容或 MySQL 自动导入。已有数据库不会因结构变化自动删除、重建或改写。

## 备份

维护模式只暂停玩家入口，税收和后台任务仍会运行。需要一致性备份时停止 Paper，在同一时间点保存 TianjiTown 数据、配置/JAR、Residence、QuickShop-Hikari 和 XConomy 数据，并记录 Vault 清算账户精确余额。

仓库提供 SQLite 独立快照脚本，需要 Bash、sqlite3 和 shasum 或 sha256sum：

```bash
bash scripts/backup_sqlite.sh \
  /path/to/plugins/TianjiTown/tianjitown.db \
  backups/tianjitown.db
```

脚本使用 SQLite `VACUUM INTO`，拒绝覆盖已有输出。Windows 可在已配置上述命令的 Bash 环境执行。快照不需要再附带来源数据库的 WAL/SHM；不要在数据库仍运行时仅复制主文件，也不要删除运行中的 WAL/SHM。

## 隔离恢复

1. 保留当前环境副本，停止目标测试服。
2. 使用独立恢复目录，放入快照数据库及同一时间点的依赖数据、配置和匹配 JAR；将 `database.file` 指向恢复副本。确保目标目录不存在另一份数据库遗留的同名 WAL/SHM，优先使用全新目录。
3. 启动同构建测试服，执行 `/tianjitown status`，确认 Flyway 与启动诊断通过；通过 SQLite 工具检查 `PRAGMA integrity_check` 和 `PRAGMA foreign_key_check`。
4. 核对申请、成员、访客、公共账本、投票、Buff、领地和服务台；执行 `land reconcile all`、`diagnose 7`。
5. 验证通过后，将备份文件、JAR 哈希、时间、清算余额和结果一起登记为可恢复备份。

## 校验失败与回退

checksum 或结构不匹配时保留原文件，在副本中确认所用初始脚本与构建，不删除 Flyway history，不使用 `repair` 掩盖结构差异。需要验证当前初始结构时可选择独立的新数据库路径，不覆盖原文件；这不会自动带入旧数据。

回退前停止写入并保存当前完整副本。在隔离环境确认备份中的 JAR、配置、SQLite、Residence 与外部经济数据配套后，再恢复整套数据。只替换 JAR 不保证能读取当前数据库，也不能恢复外部资金状态；备份之后发生的真实交易须另行核对。

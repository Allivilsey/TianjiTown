# SQLite 与备份/恢复

升级前等待交易保存后正常停服备份；共享玩家经济数据库时暂停其他服务器的经济写入以取得一致备份。升级后核对小镇与玩家余额再恢复活动。

## 运行方式

默认路径为 `plugins/TianjiTown/tianjitown.db`，`database.file` 可设置其他相对插件目录路径或绝对路径，修改后需重启。运行时使用单连接、WAL、外键约束、默认 5 秒忙等待及毫秒精度 UTC 时间戳。

正式版 `1.0.0` 使用数据库 schema `1.1`。启动执行 Flyway `migrate` 和 `validate`，用于首次建表、增量升级与完整性校验，禁止 `clean`。已发布的 `V1_0__initial_schema.sql` 保持不变，`V1_1__durable_financial_operations.sql` 新增 `application_fee_operations`、`income_tax_collections` 和税款恢复索引，并停用清算账户锁，不改变余额和流水。不删除或重建现有业务表。不提供 MySQL 自动导入。

## 从已提交版本升级到 1.0.0

支持从提交 `027107a` 的 `V1_0`（schema `1.0`）创建的生产数据库升级。Flyway 中该迁移的 checksum 为 `-142058434`；已手工修改结构、迁移历史或使用其他开发脚本建库的数据库，必须先在副本中单独核对，不能直接改 checksum 或运行 `repair`。

1. 停服，在同一时间点备份下述全部数据，保留旧 JAR 与配置。先在隔离副本验证完整升级流程。
2. 保留原配置和旧 JAR，替换插件；旧清算账户配置及身份绑定文件用于一次性 tax 账户清理，无需先改名。清理条件见经济部署说明，升级前先解除其他插件对旧账户的引用。
3. 启动校验既有 `V1_0` 并执行 `V1_1`；新库依次执行两份脚本。申请费、历史税款和未决操作保留，不重放历史付款，不导入旧 tax 余额。
4. 执行 `status`、`diagnose`，确认 schema `1.1`，核对各镇余额、流水、申请费、待核实资金及三种税源。
5. 再次重启验证不重复迁移，并保存升级后备份。详细验收见 [经济部署说明](../deployment/ECONOMY_AND_EXPANSION.md)。

回退须恢复同一时间点的旧 JAR、配置、小镇数据库和玩家经济等依赖数据；不能只替换 JAR，也不能把旧版本直接指向新 schema。

## 备份

维护模式只暂停玩家入口，税收和后台任务仍会运行。需要一致性备份时停止 Paper，在同一时间点保存 TianjiTown 数据、配置/JAR、Residence、QuickShop-Hikari 和 XConomy 数据，并记录小镇余额及待处理付款。

旧版本的 `settlement-account-migration.properties` 用于核对待删除旧账户的 UUID，须随备份保留。升级生成的 `legacy-tax-account-cleanup.properties` 也须保留，避免重复清理；恢复已删除账户须使用 XConomy 的完整备份，不能只恢复此记录文件。

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
4. 核对申请、成员、访客、公共账本、投票、Buff、领地和服务台；执行 `land reconcile all`、`diagnose`。
5. 验证通过后，将备份文件、JAR 哈希、时间、小镇余额及未决付款和结果一起登记为可恢复备份。

## 校验失败与回退

checksum 或结构不匹配时保留原文件，在副本中确认所用初始脚本与构建，不删除 Flyway history，不使用 `repair` 掩盖结构差异。需要验证当前初始结构时可选择独立的新数据库路径，不覆盖原文件；这不会自动带入旧数据。

回退前停止写入并保存当前完整副本。在隔离环境确认备份中的 JAR、配置、SQLite、Residence 与外部经济数据配套后，再恢复整套数据。只替换 JAR 不保证能读取当前数据库，也不能恢复外部资金状态；备份之后发生的真实交易须另行核对。

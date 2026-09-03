# SQLite 与备份/恢复方案

## 运行方式

TianjiTown 默认使用 `plugins/TianjiTown/tianjitown.db`，也可在 `config.yml` 的 `database.file` 中填写其他相对或绝对路径。插件启动时执行 Flyway `migrate` 和 `validate`，禁止 `clean`。

运行时启用以下 SQLite 特性：

- 单连接串行写入，与单 Paper 进程部署模型一致；
- WAL 日志模式；
- 外键约束；
- 默认 5 秒忙等待；
- 毫秒精度的 UTC 时间戳。

## 备份

上线前在同一停服时间点备份 Residence、QuickShop-Hikari、XConomy/Vault 和 TianjiTown。依赖插件数据需由运维工具或人工归档，TianjiTown 不提供在线配置快照。

SQLite 数据库备份需由停服运维流程单独执行：

```bash
bash scripts/backup_sqlite.sh \
  /path/to/plugins/TianjiTown/tianjitown.db \
  backups/tianjitown.db
```

脚本使用 SQLite `VACUUM INTO` 生成独立快照，不会复制不完整的 `-wal`/`-shm` 组合，也不会覆盖已有输出文件。

## 恢复演练

1. 停止隔离测试服务器。
2. 将备份数据库复制到 `database.file` 指定位置，不要同时复制来源环境的 `-wal` 或 `-shm` 文件。
3. 启动同版本预发服，执行 `/townadmin status` 和 `PRAGMA integrity_check`。
4. 验证申请、小镇、成员、领地投影与审计记录后，再将该备份标记为可恢复。

生产回滚默认只回退 JAR 和配置，不自动删除 SQLite 表或 Residence 领地。SQLite 版不会自动导入旧 MySQL 数据，转换必须在隔离环境另行验证。

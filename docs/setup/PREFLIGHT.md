# 安装门禁与预发验证

## 当前结论

安装门禁包括四模块 Maven 工程、可重现单 JAR、单元测试、依赖/Vault Economy 可用性检查，以及异步 SQLite/Flyway 自检。早期 YAML 镜像已移除，不参与启动或运行。

插件不再匹配固定的 Minecraft、Java 或依赖版本。启动时仅确认 Residence、Vault、XConomy 和 QuickShop-Hikari 已安装并启用，Vault 已注册可用的 `Economy` provider，SQLite 可读写且 Flyway 迁移/校验成功。任一必要检查失败时状态为 `LOCKED`，不开放业务写入。

本项目按新周目空数据启动设计。首次安装使用专用空 SQLite 文件，所有小镇、成员、名称和领地关系由 TianjiTown 重新建立。如需承接旧 MySQL 数据，必须在隔离环境单独转换，不得复制 MySQL Flyway history。

## 上线前闭环

- 执行 `/townadmin status`，保留当前运行环境与门禁结果。
- 首次启动后确认业务表为空，安装门禁记录为 1 行。
- 在隔离环境通过正常建镇与删除流程验证 Residence 创建、成员权限、边界读取和清理；不在管理员命令中暴露独立测试入口。
- 使用 [`SQLITE_AND_BACKUP.md`](../operations/SQLITE_AND_BACKUP.md) 中的方法完成一次 SQLite 备份，并按停服流程完成隔离恢复。
- 可用下列命令生成 JAR 哈希和启动日志事实报告；该报告只读，不参与启动判定：

```bash
scripts/inspect_runtime.py /path/to/server --output reports/runtime-report.json
```

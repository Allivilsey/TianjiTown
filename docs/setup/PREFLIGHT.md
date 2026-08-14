# 安装门禁与预发验证

## 当前结论

安装门禁包括四模块 Maven 工程、可重现单 JAR、单元测试、依赖/Vault Economy 可用性检查、异步 SQLite/Flyway 自检，以及带预发双重开关的 Residence API 冒烟测试。早期 YAML 镜像已移除，不参与启动或运行。

插件不再匹配固定的 Minecraft、Java 或依赖版本。启动时仅确认 Residence、Vault、XConomy 和 QuickShop-Hikari 已安装并启用，Vault 已注册可用的 `Economy` provider，SQLite 可读写且 Flyway 迁移/校验成功。任一必要检查失败时状态为 `LOCKED`，不开放业务写入。

本项目按新周目空数据启动设计。首次安装使用专用空 SQLite 文件，所有小镇、成员、名称和领地关系由 TianjiTown 重新建立。如需承接旧 MySQL 数据，必须在隔离环境单独转换，不得复制 MySQL Flyway history。

## Residence 预发冒烟

先在插件配置中设置：

```yaml
phase0:
  allow-residence-smoke: true
  residence-smoke-world: '专用预发世界名'
```

确认目标区块为空后执行：

```text
/townadmin phase0 residence-smoke <world> <chunkX> <chunkZ> <memberUuid>
```

命令通过前置检查后会在聊天栏显示确认和取消按钮，确认仅限发起者使用并在 60 秒后失效。点击确认后，测试依次验证无碰撞、创建、边界读取、成员 `build` 权限、删除、重建、碰撞命中和最终清理。任何异常都会在 `finally` 中尝试清理 `tt_phase0_*` 测试领地。生产服必须保持 `allow-residence-smoke: false`。

## 上线前闭环

- 执行 `/townadmin phase0 status` 和 `/townadmin status`，保留当前运行环境与门禁结果。
- 首次启动后确认业务表为空，安装门禁记录为 1 行，Residence 冒烟领地已完整清理。
- 使用 [`SQLITE_AND_BACKUP.md`](../operations/SQLITE_AND_BACKUP.md) 中的方法完成一次在线备份和隔离恢复。
- 可用下列命令生成 JAR 哈希和启动日志事实报告；该报告只读，不参与启动判定：

```bash
scripts/inspect_runtime.py /path/to/server --output reports/runtime-report.json
```

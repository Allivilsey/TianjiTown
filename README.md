# TianjiTown

面向单个 Paper 服务器的小镇插件，当前开发版本统一为 `1.0.0-SNAPSHOT`。玩家通过讲台服务台、小镇手册和 Paper Dialog 完成建镇、入镇、治理、捐款、领地激活和公共 Buff 购买；管理员通过 `/tianjitown` 审核、代办和排障。

## 从哪里开始

| 你要做的事 | 文档 |
|---|---|
| 安装插件、排查启动锁定 | [安装与预发检查](docs/setup/PREFLIGHT.md) |
| 查命令用途、参数、权限、操作后果 | [管理员命令手册](docs/ADMIN_COMMANDS.md) |
| 调整配置、判断是否需要重启 | [配置说明](docs/setup/CONFIGURATION.md) |
| 教玩家建镇、入镇、管理公共资产 | [玩家指南](docs/PLAYER_GUIDE.md) |
| 查详细玩法限制与默认数值 | [完整功能说明](docs/FUNCTIONS_AND_GAMEPLAY.md) |
| 做日常检查、备份或故障恢复 | [运维手册](docs/operations/OPERATIONS.md) |

全部文档见 [文档导航](docs/README.md)，验证流程和历史报告见 [测试入口](test/README.md)。

## 构建与安装

使用 Maven 3.9+、JDK 25+：

```bash
mvn -B clean verify
```

安装包为 `tianjitown-paper/target/TianjiTown-1.0.0-SNAPSHOT.jar`。当前声明的 Paper API 版本为 `26.2`；服务端必须能加载该 API 并支持 Paper Dialog，具体服务器组合需在预发环境验证。

1. 安装并启用 Residence、Vault、XConomy、WorldBorder、QuickShop-Hikari、Jobs、GlobalMarketPlus，确认 Vault 提供可用 Economy 服务。QuickShop 税务适配要求至少 `6.3.0.0` 并通过 API 能力检查。HuskSync 为可选集成。
2. 通过 WorldBorder `/wb` 为开放选址的世界配置边界。初始 5×5 区块和缓冲范围必须完整位于边界内。
3. 将构建 JAR 放入 `plugins` 并启动。首次运行创建 `plugins/TianjiTown/config.yml`、`messages.yml` 和默认 SQLite 文件 `tianjitown.db`。
4. 执行 `/tianjitown status`，待初始化和统一诊断通过、状态为 `READY` 后，再由游戏内管理员看向讲台执行 `/tianjitown station create`。
5. 通过服务台领取手册，在隔离区域完成一次建镇及经济流程验收。

Paper API、Residence 和 Vault API 不打入安装包；项目模块和重定位后的 Lamp 命令库内嵌。HikariCP、Flyway、SQLite JDBC 由 Paper 按 `plugin.yml` 的 `libraries` 下载，首次启动需要可访问依赖仓库；离线部署应预先准备服务端 `libraries` 缓存。

## 数据与开发约定

SQLite 文件可由 `database.file` 指定，支持绝对路径和相对插件目录的路径。使用单连接、WAL、外键约束，默认连接等待和忙等待均为 5 秒。数据库路径和超时修改后需要重启。

配置不设版本号。数据库结构只维护 `V1_0__initial_schema.sql`，Flyway 用于首次建表和完整性校验，不提供旧开发数据库升级、回填或 MySQL 自动导入。已有文件不会因结构调整自动删除或重建；校验失败应保留数据并在副本中定位，恢复步骤见 [SQLite 备份手册](docs/operations/SQLITE_AND_BACKUP.md)。

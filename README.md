# TianjiTown

本项目采用 [MIT 许可证](LICENSE)。

面向单个 Paper 服务器的小镇插件，当前正式版本为 `1.0.0`。玩家通过讲台服务台、小镇手册和 Paper Dialog 完成建镇、入镇、治理、捐款、领地激活和公共 Buff 购买；管理员通过 `/tianjitown` 审核、代办和排障。

## 从哪里开始

| 你要做的事 | 文档 |
|---|---|
| 安装插件、排查启动锁定 | [安装与预发检查](docs/setup/PREFLIGHT.md) |
| 查命令用途、参数、权限、操作后果 | [管理员命令手册](docs/ADMIN_COMMANDS.md) |
| 调整配置、判断是否需要重启 | [配置说明](docs/setup/CONFIGURATION.md) |
| 教玩家建镇、入镇、管理公共资产 | [玩家指南](docs/PLAYER_GUIDE.md) |
| 查详细玩法限制与默认数值 | [完整功能说明](docs/FUNCTIONS_AND_GAMEPLAY.md) |
| 做日常检查、备份或故障恢复 | [运维手册](docs/operations/OPERATIONS.md) |

全部文档见 [文档导航](docs/README.md)，开放前验证流程见 [安装与预发检查](docs/setup/PREFLIGHT.md)。

## 构建与安装

升级前正常停服并备份 TianjiTown 与依赖数据。公共资金现由小镇数据库独立管理，玩家钱包继续通过 Vault 接入；不再创建或迁移 tax 账户，也不导入旧账户余额。旧配置兼容与 schema 1.1 升级见 [经济部署说明](docs/deployment/ECONOMY_AND_EXPANSION.md)。

GitHub Actions 仅在分支 push 涉及根目录 `pom.xml` 时检查项目版本号；与本次 push 前相比，只有项目 `<version>` 变化才执行构建、测试、可复现性检查并上传 JAR。普通代码提交、PR 事件和新建分支不会构建；只修改依赖版本也不会构建。升级项目版本时应同步更新各子模块的父项目版本。构建产物使用仓库默认保留期限，不再单独设置 7 天；这不表示永久保存。

使用 Maven 3.9+、JDK 25+：

```bash
mvn -B clean verify
```

安装包为 `tianjitown-paper/target/TianjiTown-1.0.0.jar`。当前声明的 Paper API 版本为 `26.2`；服务端必须能加载该 API 并支持 Paper Dialog，具体服务器组合需在预发环境验证。

1. 安装并启用 Residence、Vault、WorldBorder、QuickShop-Hikari、Jobs、GlobalMarketPlus，安装 XConomy 或其他兼容玩家经济插件，确认 Vault 提供可用 Economy 服务。QuickShop 税务适配要求至少 `6.3.0.0` 并通过 API 能力检查。HuskSync 为可选集成。
2. 通过 WorldBorder `/wb` 为开放选址的世界配置边界。选址检查整镇 5×5 单元网格，即 25×25 区块（625 区块）及缓冲范围，必须完整位于边界内；中心 5×5 区块是建镇后自动激活的初始单元。
3. 将构建 JAR 放入 `plugins` 并启动。首次运行创建 `plugins/TianjiTown/config.yml`、`messages.yml` 和默认 SQLite 文件 `tianjitown.db`。
4. 执行 `/tianjitown status`，待初始化完成、状态为 `READY` 后，核对诊断告警，再由游戏内管理员看向讲台执行 `/tianjitown station create`。待恢复业务不会阻止恢复入口启动；数据库完整性和必要依赖失败仍会阻止启动。
5. 通过服务台领取手册，在隔离区域完成一次建镇及经济流程验收。

Paper API、Residence 和 Vault API 不打入安装包；项目模块和重定位后的 Lamp 命令库内嵌。HikariCP、Flyway、SQLite JDBC 由 Paper 按 `plugin.yml` 的 `libraries` 下载，首次启动需要可访问依赖仓库；离线部署应预先准备服务端 `libraries` 缓存。

支持通过 `/plugman reload TianjiTown` 热重载插件，或 `/plugman restart TianjiTown` 停用后重新启用。每次启用都会重读配置、重建运行时并执行启动诊断；操作步骤和验收见 [PlugMan 热重载](docs/operations/OPERATIONS.md#plugman-热重载)。

## 数据与开发约定

SQLite 文件可由 `database.file` 指定，支持绝对路径和相对插件目录的路径。使用单连接、WAL、外键约束，默认连接等待和忙等待均为 5 秒。数据库路径和超时修改后需要重启插件或服务器。

配置不设版本号，插件版本与数据库 schema 版本分别管理。当前数据库目标 schema 为 `1.1`，保留已发布的 `V1_0__initial_schema.sql`；本次升级统一由 `V1_1` 新增申请费与收入税操作表，并清理停用外部税收账户后的旧清算锁。正式服 schema `1.0` 只执行一次 `V1_1` 迁移升级到 `1.1`；首次安装依次执行两份迁移，既有数据库保留原数据。不自动修复被改写的迁移历史，也不提供 MySQL 自动导入。升级、备份和回退步骤见 [SQLite 备份手册](docs/operations/SQLITE_AND_BACKUP.md)。

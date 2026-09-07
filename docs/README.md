# 文档导航

本目录描述当前 `1.0.0-SNAPSHOT` 的实际功能。默认数值来自仓库配置，服务器可配置项以部署值为准。配置没有版本号，数据库仅维护初始建表脚本，不使用历史发布迁移链。

## 使用和安装

| 文档 | 解决什么问题 |
|---|---|
| [玩家指南](PLAYER_GUIDE.md) | 从哪里进入、怎样建镇/入镇、各身份能做什么 |
| [完整功能说明](FUNCTIONS_AND_GAMEPLAY.md) | 字段校验、治理门槛、经济和领地规则 |
| [管理员命令手册](ADMIN_COMMANDS.md) | 每条命令的用途、参数、权限、示例和执行后果 |
| [安装与预发检查](setup/PREFLIGHT.md) | 构建环境、必要依赖、首次启动与 LOCKED 排查 |
| [配置说明](setup/CONFIGURATION.md) | 配置分组、默认值、热更新与重启边界 |

## 功能部署与验收

| 文档 | 内容 |
|---|---|
| [建镇与成员](deployment/TOWN_LIFECYCLE.md) | 初始成员确认、选址、审批、删除和权限投影 |
| [治理](deployment/GOVERNANCE.md) | 副镇长、访客、接任与投票验收 |
| [经济与扩张](deployment/ECONOMY_AND_EXPANSION.md) | 三种收入税、补贴、资金补偿和扩张检查 |
| [Buff](deployment/BUFFS_AND_RESOURCES.md) | 玩家购买与管理员代购区别、到期、HuskSync 验收 |
| [定价](deployment/PRICING.md) | 五种属性商品价格及递增扩张公式 |
| [领地加成](deployment/TERRITORY_BONUSES.md) | 建筑返还和持久信标效果的配置与验证 |
| [通知与周期](deployment/FEEDBACK_UPGRADE.md) | 补贴周期、身份通知可靠性和消息配置 |

## 运维与开发

- [运维手册](operations/OPERATIONS.md)：状态检查、功能暂停、后台任务和故障处置。
- [SQLite 与备份](operations/SQLITE_AND_BACKUP.md)：一致性快照、隔离恢复及已有数据库处理。
- [告警和故障注入](operations/ALERTS_AND_FAULT_INJECTION.md)：隔离环境验收矩阵。
- [依赖升级检查](operations/DEPENDENCY_UPGRADE_CHECKLIST.md)：替换依赖前后的能力验证。
- [运行时与存储](architecture/RUNTIME_AND_STORAGE.md)：组件职责和事务边界。
- [测试入口](../test/README.md)：自动测试、人工验收、隔离接口和历史报告。历史报告只表示当时的验证结果。
- [中文代码扫描历史](CHINESE_CODE_LINES.md)：历史扫描快照，不能作为当前源码行号索引。

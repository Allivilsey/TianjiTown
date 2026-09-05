# 领地加成上线、验收与回滚

开发阶段仅维护数据库初始结构，配置不设版本号。该基线面向首次正式部署，不提供旧开发数据库升级路径；部署时应使用新建 SQLite 数据库。

## 上线前

1. 开启维护模式，停止新税收与新消费，完成补偿任务和开放投票检查，并导出未结束的历史资源订单供升级后核对。
2. 在同一停服时间点备份 TianjiTown SQLite、配置/JAR、Residence、QuickShop H2、XConomy 数据，并记录 Vault 清算账户精确余额。
3. 使用当前 `1.0.0-SNAPSHOT` 构建、默认配置和可重建的开发数据库验证。
4. 按地图和经济规模配置 `territory.building-refund`、`territory.beacon` 与 `operations`。返还黑名单默认包含红石类别和高获取难度方块。
5. 启动后确认 `/tianjitown status` 显示 `READY`，数据库初始化及配置内容校验通过；插件会在初始化阶段自动执行统一诊断，未通过时不会进入 `READY`。
6. 执行 `/tianjitown diagnose 7`，保存诊断报告；SQLite 另按运维手册停服备份。

## 领地加成验收

- 将返还概率在预发配置临时设为 `1.0`、周上限设为 `2`：本镇成员在本镇有效 Residence 内放置非黑名单普通方块恰好返还两次；镇外、他镇、创造模式和非成员不返还。
- 验证床、门、箱子、漏斗、信标、活塞、黏液块、水桶、带物品数据方块和自动机械均不返还；双层/多方块事件不计数。
- 重启后本周计数保持，每周一 00:00 按配置时区重置；SQLite 暂时不可用时不返还、不绕过上限，已有 Residence 保护继续工作。
- 在本镇 Residence 内搭建有效信标，核对原版效果和等级覆盖整个小镇领地，镇外不受影响。
- 核对只有本镇镇长和副镇长可以编辑信标效果；普通成员、访客和他镇管理者均被拒绝。
- 破坏信标、使金字塔失效、归档/变更领地、关闭功能、重载插件和停服时，托管效果应被清理。

## 统一诊断验收

`/tianjitown diagnose [天数]` 必须同时给出：

- SQLite `quick_check`、外键违规、关键对象计数、失败投影、锁定账户、待补偿操作和账户/末笔流水一致性；
- 每个 ACTIVE 小镇的 Residence 边界、区域数量和成员权限；
- Vault 清算账户外部余额、内部余额、待完成金额和应有余额；
- QuickShop transaction metric 历史与 `quickshop_tax_records` 的记录数/税额比较。

QuickShop 查询最多读取 1000 条时报告标记 `INCOMPLETE`，不得误报为完全一致。报告写入 `plugins/TianjiTown/diagnostics`，只保留最近 30 份。

至少执行一次隔离恢复，并重新运行 `PRAGMA quick_check`、Flyway 校验和统一诊断。

## 回滚

1. 开启维护模式并关闭经济、Buff 与领地加成的新写入口，导出升级后审计、税收、历史资源订单、返还计数和领地变化清单。
2. 停服并保留当前 `1.0.0-SNAPSHOT` 全量副本。
3. 同时恢复升级前的 TianjiTown SQLite、配置/JAR、Residence、QuickShop、XConomy 和清算账户状态；不提供旧开发结构的升级兼容。
4. 在隔离端口启动，执行上一版本的状态、领地和资金检查，并确认历史资源数据未变化；确认后才切回生产。
5. 人工决定如何补录 `1.0.0-SNAPSHOT` 运行期间发生的真实经济交易，不直接编辑 QuickShop 数据库或 Flyway history。

故障注入和最终验收矩阵见 [ALERTS_AND_FAULT_INJECTION.md](../operations/ALERTS_AND_FAULT_INJECTION.md)。

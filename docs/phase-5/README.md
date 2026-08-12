# 第 5 阶段上线、验收与回滚手册

本阶段交付 `1.4.0`，数据库目标版本为 `5.0`，配置 schema 为 `5`。Flyway 迁移只新增 `building_refund_daily` 及其索引/触发器，不删除或重写第 1～4 阶段业务数据。配置 schema `4` 会在启动时补齐第 5 阶段默认项并安全升级为 `5`；更旧或更新的未知 schema 会锁定插件写入口。

## 上线前

1. 开启维护模式，停止新税收与新消费，完成未领取订单、补偿任务和开放投票检查。
2. 在同一停服时间点备份 TianjiTown SQLite、配置/JAR、Residence、QuickShop H2、XConomy 数据，并记录 Vault 清算账户精确余额。
3. 在隔离环境恢复生产副本，以 `1.3.0` 确认基线后替换为 `1.4.0`；不得直接在唯一生产副本上首次验证迁移。
4. 按地图和经济规模配置 `phase5.building-refund`、`phase5.beacon` 与 `phase5.operations`。建筑白名单只放普通单方块，信标世界清单必须显式填写。
5. 启动后确认 `/townadmin status` 显示 config schema `5`、Flyway schema `5.0` 和 `READY`。
6. 执行 `/townadmin diagnose 7` 和 `/townadmin backup`，保存报告、备份文件及 SHA-256。

## 领地加成验收

- 将返还概率在预发配置临时设为 `1.0`、日上限设为 `2`：本镇成员在本镇有效 Residence 内放置普通白名单方块恰好返还两次；镇外、他镇、创造模式和非成员不返还。
- 验证床、门、箱子、漏斗、信标、活塞、黏液块、水桶、带物品数据方块和自动机械均不返还；双层/多方块事件不计数。
- 重启后当日计数保持，跨日重新计数；SQLite 暂时不可用时不返还、不绕过上限，已有 Residence 保护继续工作。
- 在本镇 Residence 内搭建有效信标，核对范围倍率、最大范围、效果等级上限和世界白名单。
- 破坏信标、使金字塔失效、卸载区块、归档/扩张领地、关闭功能、重载插件和停服时，原范围恢复且附加效果清除。
- 给信标预先设置其他插件的自定义范围，TianjiTown 必须跳过，不能覆盖第三方状态。

## 统一诊断与备份验收

`/townadmin diagnose [天数]` 必须同时给出：

- SQLite `quick_check`、外键违规、关键对象计数、失败投影、锁定账户、待补偿操作和账户/末笔流水一致性；
- 每个 ACTIVE 小镇的 Residence 边界、区域数量和成员权限；
- Vault 清算账户外部余额、内部余额、待完成金额和应有余额；
- QuickShop transaction metric 历史与 `quickshop_tax_records` 的记录数/税额比较。

QuickShop 查询最多读取 1000 条时报告标记 `INCOMPLETE`，不得误报为完全一致。报告写入 `plugins/TianjiTown/diagnostics`，只保留最近 30 份。

自动备份使用 SQLite `VACUUM INTO`，同时保存 `config.yml` 和 SHA-256。它不替代 Residence、QuickShop、XConomy 的同时间点备份。至少执行一次隔离恢复，并重新运行 `PRAGMA quick_check`、Flyway 校验和统一诊断。

## 回滚

1. 开启维护模式并关闭阶段 3～5 新写入口，导出升级后审计、税收、订单、返还计数和领地变化清单。
2. 停服并保留当前 `1.4.0` 全量副本。
3. 同时恢复升级前的 TianjiTown SQLite、配置/JAR、Residence、QuickShop、XConomy 和清算账户状态；不能将 `1.3.0` JAR 指向 schema `5.0`。
4. 在隔离端口启动，执行阶段 4 的状态、领地、资金和订单检查；确认后才切回生产。
5. 人工决定如何补录 `1.4.0` 运行期间发生的真实经济交易，不直接编辑 QuickShop 数据库或 Flyway history。

故障注入和最终验收矩阵见 [ALERTS_AND_FAULT_INJECTION.md](ALERTS_AND_FAULT_INJECTION.md)。

# TASK-CN-036A：`TownRuntime.java`—运行时初始化、补偿与启动恢复

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：运行时初始化、补偿与启动恢复
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 34 行，已迁移）
- 可见行号：无

## 可见文本位置

以下为迁移前源文件的原基线行号，范围表示包含首尾行：

`98, 106, 115, 123, 149-150, 157-158, 165, 173, 186, 195, 201, 281, 287, 290, 301, 304-305, 322, 351-352, 363-364, 370, 384, 392, 407, 433-434, 440, 443, 446-447`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 将运行时调度停止、SQLite 连接恢复/中断、启动恢复、捐款自动补偿及清算复核日志迁入 `log.scheduler.*`、`log.lifecycle.*` 和 `log.donation.*`；领地对账与自动修复日志迁入 `log.residence.*`。所有动态值使用命名占位符，日志和写入建镇失败记录的文案在事件产生时通过当前 `PluginMessages.plainText` 解析。
- 启动恢复传入 `TownRepository.recoverInterruptedProvisions` 的失败原因现在也是消息快照，保持数据库状态机、审计与恢复流程不变；Residence/API 异常改为结构化 `RESIDENCE_API_UNAVAILABLE` 结果码，由既有 `LandProtectionMessages` 在 Paper 边界解析。异常详情和领地诊断详情进入外层模板前会转义 `&`/`§`，避免污染日志颜色。
- 新增默认键：`log.scheduler.lifecycle-stopped`、`log.scheduler.quick-shop-tax-refresh-failure`、`log.lifecycle.sqlite-recovered`、`log.lifecycle.sqlite-interrupted`、`log.lifecycle.interrupted-provision-reason`、`log.lifecycle.interrupted-provisions-recovered`、`log.lifecycle.interrupted-provision-recovery-failure`、`log.donation.compensation-retry-failed`、`log.donation.compensation-finalization-failed`、`log.donation.compensation-recovered`、`log.donation.compensation-exhausted`、`log.donation.settlement-balance-read-failure`、`log.donation.settlement-shortfall`、`log.donation.settlement-reconciliation-failure`、`log.residence.reconciliation-failure`、`log.residence.reconciliation-difference`、`log.residence.reconciliation-sqlite-read-failure`、`log.residence.automatic-repair-cancelled`、`log.residence.automatic-repair-delayed`、`log.residence.automatic-repair-sqlite-read-failure`、`log.residence.automatic-repair-api-failure`、`log.residence.automatic-repair-consistent`、`log.residence.automatic-repair-completed`、`log.residence.automatic-repair-failed`；启动关键键已加入 `PluginMessages.validateRequiredMessages()`。
- 测试：新增 `TownRuntimeMessagesTest`，覆盖 24 个键的非空、完整占位符渲染、无缺失配置/残留占位符，以及写入临时 `messages.yml` 后 reload 的自定义文案。
- 验证通过：`mvn -B -pl tianjitown-paper -am clean "-Dtest=TownRuntimeMessagesTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`（2/2）；`mvn -B test`（Core 24、Storage 40、Integrations 33、Paper 164）。036A 覆盖的 `TownRuntime` 构造、补偿、启动恢复和领地对账/自动修复方法重新扫描后非注释可见中文为 0 行。
- 无技术例外。本次仅完成 TASK-CN-036A，未处理 036B～036G，也未重新生成全局 `docs/CHINESE_CODE_LINES.md`。

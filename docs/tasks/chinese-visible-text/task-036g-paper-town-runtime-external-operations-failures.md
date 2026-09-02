# TASK-CN-036G：`TownRuntime.java`—扩张恢复、外部操作与运行时故障

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：扩张恢复、外部操作与运行时故障
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 12 行，已迁移）
- 可见行号：无

## 可见文本位置

以下为迁移前的原基线行号，范围表示包含首尾行；处理后目标方法区间已无非注释可见中文：

`1550-1551, 1573, 1600, 1630, 1661, 1665, 1688-1689, 1720, 1726, 1761`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 批量领地扩张恢复成功/失败日志迁入 `log.expansion.batch-recovered` 和 `log.expansion.batch-recovery-failed`；批次 ID 和异常详情使用命名占位符，并在日志输出时通过当前 `PluginMessages.plainText` 解析。
- 外部资金操作的 SQLite 锁定、Vault 清算账户预检异常和外部调用异常改为使用 `chat.lifecycle.storage-unavailable`、`chat.lifecycle.storage-write-locked`、`chat.lifecycle.external-preflight-failed` 和 `chat.lifecycle.external-failed`；补偿后的玩家提示使用 `chat.lifecycle.compensation-auto/manual`，消息重载后下一次输出读取新值。
- 自动/人工补偿控制台日志迁入 `log.external-operation.compensation-auto/manual`；历史账本操作人回填和扫描失败诊断迁入 `log.lifecycle.ledger-actor-name-backfill-failure`、`log.lifecycle.ledger-actor-scan-failure`。操作 ID、镇 ID、玩家 UUID 与第三方异常详情进入日志模板前均进行安全文本处理。
- 失败操作写入 `requireCompensation` 或 `cancelOperation` 的原因仍使用操作发生时已解析的纯文本快照；补偿提示只在回调输出时解析，保持状态机、自动补偿和幂等行为不变。
- 新增默认消息键：`chat.lifecycle.storage-write-locked`、`log.expansion.batch-recovered`、`log.expansion.batch-recovery-failed`、`log.external-operation.compensation-auto`、`log.external-operation.compensation-manual`、`log.lifecycle.ledger-actor-name-backfill-failure`、`log.lifecycle.ledger-actor-scan-failure`；继续复用已有 `chat.lifecycle.storage-unavailable`、`external-preflight-failed`、`external-failed`、`compensation-auto` 和 `compensation-manual`。
- 测试：新增 `TownRuntimeExternalOperationsMessagesTest`，覆盖全部本任务消息的非空、完整占位符渲染、无缺失配置/残留占位符，以及 `messages.yml` reload 后的自定义文案。
- 验证通过：`mvn -B -pl tianjitown-paper -am clean "-Dtest=TownRuntimeExternalOperationsMessagesTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`（2/2）；`mvn -B test`（Core 24、Storage 40、Integrations 33、Paper 176）。目标方法区间重新扫描后非注释可见中文为 0 行。
- 验证期间发现工作树前序扩张改动在两个回调处缺少右括号，已作纯语法闭合修复，未改变业务逻辑。
- 无技术例外。本次仅完成 TASK-CN-036G，未处理 037A～037L，也未重新生成全局 `docs/CHINESE_CODE_LINES.md`。

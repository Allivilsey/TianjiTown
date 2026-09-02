# TASK-CN-015：`BuffRuntime.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/BuffRuntime.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/BuffRuntime.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 21 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号为迁移前的原基线，范围表示包含首尾行；处理后源文件对应位置已无非注释可见中文：

`78, 97, 134, 160-161, 236, 240, 327, 358-359, 372, 549, 573, 575-578, 621, 624, 678-679`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 将 Buff 商店暂停、Buff 应用失败后的自动补偿提示迁入 `chat.buff.*`；运行时刷新、到期处理、属性修复和清理失败日志迁入 `log.buff.*`，属性缺失/最大生命值异常迁入 `diagnostic.buff.*`。
- `BuffRuntime` 的玩家错误、诊断和日志均在调用时通过 `plugin.messages().plainText(...)` 解析；异常详情及动态标识在进入模板前隔离 `&`/`§`，避免污染日志或嵌套颜色。
- 自动补偿成功后写入账本的原因使用 `log.buff.refund-reason` 在事件发生时解析，保留历史记录的文案快照，不随之后的重载改写。
- 新增默认键：`chat.buff.shop-paused`、`chat.buff.application-failure-refunded`、`diagnostic.buff.missing-attribute`、`diagnostic.buff.max-health-missing`、`diagnostic.buff.max-health-invalid`，以及 `log.buff.refresh-failure`、`log.buff.refresh-check-failure`、`log.buff.refund-reason`、`log.buff.expiration-schedule-failure`、`log.buff.expiration-cleanup-failure`、`log.buff.expiration-cancel-failure`、`log.buff.attribute-repair-missing`、`log.buff.attribute-repair-mismatch`、`log.buff.cleanup-invalid-object`。
- 测试：`PluginMessagesTest` 覆盖全部 Buff 运行时键、完整占位符渲染、无缺失键提示，以及重载 `messages.yml` 后日志覆盖值生效。
- 验证通过：`mvn -B -pl tianjitown-paper -am "-Dtest=PluginMessagesTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`、`mvn -B -pl tianjitown-paper -am test`、`mvn -B test`、`mvn -B clean test`；clean 全量结果为 Core 24、Storage 40、Integrations 33、Paper 106，全部通过。目标源文件按原 21 个命中位置重新扫描为 0 行。
- 例外：无。本任务未重新生成全局 `docs/CHINESE_CODE_LINES.md`；其中其他未列入本任务的 Buff 校验文案仍由后续任务边界管理。

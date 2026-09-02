# TASK-CN-036F：`TownRuntime.java`—领地扩张与外部投影

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：领地扩张与外部投影
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 16 行，已迁移）
- 可见行号：无

## 可见文本位置

以下为迁移前源文件的原基线行号，范围表示包含首尾行：

`1272, 1281, 1286, 1310, 1326, 1333, 1398, 1413, 1461, 1472, 1476, 1509, 1512, 1534, 1536, 1539`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 扩张消费暂停和扩张环境复核复用现有 `chat.runtime.consumption-paused`、`chat.lifecycle.expansion-validation-failed`；单块扩张退款复用 `chat.lifecycle.expansion-failed-refunded`。
- 批量请求状态迁入 `validation.territory.batch-request-id-required` 和 `validation.territory.batch-already-refunded`；批量投影失败、原区域探测失败和部分回滚迁入 `chat.lifecycle.expansion-batch-failed`、`chat.lifecycle.expansion-area-presence-check-failed` 与 `chat.lifecycle.expansion-batch-rollback-partial`。
- Residence API 异常改为结构化 `LandProtectionService.ResultCode.RESIDENCE_API_UNAVAILABLE`，由 `LandProtectionMessages` 在 Paper 边界解析；批量回滚和单块恢复日志迁入 `log.expansion.batch-rollback-failed`、`log.expansion.recovered` 和 `log.expansion.recovery-failed`。异常详情、清理详情和批次/扩张 ID 使用命名占位符并进行安全文本处理。
- 测试：新增 `TownRuntimeExpansionProjectionMessagesTest`，覆盖全部本任务消息键的完整渲染、非空/无缺失配置/无残留占位符断言，以及 `messages.yml` 重载后的覆盖值。
- 无技术例外。本次仅完成 TASK-CN-036F，未处理 036G 或 037D，也未重新生成全局 `docs/CHINESE_CODE_LINES.md`。

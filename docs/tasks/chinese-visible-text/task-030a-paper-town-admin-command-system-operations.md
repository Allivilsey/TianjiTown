# TASK-CN-030A：`TownAdminCommand.java`—系统状态、诊断、备份、维护与确认

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：系统状态、诊断、备份、维护与确认
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCommand.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCommand.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 12 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号为迁移前的原基线，范围表示包含首尾行；处理后目标位置已无非注释可见中文：

`155, 159, 167, 177, 186, 190, 194, 203, 227, 275, 281, 284`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 状态页的诊断报告和备份文件后缀改为完整消息模板：使用 `chat.admin.status-diagnostic`、`chat.admin.status-diagnostic-no-report`、`chat.admin.status-backup` 和 `chat.admin.status-backup-no-file`，路径作为 `{report}`/`{file}` 动态值传入并进行安全文本处理。
- 诊断、备份、维护和审计参数错误改为通过 `chat.admin.usage-diagnose`、`chat.admin.usage-backup`、`chat.admin.usage-maintenance`、`chat.admin.usage-audit`、`chat.admin.audit-limit-integer` 和 `chat.admin.audit-limit-range` 解析；维护状态使用 enabled/disabled 两个完整模板，不再把中文状态标签放在 Java 中。
- 确认操作启动失败日志迁入 `log.admin.confirmation-start-failure`，通过 `plainText` 输出，并转义异常详情中的 `&`/`§`，避免日志颜色注入；原有玩家确认失败消息继续使用 `chat.admin.confirm-start-failed`。
- 测试：`PluginMessagesTest` 覆盖全部新增/更新键的完整渲染、无残留占位符和 `messages.yml` 重载后的自定义值。
- 验证：Paper 定向 `PluginMessagesTest` 通过；目标源文件 030A 原基线区间复扫后无非注释可见中文。未重新生成全局 `docs/CHINESE_CODE_LINES.md`，也未处理 030B～030E 或其他任务范围。

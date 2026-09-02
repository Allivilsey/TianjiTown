# TASK-CN-030C：`TownAdminCommand.java`—成员、投票、镇长与领地

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：成员、投票、镇长与领地
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCommand.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCommand.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 14 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号为迁移前的原基线，范围表示包含首尾行；处理后目标位置已无非注释可见中文：

`531, 546, 556, 558, 565, 575, 597, 603, 606, 624, 637, 662-664`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 投票、镇长和领地命令的用法与不支持动作改为通过 `chat.admin.usage-vote`、`chat.admin.usage-vote-cancel`、`chat.admin.usage-vote-create`、`chat.admin.vote-action-unsupported`、`chat.admin.usage-mayor`、`chat.admin.usage-land` 和 `chat.admin.land-action-unsupported` 解析；投票创建动作使用 `{action}` 占位符。
- 投票和成员找不到可操作小镇的提示分别使用 `chat.admin.vote-town-not-found` 与 `chat.admin.member-town-not-found`，小镇名称作为安全的 `{town}` 动态值传入。
- 治理配置拒绝创建投票的控制台日志迁入 `log.admin.governance-vote-creation-rejected`，通过 `plainText` 输出并对异常详情做安全文本处理。
- 领地重建确认按单镇/批量语义分别使用 `chat.admin.land-rebuild-confirmation-town` 与 `chat.admin.land-rebuild-confirmation-all`，在创建确认时读取当前消息配置；小镇数量使用 `{count}`。
- 测试：`PluginMessagesTest` 覆盖新增键的完整渲染、全部占位符、无残留占位符以及 messages.yml 重载后的自定义值。
- 验证：Paper 定向 `PluginMessagesTest` 通过；`TownAdminCommand.java` 按本任务 14 个原基线位置复扫后无非注释可见中文。未重新生成全局 `docs/CHINESE_CODE_LINES.md`，也未处理 030D～030E 或其他任务范围。

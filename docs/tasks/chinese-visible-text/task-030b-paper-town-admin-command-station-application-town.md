# TASK-CN-030B：`TownAdminCommand.java`—服务台、手册、申请与小镇

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：服务台、手册、申请与小镇
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCommand.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCommand.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 11 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号为迁移前的原基线，范围表示包含首尾行；处理后目标位置已无非注释可见中文：

`361, 370, 402, 423, 431, 435, 439, 463, 472, 475, 490`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 将申请和小镇命令的用法、查找失败及不支持动作迁入 `chat.admin.usage-application-review`、`chat.admin.application-not-found`、`chat.admin.usage-town`、`chat.admin.usage-town-delete`、`chat.admin.town-not-found`、`chat.admin.town-action-unsupported`、`chat.admin.usage-member` 和 `chat.admin.member-action-unsupported`；小镇名与申请动作作为命名占位符传入。
- 将删除小镇的确认描述迁入 `chat.admin.town-delete-confirmation`，在创建确认时按当前消息配置解析并保留事件发生时的确认快照。
- 将 Residence 清理失败控制台日志迁入 `log.admin.town-delete-residence-failure`，使用 `plainText` 和 `{town}`、`{residence}`、`{detail}`，并对外部文本进行安全处理；成员角色调整的审计原因使用 `log.admin.member-role-change-reason` 的纯文本快照。
- 测试：`PluginMessagesTest` 覆盖新增键的完整渲染、无残留占位符和 `messages.yml` 重载后的自定义值。
- 验证：Paper 定向 `PluginMessagesTest` 通过；`TownAdminCommand.java` 按本任务 11 个原基线位置复扫后无非注释可见中文。未重新生成全局 `docs/CHINESE_CODE_LINES.md`，也未处理 030C～030E 或其他任务范围。

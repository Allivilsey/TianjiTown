# TASK-CN-030E：`TownAdminCommand.java`—帮助页与帮助条目

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：帮助页与帮助条目
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCommand.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCommand.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 13 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号为迁移前的原基线，范围表示包含首尾行；处理后目标位置已无非注释可见中文：

`1068, 1071-1076, 1079, 1082, 1085, 1088, 1091, 1117`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 管理帮助标题、用法、未知分类、权限提示、根帮助条目和各主题帮助页统一使用现有 `chat.admin.help-*` 消息键；默认中文保留在 `messages.yml`，`{version}` 和 `{topic}` 的含义已在消息组注释中说明。
- 根帮助条目的权限过滤和顺序保持不变；移除 `TownAdminCommand` 中的硬编码可见文本，改为通过注入的消息 resolver 按请求时解析。生产路径由 `configuredRootHelpEntries` 注入 `plugin.messages()::text`，因此重载 `messages.yml` 后下一次帮助输出会使用新值。
- 经济帮助分类的不可达兜底改用 `chat.admin.help-unknown`，不再保留中文异常文本；命令子命令、权限节点和帮助主题标识保持不变。
- 测试：`TownAdminHelpTest` 覆盖权限过滤、完整根条目和重载后的自定义条目；`PluginMessagesTest` 覆盖全部帮助键的非空/占位符渲染及重载覆盖。
- 验证：Paper 定向测试 51 项通过；根项目完整测试 Core 24、Storage 40、Integrations 33、Paper 155 项全部通过；当前 `TownAdminCommand.java` 无非注释中文，旧硬编码帮助条目无生产代码残留。
- 未重新生成全局 `docs/CHINESE_CODE_LINES.md`，也未处理其他任务范围。

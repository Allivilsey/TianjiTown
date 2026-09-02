# TASK-CN-031：`TownAdminCompletionEngine.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCompletionEngine.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCompletionEngine.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 3 行，已迁移）
- 可见行号：无

## 可见文本位置

处理后重新扫描源文件，已无非注释可见中文；原基线行号为：

`21-22, 253`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 将 `<原因>`、`<玩家>`、`<金额>` 三项 Tab 补全位置提示迁入 `chat.admin.completion.reason-hint`、`chat.admin.completion.player-hint`、`chat.admin.completion.amount-hint`；默认值保留当前产品文案，消息注释说明这些值仅用于显示、不能作为实际命令参数提交。
- `TownAdminCompletionEngine` 通过 `BiFunction<String, Map<String, ?>, String>` 注入消息 resolver，并在每次生成提示时调用；`TownAdminTabCompleter` 注入 `plugin.messages()::plainText`，因此重载 `messages.yml` 后下一次补全会读取新值，同时去除颜色码对命令参数提示的污染。命令子命令、真实玩家名、小镇名、金额和数据库/权限标识保持不变。
- 新增 `TownAdminCompletionHints` 统一维护消息键与稳定的 ASCII 机器值（`<reason>`、`<player>`、`<amount>`）。`TownCommandParser` 保留原有签名，并为相关语义参数增加带 resolver 的兼容重载，另为原先直接拼接的 `vote cancel` 原因增加 resolver 入口；`TownAdminCommand` 仅在这些补全值对应的解析调用点传入 resolver，使默认/自定义显示标签以及稳定机器值都不能被误当成实际参数，同时不对整组命令参数做全局改写。
- 测试：`TownAdminCompletionEngineTest` 覆盖三个默认键的非空实际补全结果，以及 `messages.yml` 重载后原因、玩家、金额提示的完整替换；同时验证自定义显示标签在原因、玩家、金额解析位置会被拒绝。YAML 加载和默认资源存在性由 `PluginMessages` 初始化路径覆盖。
- 验证：`mvn -pl tianjitown-paper -am clean test` 和根项目 `mvn test` 均通过（Core 24、Storage 40、Integrations 33、Paper 156；Paper 中 `TownAdminCompletionEngineTest` 6 项、`TownCommandParserTest` 5 项均通过）。目标源文件复扫后非注释中文为 0 行。
- `TownAdminTabCompleter.java` 仅作本任务所需的 resolver 注入调用方调整；其缓存刷新日志属于 TASK-CN-032，未在本任务处理。未重新生成全局 `docs/CHINESE_CODE_LINES.md`，也未处理其他任务范围。

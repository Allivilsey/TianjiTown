# TASK-CN-035：`TownCommandParser.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownCommandParser.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownCommandParser.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 22 行，已迁移）
- 可见行号：无

## 可见文本位置

以下为迁移前的原基线行号，范围表示包含首尾行；处理后目标源文件已无非注释可见中文：

`17, 31, 33, 43, 72, 88, 111, 189-193, 195-197, 199-205`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 复用 `messages.yml` 中已有的 `chat.parser.*` 消息键，覆盖缺少原因/玩家/金额/目标、参数数量、操作或商品不支持、数量格式、小镇名称和占位符等全部解析错误；默认文案与原产品语义保持一致。
- `TownCommandParser` 的解析异常只保留稳定 `messageKey` 与占位符参数，`IllegalArgumentException` 技术消息使用消息键，不再内置中文 `legacyMessage` 回退表。`TownAdminCommand` 在命令输出边界通过 `plugin.messages().text(...)` 即时解析，因此自定义文案和重载后的下一次输出均生效。
- 解析器不再把中文显示标签写入代码；原因、玩家和金额位置改用 `TownAdminCompletionHints` 的稳定 ASCII 机器值，并在生产调用传入当前消息 resolver 以拒绝配置中的显示提示。命令子命令、参数和业务解析逻辑保持不变。
- 测试：`TownCommandParserTest` 验证稳定消息引用、完整 `{action}` 占位符渲染和 `messages.yml` 重载覆盖；既有 `TownAdminCompletionEngineTest` 继续覆盖自定义原因/玩家/金额提示不会被当作真实参数。
- 验证通过：`mvn -B -pl tianjitown-paper -am "-Dtest=TownCommandParserTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`（6/6）、`mvn -B -pl tianjitown-paper -am test`（Core 24、Storage 40、Integrations 33、Paper 162）、`mvn -B test`（同上）。目标源文件复扫后非注释可见中文为 0 行。
- 未重新生成全局 `docs/CHINESE_CODE_LINES.md`，未处理 task-035 以外的任务或源文件。

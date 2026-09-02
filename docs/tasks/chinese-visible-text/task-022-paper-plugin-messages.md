# TASK-CN-022：`PluginMessages.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/PluginMessages.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/PluginMessages.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 6 行，已迁移或记录为启动期例外）
- 可见行号：无

## 可见文本位置

以下行号按源文件当前版本记录，范围表示包含首尾行：

迁移前原基线为：`35, 69, 83, 95, 101, 121`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 将运行期缺失键回退迁入 `system.missing-message`，通过 `PluginMessages` 在每次使用时解析；用户覆盖 `messages.yml` 并重载后，后续输出采用新文案。
- 将必需投票消息和范围格式校验异常迁入 `diagnostic.messages.required-vote-missing`、`diagnostic.messages.range-format-missing`、`diagnostic.messages.range-format-unsupported`、`diagnostic.messages.range-format-placeholder-count`，使用 `{key}` 占位符并以纯文本构造异常详情。
- 内置 `messages.yml` 改用显式 `YamlConfiguration.load` 校验；JAR 资源缺失或读取/解析失败时使用稳定的非本地化 bootstrap 错误码，不递归依赖自身消息配置。该路径为启动期技术例外。
- 测试：`PluginMessagesTest` 覆盖新增键的完整渲染、缺失键回退、重载后的自定义文案和校验异常；`mvn -pl tianjitown-paper -am test` 与 `mvn test` 全部通过。
- 源文件复扫后非注释可见中文为 0 行。`docs/CHINESE_CODE_LINES.md` 仍保留全量迁移基线，未在本单任务中重生成。

# TASK-CN-028B：`TianjiTownPlugin.java`—依赖、数据库与配置门禁

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：依赖、数据库与配置门禁
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TianjiTownPlugin.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TianjiTownPlugin.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 13 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号为迁移前的原基线，范围表示包含首尾行；处理后目标源文件的这些位置已无非注释可见中文：

`270, 274, 280, 291, 313, 328, 339-340, 342-343, 352, 361, 366`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 将依赖未安装、依赖未启用、依赖探测异常和 Vault Economy 跳过探测的完整诊断句子迁入 `diagnostic.lifecycle.*`；依赖名、版本和异常详情使用命名占位符，并在进入纯文本诊断前转义 `&`/`§`。
- 将 SQLite/Flyway 门禁失败、数据库配置失败、schema 版本不兼容及数据库路径/目录错误迁入 `diagnostic.lifecycle.*` 与 `validation.runtime-configuration.*`；复用已有的 `validation.runtime-configuration.database-file-required`，数据库目录路径和第三方异常详情使用占位符并保留原始异常 cause。
- `TianjiTownPlugin` 在依赖、数据库和配置门禁调用时通过当前 `PluginMessages.plainText` 解析，`PluginMessages.validateRequiredMessages()` 覆盖本任务新增启动关键键；不改变依赖名、schema 数值、数据库路径和门禁状态机。
- `PluginMessagesTest` 覆盖本任务全部新增键的完整默认渲染、占位符无残留、非空/无缺失配置及 `messages.yml` reload 后的自定义依赖和数据库目录文案。
- 验证通过：定向 `PluginMessagesTest` 37 项、`mvn -B -pl tianjitown-paper -am test`（Paper 142 项）以及 `mvn -B test`、`mvn -B clean test`（Core 24、Storage 40、Integrations 33、Paper 142）均通过；目标文件复扫后，028B 原 13 个命中位置无非注释可见中文。
- 028C 的运行时激活、周期任务与锁定文案仍按单任务边界保留；本任务未重新生成全局 `docs/CHINESE_CODE_LINES.md`。

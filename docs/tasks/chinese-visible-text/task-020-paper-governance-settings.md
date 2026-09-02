# TASK-CN-020：`GovernanceSettings.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/GovernanceSettings.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/GovernanceSettings.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 2 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号为迁移前的原基线，范围表示包含首尾行；处理后源文件已无非注释可见中文：

`58-59`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 将治理时长校验的两种完整文案迁入 `validation.governance.duration-non-negative` 和 `validation.governance.duration-positive`，配置路径使用 `{path}` 占位符；保留小时/天数单位和原有安全计算校验逻辑。
- `GovernanceSettings` 将范围校验的消息解析器贯穿到时长转换和异常构造；生产调用方继续在每次读取配置时传入 `messages()::plainText`，旧无 resolver 入口仅回退到稳定消息键及路径诊断，不缓存已渲染文案。
- 测试：`GovernanceSettingsTest` 覆盖两个默认键的完整渲染、负数/零值/溢出分支，以及重载 `messages.yml` 后下一次配置读取采用自定义文案；定向 `GovernanceSettingsTest` 与 `PluginMessagesTest` 共 32 项通过。
- 源文件复扫后非注释可见中文为 0 行。例外：未重新生成全局 `docs/CHINESE_CODE_LINES.md`；其他文件中的治理相关文案不在 TASK-CN-020 边界内。

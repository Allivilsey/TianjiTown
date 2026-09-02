# TASK-CN-017：`ConfigurationValues.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/ConfigurationValues.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/ConfigurationValues.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 15 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号为迁移前的原基线，范围表示包含首尾行；处理后源文件已无非注释可见中文：

`20, 26, 34, 50, 54, 67, 73, 84, 93, 101, 109, 117, 124, 129, 134`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 将布尔值、数字、有限数、文本、整数、列表、十进制文本和范围错误等通用配置校验文案迁入 `validation.configuration.*`，统一使用 `{path}` 占位符。
- `ConfigurationValues` 新增 resolver 重载；`BuffSettings`、运行时配置校验、经济/治理设置及插件数据库路径均把 `messages()::plainText` 传入，错误文案在实际读取配置时解析并支持重载后的新值。
- 保留无 resolver 重载以兼容现有单元测试与调用方；该入口只返回稳定消息键及配置路径诊断，不作为生产玩家/管理员文案来源。
- 新增默认键：`validation.configuration.boolean-type`、`int-range`、`value-required`、`number-type`、`finite-number`、`text-type`、`decimal-text`、`string-list-type`、`list-type`、`integer-type`、`long-range`。
- 测试：新增 `ConfigurationValuesTest`，覆盖全部默认键、全部校验失败分支、调用方传递 resolver、占位符完整渲染和 `messages.yml` 重载后的实际覆盖值。
- 验证：定向配置/消息测试 40 项通过，`mvn -B -pl tianjitown-paper -am test` 的 Paper 113 项及其依赖模块测试通过；目标源文件重新扫描为 0 行非注释可见中文。
- 例外：未重新生成全局 `docs/CHINESE_CODE_LINES.md`；其他设置类和配置校验器自身的可见文案仍由对应后续任务处理。

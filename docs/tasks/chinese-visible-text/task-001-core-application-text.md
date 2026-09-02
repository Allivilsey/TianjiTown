# TASK-CN-001：`ApplicationText.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-core`
- 源文件：[`tianjitown-core/src/main/java/cn/tianji/town/core/application/ApplicationText.java`](../../../tianjitown-core/src/main/java/cn/tianji/town/core/application/ApplicationText.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 10 行，已迁移）
- 可见行号：无

## 可见文本位置

处理后重新扫描源文件，已无可见中文文本；原基线行号为：

`31, 33, 35, 37, 39, 42, 73, 75, 83, 91`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 将 `ApplicationText` 的校验结果改为稳定的 `ValidationIssue` 代码及占位符参数；core 不再生成玩家可见中文，也不依赖 Paper。
- 新增 `validation.application.*` 默认消息键，Paper 通过 `ApplicationTextMessages` 在使用时解析，覆盖了申请创建、修改、资料更新及表单字段校验。
- `requireValid()` 现在抛出包含结构化校验项的 `ValidationException`；Paper 边界会重新解析存储层传出的同类异常。
- 测试：`ApplicationTextTest`、`ApplicationTextMessagesTest`、`TownActionsTest`；确认默认文案、完整占位符替换和用户覆盖值生效。
- 例外：无。`docs/CHINESE_CODE_LINES.md` 保留为全量迁移基线，未在本单任务中重生成。

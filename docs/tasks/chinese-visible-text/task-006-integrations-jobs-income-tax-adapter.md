# TASK-CN-006：`JobsIncomeTaxAdapter.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-integrations`
- 源文件：[`tianjitown-integrations/src/main/java/cn/tianji/town/integrations/jobs/JobsIncomeTaxAdapter.java`](../../../tianjitown-integrations/src/main/java/cn/tianji/town/integrations/jobs/JobsIncomeTaxAdapter.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 8 行，已迁移）
- 可见行号：无

## 可见文本位置

处理后重新扫描源文件，已无可见中文文本；原基线行号为：

`50, 52, 71, 85, 87, 96, 103, 109`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 为 `JobsIncomeTaxAdapter` 注入 `BiFunction<String, Map<String, ?>, String>` 消息解析器；能力检查摘要、付款事件失败日志、主线程等待异常和事件边界异常均在使用时解析，`TianjiTownPlugin` 统一传入 `messages()::plainText`。
- 新增默认消息键：`diagnostic.jobs.capability-success`、`diagnostic.jobs.capability-failure`、`log.jobs.payment-failure`、`log.jobs.await-interrupted`、`log.jobs.await-timeout`、`log.jobs.main-thread-failure`、`log.jobs.boundary-failure`。
- 第三方异常详情作为动态 `{detail}` 保留，并在进入纯文本消息解析前转义 `&`/`§`，避免日志颜色控制符注入；消息解析器异常时仅回退到稳定消息键，不在 integrations 引入配置依赖。
- `PluginMessagesTest` 覆盖 Jobs 完整占位符渲染、所有新增键非缺失/无残留占位符，以及重载后的用户日志覆盖值。目标源文件重新扫描后非注释可见中文为 0 行；异步付款说明仍是不可见技术注释。
- 验证：`mvn -pl tianjitown-paper -am test` 和根项目 `mvn test` 均通过，共 173 个测试。
- 例外：无。`docs/CHINESE_CODE_LINES.md` 保留为全量迁移基线，未在本单任务中重生成。

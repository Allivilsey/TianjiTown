# TASK-CN-005：`GlobalMarketPlusIncomeTaxAdapter.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-integrations`
- 源文件：[`tianjitown-integrations/src/main/java/cn/tianji/town/integrations/globalmarketplus/GlobalMarketPlusIncomeTaxAdapter.java`](../../../tianjitown-integrations/src/main/java/cn/tianji/town/integrations/globalmarketplus/GlobalMarketPlusIncomeTaxAdapter.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 7 行，已迁移）
- 可见行号：无

## 可见文本位置

处理后重新扫描源文件，已无可见中文文本；原基线行号为：

`65, 67, 100, 124, 173, 228, 234`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 为 GlobalMarketPlus 能力检查摘要和四类事件异常日志新增消息解析器注入；integrations 不依赖 Paper 或 Bukkit 配置实现，日志与能力结果在使用时解析。
- 新增默认消息键：`diagnostic.global-market-plus.capability-success`、`diagnostic.global-market-plus.capability-failure`、`log.global-market-plus.transaction-failure`、`log.global-market-plus.auction-failure`、`log.global-market-plus.main-thread-failure`、`log.global-market-plus.boundary-failure`。
- 异常详情中的 `&`/`§` 会在进入 `plainText` 解析前转义，避免第三方错误文本注入颜色控制；消息解析器自身异常时仅回退到稳定消息键，不引入硬编码中文。
- 测试覆盖完整占位符渲染、无缺失配置/残留占位符，以及 `messages.yml` 重载后的用户覆盖值。
- 例外：无。`docs/CHINESE_CODE_LINES.md` 保留为全量迁移基线，未在本单任务中重生成。

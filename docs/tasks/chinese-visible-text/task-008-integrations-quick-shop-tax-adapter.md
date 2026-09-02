# TASK-CN-008：`QuickShopTaxAdapter.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-integrations`
- 源文件：[`tianjitown-integrations/src/main/java/cn/tianji/town/integrations/quickshop/QuickShopTaxAdapter.java`](../../../tianjitown-integrations/src/main/java/cn/tianji/town/integrations/quickshop/QuickShopTaxAdapter.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 11 行，已迁移）
- 可见行号：无

## 可见文本位置

以下为迁移前的原基线行号，范围表示包含首尾行：

处理后重新扫描源文件，已无可见中文文本；原基线行号为：

`74-76, 98, 100, 133, 156, 195, 201, 209, 215`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 为 QuickShop 税务适配器注入 `BiFunction<String, Map<String, ?>, String>` 消息解析器；能力检查结果和事件日志均在使用时解析，Paper 创建适配器时传入 `messages()::plainText`。
- 新增默认消息键：`diagnostic.quick-shop.tax-version-unsupported`、`diagnostic.quick-shop.tax-capability-success`、`diagnostic.quick-shop.tax-capability-failure`、`diagnostic.quick-shop.invalid-amount`、`log.quick-shop.tax-event-failure`、`log.quick-shop.transaction-account-failure`、`log.quick-shop.success-settlement-failure`、`log.quick-shop.boundary-failure`。
- QuickShop/第三方异常详情作为动态 `{detail}` 保留，并在进入纯文本消息解析前转义 `&`/`§`，避免日志颜色控制符注入；消息解析器异常时仅回退到稳定消息键，不在 integrations 引入配置依赖。
- `PluginMessagesTest` 覆盖新增键的完整渲染、占位符无残留和 `messages.yml` 重载后的 QuickShop 日志覆盖值。
- 例外：无。`docs/CHINESE_CODE_LINES.md` 保留为全量迁移基线，未在本单任务中重生成。

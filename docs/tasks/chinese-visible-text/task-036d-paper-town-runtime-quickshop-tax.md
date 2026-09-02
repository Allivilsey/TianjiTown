# TASK-CN-036D：`TownRuntime.java`—QuickShop 税收

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：QuickShop 税收
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 4 行，已迁移）
- 可见行号：无

## 可见文本位置

以下为迁移前源文件的原基线行号，范围表示包含首尾行：

`1003, 1011, 1040-1041`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 将 QuickShop 税收补贴额度状态、服务器补贴入账失败日志和税款账本重试日志迁入 `log.quick-shop.*`；动态业务键和失败详情使用命名占位符，并在日志输出时通过当前 `PluginMessages.plainText` 解析。
- 补贴入账失败写入 SQLite 取消预留的原因仍保留结算结果生成时的原始快照；日志详情经过安全文本处理，避免第三方异常中的 `&`/`§` 污染日志格式。消息重载后，后续 QuickShop 税收处理和日志输出读取新值。
- 新增默认键：`log.quick-shop.subsidy-quota-exhausted`、`log.quick-shop.subsidy-settlement-failure`、`log.quick-shop.tax-ledger-write-failure`。
- 测试：新增 `TownRuntimeQuickShopTaxMessagesTest`，覆盖三项完整渲染、无残留占位符、非空校验及 `messages.yml` 重载后的自定义文案。
- 无技术例外。本次仅完成 TASK-CN-036D，未处理 036E～036G，也未重新生成全局 `docs/CHINESE_CODE_LINES.md`。

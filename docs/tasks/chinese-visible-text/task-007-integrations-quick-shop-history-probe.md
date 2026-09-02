# TASK-CN-007：`QuickShopHistoryProbe.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-integrations`
- 源文件：[`tianjitown-integrations/src/main/java/cn/tianji/town/integrations/quickshop/QuickShopHistoryProbe.java`](../../../tianjitown-integrations/src/main/java/cn/tianji/town/integrations/quickshop/QuickShopHistoryProbe.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 6 行，已迁移）
- 可见行号：无

## 可见文本位置

处理后重新扫描源文件，已无可见中文文本；原基线行号为：

`34, 50, 56, 62, 72, 100`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 为 QuickShop 历史探针注入 `BiFunction<String, Map<String, ?>, String>` 消息解析器；Paper 创建运行时对象时传入 `messages()::plainText`，因此下一次诊断会读取重载后的文案。
- 新增默认消息键：`diagnostic.quick-shop.history-version-unsupported`、`diagnostic.quick-shop.history-query-constructor-missing`、`diagnostic.quick-shop.history-result-type-invalid`、`diagnostic.quick-shop.history-read-failure`、`diagnostic.quick-shop.api-target-type-mismatch`、`diagnostic.quick-shop.history-success`。
- 反射构造器缺失和 API 类型不匹配改为内部异常标记，固定上下文由消息键解析；第三方或反射异常详情作为动态 `{detail}`，并在进入纯文本解析前转义 `&`/`§`。
- 测试覆盖 QuickShop 历史成功详情的解析器注入、全部新增键的完整占位符渲染，以及 `messages.yml` 重载后的用户覆盖值。
- 例外：无。`docs/CHINESE_CODE_LINES.md` 保留为全量迁移基线，未在本单任务中重生成。

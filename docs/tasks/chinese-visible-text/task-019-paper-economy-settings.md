# TASK-CN-019：`EconomySettings.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/EconomySettings.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/EconomySettings.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 8 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号为迁移前的原基线，范围表示包含首尾行；处理后源文件已无非注释可见中文：

`40, 43, 46, 49, 53, 57, 61, 67`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 将结算账户、金额精度、扩张费用、周/十二小时补贴限额及金额范围校验文案迁入 `validation.economy.*`；配置路径和精度边界改为 `{path}`、`{minimum}`、`{maximum}` 占位符，保持原有校验语义与稳定配置路径不变。
- `EconomySettings.load(config, messageResolver)` 在每次配置校验时解析 `messages.yml`；生产调用方已传入 `messages()::plainText`，旧无 resolver 入口回退到稳定键和路径诊断，不缓存已渲染文案。
- 新增默认键：`validation.economy.settlement-account-required`、`money-scale-range`、`expansion-cost-positive`、`weekly-subsidy-limit-negative`、`twelve-hour-subsidy-limit-negative`、`twelve-hour-limit-exceeds-weekly`、`expansion-cost-overflow`、`expansion-cost-range`。
- 测试：`EconomySettingsTest` 覆盖 8 个默认键的非空/占位符渲染、全部校验分支、金额溢出 cause、无颜色缺失提示，以及重载 `messages.yml` 后配置异常采用自定义文案。
- 例外：未重新生成全局 `docs/CHINESE_CODE_LINES.md`；其他配置校验器和存储层文案不在 TASK-CN-019 边界内。

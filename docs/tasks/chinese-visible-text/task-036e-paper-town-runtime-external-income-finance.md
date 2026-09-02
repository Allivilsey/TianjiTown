# TASK-CN-036E：`TownRuntime.java`—Jobs/GlobalMarket 收入、结算与资金调整

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：Jobs/GlobalMarket 收入、结算与资金调整
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 14 行，已迁移）
- 可见行号：无

## 可见文本位置

以下为迁移前源文件的原基线行号，范围表示包含首尾行：

`1056, 1076, 1084-1085, 1132-1133, 1141, 1148-1149, 1153, 1162, 1168, 1171, 1196`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 将 Jobs 收入税转入清算账户失败、GlobalMarketPlus 收入税扣取/补贴入账失败、外部收入税账本重试及清算账户对账日志迁入 `log.jobs.*`、`log.global-market-plus.*`、`log.external-income-tax.*` 和 `log.settlement.*`；异常详情、业务键和来源标识使用命名占位符，并在日志输出时通过当前 `PluginMessages.plainText` 解析。
- GlobalMarketPlus 补贴失败按“已返还玩家/返还玩家也失败”分别选择完整消息键，避免在 Java 中拼接可见句子；第三方结算详情和业务键进入消息前会进行安全文本处理。捐款暂停复用 `chat.runtime.consumption-paused`，“无小镇”复用 `validation.territory.town-required`。
- 捐款操作原因和税率修改原因迁入 `log.donation.operation-reason`、`log.tax.rate-change-reason`，在经济操作/审计写入时解析为事件发生时的纯文本快照，保持历史记录语义不受后续重载影响。
- 新增默认键：`log.global-market-plus.income-tax-debit-failure`、`log.global-market-plus.subsidy-settlement-failure-refunded`、`log.global-market-plus.subsidy-settlement-failure-refund-failed`、`log.jobs.income-tax-settlement-failure`、`log.external-income-tax.ledger-write-failure`、`log.settlement.balance-read-failure`、`log.settlement.shortfall`、`log.settlement.reconciliation-failure`、`log.donation.operation-reason`、`log.tax.rate-change-reason`。
- 测试：新增 `TownRuntimeExternalIncomeFinanceMessagesTest`，覆盖全部新增键的完整占位符渲染、非空/无缺失配置/无残留占位符断言，以及 `messages.yml` 重载后的用户覆盖值。
- 无技术例外。本次仅完成 TASK-CN-036E，未处理 036F～036G，也未重新生成全局 `docs/CHINESE_CODE_LINES.md`。

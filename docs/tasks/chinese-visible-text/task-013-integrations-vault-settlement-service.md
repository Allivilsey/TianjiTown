# TASK-CN-013：`VaultSettlementService.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-integrations`
- 源文件：[`tianjitown-integrations/src/main/java/cn/tianji/town/integrations/vault/VaultSettlementService.java`](../../../tianjitown-integrations/src/main/java/cn/tianji/town/integrations/vault/VaultSettlementService.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线：49）
- 可见行号：无

## 可见文本位置

以下为迁移前的原基线行号，范围表示包含首尾行：

`25, 32, 39, 43, 52, 55, 57, 62, 74, 82, 84, 92, 105, 108, 111, 117, 120, 123, 129, 132, 136-137, 144, 156, 159, 162-163, 170, 183, 186, 189, 195, 198, 201, 207, 210, 213, 220, 235, 238, 240-241, 267, 273, 285, 293, 298, 308, 313`

处理后源文件已无非注释可见中文文本。

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 为 `VaultSettlementService` 增加消息 resolver 注入；生产 Paper 运行时由 `TownRuntime` 传入 `plugin.messages()::plainText`，三参数构造器保留稳定消息键回退，integrations 不读取 Paper 配置。
- 将账户校验、Vault provider 探测、清算账户初始化、余额读取、玩家扣款/退款、清算账户转账/调账、补偿和主线程约束等固定文案迁入 `validation.vault.*`、`diagnostic.vault.*` 与 `log.vault.settlement.*`。动态账户名、Vault 返回错误和异常详情使用命名占位符。
- 账户原始名称仍用于 Vault/QuickShop 稳定身份；只有进入消息的账户名、第三方返回值和异常详情会转义 `&`/`§`，避免污染纯文本日志。`Result.message()` 仍在事件处理时解析，供当前账本/补偿流程保存事件发生时的详情快照。
- `VaultSettlementServiceTest` 覆盖 resolver 输出、稳定键回退、provider 故障和第三方详情转义；`PluginMessagesTest` 覆盖全部新增键的加载、占位符渲染及重载后的用户覆盖值。
- 验证通过：`mvn -pl tianjitown-integrations -am test`、`mvn -pl tianjitown-paper -am '-Dtest=PluginMessagesTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`、根项目 `mvn test`（Core 24、Storage 40、Integrations 31、Paper 103）。目标源文件重新扫描后可见文本行数为 0。
- 例外：无。按目录规则，本任务未重新生成全局 `docs/CHINESE_CODE_LINES.md`。

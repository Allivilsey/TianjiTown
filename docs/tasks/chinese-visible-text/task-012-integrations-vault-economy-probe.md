# TASK-CN-012：`VaultEconomyProbe.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-integrations`
- 源文件：[`tianjitown-integrations/src/main/java/cn/tianji/town/integrations/vault/VaultEconomyProbe.java`](../../../tianjitown-integrations/src/main/java/cn/tianji/town/integrations/vault/VaultEconomyProbe.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线：3）
- 可见行号：无（重新扫描后无非注释可见中文）

## 可见文本位置

以下行号为迁移前的原基线，范围表示包含首尾行；处理后源文件已无非注释可见中文：

`19, 24, 26`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 为 `VaultEconomyProbe` 注入 `BiFunction<String, Map<String, ?>, String>` 消息解析器；Paper 启动门禁传入 `messages()::plainText`，探针结果在使用时解析配置，integrations 不读取 Paper 配置。
- 新增默认消息键：`diagnostic.vault.economy-not-registered`、`diagnostic.vault.economy-provider-enabled`、`diagnostic.vault.economy-provider-disabled`、`diagnostic.vault.economy-probe-failure`。
- Vault 未注册 Economy、provider 未启用和探测异常的固定上下文均迁入 `messages.yml`；第三方 provider 名称与异常详情分别作为结果字段和 `{detail}` 传递，并在进入 Paper 诊断输出前转义 `&`/`§`。解析器异常时仅回退到稳定消息键。
- `VaultEconomyProbeTest` 覆盖四类探测结果、provider 名称与健康状态、第三方动态值转义及稳定键回退；`PluginMessagesTest` 覆盖默认键完整渲染、占位符无残留和 `messages.yml` 重载后的用户覆盖值。
- 验证通过：`mvn -pl tianjitown-integrations -am test`、`mvn -pl tianjitown-paper -am '-Dtest=PluginMessagesTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`、根项目 `mvn test`（Core 24、Storage 40、Integrations 29、Paper 101）。目标源文件重新扫描后可见文本行数为 0。
- 例外：无。按目录规则，本任务未重新生成全局 `docs/CHINESE_CODE_LINES.md`。

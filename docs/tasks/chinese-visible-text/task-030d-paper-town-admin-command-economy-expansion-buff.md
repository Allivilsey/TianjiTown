# TASK-CN-030D：`TownAdminCommand.java`—资金、税率、账本、扩张与 Buff

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：资金、税率、账本、扩张与 Buff
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCommand.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCommand.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 32 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号为迁移前的原基线，范围表示包含首尾行；处理后目标位置已无非注释可见中文：

`680, 693, 700, 704, 711, 716, 718, 726, 737, 739, 759, 777, 788, 812, 828, 830, 838, 843-844, 859, 869-870, 894, 914, 919, 951, 957, 961-962, 969, 984-985`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 资金、税率、账本、扩张和 Buff 命令的用法、参数错误及不支持动作迁入 `chat.admin.usage-money-root`、`chat.admin.usage-money`、`chat.admin.usage-money-adjust`、`chat.admin.money-adjust-zero`、`chat.admin.money-action-unsupported`、`chat.admin.usage-tax`、`chat.admin.tax-action-unsupported`、`chat.admin.usage-ledger`、`chat.admin.ledger-action-unsupported`、`chat.admin.usage-expand`、`chat.admin.expand-direction-required`、`chat.admin.expand-action-unsupported`、`chat.admin.usage-buff-root`、`chat.admin.usage-buff-list`、`chat.admin.usage-buff-grant`、`chat.admin.buff-purchase-paused` 和 `chat.admin.buff-action-unsupported`；配置中的命令子命令、方向和 `buffKey` 机器值保持不变。
- Buff 管理员代购确认改为 `chat.admin.buff-purchase-confirmation`，使用 `{town}` 和 `{buff}` 占位符，在创建确认时读取当前消息配置；外部/配置文本经安全处理后再渲染。
- 共享校验文本使用 `chat.admin.town-not-found-generic`、既有的 `chat.admin.town-not-found`、`chat.admin.town-archived`、`chat.admin.no-operable-town`、`chat.admin.town-record-not-found`、`chat.admin.permission-missing` 和 `chat.admin.confirmation-stale`；`requireLength` 已由消息键校验替代。删除前已确认 `reasonTail` 无调用者，移除其不可达的“必须填写原因/实际原因”文本，不保留隐藏回退。
- 测试：`PluginMessagesTest` 新增 030D 全部键的非空、完整占位符渲染和 `messages.yml` 重载覆盖验证；定向测试 46 项通过，根项目完整测试 Core 24、Storage 40、Integrations 33、Paper 152 项全部通过。
- 扫描：当前 `TownAdminCommand.java` 的 030D 实现区间（当前 718–1020 行）无非注释中文，旧 Java 模板全文无残留；剩余中文仅为 030E 帮助页范围。未重新生成全局 `docs/CHINESE_CODE_LINES.md`，也未处理 030E 或其他任务范围。

# TASK-CN-018：`DonationCompensationCoordinator.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/DonationCompensationCoordinator.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/DonationCompensationCoordinator.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 3 行，已迁移）
- 可见行号：`53, 81, 106`

## 可见文本位置

以下行号按源文件当前版本记录，范围表示包含首尾行：

`53, 81, 106`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 将捐款自动补偿操作校验、Vault 补偿调用异常和补偿成功后的账本原因分别迁入 `validation.donation.compensation-operation`、`diagnostic.donation.compensation-call-failure` 与 `log.donation.compensation-resolved`。
- `DonationCompensationCoordinator` 注入 `BiFunction<String, Map<String, ?>, String>` 消息解析器；生产运行时使用 `plugin.messages()::plainText`，在每次实际重试和账本收尾时解析，账本保留事件发生时的文案快照。旧构造器仅回退稳定消息键，不作为生产文案来源。
- 外部异常详情在进入消息占位符前转义 `&`/`§`；补偿状态机、重试次数、幂等键和持久化锁逻辑保持不变。
- 测试覆盖三枚默认键的完整渲染、消息重载覆盖、解析器注入、异常详情隔离及收尾账本快照。
- 验证通过：定向 `DonationCompensationCoordinatorTest`/`PluginMessagesTest` 共 32 项、`mvn -B -pl tianjitown-paper -am test`（Paper 117 项）、根项目 `mvn -B test` 及干净构建 `mvn -B clean test`（Core 24、Storage 40、Integrations 33、Paper 117）均通过；目标源文件复扫后本任务 3 个命中位置无非注释可见中文。
- 例外：未重新生成全局 `docs/CHINESE_CODE_LINES.md`；源文件中剩余的两条构造参数校验文案对应原清单的 `❌` 行 40、43，不是玩家/管理员可见文本；`TownRuntime.java` 中补偿协调器周边日志属于 TASK-CN-036A/036G，未在本任务范围内迁移。

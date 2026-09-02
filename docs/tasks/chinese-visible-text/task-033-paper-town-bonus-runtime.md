# TASK-CN-033：`TownBonusRuntime.java` 可见文本

- 状态：已完成
- 模块：`tianjitown-paper`
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownBonusRuntime.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownBonusRuntime.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 11 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号按源文件当前版本记录，范围表示包含首尾行：

`116, 134, 152, 164, 237-238, 376, 436, 497, 521, 548`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本文件，确保行号和数量更新准确。

## 处理记录

- 将索引刷新、建筑返还计数、信标对象失效、托管效果清理和诊断报告写入失败日志迁入 `log.bonus.index-refresh-failure`、`log.bonus.refund-counter-cleanup-failure`、`log.bonus.beacon-refresh-object-failure`、`log.bonus.beacon-record-object-failure`、`log.bonus.beacon-cleanup-object-failure`、`log.bonus.diagnostic-report-write-failure`；定时备份成功日志使用 `log.bonus.scheduled-backup-success`，异常详情和文件路径分别作为 `{detail}`、`{file}` 传入。
- 将备份聊天结果改为完整模板：`chat.bonus.backup-result-success` 负责带文件路径的结果，`chat.bonus.backup-result-success-no-file` 负责无文件结果，避免在 Java 中拼接“；文件=”片段。动态异常、结果和路径在进入消息解析前隔离 `&`/`§`。
- 将诊断报告中的 Vault 清算账户不可读和 QuickShop 历史不可比固定行迁入 `diagnostic.bonus.settlement-account-unavailable`、`diagnostic.bonus.quick-shop-reconciliation-incomplete`；两项在诊断收尾时解析后同时作为管理员输出和报告快照，报告不会因后续重载改写历史文本。
- 测试：新增 `TownBonusRuntimeMessagesTest`，覆盖全部 task-033 消息键的完整渲染、无缺失消息/占位符残留，以及 `messages.yml` 重载后的日志和备份聊天覆盖。
- 验证通过：`mvn -B -pl tianjitown-paper -am "-Dtest=TownBonusRuntimeMessagesTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`（2/2）、`mvn -B -pl tianjitown-paper -am test`（Core 24、Storage 40、Integrations 33、Paper 158）、`mvn -B test`（同上）。目标源文件重新扫描后非注释可见中文为 0 行。
- 发现并修复 `messages.yml` 既有 `log.scheduler.periodic` 五个键多一个空格的 YAML 语法问题；仅统一缩进，未改变这些键的名称或值。目标源文件保留第七版信标恢复技术注释，该注释不进入玩家、管理员或日志输出。
- 未重新生成全局 `docs/CHINESE_CODE_LINES.md`，未处理 task-033 以外的任务或源文件。

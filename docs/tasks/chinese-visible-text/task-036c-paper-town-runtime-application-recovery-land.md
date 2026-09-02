# TASK-CN-036C：`TownRuntime.java`—失败申请恢复、传送点与领地对账

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：失败申请恢复、传送点与领地对账
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 27 行，已迁移）
- 可见行号：无

## 可见文本位置

以下行号按源文件当前版本记录，范围表示包含首尾行：

`793, 808-809, 814-815, 824-825, 831, 840, 844-845, 848, 853-855, 861, 871, 884-885, 896-897, 916, 923, 928, 959, 976, 979`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 将失败申请恢复的验证、清理、退款和数据库确认结果迁移到 `dialog.provision.recovery-*`；异常详情使用命名占位符并在进入消息前进行安全文本处理。恢复原因和外部 Residence 清理日志迁移到 `log.provision.recovery-*`，传送点与领地对账审计状态/写入失败迁移到 `log.residence.*`。
- Residence API 异常改用已有 `RESIDENCE_API_UNAVAILABLE` 结构化结果码；审计 reason/detail 在事件发生时通过当前 `PluginMessages.plainText` 解析为快照，保留原有恢复状态机、幂等键和审计动作标识。
- 新增默认键：`dialog.provision.recovery-refresh-action`、`dialog.provision.recovery-inspection-failed-detail`、`dialog.provision.recovery-verify-action`、`dialog.provision.recovery-healthy-projection-detail`、`dialog.provision.recovery-healthy-projection-action`、`dialog.provision.recovery-control-check-failed-detail`、`dialog.provision.recovery-cleanup-failed-detail`、`dialog.provision.recovery-cleanup-api-failed-detail`、`dialog.provision.recovery-cleanup-action`、`dialog.provision.recovery-refund-failed-detail`、`dialog.provision.recovery-refund-action`、`dialog.provision.recovery-refund-confirmation-failed-detail`、`dialog.provision.recovery-refund-confirmation-action`、`log.provision.recovery-external-residence`、`log.provision.recovery-projection-cleaned`、`log.provision.recovery-unlock-reason`、`log.provision.recovery-cancel-refund-reason`、`log.provision.recovery-force-cleanup-reason`、`log.provision.recovery-reason-with-inspection`、`log.residence.teleport-point-audit-success`、`log.residence.teleport-point-audit-failure`、`log.residence.teleport-point-audit-write-failure`、`log.residence.reconciliation-audit-success`、`log.residence.reconciliation-audit-failure`、`log.residence.reconciliation-audit-write-failure`。
- 测试：新增 `TownRuntimeRecoveryMessagesTest`，覆盖全部新增键的非空、完整占位符渲染、无缺失配置/残留占位符，以及 `messages.yml` 重载后的自定义文案。
- 验证通过：`mvn -B -pl tianjitown-paper -am clean "-Dtest=TownRuntimeRecoveryMessagesTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`（2/2）；`mvn -B -pl tianjitown-paper -am test`（Core 24、Storage 40、Integrations 33、Paper 168）；`mvn -B test`（Core 24、Storage 40、Integrations 33、Paper 168）。目标恢复、传送点和领地对账方法重新扫描后非注释可见中文为 0 行。
- 无技术例外。本次仅完成 TASK-CN-036C，未处理其他任务，也未重新生成全局 `docs/CHINESE_CODE_LINES.md`。

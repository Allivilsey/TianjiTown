# TASK-CN-036B：`TownRuntime.java`—投票结算与建镇审批

- 状态：已完成
- 模块：`tianjitown-paper`
- 功能分组：投票结算与建镇审批
- 源文件：[`tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java`](../../../tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java)
- 来源：[`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md)
- 可见文本行数：**0**（原基线 45 行，已迁移）
- 可见行号：无

## 可见文本位置

以下为迁移前源文件的原基线行号，范围表示包含首尾行：

`486, 502, 506-507, 512, 518, 532, 538-539, 545, 553-554, 569-570, 577, 583-584, 597-598, 608, 614-615, 624, 632, 641-642, 661-662, 667, 676, 683-684, 690, 696, 709, 715, 721, 729-731, 733, 755, 759, 764, 769`

## 完成检查

- [x] 逐项检查上述可见文本，确认文案符合当前产品语义。
- [x] 若需改造文案，遵循项目现有消息配置与可见性约定。
- [x] 处理后重新统计本功能分组，确保行号和数量更新准确。

## 处理记录

- 投票结算失败日志复用 `log.scheduler.periodic.vote-settlement-failure`；建镇审批开始、数据库准备、领地投影、退款失败、投影异常和 UI 回调日志新增 `log.provision.*`，统一经当前 `PluginMessages.plainText` 解析，动态值使用命名占位符。
- 建镇审批的失败详情与恢复建议迁入 `dialog.provision.*`；`ProvisionResult.MessageRef` 支持消息键及占位符，管理员 Dialog 展示时按当前消息配置解析，满足热重载后后续输出使用新文案。外部异常、支付详情和领地诊断进入模板前均做安全文本处理；写入数据库的最终结果保留事件时的纯文本快照。
- 新增默认键：`log.provision.approval-started`、`log.provision.database-prepared`、`log.provision.projection-started`、`log.provision.refund-failure`、`log.provision.projection-exception`、`log.provision.ui-callback`、`dialog.provision.storage-unavailable-detail`、`dialog.provision.storage-unavailable-recovery-action`、`dialog.provision.busy-detail`、`dialog.provision.application-not-found-detail`、`dialog.provision.residence-check-recovery-action`、`dialog.provision.lifecycle-start-failed-detail`、`dialog.provision.lifecycle-recovery-action`、`dialog.provision.refresh-application-action`、`dialog.provision.site-validation-failed-detail`、`dialog.provision.site-validation-recovery-action`、`dialog.provision.residence-name-conflict-detail`、`dialog.provision.residence-name-conflict-recovery-action`、`dialog.provision.fee-failed-detail`、`dialog.provision.fee-failed-recovery-action`、`dialog.provision.data-write-recovery-action`、`dialog.provision.preparation-write-failed-detail`、`dialog.provision.projection-start-failed-detail`、`dialog.provision.result-read-failed-detail`、`dialog.provision.projection-save-failed-detail`、`dialog.provision.projection-result-recovery-action`、`dialog.provision.residence-retry-action`、`dialog.provision.default-teleport-world-unloaded-detail`、`dialog.provision.default-teleport-height-invalid-detail`、`dialog.provision.default-teleport-space-invalid-detail`、`dialog.provision.default-teleport-failed-rolled-back-detail`、`dialog.provision.default-teleport-failed-rollback-failed-detail`、`dialog.provision.land-created-with-default-teleport-detail`、`dialog.provision.retry-approval-action`、`dialog.provision.refresh-state-action`；保留并继续使用既有 `dialog.provision.success-detail`、`dialog.provision.busy-recovery-action`、`dialog.provision.timeout-detail`、`dialog.provision.timeout-recovery-action`。
- 内部“尚未创建临时小镇”状态改用 `MISSING_TEMPORARY_TOWN` 哨兵，不作为用户或管理员可见输出。
- 测试：新增 `TownRuntimeProvisionMessagesTest`，覆盖投票/审批日志、全部 036B 新增建镇详情键的占位符完整性与无残留占位符，并验证 `ProvisionResult.MessageRef` 在 `messages.yml` reload 后读取自定义文案；相关 `ProvisionResultTest`、`TownRuntimeMessagesTest` 和 `PluginMessagesTest` 一并通过。
- 验证通过：`mvn -B -pl tianjitown-paper -am clean "-Dtest=TownRuntimeProvisionMessagesTest,ProvisionResultTest,TownRuntimeMessagesTest,PluginMessagesTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`（Paper 55 个定向测试）；`mvn -B test`（Core 24、Storage 40、Integrations 33、Paper 166）。036B 目标方法重新扫描后非注释可见中文为 0 行。
- 无技术例外。本次仅完成 TASK-CN-036B，未处理 036C～036G，也未重新生成全局 `docs/CHINESE_CODE_LINES.md`。

# 可见中文文本文件级任务

本目录依据 [`docs/CHINESE_CODE_LINES.md`](../../CHINESE_CODE_LINES.md) 的“可见性标记”拆分生成。每个任务文件对应一个源文件，仅包含原清单标记为 `✅` 的可见中文文本行；`❌` 行不纳入任务。

## 汇总

- 文件数：**39**
- 可见文本行数：**972**
- 统计基准：原清单中的行号范围按首尾均包含计算。

| 模块 | 文件数 | 可见文本行数 |
| --- | ---: | ---: |
| `tianjitown-core` | 4 | 23 |
| `tianjitown-integrations` | 10 | 160 |
| `tianjitown-paper` | 25 | 789 |

## 文件级任务

| ID | 模块 | 源文件 | 可见文本行数 | 任务文件 |
| --- | --- | --- | ---: | --- |
 | TASK-CN-001 | `tianjitown-core` | `tianjitown-core/src/main/java/cn/tianji/town/core/application/ApplicationText.java` | 10 | [`task-001-core-application-text.md`](task-001-core-application-text.md) |
 | TASK-CN-002 | `tianjitown-core` | `tianjitown-core/src/main/java/cn/tianji/town/core/consumption/BuffDurationOption.java` | 4 | [`task-002-core-buff-duration-option.md`](task-002-core-buff-duration-option.md) |
 | TASK-CN-003 | `tianjitown-core` | `tianjitown-core/src/main/java/cn/tianji/town/core/land/ExpansionDirection.java` | 4 | [`task-003-core-expansion-direction.md`](task-003-core-expansion-direction.md) |
 | TASK-CN-004 | `tianjitown-core` | `tianjitown-core/src/main/java/cn/tianji/town/core/ports/LandProtectionService.java` | 5 | [`task-004-core-land-protection-service.md`](task-004-core-land-protection-service.md) |
 | TASK-CN-005 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/globalmarketplus/GlobalMarketPlusIncomeTaxAdapter.java` | 7 | [`task-005-integrations-global-market-plus-income-tax-adapter.md`](task-005-integrations-global-market-plus-income-tax-adapter.md) |
 | TASK-CN-006 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/jobs/JobsIncomeTaxAdapter.java` | 8 | [`task-006-integrations-jobs-income-tax-adapter.md`](task-006-integrations-jobs-income-tax-adapter.md) |
 | TASK-CN-007 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/quickshop/QuickShopHistoryProbe.java` | 6 | [`task-007-integrations-quick-shop-history-probe.md`](task-007-integrations-quick-shop-history-probe.md) |
 | TASK-CN-008 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/quickshop/QuickShopTaxAdapter.java` | 11 | [`task-008-integrations-quick-shop-tax-adapter.md`](task-008-integrations-quick-shop-tax-adapter.md) |
 | TASK-CN-009 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/residence/ResidenceCommandGuard.java` | 2 | [`task-009-integrations-residence-command-guard.md`](task-009-integrations-residence-command-guard.md) |
 | TASK-CN-010 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/residence/ResidenceDeletionGuard.java` | 4 | [`task-010-integrations-residence-deletion-guard.md`](task-010-integrations-residence-deletion-guard.md) |
 | TASK-CN-011 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/residence/ResidenceLandProtectionService.java` | 64 | [`task-011-integrations-residence-land-protection-service.md`](task-011-integrations-residence-land-protection-service.md) |
 | TASK-CN-012 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/vault/VaultEconomyProbe.java` | 3 | [`task-012-integrations-vault-economy-probe.md`](task-012-integrations-vault-economy-probe.md) |
 | TASK-CN-013 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/vault/VaultSettlementService.java` | 49 | [`task-013-integrations-vault-settlement-service.md`](task-013-integrations-vault-settlement-service.md) |
 | TASK-CN-014 | `tianjitown-integrations` | `tianjitown-integrations/src/main/java/cn/tianji/town/integrations/worldborder/WorldBorderBoundaryService.java` | 6 | [`task-014-integrations-world-border-boundary-service.md`](task-014-integrations-world-border-boundary-service.md) |
 | TASK-CN-015 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/BuffRuntime.java` | 21 | [`task-015-paper-buff-runtime.md`](task-015-paper-buff-runtime.md) |
 | TASK-CN-016 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/BuffSettings.java` | 10 | [`task-016-paper-buff-settings.md`](task-016-paper-buff-settings.md) |
 | TASK-CN-017 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/ConfigurationValues.java` | 15 | [`task-017-paper-configuration-values.md`](task-017-paper-configuration-values.md) |
 | TASK-CN-018 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/DonationCompensationCoordinator.java` | 3 | [`task-018-paper-donation-compensation-coordinator.md`](task-018-paper-donation-compensation-coordinator.md) |
 | TASK-CN-019 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/EconomySettings.java` | 8 | [`task-019-paper-economy-settings.md`](task-019-paper-economy-settings.md) |
 | TASK-CN-020 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/GovernanceSettings.java` | 2 | [`task-020-paper-governance-settings.md`](task-020-paper-governance-settings.md) |
 | TASK-CN-021 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/OnlineBackupService.java` | 8 | [`task-021-paper-online-backup-service.md`](task-021-paper-online-backup-service.md) |
 | TASK-CN-022 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/PluginMessages.java` | 6 | [`task-022-paper-plugin-messages.md`](task-022-paper-plugin-messages.md) |
 | TASK-CN-023 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/ProvisionResult.java` | 3 | [`task-023-paper-provision-result.md`](task-023-paper-provision-result.md) |
 | TASK-CN-024 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/RuleEditorDialogRenderer.java` | 5 | [`task-024-paper-rule-editor-dialog-renderer.md`](task-024-paper-rule-editor-dialog-renderer.md) |
 | TASK-CN-025 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/RuntimeConfigurationValidator.java` | 10 | [`task-025-paper-runtime-configuration-validator.md`](task-025-paper-runtime-configuration-validator.md) |
 | TASK-CN-026 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/SitePolicy.java` | 9 | [`task-026-paper-site-policy.md`](task-026-paper-site-policy.md) |
 | TASK-CN-027 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TerritoryService.java` | 12 | [`task-027-paper-territory-service.md`](task-027-paper-territory-service.md) |
 | TASK-CN-028 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TianjiTownPlugin.java` | 53 | [`task-028-paper-tianji-town-plugin.md`](task-028-paper-tianji-town-plugin.md) |
 | TASK-CN-029 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownActions.java` | 7 | [`task-029-paper-town-actions.md`](task-029-paper-town-actions.md) |
 | TASK-CN-030 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCommand.java` | 82 | [`task-030-paper-town-admin-command.md`](task-030-paper-town-admin-command.md) |
 | TASK-CN-031 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminCompletionEngine.java` | 3 | [`task-031-paper-town-admin-completion-engine.md`](task-031-paper-town-admin-completion-engine.md) |
 | TASK-CN-032 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownAdminTabCompleter.java` | 2 | [`task-032-paper-town-admin-tab-completer.md`](task-032-paper-town-admin-tab-completer.md) |
 | TASK-CN-033 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownBonusRuntime.java` | 11 | [`task-033-paper-town-bonus-runtime.md`](task-033-paper-town-bonus-runtime.md) |
 | TASK-CN-034 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownBonusSettings.java` | 19 | [`task-034-paper-town-bonus-settings.md`](task-034-paper-town-bonus-settings.md) |
 | TASK-CN-035 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownCommandParser.java` | 22 | [`task-035-paper-town-command-parser.md`](task-035-paper-town-command-parser.md) |
 | TASK-CN-036 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownRuntime.java` | 152 | [`task-036-paper-town-runtime.md`](task-036-paper-town-runtime.md) |
 | TASK-CN-037 | `tianjitown-paper` | `tianjitown-paper/src/main/java/cn/tianji/town/paper/TownUiController.java` | 315 | [`task-037-paper-town-ui-controller.md`](task-037-paper-town-ui-controller.md) |
 | TASK-CN-038 | `tianjitown-paper` | `tianjitown-paper/src/main/resources/config.yml` | 2 | [`task-038-paper-config.md`](task-038-paper-config.md) |
 | TASK-CN-039 | `tianjitown-paper` | `tianjitown-paper/src/main/resources/plugin.yml` | 9 | [`task-039-paper-plugin.md`](task-039-paper-plugin.md) |

## 使用说明

1. 按任务文件逐个检查源文件中的可见文本。
2. 源文件发生改动后，以源文件当前行号重新核对任务文件，不要机械沿用旧行号。
3. 完成全部任务后，重新运行与原清单相同的统计规则，并核对总数。

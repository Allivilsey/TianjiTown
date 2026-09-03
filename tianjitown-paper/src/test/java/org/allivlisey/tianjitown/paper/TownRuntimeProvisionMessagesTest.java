package org.allivlisey.tianjitown.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TownRuntimeProvisionMessagesTest {
    private static final List<String> PROVISION_KEYS = List.of(
            "dialog.provision.success-detail",
            "dialog.provision.busy-recovery-action",
            "dialog.provision.timeout-detail",
            "dialog.provision.timeout-recovery-action",
            "dialog.provision.storage-unavailable-detail",
            "dialog.provision.storage-unavailable-recovery-action",
            "dialog.provision.busy-detail",
            "dialog.provision.application-not-found-detail",
            "dialog.provision.residence-check-recovery-action",
            "dialog.provision.lifecycle-start-failed-detail",
            "dialog.provision.lifecycle-recovery-action",
            "dialog.provision.refresh-application-action",
            "dialog.provision.site-validation-failed-detail",
            "dialog.provision.site-validation-recovery-action",
            "dialog.provision.residence-name-conflict-detail",
            "dialog.provision.residence-name-conflict-recovery-action",
            "dialog.provision.fee-failed-detail",
            "dialog.provision.fee-failed-recovery-action",
            "dialog.provision.data-write-recovery-action",
            "dialog.provision.preparation-write-failed-detail",
            "dialog.provision.projection-start-failed-detail",
            "dialog.provision.result-read-failed-detail",
            "dialog.provision.projection-save-failed-detail",
            "dialog.provision.projection-result-recovery-action",
            "dialog.provision.residence-retry-action",
            "dialog.provision.default-teleport-world-unloaded-detail",
            "dialog.provision.default-teleport-height-invalid-detail",
            "dialog.provision.default-teleport-space-invalid-detail",
            "dialog.provision.default-teleport-failed-rolled-back-detail",
            "dialog.provision.default-teleport-failed-rollback-failed-detail",
            "dialog.provision.land-created-with-default-teleport-detail",
            "dialog.provision.retry-approval-action",
            "dialog.provision.refresh-state-action");

    private static final List<String> PROVISION_LOG_KEYS = List.of(
            "log.scheduler.periodic.vote-settlement-failure",
            "log.provision.approval-started",
            "log.provision.database-prepared",
            "log.provision.projection-started",
            "log.provision.refund-failure",
            "log.provision.projection-exception",
            "log.provision.ui-callback");

    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersProvisionDetailsAndLogsWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.of(
                "application", "application-1",
                "time", "2026-09-03T03:00:00Z",
                "detail", "Vault 余额不足",
                "cleanup", "Residence 清理失败",
                "success", true);

        assertEquals("投票结算失败，后续周期仍会继续尝试: Vault 余额不足",
                messages.plainText("log.scheduler.periodic.vote-settlement-failure", placeholders));
        assertEquals("建镇审批开始 application=application-1 at=2026-09-03T03:00:00Z",
                messages.plainText("log.provision.approval-started", placeholders));
        assertEquals("建镇数据库准备完成 application=application-1 at=2026-09-03T03:00:00Z",
                messages.plainText("log.provision.database-prepared", placeholders));
        assertEquals("建镇领地投影开始 application=application-1 at=2026-09-03T03:00:00Z",
                messages.plainText("log.provision.projection-started", placeholders));
        assertEquals("建镇申请数据库写入失败且申请费自动返还失败: Vault 余额不足",
                messages.plainText("log.provision.refund-failure", placeholders));
        assertEquals("建镇领地投影异常 application=application-1: Vault 余额不足",
                messages.plainText("log.provision.projection-exception", placeholders));
        assertEquals("建镇 UI 回调 application=application-1 at=2026-09-03T03:00:00Z success=true",
                messages.plainText("log.provision.ui-callback", placeholders));

        assertEquals("&f批准前选址复核失败: Vault 余额不足",
                messages.rawText("dialog.provision.site-validation-failed-detail", placeholders));
        assertEquals("&f默认传送点设置失败: Vault 余额不足；新建投影已回滚",
                messages.rawText("dialog.provision.default-teleport-failed-rolled-back-detail",
                        placeholders));
        assertEquals("&f默认传送点设置失败: Vault 余额不足；新建投影回滚失败: Residence 清理失败",
                messages.rawText("dialog.provision.default-teleport-failed-rollback-failed-detail",
                        placeholders));
        assertEquals("&fVault 余额不足；已设置安全默认传送点",
                messages.rawText("dialog.provision.land-created-with-default-teleport-detail",
                        placeholders));

        for (String key : PROVISION_KEYS) {
            String rendered = messages.rawText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
        for (String key : PROVISION_LOG_KEYS) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void resolvesConfiguredProvisionResultDetailsAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        ProvisionResult result = ProvisionResult.failure(null,
                ProvisionResult.MessageRef.configured(
                        "dialog.provision.fee-failed-detail",
                        Map.of("detail", "Vault 余额不足")),
                ProvisionResult.MessageRef.configured(
                        "dialog.provision.fee-failed-recovery-action"));

        assertEquals("&f申请费扣取失败: Vault 余额不足", result.detail(messages));
        assertEquals("&7确认申请人余额后返回审核列表重试", result.recoveryAction(messages));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("dialog.provision.fee-failed-detail", "&b自定义扣费失败: {detail}");
        configuration.set("dialog.provision.fee-failed-recovery-action", "&e自定义余额恢复操作");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("&b自定义扣费失败: Vault 余额不足", result.detail(messages));
        assertEquals("&e自定义余额恢复操作", result.recoveryAction(messages));
    }
}

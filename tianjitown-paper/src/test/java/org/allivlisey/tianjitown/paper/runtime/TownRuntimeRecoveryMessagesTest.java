package org.allivlisey.tianjitown.paper.runtime;
import org.allivlisey.tianjitown.paper.message.PluginMessages;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TownRuntimeRecoveryMessagesTest {
    private static final List<String> DIALOG_KEYS = List.of(
            "dialog.provision.recovery-refresh-action",
            "dialog.provision.recovery-inspection-failed-detail",
            "dialog.provision.recovery-verify-action",
            "dialog.provision.recovery-healthy-projection-detail",
            "dialog.provision.recovery-healthy-projection-action",
            "dialog.provision.recovery-control-check-failed-detail",
            "dialog.provision.recovery-cleanup-failed-detail",
            "dialog.provision.recovery-cleanup-api-failed-detail",
            "dialog.provision.recovery-cleanup-action",
            "dialog.provision.recovery-refund-failed-detail",
            "dialog.provision.recovery-refund-action",
            "dialog.provision.recovery-refund-confirmation-failed-detail",
            "dialog.provision.recovery-refund-confirmation-action");

    private static final List<String> LOG_KEYS = List.of(
            "log.provision.recovery-external-residence",
            "log.provision.recovery-projection-cleaned",
            "log.provision.recovery-unlock-reason",
            "log.provision.recovery-cancel-refund-reason",
            "log.provision.recovery-force-cleanup-reason",
            "log.provision.recovery-reason-with-inspection",
            "log.residence.teleport-point-audit-success",
            "log.residence.teleport-point-audit-failure",
            "log.residence.teleport-point-audit-write-failure",
            "log.residence.reconciliation-audit-success",
            "log.residence.reconciliation-audit-failure",
            "log.residence.reconciliation-audit-write-failure");

    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersRecoveryTeleportAndReconciliationMessages() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.of(
                "application", "application-1",
                "detail", "Residence API 异常",
                "reason", "管理员取消创建失败申请并退款",
                "inspection", "投影边界异常");

        assertEquals("&f无法验证失败申请的 Residence 投影: Residence API 异常",
                messages.rawText("dialog.provision.recovery-inspection-failed-detail",
                        placeholders));
        assertFalse(messages.rawText("dialog.provision.recovery-healthy-projection-detail",
                placeholders).isBlank());
        assertEquals("&f无法确认异常 Residence 是否属于系统: Residence API 异常",
                messages.rawText("dialog.provision.recovery-control-check-failed-detail",
                        placeholders));
        assertEquals("&f检测到异常 Residence 投影，且无法安全移除: Residence API 异常",
                messages.rawText("dialog.provision.recovery-cleanup-failed-detail",
                        placeholders));
        assertEquals("&f检测到异常 Residence 投影，且无法安全移除: 清理失败投影时 Residence API 异常: Residence API 异常",
                messages.rawText("dialog.provision.recovery-cleanup-api-failed-detail",
                        placeholders));
        assertEquals("&f临时数据已安全清理，但申请费退款失败: Residence API 异常",
                messages.rawText("dialog.provision.recovery-refund-failed-detail",
                        placeholders));
        assertEquals("&fVault 已退款但数据库确认失败: Residence API 异常",
                messages.rawText("dialog.provision.recovery-refund-confirmation-failed-detail",
                        placeholders));
        assertEquals("管理员取消创建失败申请并退款；外部检查=投影边界异常",
                messages.plainText("log.provision.recovery-reason-with-inspection",
                        placeholders));
        assertEquals("失败申请发现同名外部 Residence，保留外部领地并继续回滚申请数据 application=application-1",
                messages.plainText("log.provision.recovery-external-residence", placeholders));
        assertEquals("失败建镇投影已按受控边界安全清理 application=application-1: Residence API 异常",
                messages.plainText("log.provision.recovery-projection-cleaned", placeholders));
        assertEquals("写入传送点审计失败: Residence API 异常",
                messages.plainText("log.residence.teleport-point-audit-write-failure", placeholders));
        assertEquals("写入领地对账审计失败: Residence API 异常",
                messages.plainText("log.residence.reconciliation-audit-write-failure", placeholders));

        for (String key : DIALOG_KEYS) {
            String rendered = messages.rawText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
        for (String key : LOG_KEYS) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void usesRecoveryOverridesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("dialog.provision.recovery-refund-failed-detail",
                "&b自定义退款失败: {detail}");
        configuration.set("log.provision.recovery-reason-with-inspection",
                "自定义恢复原因: {reason}|检查={inspection}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("&b自定义退款失败: Vault 不可用",
                messages.rawText("dialog.provision.recovery-refund-failed-detail",
                        Map.of("detail", "Vault 不可用")));
        assertEquals("自定义恢复原因: 管理员解锁|检查=投影异常",
                messages.plainText("log.provision.recovery-reason-with-inspection",
                        Map.of("reason", "管理员解锁", "inspection", "投影异常")));
    }
}

package org.allivlisey.tianjitown.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TownRuntimeMessagesTest {
    private static final List<String> RUNTIME_KEYS = List.of(
            "log.scheduler.lifecycle-stopped",
            "log.scheduler.quick-shop-tax-refresh-failure",
            "log.lifecycle.sqlite-recovered",
            "log.lifecycle.sqlite-interrupted",
            "log.lifecycle.interrupted-provision-reason",
            "log.lifecycle.interrupted-provisions-recovered",
            "log.lifecycle.interrupted-provision-recovery-failure",
            "log.donation.refund-retry-failed",
            "log.donation.refund-finalization-failed",
            "log.donation.refund-recovered",
            "log.donation.refund-exhausted",
            "log.donation.settlement-balance-read-failure",
            "log.donation.settlement-shortfall",
            "log.donation.settlement-reconciliation-failure",
            "log.residence.reconciliation-failure",
            "log.residence.reconciliation-difference",
            "log.residence.reconciliation-sqlite-read-failure",
            "log.residence.automatic-repair-cancelled",
            "log.residence.automatic-repair-delayed",
            "log.residence.automatic-repair-sqlite-read-failure",
            "log.residence.automatic-repair-api-failure",
            "log.residence.automatic-repair-consistent",
            "log.residence.automatic-repair-completed",
            "log.residence.automatic-repair-failed");

    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersInitializationRecoveryCompensationAndReconciliationMessages() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.ofEntries(
                Map.entry("attempt", 2),
                Map.entry("attempts", 3),
                Map.entry("count", 4),
                Map.entry("operation", "op-1"),
                Map.entry("external", 1200),
                Map.entry("required", 1300),
                Map.entry("town", "town-1"),
                Map.entry("detail", "boom"),
                Map.entry("inspection", "检查异常"),
                Map.entry("repair", "修复失败"));

        assertEquals("插件生命周期已停止",
                messages.plainText("log.scheduler.lifecycle-stopped"));
        assertEquals("刷新 QuickShop 税率内存映射失败: boom",
                messages.plainText("log.scheduler.quick-shop-tax-refresh-failure", placeholders));
        assertEquals("SQLite 连接已恢复，写操作重新开放。",
                messages.plainText("log.lifecycle.sqlite-recovered"));
        assertEquals("SQLite 连接中断，写操作已锁定；Residence 保护保持不变。",
                messages.plainText("log.lifecycle.sqlite-interrupted"));
        assertEquals("服务器在 Residence 投影完成前停止，启动时已转为可重试失败状态",
                messages.plainText("log.lifecycle.interrupted-provision-reason"));
        assertEquals("已恢复 4 个中断的建镇流程；申请进入 PROVISION_FAILED，等待管理员重试。",
                messages.plainText("log.lifecycle.interrupted-provisions-recovered", placeholders));
        assertEquals("恢复中断建镇流程失败: boom",
                messages.plainText("log.lifecycle.interrupted-provision-recovery-failure", placeholders));
        assertEquals("捐款自动退款第 2 次尝试失败: operation=op-1, error=boom",
                messages.plainText("log.donation.refund-retry-failed", placeholders));
        assertEquals("捐款退款后外部余额已恢复，但第 2 次 SQLite 收尾失败: operation=op-1, error=boom",
                messages.plainText("log.donation.refund-finalization-failed", placeholders));
        assertEquals("捐款自动退款已恢复玩家余额，正在复核消费锁: operation=op-1, attempts=3",
                messages.plainText("log.donation.refund-recovered", placeholders));
        assertEquals("捐款自动退款达到重试上限，消费锁保持不变: operation=op-1, error=boom",
                messages.plainText("log.donation.refund-exhausted", placeholders));
        assertEquals("自动退款后的清算余额读取失败，消费锁保持不变: operation=op-1, error=boom",
                messages.plainText("log.donation.settlement-balance-read-failure", placeholders));
        assertEquals("自动退款完成但清算仍有短款，消费锁保持不变: operation=op-1, external=1200, required=1300",
                messages.plainText("log.donation.settlement-shortfall", placeholders));
        assertEquals("自动退款后的清算复核失败，消费锁保持不变: operation=op-1, error=boom",
                messages.plainText("log.donation.settlement-reconciliation-failure", placeholders));
        assertEquals("Residence 对账失败 town-1: boom",
                messages.plainText("log.residence.reconciliation-failure", placeholders));
        assertEquals("Residence 对账发现异常 town-1: boom；正在读取最新 SQLite 记录修复。",
                messages.plainText("log.residence.reconciliation-difference", placeholders));
        assertEquals("Residence 对账读取 SQLite 失败: boom",
                messages.plainText("log.residence.reconciliation-sqlite-read-failure", placeholders));
        assertEquals("Residence 自动修复已取消，小镇数据库版本已不存在或非 ACTIVE: town-1",
                messages.plainText("log.residence.automatic-repair-cancelled", placeholders));
        assertEquals("Residence 自动修复已延后，小镇仍有未完成的领地投影: town-1",
                messages.plainText("log.residence.automatic-repair-delayed", placeholders));
        assertEquals("Residence 自动修复读取最新 SQLite 记录失败 town-1: boom",
                messages.plainText("log.residence.automatic-repair-sqlite-read-failure", placeholders));
        assertEquals("Residence 对账自动修复失败 town-1: boom",
                messages.plainText("log.residence.automatic-repair-api-failure", placeholders));
        assertEquals("Residence 投影在读取最新 SQLite 记录后已一致 town-1: boom",
                messages.plainText("log.residence.automatic-repair-consistent", placeholders));
        assertEquals("Residence 对账发现异常并已依照最新 SQLite 记录自动修复 town-1: boom",
                messages.plainText("log.residence.automatic-repair-completed", placeholders));
        assertEquals("Residence 对账自动修复失败 town-1: 检查=检查异常，修复=修复失败",
                messages.plainText("log.residence.automatic-repair-failed", placeholders));

        for (String key : RUNTIME_KEYS) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void usesRuntimeOverridesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.of(
                "attempt", 2,
                "operation", "op-1",
                "detail", "boom",
                "town", "town-1",
                "inspection", "检查异常",
                "repair", "修复失败");

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("log.scheduler.lifecycle-stopped", "自定义生命周期停止消息");
        configuration.set("log.donation.refund-retry-failed",
                "自定义退款重试: {attempt}/{operation}/{detail}");
        configuration.set("log.residence.automatic-repair-failed",
                "自定义自动修复: {town}/{inspection}/{repair}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义生命周期停止消息",
                messages.plainText("log.scheduler.lifecycle-stopped"));
        assertEquals("自定义退款重试: 2/op-1/boom",
                messages.plainText("log.donation.refund-retry-failed", placeholders));
        assertEquals("自定义自动修复: town-1/检查异常/修复失败",
                messages.plainText("log.residence.automatic-repair-failed", placeholders));
    }
}

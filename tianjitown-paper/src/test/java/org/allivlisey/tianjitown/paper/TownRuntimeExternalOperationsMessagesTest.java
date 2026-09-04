package org.allivlisey.tianjitown.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TownRuntimeExternalOperationsMessagesTest {
    private static final List<String> EXTERNAL_OPERATION_KEYS = List.of(
            "chat.lifecycle.storage-unavailable",
            "chat.lifecycle.storage-write-locked",
            "chat.lifecycle.external-preflight-failed",
            "chat.lifecycle.external-failed",
            "chat.lifecycle.refund-auto",
            "chat.lifecycle.manual-review",
            "log.expansion.batch-recovered",
            "log.expansion.batch-recovery-failed",
            "log.external-operation.refund-auto",
            "log.external-operation.manual-review",
            "log.lifecycle.ledger-actor-name-backfill-failure",
            "log.lifecycle.ledger-actor-scan-failure");

    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersExternalOperationAndRuntimeFailureMessages() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.of(
                "batch", "batch-1",
                "detail", "boom",
                "operation", "operation-1",
                "town", "town-1",
                "playerId", "player-1");

        assertEquals("小镇资金服务暂时不可用",
                messages.plainText("chat.lifecycle.storage-unavailable"));
        assertEquals("SQLite 当前不可用，写操作已锁定",
                messages.plainText("chat.lifecycle.storage-write-locked"));
        assertEquals("暂时无法确认公共资金余额：boom",
                messages.plainText("chat.lifecycle.external-preflight-failed", placeholders));
        assertEquals("资金操作结果需要管理员复核：boom",
                messages.plainText("chat.lifecycle.external-failed", placeholders));
        assertFalse(messages.plainText("chat.lifecycle.refund-auto").isBlank());
        assertFalse(messages.plainText("chat.lifecycle.manual-review").isBlank());
        assertEquals("已恢复批量领地扩张 batch-1",
                messages.plainText("log.expansion.batch-recovered", placeholders));
        assertEquals("恢复批量领地扩张失败 batch-1: boom",
                messages.plainText("log.expansion.batch-recovery-failed", placeholders));
        assertEquals("捐款退款暂时失败，已锁定小镇消费并启动自动退款: "
                        + "operation=operation-1, town=town-1, error=boom",
                messages.plainText("log.external-operation.refund-auto", placeholders));
        assertEquals("资金操作需要人工核对，已锁定小镇消费: "
                        + "operation=operation-1, town=town-1, error=boom",
                messages.plainText("log.external-operation.manual-review", placeholders));
        assertEquals("回填账本玩家名失败 player-1: boom",
                messages.plainText("log.lifecycle.ledger-actor-name-backfill-failure",
                        placeholders));
        assertEquals("扫描历史 UUID 操作人失败: boom",
                messages.plainText("log.lifecycle.ledger-actor-scan-failure", placeholders));

        for (String key : EXTERNAL_OPERATION_KEYS) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void usesRuntimeOverridesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("chat.lifecycle.external-failed", "自定义外部失败: {detail}");
        configuration.set("log.expansion.batch-recovered", "自定义批量恢复: {batch}");
        configuration.set("log.external-operation.manual-review",
                "自定义人工核对: {operation}/{town}/{detail}");
        configuration.set("log.lifecycle.ledger-actor-name-backfill-failure",
                "自定义回填: {playerId}/{detail}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        Map<String, ?> placeholders = Map.of(
                "batch", "batch-1",
                "detail", "boom",
                "operation", "operation-1",
                "town", "town-1",
                "playerId", "player-1");
        assertEquals("自定义外部失败: boom",
                messages.plainText("chat.lifecycle.external-failed", placeholders));
        assertEquals("自定义批量恢复: batch-1",
                messages.plainText("log.expansion.batch-recovered", placeholders));
        assertEquals("自定义人工核对: operation-1/town-1/boom",
                messages.plainText("log.external-operation.manual-review", placeholders));
        assertEquals("自定义回填: player-1/boom",
                messages.plainText("log.lifecycle.ledger-actor-name-backfill-failure",
                        placeholders));
    }
}

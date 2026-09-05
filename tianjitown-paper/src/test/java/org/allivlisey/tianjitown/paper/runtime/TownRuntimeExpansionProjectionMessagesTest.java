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

class TownRuntimeExpansionProjectionMessagesTest {
    private static final List<String> EXPANSION_KEYS = List.of(
            "chat.runtime.consumption-paused",
            "chat.lifecycle.expansion-validation-failed",
            "validation.territory.batch-request-id-required",
            "validation.territory.batch-already-refunded",
            "chat.land-protection.api-unavailable",
            "chat.lifecycle.expansion-failed-refunded",
            "chat.lifecycle.expansion-area-presence-check-failed",
            "chat.lifecycle.expansion-batch-failed",
            "log.expansion.batch-rollback-failed",
            "chat.lifecycle.expansion-batch-rollback-partial",
            "log.expansion.recovered",
            "log.expansion.recovery-failed");

    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersExpansionProjectionMessages() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.of(
                "detail", "Residence API 异常",
                "batch", "batch-1",
                "cleanup", "area-1: Residence 清理失败",
                "cause", "Residence 批量扩张失败: Residence API 异常",
                "expansion", "expansion-1");

        assertFalse(messages.plainText("chat.runtime.consumption-paused").isBlank());
        assertEquals("暂时无法确认该区域能否扩张：Residence API 异常",
                messages.plainText("chat.lifecycle.expansion-validation-failed", placeholders));
        assertEquals("批量扩张幂等键不能为空",
                messages.plainText("validation.territory.batch-request-id-required"));
        assertEquals("该批量扩张操作已经退款，请重新选择区域",
                messages.plainText("validation.territory.batch-already-refunded"));
        assertEquals("Residence API 不可用: Residence API 异常",
                messages.plainText("chat.land-protection.api-unavailable", placeholders));
        assertEquals("领地扩张未成功，费用已退回公共资金：Residence API 异常",
                messages.plainText("chat.lifecycle.expansion-failed-refunded", placeholders));
        assertEquals("无法确认 Residence 原有区域: Residence API 异常",
                messages.plainText("chat.lifecycle.expansion-area-presence-check-failed", placeholders));
        assertEquals("Residence 批量扩张失败: Residence API 异常",
                messages.plainText("chat.lifecycle.expansion-batch-failed", placeholders));
        assertEquals("批量扩张外部区域回滚失败 batch=batch-1: area-1: Residence 清理失败",
                messages.plainText("log.expansion.batch-rollback-failed", placeholders));
        assertEquals("Residence 批量扩张失败: Residence API 异常；部分 Residence 区域未能回滚，批次保留待恢复",
                messages.plainText("chat.lifecycle.expansion-batch-rollback-partial", placeholders));
        assertEquals("已恢复领地扩张 expansion-1",
                messages.plainText("log.expansion.recovered", placeholders));
        assertEquals("恢复领地扩张失败 expansion-1: Residence API 异常",
                messages.plainText("log.expansion.recovery-failed", placeholders));

        for (String key : EXPANSION_KEYS) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void usesExpansionProjectionOverridesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("chat.lifecycle.expansion-validation-failed", "自定义扩张复核: {detail}");
        configuration.set("validation.territory.batch-already-refunded", "自定义批次已退款");
        configuration.set("chat.land-protection.api-unavailable", "自定义 Residence 异常: {detail}");
        configuration.set("log.expansion.batch-rollback-failed",
                "自定义批量回滚: {batch}/{cleanup}");
        configuration.set("log.expansion.recovery-failed",
                "自定义恢复失败: {expansion}/{detail}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义扩张复核: WorldBorder 不可用",
                messages.plainText("chat.lifecycle.expansion-validation-failed",
                        Map.of("detail", "WorldBorder 不可用")));
        assertEquals("自定义批次已退款",
                messages.plainText("validation.territory.batch-already-refunded"));
        assertEquals("自定义 Residence 异常: API 不可用",
                messages.plainText("chat.land-protection.api-unavailable",
                        Map.of("detail", "API 不可用")));
        assertEquals("自定义批量回滚: batch-2/area-2: API 不可用",
                messages.plainText("log.expansion.batch-rollback-failed",
                        Map.of("batch", "batch-2", "cleanup", "area-2: API 不可用")));
        assertEquals("自定义恢复失败: expansion-2/SQLite 不可用",
                messages.plainText("log.expansion.recovery-failed",
                        Map.of("expansion", "expansion-2", "detail", "SQLite 不可用")));
    }
}

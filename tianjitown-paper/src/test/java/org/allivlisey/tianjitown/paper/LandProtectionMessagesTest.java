package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandProtectionMessagesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersEveryStructuredLandProtectionCodeFromBuiltInMessages() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        for (LandProtectionService.ResultCode code : LandProtectionService.ResultCode.values()) {
            String key = LandProtectionMessages.key(code);
            String rendered = LandProtectionMessages.text(messages,
                    LandProtectionService.Result.failureCode(code, sampleParameters()));

            assertNotNull(messages.rawText(key));
            assertFalse(messages.rawText(key).isBlank());
            assertFalse(rendered.contains("缺少消息配置"));
            assertFalse(rendered.contains("{"));
        }

    }

    @Test
    void rendersStructuredCollisionDiagnostics() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        LandProtectionService.Collision collision = LandProtectionService.Collision.failureCode(
                LandProtectionService.ResultCode.RESIDENCE_API_UNAVAILABLE,
                Map.of("detail", "NoSuchMethodError"));

        String rendered = LandProtectionMessages.detail(messages, collision);
        assertTrue(rendered.contains("NoSuchMethodError"));
        assertFalse(rendered.contains("{"));
    }

    @Test
    void rendersResidenceResultWithAllPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        LandProtectionService.Result result = LandProtectionService.Result.failureCode(
                LandProtectionService.ResultCode.AREA_ADD_ROLLBACK_FAILED,
                Map.of("detail", "api boom", "cleanup", "rollback boom"));

        String rendered = LandProtectionMessages.detail(messages, result);
        assertTrue(rendered.contains("api boom"));
        assertTrue(rendered.contains("rollback boom"));
        assertFalse(rendered.contains("{"));
    }

    @Test
    void usesTheUserOverrideAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        LandProtectionService.Result result = LandProtectionService.Result.failureCode(
                LandProtectionService.ResultCode.UNSUPPORTED_ADD_AREA);

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(LandProtectionMessages.key(result.code()), "&b自定义领地扩张能力提示");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertTrue(LandProtectionMessages.text(messages, result).startsWith("§b"));
        assertTrue(LandProtectionMessages.detail(messages, result).contains("自定义领地扩张能力提示"));
    }

    @Test
    void usesResidenceResultOverrideAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        LandProtectionService.Result result = LandProtectionService.Result.successCode(
                LandProtectionService.ResultCode.PROJECTION_AUTO_REPAIRED,
                Map.of("residence", "sky"));
        String key = LandProtectionMessages.key(result.code());

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(key, "自定义 Residence 修复结果: {residence}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertTrue(LandProtectionMessages.detail(messages, result).contains("sky"));
    }

    private static Map<String, String> sampleParameters() {
        return Map.of("world", "world", "detail", "boom", "residence", "sky",
                "area", "north", "owner", "player", "member", "00000000-0000-0000-0000-000000000001",
                "flag", "explode", "cleanup", "rollback");
    }
}

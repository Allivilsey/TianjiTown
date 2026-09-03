package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertEquals("§c当前领地适配器不支持设置传送点。",
                LandProtectionMessages.text(messages,
                        LandProtectionService.Result.failureCode(
                                LandProtectionService.ResultCode.UNSUPPORTED_TELEPORT_POINT)));
        assertEquals("当前领地适配器不支持多区域 Residence。",
                LandProtectionMessages.detail(messages,
                        LandProtectionService.Inspection.invalidCode(
                                LandProtectionService.ResultCode.UNSUPPORTED_MULTI_AREA)));
    }

    @Test
    void rendersStructuredCollisionDiagnostics() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        LandProtectionService.Collision collision = LandProtectionService.Collision.failureCode(
                LandProtectionService.ResultCode.RESIDENCE_API_UNAVAILABLE,
                Map.of("detail", "NoSuchMethodError"));

        assertEquals("Residence API 不可用: NoSuchMethodError",
                LandProtectionMessages.detail(messages, collision));
        assertFalse(LandProtectionMessages.detail(messages, collision).contains("{"));
    }

    @Test
    void rendersResidenceResultWithAllPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        LandProtectionService.Result result = LandProtectionService.Result.failureCode(
                LandProtectionService.ResultCode.AREA_ADD_ROLLBACK_FAILED,
                Map.of("detail", "api boom", "cleanup", "rollback boom"));

        assertEquals("Residence API 不可用: api boom；新增区域回滚失败: rollback boom",
                LandProtectionMessages.detail(messages, result));
        assertFalse(LandProtectionMessages.detail(messages, result).contains("{"));
    }

    @Test
    void usesTheUserOverrideAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        LandProtectionService.Result result = LandProtectionService.Result.failureCode(
                LandProtectionService.ResultCode.UNSUPPORTED_ADD_AREA);

        assertTrue(LandProtectionMessages.text(messages, result)
                .contains("当前领地适配器不支持扩张区域"));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(LandProtectionMessages.key(result.code()), "&b自定义领地扩张能力提示");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("§b自定义领地扩张能力提示",
                LandProtectionMessages.text(messages, result));
        assertEquals("自定义领地扩张能力提示",
                LandProtectionMessages.detail(messages, result));
    }

    @Test
    void usesResidenceResultOverrideAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        LandProtectionService.Result result = LandProtectionService.Result.successCode(
                LandProtectionService.ResultCode.PROJECTION_AUTO_REPAIRED,
                Map.of("residence", "sky"));
        String key = LandProtectionMessages.key(result.code());

        assertEquals("Residence 投影已依照数据库自动修复: sky",
                LandProtectionMessages.detail(messages, result));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(key, "自定义 Residence 修复结果: {residence}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义 Residence 修复结果: sky",
                LandProtectionMessages.detail(messages, result));
    }

    private static Map<String, String> sampleParameters() {
        return Map.of("world", "world", "detail", "boom", "residence", "sky",
                "area", "north", "owner", "player", "member", "00000000-0000-0000-0000-000000000001",
                "flag", "explode", "cleanup", "rollback");
    }
}

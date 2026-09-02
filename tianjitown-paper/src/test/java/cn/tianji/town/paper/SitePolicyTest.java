package cn.tianji.town.paper;

import cn.tianji.town.core.land.ChunkPosition;
import cn.tianji.town.core.land.InitialTerritory;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SitePolicyTest {
    private static final UUID WORLD_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID OTHER_WORLD_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");

    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersSiteMessagesWithCompletePlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.of(
                "scope", "2 个领地单元",
                "count", 2,
                "duration", 15,
                "detail", "boom",
                "path", "town.site.blacklist",
                "key", "min-chunk-x");

        for (String key : List.of(
                "chat.site.preview-scope-single",
                "chat.site.preview-scope-multiple",
                "chat.site.preview-started",
                "validation.site.areas-required",
                "validation.site.world-mismatch",
                "validation.site.focus-missing",
                "validation.site.blacklist-bounds-invalid",
                "validation.site.blacklist-integer-required",
                "log.site.preview-invalidated",
                "log.site.preview-cancel-failed",
                "log.site.blacklist-invalid-area")) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }

        assertEquals("5×5 区块", SitePolicy.previewScope(1, messages::plainText));
        assertEquals("2 个领地单元", SitePolicy.previewScope(2, messages::plainText));
    }

    @Test
    void validatesPreviewAreasThroughConfiguredMessages() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        InitialTerritory area = territory(WORLD_ID, 0, 0, "world");
        InitialTerritory otherWorldArea = territory(OTHER_WORLD_ID, 0, 0, "nether");
        InitialTerritory missingFocus = territory(WORLD_ID, 10, 10, "world");

        assertEquals("领地预览至少需要一个区域", assertThrows(IllegalArgumentException.class,
                () -> SitePolicy.previewAreas(null, List.of(), messages::plainText)).getMessage());
        assertEquals("领地预览区域必须位于同一世界",
                assertThrows(IllegalArgumentException.class,
                        () -> SitePolicy.previewAreas(null, List.of(area, otherWorldArea),
                                messages::plainText)).getMessage());
        assertEquals("领地中心不在预览区域中",
                assertThrows(IllegalArgumentException.class,
                        () -> SitePolicy.previewAreas(missingFocus, List.of(area),
                                messages::plainText)).getMessage());
    }

    @Test
    void usesReloadedScopeAndValidationMessagesForNewCalls() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration override = new YamlConfiguration();
        override.set("chat.site.preview-scope-single", "自定义单区域");
        override.set("chat.site.preview-scope-multiple", "{count} 个自定义区域");
        override.set("validation.site.areas-required", "自定义空预览错误");
        override.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义单区域", SitePolicy.previewScope(1, messages::plainText));
        assertEquals("3 个自定义区域", SitePolicy.previewScope(3, messages::plainText));
        assertEquals("自定义空预览错误", assertThrows(IllegalArgumentException.class,
                () -> SitePolicy.previewAreas(null, List.of(), messages::plainText)).getMessage());
    }

    private static InitialTerritory territory(UUID worldId, int x, int z, String worldName) {
        return new InitialTerritory(new ChunkPosition(worldId, worldName, x, z));
    }
}

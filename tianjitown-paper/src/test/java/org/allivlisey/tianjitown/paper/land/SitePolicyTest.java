package org.allivlisey.tianjitown.paper.land;
import org.allivlisey.tianjitown.paper.message.PluginMessages;

import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SitePolicyTest {
    private static final UUID WORLD_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID OTHER_WORLD_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");

    @TempDir
    Path temporaryDirectory;

    @Test
    void checksOuterReservedUnitsBeforeAcceptingSite() {
        var plugin = org.mockito.Mockito.mock(org.allivlisey.tianjitown.paper.TianjiTownPlugin.class);
        var server = org.mockito.Mockito.mock(org.bukkit.Server.class);
        var world = org.mockito.Mockito.mock(org.bukkit.World.class);
        var land = org.mockito.Mockito.mock(org.allivlisey.tianjitown.core.ports.LandProtectionService.class);
        var boundaries = org.mockito.Mockito.mock(org.allivlisey.tianjitown.core.ports.WorldBoundaryService.class);
        org.mockito.Mockito.when(plugin.getServer()).thenReturn(server);
        org.mockito.Mockito.when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        org.mockito.Mockito.when(plugin.messages()).thenReturn(new PluginMessages(temporaryDirectory.toFile()));
        org.mockito.Mockito.when(server.getWorld(WORLD_ID)).thenReturn(world);
        org.mockito.Mockito.when(boundaries.check(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(org.allivlisey.tianjitown.core.ports.WorldBoundaryService.Check.configuredInside());
        org.mockito.Mockito.when(land.findCollision(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            InitialTerritory unit = call.getArgument(0);
            return unit.center().x() == 10 && unit.center().z() == 10
                    ? new org.allivlisey.tianjitown.core.ports.LandProtectionService.Collision(true, "neighbor")
                    : org.allivlisey.tianjitown.core.ports.LandProtectionService.Collision.none();
        });
        SitePolicy policy = new SitePolicy(plugin, land, boundaries);
        assertFalse(policy.validate(territory(WORLD_ID, 0, 0, "world")).valid());
        org.mockito.Mockito.verify(land, org.mockito.Mockito.times(25)).findCollision(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.when(boundaries.check(territory(WORLD_ID, 10, 10, "world"), 1))
                .thenReturn(org.allivlisey.tianjitown.core.ports.WorldBoundaryService.Check.configuredOutside());
        assertFalse(policy.validateReservationEnvironment(territory(WORLD_ID, 0, 0, "world")).valid());
    }

    @Test
    void rendersSiteMessagesWithCompletePlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.of(
                "scope", "2 个领地单元",
                "count", 2,
                "duration", 15,
                "detail", "boom");

        for (String key : List.of(
                "chat.site.preview-scope-single",
                "chat.site.preview-scope-multiple",
                "chat.site.preview-started",
                "validation.site.areas-required",
                "validation.site.world-mismatch",
                "validation.site.focus-missing",
                "log.site.preview-invalidated",
                "log.site.preview-cancel-failed")) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertTrue(messages.hasMessage(key), key);
            assertFalse(rendered.contains("{"), key);
        }

        assertEquals(messages.plainText("chat.site.preview-scope-single"), TerritoryPreviewService.previewScope(1, messages::plainText));
        assertEquals(messages.plainText("chat.site.preview-scope-multiple",
                Map.of("count", 2)),
                TerritoryPreviewService.previewScope(2, messages::plainText));
    }

    @Test
    void validatesPreviewAreasThroughConfiguredMessages() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        InitialTerritory area = territory(WORLD_ID, 0, 0, "world");
        InitialTerritory otherWorldArea = territory(OTHER_WORLD_ID, 0, 0, "nether");
        InitialTerritory missingFocus = territory(WORLD_ID, 10, 10, "world");

        assertEquals(messages.plainText("validation.site.areas-required"), assertThrows(IllegalArgumentException.class,
                () -> TerritoryPreviewService.previewAreas(null, List.of(), messages::plainText)).getMessage());
        assertEquals(messages.plainText("validation.site.world-mismatch"),
                assertThrows(IllegalArgumentException.class,
                        () -> TerritoryPreviewService.previewAreas(null, List.of(area, otherWorldArea),
                                messages::plainText)).getMessage());
        assertEquals(messages.plainText("validation.site.focus-missing"),
                assertThrows(IllegalArgumentException.class,
                        () -> TerritoryPreviewService.previewAreas(missingFocus, List.of(area),
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

        assertEquals("自定义单区域", TerritoryPreviewService.previewScope(1, messages::plainText));
        assertEquals("3 个自定义区域", TerritoryPreviewService.previewScope(3, messages::plainText));
        assertEquals("自定义空预览错误", assertThrows(IllegalArgumentException.class,
                () -> TerritoryPreviewService.previewAreas(null, List.of(), messages::plainText)).getMessage());
    }

    private static InitialTerritory territory(UUID worldId, int x, int z, String worldName) {
        return new InitialTerritory(new ChunkPosition(worldId, worldName, x, z));
    }
}

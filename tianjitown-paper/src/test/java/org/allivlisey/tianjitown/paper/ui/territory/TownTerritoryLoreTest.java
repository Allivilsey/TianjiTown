package org.allivlisey.tianjitown.paper.ui.territory;
import org.allivlisey.tianjitown.paper.message.MessageTestSupport;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.paper.ui.TownUiController;

import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownTerritoryLoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void territoryLoreContainsOnlyTheCenterAndTeleportHints() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        InitialTerritory territory = new InitialTerritory(new ChunkPosition(
                UUID.randomUUID(), "world", 12, -8));

        List<String> lore = TownUiController.townTerritoryLore(messages, territory);

        assertEquals(List.of(
                messages.rawText("dialog.tooltip.town.territory-center",
                        Map.of("x", 12, "z", -8)),
                messages.rawText("dialog.tooltip.town.territory-preview")), lore);
        assertEquals(2, lore.size());
        assertTrue(lore.get(0).contains("12"));
        assertTrue(lore.get(0).contains("-8"));
    }

    @Test
    void territoryMessageKeysKeepTheirExpectedConfigurationContract() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        MessageTestSupport.assertConfigured(messages,
                "dialog.tooltip.town.territory-center", Map.of("x", 12, "z", -8));
        MessageTestSupport.assertConfigured(messages,
                "dialog.tooltip.town.territory-preview");
        assertFalse(messages.hasMessage("dialog.tooltip.town.territory"));
    }
}

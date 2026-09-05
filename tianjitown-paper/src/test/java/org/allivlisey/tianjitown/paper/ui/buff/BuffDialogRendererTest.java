package org.allivlisey.tianjitown.paper.ui.buff;
import org.allivlisey.tianjitown.paper.message.PluginMessages;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuffDialogRendererTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void parameterPageOnlyShowsStateAndPriceAcrossShopAndActiveStates() throws Exception {
        PluginMessages messages = messages();
        assertFalse(messages.hasMessage("dialog.buff.intensity-hint"));

        for (boolean shopEnabled : new boolean[]{true, false}) {
            assertEquals(shopEnabled ? "SHOP_ENABLED" : "SHOP_PAUSED",
                    BuffDialogRenderer.shopHint(messages, shopEnabled));
            for (BuffDialogRenderer.ActiveState current : new BuffDialogRenderer.ActiveState[]{
                    null, new BuffDialogRenderer.ActiveState(3, "2026-09-04T00:00:00Z")}) {
                List<String> lore = BuffDialogRenderer.parameterSummaryLore(messages,
                        "speed", current);

                assertEquals(3, lore.size());
                assertEquals("EFFECT speed", lore.get(0));
                assertEquals(current == null ? "INACTIVE" :
                                "ACTIVE III 2026-09-04T00:00:00Z", lore.get(1));
                assertEquals("PRICE", lore.get(2));
                assertFalse(String.join("\n", lore).contains("强度使用罗马数字"));
                assertFalse(String.join("\n", lore).contains("intensity-hint"));
            }
        }
    }

    @Test
    void keepsRomanNumeralStrengthRenderingForTheConfiguredLevel() throws Exception {
        assertEquals(List.of("I", "II", "III", "IV", "V"),
                List.of(1, 2, 3, 4, 5).stream().map(BuffDialogRenderer::roman).toList());
        PluginMessages messages = messages();
        assertTrue(BuffDialogRenderer.parameterSummaryLore(messages, "speed",
                new BuffDialogRenderer.ActiveState(5, "expires")).get(1).contains("V"));
    }

    private PluginMessages messages() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("dialog.buff.shop-enabled-hint", "SHOP_ENABLED");
        configuration.set("dialog.buff.shop-paused-hint", "SHOP_PAUSED");
        configuration.set("dialog.buff.effect", "EFFECT {effect}");
        configuration.set("dialog.buff.inactive", "INACTIVE");
        configuration.set("dialog.buff.active", "ACTIVE {level} {expires}");
        configuration.set("dialog.buff.price-hint", "PRICE");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        return new PluginMessages(temporaryDirectory.toFile());
    }
}

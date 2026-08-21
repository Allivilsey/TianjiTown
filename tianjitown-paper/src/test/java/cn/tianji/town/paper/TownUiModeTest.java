package cn.tianji.town.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TownUiModeTest {
    @Test
    void defaultsToDialog() {
        assertEquals(TownUiMode.DIALOG, TownUiMode.load(new YamlConfiguration()));
    }

    @Test
    void keepsLegacyInventoryAsExplicitCompatibilityMode() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("ui.mode", "legacy");

        assertEquals(TownUiMode.LEGACY, TownUiMode.load(config));
    }

    @Test
    void rejectsUnknownMode() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("ui.mode", "CHEST");

        assertThrows(IllegalArgumentException.class, () -> TownUiMode.load(config));
    }
}

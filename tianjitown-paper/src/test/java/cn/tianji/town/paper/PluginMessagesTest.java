package cn.tianji.town.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginMessagesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsBuiltInMessagesAndValidRangeFormats() {
        assertDoesNotThrow(() -> new PluginMessages(temporaryDirectory.toFile()));
    }

    @Test
    void rendersTownTerritoryNameWithoutResidenceBranding() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("§7小镇领地名: 青石镇",
                messages.text("dialog.town.residence-name", Map.of("name", "青石镇")));
    }

    @Test
    void keepsOnlyTheSafetyRequirementInTheTeleportPointTooltip() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("§7必须站在本镇领地内的安全位置",
                messages.text("dialog.tooltip.town.set-teleport"));
    }

    @Test
    void providesTheFixedApplicationFormDraftSavedNotice() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("§6草稿已保存", messages.text("dialog.notice.form-draft-saved-title"));
        assertEquals("§f已保存小镇申请草稿",
                messages.text("dialog.notice.form-draft-saved-message"));
    }

    @Test
    void rejectsInvalidRangePlaceholders() throws Exception {
        assertInvalidRangeFormat("dialog.tax.rate-format", "&f%s: %f%%");
        assertInvalidRangeFormat("dialog.buff.duration-format", "&f%s: %.0f 周");
        assertInvalidRangeFormat("dialog.buff.duration-format", "&f%s 周");
        assertInvalidRangeFormat("dialog.buff.intensity-format", "&f%s: 等级 %s（%s）");
    }

    @Test
    void acceptsTwoRangePlaceholdersAndEscapedPercents() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("dialog.tax.rate-format", "&f%% %s: %s%%");
        configuration.set("dialog.buff.duration-format", "&f%% %s: %s 周");
        configuration.set("dialog.buff.intensity-format", "&f%s: 等级 %s");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        assertDoesNotThrow(() -> new PluginMessages(temporaryDirectory.toFile()));
    }

    private void assertInvalidRangeFormat(String key, String format) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(key, format);
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new PluginMessages(temporaryDirectory.toFile()));
        assertTrue(exception.getMessage().contains(key));
    }
}

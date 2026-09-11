package org.allivlisey.tianjitown.paper.message;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PluginMessagesTest {
    @TempDir Path directory;

    @Test void readsCanonicalOverridesWithoutMigratingOldKeys() throws Exception {
        PluginMessages messages = new PluginMessages(directory.toFile());
        String defaultPrevious = messages.rawText("dialog.common.previous");
        YamlConfiguration overrides = new YamlConfiguration();
        overrides.set("dialog.join.previous", "old override");
        overrides.save(directory.resolve("messages.yml").toFile());
        messages.reload();
        assertEquals(defaultPrevious, messages.rawText("dialog.common.previous"));
        overrides.set("dialog.common.previous", "current override");
        overrides.save(directory.resolve("messages.yml").toFile());
        messages.reload();
        assertEquals("current override", messages.rawText("dialog.common.previous"));
    }

    @Test void reloadsWordingColoursLineBreaksAndPlaceholderOrder() throws Exception {
        PluginMessages messages = new PluginMessages(directory.toFile());
        String key = "validation.application.name-length";
        YamlConfiguration overrides = new YamlConfiguration();
        overrides.set(key, "&dMaximum: {maximum}\nMinimum: {minimum}!");
        overrides.save(directory.resolve("messages.yml").toFile());

        messages.reload();

        Map<String, ?> values = Map.of("minimum", 2, "maximum", 16);
        assertEquals("&dMaximum: 16\nMinimum: 2!", messages.rawText(key, values));
        assertEquals("§dMaximum: 16\nMinimum: 2!", messages.text(key, values));
        assertEquals("Maximum: 16\nMinimum: 2!", messages.plainText(key, values));
        assertEquals(NamedTextColor.LIGHT_PURPLE, messages.component(key, values).color());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Joined", "Joined {town} {unknown}"})
    void rejectsBlankOrInvalidPlaceholderContracts(String template) throws Exception {
        PluginMessages messages = new PluginMessages(directory.toFile());
        String key = "chat.notification.member-joined";
        String expected = messages.plainText(template.isBlank()
                ? "diagnostic.messages.required-message-missing"
                : "diagnostic.messages.placeholder-contract-mismatch", Map.of("key", key));
        YamlConfiguration overrides = new YamlConfiguration();
        overrides.set(key, template);
        overrides.save(directory.resolve("messages.yml").toFile());

        assertEquals(expected, assertThrows(IllegalStateException.class, messages::reload).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"%s", "%s / %s / %s", "%d / %s"})
    void rejectsInvalidRangeFormats(String template) throws Exception {
        PluginMessages messages = new PluginMessages(directory.toFile());
        String key = "dialog.tax.rate-format";
        String expected = messages.plainText(template.contains("%d")
                ? "diagnostic.messages.range-format-unsupported"
                : "diagnostic.messages.range-format-placeholder-count", Map.of("key", key));
        YamlConfiguration overrides = new YamlConfiguration();
        overrides.set(key, template);
        overrides.save(directory.resolve("messages.yml").toFile());

        assertEquals(expected, assertThrows(IllegalStateException.class, messages::reload).getMessage());
    }
}

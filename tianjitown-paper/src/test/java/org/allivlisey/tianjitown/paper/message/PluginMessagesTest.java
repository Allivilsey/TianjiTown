package org.allivlisey.tianjitown.paper.message;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginMessagesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void packagedMessagesAreNonEmptyAndSatisfyTheMessageContract() throws IOException {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration defaults = packagedMessages();

        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                continue;
            }
            String value = defaults.getString(key);
            assertNotNull(value, key);
            assertFalse(value.isBlank(), key);
        }
        for (MessageContract.Entry entry : MessageContract.requiredEntries()) {
            assertTrue(defaults.isString(entry.key()), entry.key());
            if (entry.placeholderContract()) {
                assertEquals(entry.placeholders(),
                        MessageContract.placeholders(defaults.getString(entry.key())), entry.key());
                MessageTestSupport.assertConfigured(messages, entry.key());
            }
        }
    }

    @Test
    void wordingAndColourAreConfigDataRatherThanBuildContracts() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("system.operation-failed", "&aTEST {detail}");
        configuration.set("validation.application.name-length", "TEST {minimum}/{maximum}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        assertEquals("§aTEST boom", messages.text("system.operation-failed", Map.of("detail", "boom")));
        assertEquals("TEST 2/24", messages.plainText("validation.application.name-length",
                Map.of("minimum", 2, "maximum", 24)));
    }

    @Test
    void rejectsMissingAndUnknownContractPlaceholdersWithTheMessageKey() throws Exception {
        assertContractViolation("validation.application.name-length", "TEST {minimum}");
        assertContractViolation("validation.application.name-length", "TEST {minimum}/{maximum}/{foo}");
        assertContractViolation("system.operation-failed", "TEST {foo}");
    }

    @Test
    void rejectsBlankRequiredMessageWithTheMessageKey() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("dialog.votes.type-kick", "");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new PluginMessages(temporaryDirectory.toFile()));
        assertTrue(exception.getMessage().contains("dialog.votes.type-kick"));
    }

    @Test
    void validatesRangeFormatsWithoutDependingOnTheirWording() throws Exception {
        YamlConfiguration valid = new YamlConfiguration();
        valid.set("dialog.tax.rate-format", "%% %s -> %s%%");
        valid.set("dialog.buff.duration-format", "%s -> %s");
        valid.set("dialog.buff.intensity-format", "%s -> %s");
        valid.save(temporaryDirectory.resolve("messages.yml").toFile());
        assertDoesNotThrow(() -> new PluginMessages(temporaryDirectory.toFile()));

        YamlConfiguration invalid = new YamlConfiguration();
        invalid.set("dialog.tax.rate-format", "%s -> %f");
        invalid.save(temporaryDirectory.resolve("messages.yml").toFile());
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new PluginMessages(temporaryDirectory.toFile()));
        assertTrue(exception.getMessage().contains("dialog.tax.rate-format"));
    }

    @Test
    void usesConfiguredMissingMessageAndMigratesLegacyOverrides() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("system.missing-message", "&eMISSING {key}");
        configuration.set("dialog.votes.previous", "&dPREVIOUS");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        assertEquals("§eMISSING absent.key", messages.text("absent.key"));
        assertEquals("§dPREVIOUS", messages.text("dialog.common.previous"));
    }

    private void assertContractViolation(String key, String template) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(key, template);
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new PluginMessages(temporaryDirectory.toFile()));
        assertTrue(exception.getMessage().contains(key));
    }

    private static YamlConfiguration packagedMessages() throws IOException {
        try (InputStream stream = PluginMessagesTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(stream, "messages.yml should be on the test classpath");
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }
}

package cn.tianji.town.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    void rejectsUnsupportedFloatingPointRangePlaceholders() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("dialog.buff.duration-format", "&f%.0f 周");
        configuration.set("dialog.buff.intensity-format", "&f等级 %s");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new PluginMessages(temporaryDirectory.toFile()));
        assertTrue(exception.getMessage().contains("dialog.buff.duration-format"));
    }
}

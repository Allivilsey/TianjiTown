package org.allivlisey.tianjitown.paper.message;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
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
}

package org.allivlisey.tianjitown.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownAdminHelpTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rootHelpListsConfiguredEntriesOnceAndDoesNotAdvertiseBuffRefunds() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        List<String> entries = TownAdminCommand.rootHelpEntries(
                Set.of(TownAdminPermissions.ROOT)::contains, messages::text);

        assertEquals(1, entries.stream().filter(line -> line.startsWith("§esystem ")).count());
        assertTrue(entries.stream().anyMatch(line -> line.startsWith("§ebuff ")));
        assertFalse(entries.stream().anyMatch(line -> line.contains("退款取消")));
    }

    @Test
    void operationsOnlyHelpStillListsConfiguredSystemOnce() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        List<String> entries = TownAdminCommand.rootHelpEntries(
                Set.of(TownAdminPermissions.OPERATIONS)::contains, messages::text);

        assertEquals(List.of(messages.text("chat.admin.help-entry-system")), entries);
    }

    @Test
    void rootHelpUsesUpdatedMessagesAfterReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("chat.admin.help-entry-system", "&aCustom system help");
        configuration.set("chat.admin.help-entry-buff", "&dCustom buff help");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        List<String> entries = TownAdminCommand.rootHelpEntries(
                Set.of(TownAdminPermissions.ROOT)::contains, messages::text);

        assertEquals("§aCustom system help", entries.get(0));
        assertEquals("§dCustom buff help", entries.get(entries.size() - 1));
    }
}

package org.allivlisey.tianjitown.paper.command;
import org.allivlisey.tianjitown.paper.message.PluginMessages;

import org.allivlisey.tianjitown.core.application.ApplicationStatus;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownAdminCompletionEngineTest {
    @TempDir
    Path temporaryDirectory;

    private final UUID submitted = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID review = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID failed = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private final UUID town = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final UUID member = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private PluginMessages messages;
    private TownAdminCompletionEngine engine;
    private final TownAdminCompletionEngine.Snapshot snapshot = new TownAdminCompletionEngine.Snapshot(
            List.of(new TownAdminCompletionEngine.TownCandidate(town, "sky", TownStatus.ACTIVE)),
            Map.of(town, List.of(member)));
    private final TownAdminCompletionEngine.Dynamic dynamic = new TownAdminCompletionEngine.Dynamic(
            List.of(new TownAdminCompletionEngine.PlayerCandidate(member, "MemberOne", true)),
            true);

    @BeforeEach
    void setUp() {
        messages = new PluginMessages(temporaryDirectory.toFile());
        engine = new TownAdminCompletionEngine(messages::plainText);
    }

    @Test
    void removedCommandsHaveNoArgumentSuggestions() {
        for (String command : List.of("application approve", "application reject", "application change",
                "vote create-kick", "vote create-mayor", "vote settle", "expand view", "expand preview",
                "money reconcile")) {
            assertEquals(List.of(), engine.complete((command + " ").split(" ", -1), snapshot, dynamic));
        }
        assertEquals(List.of("sky"), engine.complete(new String[]{"vote", "cancel", "s"}, snapshot, dynamic));
    }

    @Test
    void completesTownMembersReasonHintsAndRepairAction() {

        assertEquals(List.of("MemberOne"), engine.complete(
                new String[]{"member", "remove", "sky", ""},
                snapshot, dynamic));
        assertEquals(List.of("<原因>"), engine.complete(
                new String[]{"member", "remove", "sky", "MemberOne", ""},
                snapshot, dynamic));
        assertEquals(List.of("DEPUTY_MAYOR", "MEMBER"), engine.complete(
                new String[]{"member", "role", "sky", "MemberOne", ""},
                snapshot, dynamic));

        assertEquals(List.of("<原因>"), engine.complete(
                new String[]{"vote", "cancel", "sky", ""},
                snapshot, dynamic));
        assertEquals(List.of("repair"), engine.complete(
                new String[]{"land", "reconcile", "sky", ""}, snapshot, dynamic));
        assertEquals(List.of("<原因>"), engine.complete(
                new String[]{"town", "delete", "sky", ""}, snapshot, dynamic));

    }

    @Test
    void completesBuffManagementCommands() {

        assertEquals(List.of("<buffKey>"), engine.complete(
                new String[]{"buff", "grant", "sky", ""}, snapshot, dynamic));
        assertEquals(List.of(), engine.complete(
                new String[]{"order", "create", "sky", ""}, snapshot, dynamic));
    }

    @Test
    void usesConfiguredArgumentHintsAfterMessagesReload() throws Exception {
        assertEquals(List.of("<原因>"), engine.complete(
                new String[]{"town", "delete", "sky", ""}, snapshot, dynamic));
        assertEquals(List.of("<玩家>"), engine.complete(
                new String[]{"member", "add", "sky", ""}, snapshot, dynamic));
        assertEquals(List.of("<金额>"), engine.complete(
                new String[]{"money", "adjust", "sky", ""}, snapshot, dynamic));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("chat.admin.completion.reason-hint", "自定义原因");
        configuration.set("chat.admin.completion.player-hint", "自定义玩家");
        configuration.set("chat.admin.completion.amount-hint", "自定义金额");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals(List.of("自定义原因"), engine.complete(
                new String[]{"town", "delete", "sky", ""}, snapshot, dynamic));
        assertEquals(List.of("自定义玩家"), engine.complete(
                new String[]{"member", "add", "sky", ""}, snapshot, dynamic));
        assertEquals(List.of("自定义金额"), engine.complete(
                new String[]{"money", "adjust", "sky", ""}, snapshot, dynamic));

        assertThrows(IllegalArgumentException.class, () -> TownCommandParser.namedReason(
                new String[]{"town", "delete", "sky", "自定义原因"}, 2,
                List.of("sky"), messages::plainText));
        assertThrows(IllegalArgumentException.class, () -> TownCommandParser.namedPlayerReason(
                new String[]{"member", "add", "sky", "自定义玩家", "原因"}, 2,
                List.of("sky"), messages::plainText));
        assertThrows(IllegalArgumentException.class, () -> TownCommandParser.namedAmountReason(
                new String[]{"money", "adjust", "sky", "自定义金额", "原因"}, 2,
                List.of("sky"), messages::plainText));
        assertThrows(IllegalArgumentException.class, () -> TownCommandParser.reason(
                new String[]{"vote", "cancel", "vote-id", "自定义原因"}, 3,
                messages::plainText));
    }

}

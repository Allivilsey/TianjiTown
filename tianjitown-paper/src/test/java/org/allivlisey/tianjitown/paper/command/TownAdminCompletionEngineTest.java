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
            List.of(
                    new TownAdminCompletionEngine.ApplicationCandidate(submitted,
                            "待审小镇", ApplicationStatus.SUBMITTED),
                    new TownAdminCompletionEngine.ApplicationCandidate(review,
                            "补件小镇", ApplicationStatus.UNDER_REVIEW),
                    new TownAdminCompletionEngine.ApplicationCandidate(failed,
                            "失败小镇", ApplicationStatus.PROVISION_FAILED)),
            List.of(new TownAdminCompletionEngine.TownCandidate(town, "天际 之城", TownStatus.ACTIVE)),
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
    void filtersApplicationsByWorkflowStatus() {

        assertEquals(List.of("待审小镇"), engine.complete(
                new String[]{"application", "approve", "待"}, snapshot, dynamic));
        assertEquals(List.of("失败小镇"), engine.complete(
                new String[]{"application", "approve", "失"}, snapshot, dynamic));
        assertEquals(List.of("补件小镇"), engine.complete(
                new String[]{"application", "change", "补"}, snapshot, dynamic));
        assertEquals(List.of("<原因>"), engine.complete(
                new String[]{"application", "approve", "待审小镇", ""}, snapshot, dynamic));
        assertEquals(List.of(), engine.complete(
                new String[]{"application", "approve", "待审小镇", "已"}, snapshot, dynamic));
    }

    @Test
    void completesTownMembersReasonHintsAndRepairAction() {

        assertEquals(List.of("MemberOne"), engine.complete(
                new String[]{"member", "remove", "天际", "之城", ""},
                snapshot, dynamic));
        assertEquals(List.of("<原因>"), engine.complete(
                new String[]{"member", "remove", "天际", "之城", "MemberOne", ""},
                snapshot, dynamic));
        assertEquals(List.of("DEPUTY_MAYOR", "MEMBER"), engine.complete(
                new String[]{"member", "role", "天际", "之城", "MemberOne", ""},
                snapshot, dynamic));

        assertEquals(List.of("MemberOne"), engine.complete(
                new String[]{"vote", "create-kick", "天际", "之城", ""},
                snapshot, dynamic));
        assertEquals(List.of("repair"), engine.complete(
                new String[]{"land", "reconcile", "天际", "之城", ""}, snapshot, dynamic));
        assertEquals(List.of("<原因>"), engine.complete(
                new String[]{"town", "delete", "天际之城", ""}, snapshot, dynamic));

    }

    @Test
    void completesBuffManagementCommands() {

        assertEquals(List.of("<buffKey>"), engine.complete(
                new String[]{"buff", "grant", "天际", "之城", ""}, snapshot, dynamic));
        assertEquals(List.of(), engine.complete(
                new String[]{"order", "create", "天际", "之城", ""}, snapshot, dynamic));
    }

    @Test
    void usesConfiguredArgumentHintsAfterMessagesReload() throws Exception {
        assertEquals(List.of("<原因>"), engine.complete(
                new String[]{"application", "approve", "待审小镇", ""}, snapshot, dynamic));
        assertEquals(List.of("<玩家>"), engine.complete(
                new String[]{"member", "add", "天际", "之城", ""}, snapshot, dynamic));
        assertEquals(List.of("<金额>"), engine.complete(
                new String[]{"money", "adjust", "天际", "之城", ""}, snapshot, dynamic));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("chat.admin.completion.reason-hint", "自定义原因");
        configuration.set("chat.admin.completion.player-hint", "自定义玩家");
        configuration.set("chat.admin.completion.amount-hint", "自定义金额");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals(List.of("自定义原因"), engine.complete(
                new String[]{"application", "approve", "待审小镇", ""}, snapshot, dynamic));
        assertEquals(List.of("自定义玩家"), engine.complete(
                new String[]{"member", "add", "天际", "之城", ""}, snapshot, dynamic));
        assertEquals(List.of("自定义金额"), engine.complete(
                new String[]{"money", "adjust", "天际", "之城", ""}, snapshot, dynamic));

        assertThrows(IllegalArgumentException.class, () -> TownCommandParser.namedReason(
                new String[]{"application", "approve", "待审小镇", "自定义原因"}, 2,
                List.of("待审小镇"), messages::plainText));
        assertThrows(IllegalArgumentException.class, () -> TownCommandParser.namedPlayerReason(
                new String[]{"member", "add", "天际之城", "自定义玩家", "原因"}, 2,
                List.of("天际 之城"), messages::plainText));
        assertThrows(IllegalArgumentException.class, () -> TownCommandParser.namedAmountReason(
                new String[]{"money", "adjust", "天际之城", "自定义金额", "原因"}, 2,
                List.of("天际 之城"), messages::plainText));
        assertThrows(IllegalArgumentException.class, () -> TownCommandParser.reason(
                new String[]{"vote", "cancel", "vote-id", "自定义原因"}, 3,
                messages::plainText));
    }

}

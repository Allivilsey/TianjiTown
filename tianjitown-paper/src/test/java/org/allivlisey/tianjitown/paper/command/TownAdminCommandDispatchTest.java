package org.allivlisey.tianjitown.paper.command;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.bukkit.entity.Player;
import org.allivlisey.tianjitown.paper.ui.TownUiController;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import java.util.UUID;
import java.util.Set;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import revxrsal.commands.Lamp;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import io.papermc.paper.plugin.configuration.PluginMeta;
import java.util.Map;

class TownAdminCommandDispatchTest {
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final PluginMessages messages = mock(PluginMessages.class);
    private final TownRuntime runtime = mock(TownRuntime.class);
    private final CommandSender sender = mock(CommandSender.class);
    private Lamp<BukkitCommandActor> lamp;
    private final Player targetPlayer = mock(Player.class);
    private final BukkitCommandActor actor = mock(BukkitCommandActor.class);
    private final TownAdminTabCompleter completer = mock(TownAdminTabCompleter.class);

    @BeforeEach
    void setUp() {
        when(plugin.messages()).thenReturn(messages);
        when(plugin.townRuntime()).thenReturn(runtime);
        when(actor.sender()).thenReturn(sender);
        lamp = TownAdminLamp.configure(Lamp.<BukkitCommandActor>builder()
                .accept(revxrsal.commands.bukkit.BukkitVisitors.bukkitSenderResolver())
                .parameterTypes(types -> types.addParameterType(org.bukkit.entity.Player.class,
                        (input, context) -> { input.readString(); return targetPlayer; })), plugin, completer).build();
        new TownAdminCommand(plugin).register(lamp);
    }

    @Test
    void helpDirectoryWorksWithoutRuntimeAndHasClickablePermittedTopics() {
        prepareHelp();
        when(sender.hasPermission(TownAdminPermissions.MONEY)).thenReturn(true);
        when(plugin.townRuntime()).thenReturn(null);

        execute("help");

        verify(sender).sendMessage(Component.text("chat.admin.help-entry-money")
                .clickEvent(ClickEvent.runCommand("/tianjitown help money"))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        Component.text("chat.admin.help-topic-tooltip"))));
        verify(messages, never()).component("chat.admin.help-entry-tax");
        verifyNoInteractions(runtime);
    }

    @Test
    void rootCommandOpensHelpDirectory() {
        prepareHelp();
        when(sender.hasPermission(TownAdminPermissions.MONEY)).thenReturn(true);
        execute();
        verify(messages).send(sender, "chat.admin.help-title", Map.of("version", "1.0.0-SNAPSHOT"));
    }

    @Test
    void operationsHelpOnlyShowsCommandsAvailableToOperations() {
        prepareHelp();
        when(sender.hasPermission(TownAdminPermissions.OPERATIONS)).thenReturn(true);
        execute("help", "SYSTEM");
        verify(messages).send(sender, "chat.admin.help-system-status");
        verify(messages).send(sender, "chat.admin.help-system-diagnose");
        verify(messages, never()).send(sender, "chat.admin.help-system-reload");
        verify(messages, never()).send(sender, "chat.admin.help-system-maintenance");
        verify(messages, never()).send(sender, "chat.admin.help-system-audit");
        verify(sender).sendMessage(Component.text("chat.admin.help-back")
                .clickEvent(ClickEvent.runCommand("/tianjitown help")));
    }

    @Test
    void unknownHelpTopicReportsTypoAndShowsDirectoryForScopedAdmin() {
        prepareHelp();
        when(sender.hasPermission(TownAdminPermissions.MONEY)).thenReturn(true);
        execute("help", "monye");
        verify(messages).send(sender, "chat.admin.help-unknown", Map.of("topic", "monye"));
        verify(messages).send(sender, "chat.admin.help-usage");
        verify(messages, never()).send(sender, "chat.admin.help-forbidden");
    }

    @Test
    void helpRejectsKnownTopicOutsidePermissionScope() {
        when(sender.hasPermission(TownAdminPermissions.MONEY)).thenReturn(true);
        execute("help", "town");
        verify(messages).send(sender, "chat.admin.help-forbidden");
        verify(messages, never()).send(sender, "chat.admin.help-town-title");
    }

    @Test
    void stationHelpIncludesHandbook() {
        prepareHelp();
        when(sender.hasPermission(TownAdminPermissions.ROOT)).thenReturn(true);
        execute("help", "station");
        verify(messages).send(sender, "chat.admin.help-station-handbook");
        verifyNoInteractions(runtime);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void openTargetsSpecifiedPlayerForConsoleAndPlayerSenders(boolean playerSender) {
        CommandSender executor = playerSender ? mock(Player.class) : sender;
        when(actor.sender()).thenReturn(executor);
        when(executor.hasPermission(TownAdminPermissions.ROOT)).thenReturn(true);
        TownUiController ui = mock(TownUiController.class);
        when(plugin.townUi()).thenReturn(ui);

        execute("open", "Steve");

        verify(ui).openMain(targetPlayer);
        verifyNoInteractions(runtime);
    }

    @Test
    void openRequiresFullAdminPermission() {
        when(sender.hasPermission(TownAdminPermissions.OPERATIONS)).thenReturn(true);

        execute("open", "Steve");

        verify(messages).send(sender, "chat.admin.no-permission");
        verify(plugin, never()).townUi();
    }

    @Test
    void openRequiresReadyRuntime() {
        when(sender.hasPermission(TownAdminPermissions.ROOT)).thenReturn(true);
        when(plugin.townRuntime()).thenReturn(null);

        execute("open", "Steve");

        verify(messages).send(sender, "chat.admin.runtime-not-ready");
        verify(plugin, never()).townUi();
    }

    @ParameterizedTest
    @ValueSource(strings = {"open", "open Steve extra"})
    void openRequiresExactlyOneTarget(String input) {
        when(sender.hasPermission(TownAdminPermissions.ROOT)).thenReturn(true);

        lamp.dispatch(actor, "tianjitown " + input);

        verify(messages).send(eq(sender), eq("chat.admin.argument-error"), anyMap());
        verify(plugin, never()).townUi();
    }

    private void prepareHelp() {
        PluginMeta meta = mock(PluginMeta.class);
        when(plugin.getPluginMeta()).thenReturn(meta);
        when(meta.getVersion()).thenReturn("1.0.0-SNAPSHOT");
        when(messages.component(anyString())).thenAnswer(invocation ->
                Component.text((String) invocation.getArgument(0)));
        when(messages.component(anyString(), anyMap())).thenAnswer(invocation ->
                Component.text((String) invocation.getArgument(0)));
    }

    @Test
    void townListRequiresAdminPermission() {
        execute("town", "list");
        verify(messages).send(sender, "chat.admin.no-permission");
        verifyNoInteractions(runtime);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void townListReadsAndDisplaysTownsIncludingArchived(boolean empty) {
        when(sender.hasPermission(TownAdminPermissions.ROOT)).thenReturn(true);
        TownRepository repository = mock(TownRepository.class);
        when(runtime.repository()).thenReturn(repository);
        TownSnapshot town = mock(TownSnapshot.class, RETURNS_DEEP_STUBS);
        when(town.profile().name()).thenReturn("天际 之城");
        when(town.profile().residenceName()).thenReturn("sky");
        when(town.status()).thenReturn(org.allivlisey.tianjitown.core.town.TownStatus.ARCHIVED);
        List<TownSnapshot> towns = empty ? List.of() : List.of(town);
        when(repository.listTowns(true)).thenReturn(towns);
        doAnswer(invocation -> {
            Object result = ((Supplier<?>) invocation.getArgument(1)).get();
            java.util.function.Consumer<Object> success = invocation.getArgument(2);
            success.accept(result);
            return null;
        }).when(runtime).read(eq(sender), any(), any());

        execute("town", "list");

        verify(repository).listTowns(true);
        verify(messages).send(sender, "chat.admin.town-list-title", java.util.Map.of("count", towns.size()));
        if (empty) {
            verify(messages).send(sender, "chat.admin.town-list-empty");
        } else {
            verify(messages).send(sender, "chat.admin.town-list-entry", java.util.Map.of(
                    "town", "天际 之城", "code", "sky",
                    "status", org.allivlisey.tianjitown.core.town.TownStatus.ARCHIVED));
        }
        assertEquals(List.of("list"), lamp.autoCompleter().complete(actor, "tianjitown town li"));
    }

    @Test
    void unauthorizedCommandNeverReachesTheExtractedHandler() {
        execute("money", "view", "sky");

        verify(messages).send(sender, "chat.admin.no-permission");
        verifyNoInteractions(runtime);
    }

    @Test
    void authorizedEconomyCommandStillReachesTheRuntime() {
        when(sender.hasPermission(TownAdminPermissions.MONEY)).thenReturn(true);

        execute("money", "view", "sky");

        verify(runtime).read(eq(sender), any(), any());
    }

    @Test
    void malformedArgumentsStillUseTheSharedErrorBoundary() {
        when(sender.hasPermission(TownAdminPermissions.MONEY)).thenReturn(true);

        execute("money");

        verify(messages).send(eq(sender), eq("chat.admin.argument-error"), anyMap());
        verifyNoInteractions(runtime);
    }

    @Test
    void emptyCommandStillChecksPermissions() {
        execute();
        verify(messages).send(sender, "chat.admin.no-permission");
        verifyNoInteractions(runtime);
    }

    @Test
    void lampCompletesRootAndNestedArguments() {
        when(sender.hasPermission(TownAdminPermissions.MONEY)).thenReturn(true);
        assertEquals(List.of("money"), lamp.autoCompleter().complete(actor, "tianjitown mo"));
        assertEquals(List.of(), lamp.autoCompleter().complete(actor, "tianjitown money rec"));
        assertEquals(java.util.Set.of("view", "adjust"),
                java.util.Set.copyOf(lamp.autoCompleter().complete(actor, "tianjitown money ")));
        verifyNoInteractions(completer);
    }

    @Test
    void permissionFilteredCompletionsDoNotExposeOtherScopesOrSecretCommands() {
        when(sender.hasPermission(TownAdminPermissions.MONEY)).thenReturn(true);
        assertEquals(Set.of("help", "money"), Set.copyOf(lamp.autoCompleter().complete(actor, "tianjitown ")));
        assertEquals(List.of(), lamp.autoCompleter().complete(actor, "tianjitown tax "));
        verifyNoInteractions(runtime);
    }

    @Test
    void consoleCannotExecuteOrCompletePlayerOnlyActions() {
        when(sender.hasPermission(TownAdminPermissions.ROOT)).thenReturn(true);
        assertEquals(List.of("list"), lamp.autoCompleter().complete(actor, "tianjitown station "));
        assertFalse(lamp.autoCompleter().complete(actor, "tianjitown land ").contains("preview"));
        assertFalse(lamp.autoCompleter().complete(actor, "tianjitown expand ").contains("preview"));
        execute("station", "create");
        verify(messages).send(sender, "chat.admin.lamp-player-only");
        verifyNoInteractions(runtime);
    }

    @Test
    void playerSenderIsInjectedByLamp() {
        Player player = mock(Player.class);
        when(player.hasPermission(TownAdminPermissions.ROOT)).thenReturn(true);
        when(actor.sender()).thenReturn(player);
        when(actor.requirePlayer()).thenReturn(player);
        TownUiController ui = mock(TownUiController.class);
        when(plugin.townUi()).thenReturn(ui);
        execute("station", "create");
        verify(ui).createStation(player);
    }

    @Test
    void explicitHandbookTargetIsParsedRatherThanInjectedAsSender() {
        when(sender.hasPermission(TownAdminPermissions.ROOT)).thenReturn(true);
        TownUiController ui = mock(TownUiController.class);
        when(plugin.townUi()).thenReturn(ui);
        when(targetPlayer.getName()).thenReturn("Steve");
        execute("handbook", "Steve");
        verify(ui).giveHandbookByAdmin(targetPlayer);
    }

    @Test
    void runtimeGateRunsBeforeBusinessLogic() {
        when(sender.hasPermission(TownAdminPermissions.MONEY)).thenReturn(true);
        when(plugin.townRuntime()).thenReturn(null);
        execute("money", "view", "sky");
        verify(messages).send(sender, "chat.admin.runtime-not-ready");
        verifyNoInteractions(runtime);
    }

    @ParameterizedTest
    @ValueSource(strings = {"audit 0", "audit 201", "audit invalid", "audit 20 extra",
            "diagnose 0", "diagnose 181", "vote settle invalid", "vote cancel",
            "money view", "reload extra", "money reconcile extra", "confirm token extra"})
    void rejectsInvalidInputBeforeAnyBusinessAction(String input) {
        when(sender.hasPermission(TownAdminPermissions.ROOT)).thenReturn(true);
        lamp.dispatch(actor, "tianjitown " + input);
        verify(messages).send(eq(sender), eq("chat.admin.argument-error"), anyMap());
        verifyNoInteractions(runtime);
        verify(plugin, never()).reloadConfig();
    }

    @Test
    void auditDefaultAndRangeUseLampParsing() {
        when(sender.hasPermission(TownAdminPermissions.ROOT)).thenReturn(true);
        TownRepository repository = mock(TownRepository.class);
        when(runtime.repository()).thenReturn(repository);
        doAnswer(invocation -> { ((Supplier<?>) invocation.getArgument(1)).get(); return null; })
                .when(runtime).read(eq(sender), any(), any());
        execute("audit");
        execute("audit", "200");
        verify(repository).auditLog(20);
        verify(repository).auditLog(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application approve sky reason", "application reject sky reason",
            "application change sky reason", "vote create-kick sky Steve", "vote create-mayor sky Steve",
            "vote settle 00000000-0000-0000-0000-000000000001", "expand view sky",
            "expand preview sky north", "money reconcile"})
    void removedCommandsCannotReachBusinessLogic(String input) {
        when(sender.hasPermission(TownAdminPermissions.ROOT)).thenReturn(true);
        lamp.dispatch(actor, "tianjitown " + input);
        verify(messages).send(eq(sender), eq("chat.admin.argument-error"), anyMap());
        verifyNoInteractions(runtime);
    }

    @Test
    void townCodeReachesRepository() {
        when(sender.hasPermission(TownAdminPermissions.ROOT)).thenReturn(true);
        TownRepository repository = mock(TownRepository.class);
        when(runtime.repository()).thenReturn(repository);
        when(repository.findTownByCode("sky")).thenReturn(java.util.Optional.of(mock(TownSnapshot.class)));
        doAnswer(invocation -> { ((Supplier<?>) invocation.getArgument(1)).get(); return null; })
                .when(runtime).read(eq(sender), any(), any());
        execute("town", "view", "sky");
        verify(repository).findTownByCode("sky");
        verify(repository, never()).findTownByName(anyString());
    }

    @Test
    void lampKeepsTownCodeSuggestionsAndReasonHints() {
        when(sender.hasPermission(TownAdminPermissions.MONEY)).thenReturn(true);
        when(completer.complete(eq(sender), aryEq(new String[]{"money", "view", "sk"})))
                .thenReturn(List.of("sky"));
        when(completer.complete(eq(sender), aryEq(new String[]{"money", "adjust", "sky", "10", ""})))
                .thenReturn(List.of("<原因>"));
        assertEquals(List.of("sky"), lamp.autoCompleter().complete(actor, "tianjitown money view sk"));
        assertEquals(List.of("<原因>"), lamp.autoCompleter().complete(actor, "tianjitown money adjust sky 10 "));
        verifyNoInteractions(runtime);
    }

    @Test
    void numericSuggestionsComeFromLampAnnotations() {
        when(sender.hasPermission(TownAdminPermissions.OPERATIONS)).thenReturn(true);
        assertEquals(Set.of("1", "7", "14", "30", "90", "180"),
                Set.copyOf(lamp.autoCompleter().complete(actor, "tianjitown diagnose ")));
        verifyNoInteractions(completer);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cancelResolvesTheTownsOpenVote(boolean hasOpenVote) {
        when(sender.hasPermission(TownAdminPermissions.ROOT)).thenReturn(true);
        when(sender.getName()).thenReturn("Console");
        when(messages.plainText(TownAdminCompletionHints.REASON_KEY, Map.of())).thenReturn("<原因>");
        TownRepository repository = mock(TownRepository.class);
        var governance = mock(org.allivlisey.tianjitown.storage.governance.GovernanceRepository.class);
        when(runtime.repository()).thenReturn(repository);
        when(runtime.governance()).thenReturn(governance);
        TownSnapshot town = mock(TownSnapshot.class);
        UUID townId = UUID.randomUUID();
        UUID voteId = UUID.randomUUID();
        UUID actorId = new UUID(0, 0);
        when(town.id()).thenReturn(townId);
        when(repository.findTownByCode("sky")).thenReturn(java.util.Optional.of(town));
        var vote = mock(org.allivlisey.tianjitown.storage.governance.VoteSnapshot.class);
        when(vote.id()).thenReturn(voteId);
        when(governance.listTownVotes(townId, actorId, true))
                .thenReturn(hasOpenVote ? List.of(vote) : List.of());
        when(governance.cancelVote(voteId, actorId, "Console", "test reason")).thenReturn(vote);
        when(messages.text("chat.admin.vote-no-open", Map.of("town", "sky")))
                .thenReturn("No open vote");
        doAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(1);
            if (hasOpenVote) {
                Object result = operation.get();
                java.util.function.Consumer<Object> success = invocation.getArgument(2);
                success.accept(result);
            } else {
                assertEquals("No open vote", org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalArgumentException.class, operation::get).getMessage());
            }
            return null;
        }).when(runtime).write(eq(sender), any(), any());

        execute("vote", "cancel", "sky", "test", "reason");

        verify(governance).listTownVotes(townId, actorId, true);
        if (hasOpenVote) {
            verify(governance).cancelVote(voteId, actorId, "Console", "test reason");
            verify(messages).send(sender, "chat.admin.vote-cancelled", Map.of("id", voteId));
        } else {
            verify(governance, never()).cancelVote(any(), any(), anyString(), anyString());
        }
    }

    private void execute(String... args) {
        lamp.dispatch(actor, "tianjitown" + (args.length == 0 ? "" : " " + String.join(" ", args)));
    }
}

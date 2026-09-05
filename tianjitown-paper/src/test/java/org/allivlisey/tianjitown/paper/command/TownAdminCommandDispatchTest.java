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
        assertEquals(List.of("list"), lamp.autoCompleter().complete(actor, "townadmin town li"));
    }

    @Test
    void unauthorizedCommandNeverReachesTheExtractedHandler() {
        execute("money", "reconcile");

        verify(messages).send(sender, "chat.admin.no-permission");
        verifyNoInteractions(runtime);
    }

    @Test
    void authorizedEconomyCommandStillReachesTheRuntime() {
        when(sender.hasPermission(TownAdminPermissions.MONEY)).thenReturn(true);

        execute("money", "reconcile");

        verify(runtime).reconcileSettlement();
        verify(messages).send(sender, "chat.admin.settlement-reconcile-submitted");
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
        assertEquals(List.of("money"), lamp.autoCompleter().complete(actor, "townadmin mo"));
        assertEquals(List.of("reconcile"), lamp.autoCompleter().complete(actor, "townadmin money rec"));
        assertEquals(java.util.Set.of("reconcile", "view", "adjust"),
                java.util.Set.copyOf(lamp.autoCompleter().complete(actor, "townadmin money ")));
        verifyNoInteractions(completer);
    }

    @Test
    void permissionFilteredCompletionsDoNotExposeOtherScopesOrSecretCommands() {
        when(sender.hasPermission(TownAdminPermissions.MONEY)).thenReturn(true);
        assertEquals(Set.of("help", "money"), Set.copyOf(lamp.autoCompleter().complete(actor, "townadmin ")));
        assertEquals(List.of(), lamp.autoCompleter().complete(actor, "townadmin tax "));
        verifyNoInteractions(runtime);
    }

    @Test
    void consoleCannotExecuteOrCompletePlayerOnlyActions() {
        when(sender.hasPermission(TownAdminPermissions.ROOT)).thenReturn(true);
        assertEquals(List.of("list"), lamp.autoCompleter().complete(actor, "townadmin station "));
        assertFalse(lamp.autoCompleter().complete(actor, "townadmin land ").contains("preview"));
        assertFalse(lamp.autoCompleter().complete(actor, "townadmin expand ").contains("preview"));
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
        verify(ui).giveHandbook(targetPlayer, true);
    }

    @Test
    void runtimeGateRunsBeforeBusinessLogic() {
        when(sender.hasPermission(TownAdminPermissions.MONEY)).thenReturn(true);
        when(plugin.townRuntime()).thenReturn(null);
        execute("money", "reconcile");
        verify(messages).send(sender, "chat.admin.runtime-not-ready");
        verifyNoInteractions(runtime);
    }

    @ParameterizedTest
    @ValueSource(strings = {"audit 0", "audit 201", "audit invalid", "audit 20 extra",
            "diagnose 0", "diagnose 181", "vote settle invalid", "vote cancel invalid reason",
            "money view", "reload extra", "money reconcile extra", "confirm token extra"})
    void rejectsInvalidInputBeforeAnyBusinessAction(String input) {
        when(sender.hasPermission(TownAdminPermissions.ROOT)).thenReturn(true);
        lamp.dispatch(actor, "townadmin " + input);
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

    @Test
    void uuidIsResolvedBeforeTheVoteWriteIsQueued() {
        when(sender.hasPermission(TownAdminPermissions.ROOT)).thenReturn(true);
        UUID voteId = UUID.randomUUID();
        execute("vote", "settle", voteId.toString());
        verify(runtime).write(eq(sender), any(), any());
        verifyNoInteractions(messages);
    }

    @Test
    void greedyTownNameReachesTheRepositoryWithoutLosingWords() {
        when(sender.hasPermission(TownAdminPermissions.ROOT)).thenReturn(true);
        TownRepository repository = mock(TownRepository.class);
        when(runtime.repository()).thenReturn(repository);
        when(repository.findTownByName("天际 之城")).thenReturn(java.util.Optional.of(mock(TownSnapshot.class)));
        doAnswer(invocation -> { ((Supplier<?>) invocation.getArgument(1)).get(); return null; })
                .when(runtime).read(eq(sender), any(), any());
        execute("town", "view", "天际", "之城");
        verify(repository).findTownByName("天际 之城");
    }

    @Test
    void lampKeepsMultiWordDataSuggestionsAndReasonHints() {
        when(sender.hasPermission(TownAdminPermissions.MONEY)).thenReturn(true);
        when(completer.complete(eq(sender), aryEq(new String[]{"money", "view", "天际", ""})))
                .thenReturn(List.of("之城"));
        when(completer.complete(eq(sender), aryEq(new String[]{"money", "adjust", "天际", "之城", "10", ""})))
                .thenReturn(List.of("<原因>"));
        assertEquals(List.of("之城"), lamp.autoCompleter().complete(actor, "townadmin money view 天际 "));
        assertEquals(List.of("<原因>"), lamp.autoCompleter().complete(actor, "townadmin money adjust 天际 之城 10 "));
        verifyNoInteractions(runtime);
    }

    @Test
    void numericSuggestionsComeFromLampAnnotations() {
        when(sender.hasPermission(TownAdminPermissions.OPERATIONS)).thenReturn(true);
        assertEquals(Set.of("1", "7", "14", "30", "90", "180"),
                Set.copyOf(lamp.autoCompleter().complete(actor, "townadmin diagnose ")));
        verifyNoInteractions(completer);
    }

    private void execute(String... args) {
        lamp.dispatch(actor, "townadmin" + (args.length == 0 ? "" : " " + String.join(" ", args)));
    }
}

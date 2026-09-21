package org.allivlisey.tianjitown.paper.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.allivlisey.tianjitown.integrations.vault.VaultSettlementService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.paper.runtime.TownApplicationFeeRuntime;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.allivlisey.tianjitown.storage.town.ApplicationFeeOperation;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import revxrsal.commands.Lamp;
import revxrsal.commands.bukkit.BukkitVisitors;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TownAdminRecoveryCommandsTest {
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final PluginMessages messages = mock(PluginMessages.class);
    private final TownRuntime runtime = mock(TownRuntime.class);
    private final EconomyRepository finance = mock(EconomyRepository.class);
    private final TownRepository towns = mock(TownRepository.class);
    private final TownApplicationFeeRuntime fees = mock(TownApplicationFeeRuntime.class);
    private final VaultSettlementService vault = mock(VaultSettlementService.class);
    private final CommandSender sender = mock(CommandSender.class);
    private final Player targetPlayer = mock(Player.class);
    private final BukkitCommandActor actor = mock(BukkitCommandActor.class);
    private final UUID operationId = UUID.randomUUID(), applicationId = UUID.randomUUID();
    private final EconomyRepository.EconomyOperation operation = new EconomyRepository.EconomyOperation(
            operationId, UUID.randomUUID(), "DONATION", "donation:key", 100, UUID.randomUUID(),
            "Player", "donation", "COMPENSATION_REQUIRED", "result unknown", Instant.now());
    private final EconomyRepository.TaxSubsidyRecovery subsidy = new EconomyRepository.TaxSubsidyRecovery(
            UUID.randomUUID(), "jobs:subsidy:key", 100, 100, "RESERVED", "result unknown", true);
    private final ApplicationFeeOperation fee = new ApplicationFeeOperation(applicationId, UUID.randomUUID(),
            100, ApplicationFeeOperation.State.COLLECTION_UNKNOWN, "result unknown", 17);
    private final EconomyRepository.IncomeTaxCollection incomeTax = new EconomyRepository.IncomeTaxCollection(
            UUID.randomUUID(), new EconomyRepository.ExternalIncomeTax(UUID.randomUUID(), "jobs:collection:key",
                    "JOBS", UUID.randomUUID(), "Player", 1_000, 1_000, 100),
            "AMBIGUOUS", "result unknown", 23);
    private Lamp<BukkitCommandActor> lamp;
    private TownAdminCommand facade;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(plugin.messages()).thenReturn(messages);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(plugin.townRuntime()).thenReturn(runtime);
        when(runtime.finance()).thenReturn(finance);
        when(runtime.repository()).thenReturn(towns);
        when(runtime.applicationFees()).thenReturn(fees);
        when(runtime.settlement()).thenReturn(vault);
        when(vault.formatMinor(anyLong())).thenReturn("1.00");
        when(actor.sender()).thenReturn(sender);
        when(sender.getName()).thenReturn("Admin");
        when(sender.hasPermission(TownAdminPermissions.ROOT)).thenReturn(true);
        when(messages.text(anyString())).thenAnswer(c -> c.getArgument(0));
        when(messages.text(anyString(), anyMap())).thenAnswer(c -> c.getArgument(0));
        when(messages.plainText(anyString(), anyMap())).thenAnswer(c -> c.getArgument(0));
        when(messages.component(anyString())).thenAnswer(c -> Component.text((String) c.getArgument(0)));
        when(messages.component(anyString(), anyMap())).thenAnswer(c -> Component.text((String) c.getArgument(0)));
        when(finance.pendingOperations()).thenReturn(List.of(operation));
        when(finance.pendingTaxSubsidies(100)).thenReturn(List.of(subsidy));
        when(finance.pendingIncomeTaxCollections()).thenReturn(List.of(incomeTax));
        when(towns.pendingApplicationFees(100)).thenReturn(List.of(fee));
        when(towns.applicationFeeOperation(applicationId)).thenReturn(fee);
        doAnswer(c -> {
            Object value = ((Supplier<?>) c.getArgument(1)).get();
            ((Consumer<Object>) c.getArgument(2)).accept(value);
            return null;
        }).when(runtime).read(eq(sender), any(), any());
        doAnswer(c -> {
            Object value = ((Supplier<?>) c.getArgument(1)).get();
            ((Consumer<Object>) c.getArgument(2)).accept(value);
            return null;
        }).when(runtime).write(eq(sender), any(), any());
        lamp = TownAdminLamp.configure(Lamp.<BukkitCommandActor>builder()
                        .accept(BukkitVisitors.bukkitSenderResolver())
                        .parameterTypes(types -> types.addParameterType(Player.class,
                                (input, context) -> { input.readString(); return targetPlayer; })), plugin,
                mock(TownAdminTabCompleter.class)).build();
        facade = new TownAdminCommand(plugin);
        facade.register(lamp);
    }

    @Test
    void pendingQueriesDisplayAllFourKindsWithoutMutatingThem() {
        dispatch("money pending");
        dispatch("money subsidy pending");
        dispatch("money tax pending");
        dispatch("application fee list");
        dispatch("application fee inspect " + applicationId);

        verify(finance).pendingOperations();
        verify(finance).pendingTaxSubsidies(100);
        verify(finance).pendingIncomeTaxCollections();
        verify(towns).pendingApplicationFees(100);
        verify(towns).applicationFeeOperation(applicationId);
        verify(messages).send(eq(sender), eq("chat.admin.recovery-entry"), anyMap());
        verify(messages).send(eq(sender), eq("chat.admin.subsidy-recovery-entry"), anyMap());
        verify(messages).send(eq(sender), eq("chat.admin.income-tax-recovery-entry"), anyMap());
        verify(messages, times(2)).send(eq(sender), eq("chat.admin.application-fee-entry"), anyMap());
        assertNoMutation();
    }

    @Test
    void economyResolutionKeepsSnapshotAndMultiwordReasonUntilConfirmation() {
        dispatch("money resolve " + operationId + " applied verified external statement");
        assertNoMutation();
        String token = confirmationToken();

        dispatch("confirm " + token);

        verify(runtime).resolveEconomyOperation(eq(sender), same(operation), eq(true),
                eq("verified external statement"), any());
        dispatch("confirm " + token);
        verify(runtime, times(1)).resolveEconomyOperation(any(), any(), anyBoolean(), anyString(), any());
    }

    @Test
    void subsidyResolutionWritesOnlyAfterConfirmation() {
        dispatch("money subsidy resolve jobs:subsidy:key paid verified external statement");
        assertNoMutation();

        dispatch("confirm " + confirmationToken());

        verify(finance).resolveTaxSubsidy(eq(subsidy), eq(true), any(),
                eq("Admin"), eq("verified external statement"));
        verify(runtime).reconcileSettlement();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void subsidyStillBeingProcessedDoesNotOfferAConfirmation(boolean taxRecorded) {
        var inProgress = new EconomyRepository.TaxSubsidyRecovery(subsidy.townId(), subsidy.businessKey(),
                subsidy.taxMinor(), subsidy.subsidyMinor(), "RESERVED", taxRecorded ? null : "awaiting tax", taxRecorded);
        when(finance.pendingTaxSubsidies(100)).thenReturn(List.of(inProgress));

        dispatch("money subsidy resolve jobs:subsidy:key paid verified external statement");

        verify(messages).send(sender, "chat.admin.subsidy-recovery-running");
        verify(sender, never()).sendMessage(any(Component.class));
        assertNoMutation();
    }

    @Test
    void incomeTaxResolutionKeepsInspectedSnapshotAndReasonUntilConfirmation() {
        dispatch("money tax resolve " + incomeTax.operationId() + " paid verified external statement");
        assertNoMutation();
        String token = confirmationToken();

        dispatch("confirm " + token);
        dispatch("confirm " + token);

        verify(runtime, times(1)).resolveIncomeTaxCollection(eq(sender), same(incomeTax), eq(true),
                eq("verified external statement"), any());
        verify(runtime, never()).refundIncomeTaxCollection(any(), any(), any());
    }

    @Test
    void incomeTaxRefundUsesInspectedSnapshotOnlyOnceAfterConfirmation() {
        var refundable = new EconomyRepository.IncomeTaxCollection(incomeTax.operationId(), incomeTax.tax(),
                "REFUND_REQUIRED", "player debit only", 24);
        when(finance.pendingIncomeTaxCollections()).thenReturn(List.of(refundable));
        dispatch("money tax refund " + refundable.operationId());
        assertNoMutation();
        String token = confirmationToken();

        dispatch("confirm " + token);
        dispatch("confirm " + token);

        verify(runtime, times(1)).refundIncomeTaxCollection(eq(sender), same(refundable), any());
        verify(runtime, never()).resolveIncomeTaxCollection(any(), any(), anyBoolean(), anyString(), any());
    }

    @Test
    void feeResolutionKeepsVersionAndMultiwordReasonUntilConfirmation() {
        dispatch("application fee resolve " + applicationId + " no_payment verified external statement");
        assertNoMutation();

        dispatch("confirm " + confirmationToken());

        verify(fees).resolve(eq(sender), eq(applicationId), eq(17L),
                eq(ApplicationFeeOperation.Resolution.NO_PAYMENT), eq("verified external statement"), any());
    }

    @Test
    void refundRetryUsesTheInspectedVersionOnlyAfterConfirmation() {
        dispatch("application fee retry " + applicationId);
        assertNoMutation();

        dispatch("confirm " + confirmationToken());

        verify(fees).refund(eq(sender), eq(applicationId), eq(17L), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"money", "subsidy", "fee", "retry", "tax", "taxRefund"})
    void permissionRevokedAfterPreviewIsCheckedAgainInsideConfirmation(String kind) {
        dispatch(validCommand(kind));
        String token = confirmationToken();
        when(sender.hasPermission(TownAdminPermissions.ROOT)).thenReturn(false);

        // Invoke the real confirmation handler to exercise the deferred action's own check,
        // independently of Lamp's command-level permission check.
        facade.confirm(sender, token);

        assertNoMutation();
        verify(messages).send(eq(sender), eq("chat.admin.confirm-start-failed"), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"money", "subsidy", "fee", "retry", "tax", "taxRefund"})
    void aConfirmationFromAPreviousRuntimeCannotApplyToItsReplacement(String kind) {
        dispatch(validCommand(kind));
        String token = confirmationToken();
        when(plugin.townRuntime()).thenReturn(mock(TownRuntime.class));

        facade.confirm(sender, token);

        assertNoMutation();
        verify(messages).send(eq(sender), eq("chat.admin.confirm-start-failed"), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"money", "subsidy", "fee", "tax"})
    void invalidResolutionDoesNotReadOrWrite(String kind) {
        dispatch(resolvePrefix(kind) + " invalid verified statement");

        verify(messages).send(eq(sender), eq("chat.admin.argument-error"), anyMap());
        verify(runtime, never()).read(any(), any(), any());
        assertNoMutation();
    }

    @ParameterizedTest
    @ValueSource(strings = {"money", "subsidy", "fee", "tax"})
    void missingReasonDoesNotReadOrWrite(String kind) {
        dispatch(resolvePrefix(kind) + " " + resolution(kind));

        verify(messages).send(eq(sender), eq("chat.admin.argument-error"), anyMap());
        verify(runtime, never()).read(any(), any(), any());
        assertNoMutation();
    }

    @ParameterizedTest
    @ValueSource(strings = {"money", "subsidy", "fee", "tax"})
    void blankReasonDoesNotReadOrWrite(String kind) {
        dispatch(resolvePrefix(kind) + " " + resolution(kind) + " \"   \"");

        verify(messages).send(eq(sender), eq("chat.admin.argument-error"), anyMap());
        verify(runtime, never()).read(any(), any(), any());
        assertNoMutation();
    }

    @Test
    void scopedPermissionsCannotReachRecoveryRuntime() {
        when(sender.hasPermission(TownAdminPermissions.ROOT)).thenReturn(false);
        when(sender.hasPermission("tianjitown.admin.money")).thenReturn(true);
        for (String command : List.of("money pending", "money subsidy pending", "money tax pending", "application fee list",
                "application fee inspect " + applicationId, validCommand("money"), validCommand("subsidy"),
                validCommand("fee"), validCommand("retry"), validCommand("tax"), validCommand("taxRefund"))) {
            clearInvocations(messages, runtime);
            dispatch(command);
            verify(messages).send(sender, "chat.admin.no-permission");
            verifyNoInteractions(runtime);
        }
    }

    private String validCommand(String kind) {
        return switch (kind) {
            case "retry" -> "application fee retry " + applicationId;
            case "taxRefund" -> "money tax refund " + incomeTax.operationId();
            default -> resolvePrefix(kind) + " " + resolution(kind) + " verified statement";
        };
    }

    private String resolvePrefix(String kind) {
        return switch (kind) {
            case "money" -> "money resolve " + operationId;
            case "subsidy" -> "money subsidy resolve " + subsidy.businessKey();
            case "fee" -> "application fee resolve " + applicationId;
            case "tax" -> "money tax resolve " + incomeTax.operationId();
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private String resolution(String kind) {
        return switch (kind) {
            case "money" -> "applied";
            case "subsidy" -> "paid";
            case "fee" -> "NO_PAYMENT";
            case "tax" -> "paid";
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private void assertNoMutation() {
        verify(runtime, never()).write(any(), any(), any());
        verify(runtime, never()).resolveEconomyOperation(any(), any(), anyBoolean(), anyString(), any());
        verify(runtime, never()).resolveIncomeTaxCollection(any(), any(), anyBoolean(), anyString(), any());
        verify(runtime, never()).refundIncomeTaxCollection(any(), any(), any());
        verifyNoInteractions(fees);
        verify(finance, never()).resolveTaxSubsidy(any(EconomyRepository.TaxSubsidyRecovery.class), anyBoolean(), any(), anyString(), anyString());
    }

    private void dispatch(String input) { lamp.dispatch(actor, "tianjitown " + input); }

    private String confirmationToken() {
        ArgumentCaptor<Component> components = ArgumentCaptor.forClass(Component.class);
        verify(sender, atLeastOnce()).sendMessage(components.capture());
        return components.getAllValues().stream().map(this::confirmationCommand).filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow(() -> new AssertionError("confirmation button not rendered"))
                .substring("/tianjitown confirm ".length());
    }

    private String confirmationCommand(Component component) {
        ClickEvent event = component.clickEvent();
        if (event != null && event.action() == ClickEvent.Action.RUN_COMMAND
                && event.payload() instanceof ClickEvent.Payload.Text payload
                && payload.value().startsWith("/tianjitown confirm ")) return payload.value();
        return component.children().stream().map(this::confirmationCommand).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }
}

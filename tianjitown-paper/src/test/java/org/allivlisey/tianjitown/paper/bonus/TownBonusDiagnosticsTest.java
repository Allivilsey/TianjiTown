package org.allivlisey.tianjitown.paper.bonus;

import io.papermc.paper.plugin.configuration.PluginMeta;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.integrations.quickshop.QuickShopHistoryProbe;
import org.allivlisey.tianjitown.integrations.vault.VaultSettlementService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.config.TownBonusSettings;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.storage.diagnostics.TownDiagnosticRepository;
import org.allivlisey.tianjitown.storage.database.DatabaseGate;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TownBonusDiagnosticsTest {
    @Test
    void manualSuccessOnlySendsSummaryAndStartupSuccessIsSilent() {
        PluginMessages messages = spy(plugin.messages());
        when(plugin.messages()).thenReturn(messages);
        diagnostics.diagnose(sender, 7);
        async.getFirst().run();
        main.getFirst().run();
        verify(messages).send(sender, "chat.bonus.diagnostic-summary-success");
        verify(messages, never()).send(eq(sender), eq("chat.bonus.diagnostic-line"), anyMap());
        clearInvocations(messages, sender);
        diagnostics.diagnoseAtStartup(ignored -> {});
        async.getLast().run();
        main.getLast().run();
        verifyNoInteractions(sender);
    }

    @TempDir Path directory;
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final TownRuntime host = mock(TownRuntime.class);
    private final TownDiagnosticRepository repository = mock(TownDiagnosticRepository.class);
    private final QuickShopHistoryProbe history = mock(QuickShopHistoryProbe.class);
    private final VaultSettlementService settlement = mock(VaultSettlementService.class);
    private final EconomyRepository finance = mock(EconomyRepository.class);
    private final LandProtectionService land = mock(LandProtectionService.class);
    private final ConsoleCommandSender sender = mock(ConsoleCommandSender.class);
    private final List<Runnable> async = new ArrayList<>();
    private final List<Runnable> main = new ArrayList<>();
    private TownBonusDiagnostics diagnostics;

    @BeforeEach
    void setup() {
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getConsoleSender()).thenReturn(sender);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.messages()).thenReturn(new PluginMessages(directory.toFile()));
        PluginMeta meta = mock(PluginMeta.class);
        when(plugin.getPluginMeta()).thenReturn(meta);
        when(meta.getVersion()).thenReturn("test");
        doAnswer(call -> async.add(call.getArgument(0))).when(plugin).runAsync(any());
        when(plugin.runMain(any())).thenAnswer(call -> main.add(call.getArgument(0)));
        when(host.settlement()).thenReturn(settlement);
        when(host.finance()).thenReturn(finance);
        when(host.landProtection()).thenReturn(land);
        DatabaseGate database = mock(DatabaseGate.class);
        when(host.database()).thenReturn(database);
        when(database.schemaVersion()).thenReturn("7");
        when(settlement.balanceMinor()).thenReturn(100L);
        EconomyRepository.Reconciliation reconciliation = mock(EconomyRepository.Reconciliation.class);
        when(reconciliation.healthy()).thenReturn(true);
        when(finance.inspectSettlement(100)).thenReturn(reconciliation);
        when(repository.diagnose(any())).thenReturn(new TownDiagnosticRepository.DiagnosticSnapshot(
                "ok", 0, Map.of(), 2, 50, List.of(), List.of()));
        when(history.inspect(any(), any())).thenReturn(QuickShopHistoryProbe.Result.available(2, 50, false, "ok"));
        diagnostics = new TownBonusDiagnostics(plugin, host, repository,
                new TownBonusSettings.Operations(7), history);
    }

    @Test
    void capturesBalanceBeforeAsyncCollectionAndInspectsLandOnMainBeforeWritingReport() throws Exception {
        var state = new TownDiagnosticRepository.LandState(UUID.randomUUID(), "town", "res",
                List.of(), List.of());
        when(repository.diagnose(any())).thenReturn(new TownDiagnosticRepository.DiagnosticSnapshot(
                "ok", 0, Map.of(), 2, 50, List.of(state), List.of()));
        LandProtectionService.Inspection inspection = mock(LandProtectionService.Inspection.class);
        when(inspection.state()).thenReturn(LandProtectionService.ProjectionState.HEALTHY);
        when(land.inspect("res", List.of(), List.of())).thenReturn(inspection);
        AtomicReference<TownBonusRuntime.DiagnosticResult> completed = new AtomicReference<>();

        diagnostics.diagnoseAtStartup(completed::set);
        verify(settlement).balanceMinor();
        verifyNoInteractions(repository, history, land, finance);
        async.getFirst().run();
        verify(finance).inspectSettlement(100);
        verifyNoInteractions(land);
        assertNull(completed.get());
        main.getFirst().run();
        verify(land).inspect("res", List.of(), List.of());
        assertTrue(completed.get().healthy());
        assertNull(completed.get().report());
        async.getLast().run();
        Path report = diagnostics.lastDiagnostic().report();
        assertTrue(Files.isRegularFile(report));
        assertTrue(Files.readString(report).contains("QuickShop purchase reconciliation=MATCH"));
    }

    @Test
    void rejectsConcurrentAndOutOfRangeRequestsWithoutStartingMoreWork() {
        assertThrows(IllegalArgumentException.class, () -> diagnostics.diagnose(sender, 0));
        assertThrows(IllegalArgumentException.class, () -> diagnostics.diagnose(sender, 181));
        diagnostics.diagnose(sender, 7);
        diagnostics.diagnose(sender, 7);
        AtomicReference<TownBonusRuntime.DiagnosticResult> rejected = new AtomicReference<>();
        diagnostics.diagnoseAtStartup(rejected::set);
        assertFalse(rejected.get().healthy());
        assertEquals(1, async.size());
        verify(settlement, times(1)).balanceMinor();
    }

    @Test
    void failedCollectionCompletesOnMainAndAllowsRetry() {
        when(repository.diagnose(any())).thenThrow(new IllegalStateException("offline"));
        AtomicReference<TownBonusRuntime.DiagnosticResult> completed = new AtomicReference<>();
        diagnostics.diagnoseAtStartup(completed::set);
        async.getFirst().run();
        assertNull(completed.get());
        main.getFirst().run();
        assertFalse(completed.get().healthy());
        assertTrue(completed.get().detail().contains("offline"));
        assertSame(completed.get(), diagnostics.lastDiagnostic());
        diagnostics.diagnose(sender, 7);
        assertEquals(2, async.size());
    }

    @Test
    void rejectedAsyncSubmissionCompletesFailureAndReleasesDiagnosticGate() {
        doReturn(false).when(plugin).runAsync(any());
        AtomicReference<TownBonusRuntime.DiagnosticResult> completed = new AtomicReference<>();
        diagnostics.diagnoseAtStartup(completed::set);
        assertFalse(completed.get().healthy());
        doAnswer(call -> async.add(call.getArgument(0))).when(plugin).runAsync(any());
        diagnostics.diagnose(sender, 7);
        assertEquals(1, async.size());
    }

    @Test
    void rejectedMainSubmissionReleasesDiagnosticGate() {
        doReturn(false).when(plugin).runMain(any());
        diagnostics.diagnose(sender, 7);
        async.getFirst().run();
        diagnostics.diagnose(sender, 7);
        assertEquals(2, async.size());
    }

    @Test
    void unavailableSettlementOrIncompleteHistoryCannotReportHealthy() {
        when(settlement.balanceMinor()).thenThrow(new IllegalStateException("offline"));
        when(history.inspect(any(), any())).thenReturn(QuickShopHistoryProbe.Result.available(2, 50, true, "limit"));
        diagnostics.diagnose(sender, 7);
        async.getFirst().run();
        main.getFirst().run();
        assertFalse(diagnostics.lastDiagnostic().healthy());
        verifyNoInteractions(finance);
    }

    @Test
    void retainsThirtyReportsAndLeavesUnrelatedFilesAlone() throws Exception {
        Path reports = Files.createDirectories(directory.resolve("diagnostics"));
        for (int i = 1; i <= 31; i++) {
            Files.writeString(reports.resolve("diagnostic-200001%02d-000000.txt".formatted(i)), "old");
        }
        Path unrelated = Files.writeString(reports.resolve("notes.txt"), "keep");
        diagnostics.diagnose(sender, 7);
        async.getFirst().run();
        main.getFirst().run();
        async.getLast().run();
        try (var files = Files.list(reports)) {
            assertEquals(31, files.count());
        }
        assertTrue(Files.exists(unrelated));
        assertFalse(Files.exists(reports.resolve("diagnostic-20000101-000000.txt")));
        assertFalse(Files.exists(reports.resolve("diagnostic-20000102-000000.txt")));
        assertTrue(Files.exists(diagnostics.lastDiagnostic().report()));
    }
}

package org.allivlisey.tianjitown.paper.bonus;

import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.integrations.quickshop.QuickShopHistoryProbe;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.bonus.TownBonusRuntime.DiagnosticResult;
import org.allivlisey.tianjitown.paper.config.TownBonusSettings;
import org.allivlisey.tianjitown.paper.message.LandProtectionMessages;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.storage.diagnostics.TownDiagnosticRepository;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Coordinates database diagnostics, main-thread inspections and report retention. */
final class TownBonusDiagnostics {
    private static final String SETTLEMENT_ACCOUNT_UNAVAILABLE =
            "diagnostic.bonus.settlement-account-unavailable";
    private static final String QUICKSHOP_HISTORY_INCOMPLETE =
            "diagnostic.bonus.quick-shop-reconciliation-incomplete";
    private static final String DIAGNOSTIC_REPORT_WRITE_FAILURE =
            "log.bonus.diagnostic-report-write-failure";
    private static final DateTimeFormatter REPORT_STAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss").withZone(org.allivlisey.tianjitown.core.time.TownTime.ZONE);
    private final TianjiTownPlugin plugin;
    private final TownRuntime host;
    private final TownDiagnosticRepository repository;
    private final TownBonusSettings.Operations settings;
    private final QuickShopHistoryProbe quickShopHistory;
    private final AtomicBoolean diagnosticRunning = new AtomicBoolean();
    private final AtomicReference<DiagnosticResult> lastDiagnostic;

    TownBonusDiagnostics(TianjiTownPlugin plugin, TownRuntime host,
                         TownDiagnosticRepository repository, TownBonusSettings.Operations settings,
                         QuickShopHistoryProbe quickShopHistory) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.host = Objects.requireNonNull(host, "host");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.quickShopHistory = Objects.requireNonNull(quickShopHistory, "quickShopHistory");
        this.lastDiagnostic = new AtomicReference<>(new DiagnosticResult(false, null,
                plugin.messages().text("chat.bonus.diagnostic-not-run"), null));
    }

    DiagnosticResult lastDiagnostic() {
        return lastDiagnostic.get();
    }

    void diagnose(CommandSender sender, int days) {
        if (days < 1 || days > 180) {
            throw new IllegalArgumentException(plugin.messages().plainText(
                    "chat.bonus.diagnostic-range"));
        }
        if (!diagnosticRunning.compareAndSet(false, true)) {
            plugin.messages().send(sender, "chat.bonus.diagnostic-running");
            return;
        }
        long externalBalance = captureExternalBalance();
        plugin.messages().send(sender, "chat.bonus.diagnostic-started");
        submitDiagnostic(sender, days, externalBalance, null);
    }

    void diagnoseAtStartup(Consumer<DiagnosticResult> completion) {
        Objects.requireNonNull(completion, "completion");
        int days = settings.quickShopDiagnosticDays();
        if (!diagnosticRunning.compareAndSet(false, true)) {
            completion.accept(failedDiagnostic(new IllegalStateException(
                    plugin.messages().plainText("chat.bonus.diagnostic-running"))));
            return;
        }
        submitDiagnostic(plugin.getServer().getConsoleSender(), days,
                captureExternalBalance(), completion);
    }

    private long captureExternalBalance() {
        try {
            return host.settlement().balanceMinor();
        } catch (RuntimeException | LinkageError exception) {
            return -1;
        }
    }

    private void submitDiagnostic(CommandSender sender, int days, long capturedExternal,
                                  Consumer<DiagnosticResult> completion) {
        Instant since = Instant.now().minus(java.time.Duration.ofDays(days));
        boolean submitted = plugin.runAsync(() -> {
            try {
                DiagnosticData data = collectDiagnosticData(days, since, capturedExternal);
                if (!plugin.runMain(() -> completeDiagnostic(sender, data, completion))) {
                    diagnosticRunning.set(false);
                }
            } catch (RuntimeException | LinkageError exception) {
                DiagnosticResult failed = failedDiagnostic(exception);
                if (!plugin.runMain(() -> completeFailedDiagnostic(sender, completion, failed))) {
                    diagnosticRunning.set(false);
                }
            }
        });
        if (!submitted) {
            completeFailedDiagnostic(sender, completion, failedDiagnostic(
                    new IllegalStateException("插件异步执行器不可用")));
        }
    }

    private DiagnosticData collectDiagnosticData(int days, Instant since,
                                                 long capturedExternal) {
        TownDiagnosticRepository.DiagnosticSnapshot database = repository.diagnose(since);
        String schemaVersion = host.database().schemaVersion();
        EconomyRepository.Reconciliation settlement = capturedExternal < 0 ? null
                : host.finance().inspectSettlement(capturedExternal);
        QuickShopHistoryProbe.Result history = quickShopHistory.inspect(since);
        return new DiagnosticData(days, database, schemaVersion, settlement, history);
    }

    private void completeDiagnostic(CommandSender sender, DiagnosticData data,
                                    Consumer<DiagnosticResult> completion) {
        DiagnosticResult result;
        try {
            result = finishDiagnostic(sender, data);
        } catch (RuntimeException | LinkageError exception) {
            result = failedDiagnostic(exception);
            completeFailedDiagnostic(sender, completion, result);
            return;
        }
        if (completion != null) {
            completion.accept(result);
        }
    }

    private void completeFailedDiagnostic(CommandSender sender,
                                          Consumer<DiagnosticResult> completion,
                                          DiagnosticResult failed) {
        diagnosticRunning.set(false);
        lastDiagnostic.set(failed);
        if (sender != null) {
            plugin.messages().send(sender, "chat.bonus.diagnostic-failed", Map.of(
                    "detail", safeText(failed.detail())));
        }
        if (completion != null) {
            completion.accept(failed);
        }
    }

    private DiagnosticResult failedDiagnostic(Throwable exception) {
        return new DiagnosticResult(false, Instant.now(),
                plugin.messages().text("chat.bonus.diagnostic-failed", Map.of(
                        "detail", safeMessage(exception))), null);
    }

    private DiagnosticResult finishDiagnostic(CommandSender sender, DiagnosticData data) {
        int days = data.days();
        TownDiagnosticRepository.DiagnosticSnapshot database = data.database();
        String schemaVersion = data.schemaVersion();
        EconomyRepository.Reconciliation settlement = data.settlement();
        QuickShopHistoryProbe.Result history = data.history();
        List<String> lines = new ArrayList<>();
        boolean healthy = database.quickCheck().equalsIgnoreCase("ok")
                && database.foreignKeyViolations() == 0;
        lines.add("TianjiTown " + plugin.getPluginMeta().getVersion()
                + " unified diagnostic @ " + org.allivlisey.tianjitown.core.time.TownTime.display(Instant.now()));
        lines.add("SQLite quick_check=" + database.quickCheck()
                + ", foreign_key_violations=" + database.foreignKeyViolations()
                + ", schema=" + schemaVersion);
        database.counts().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> lines.add("SQLite " + entry.getKey() + "=" + entry.getValue()));
        for (String key : List.of("failedProjections", "accountLedgerMismatches",
                "pendingEconomy", "pendingExpansions")) {
            healthy &= database.counts().getOrDefault(key, 0L) == 0;
        }
        int healthyResidence = 0;
        List<String> residenceErrors = new ArrayList<>();
        for (TownDiagnosticRepository.LandState state : database.landStates()) {
            LandProtectionService.Inspection inspection = host.landProtection().inspect(
                    state.residenceName(), state.areas(), state.members());
            if (inspection.state() == LandProtectionService.ProjectionState.HEALTHY) {
                healthyResidence++;
            } else {
                residenceErrors.add(state.townName() + "=" + inspection.state() + ":"
                        + LandProtectionMessages.detail(plugin.messages(), inspection));
            }
        }
        healthy &= residenceErrors.isEmpty();
        lines.add("Residence healthy=" + healthyResidence + "/" + database.landStates().size());
        residenceErrors.forEach(error -> lines.add("Residence ERROR " + error));
        if (settlement == null) {
            healthy = false;
            lines.add(plugin.messages().plainText(SETTLEMENT_ACCOUNT_UNAVAILABLE));
        } else {
            healthy &= settlement.healthy();
            lines.add("Vault settlement external=" + settlement.externalBalanceMinor()
                    + ", internal=" + settlement.internalBalanceMinor()
                    + ", pending=" + settlement.pendingMinor()
                    + ", required=" + settlement.requiredMinor()
                    + ", healthy=" + settlement.healthy());
        }
        lines.add("QuickShop history available=" + history.available()
                + ", records=" + history.successfulTaxRecords()
                + ", taxMinor=" + history.taxMinor() + ", truncated=" + history.truncated()
                + ", detail=" + history.detail());
        lines.add("TianjiTown QuickShop records(" + days + "d)=" + database.internalTaxCount()
                + ", taxMinor=" + database.internalTaxMinor());
        boolean comparable = history.available() && !history.truncated();
        boolean historyMatches = comparable
                && history.successfulTaxRecords() == database.internalTaxCount()
                && history.taxMinor() == database.internalTaxMinor();
        if (comparable) {
            healthy &= historyMatches;
            lines.add("QuickShop reconciliation=" + (historyMatches ? "MATCH" : "DIFFERENCE"));
        } else {
            healthy = false;
            lines.add(plugin.messages().plainText(QUICKSHOP_HISTORY_INCOMPLETE));
        }
        String summaryKey = healthy ? "chat.bonus.diagnostic-summary-success"
                : "chat.bonus.diagnostic-summary-failure";
        String detail = plugin.messages().text(summaryKey);
        DiagnosticResult result = new DiagnosticResult(healthy, Instant.now(), detail, null);
        lastDiagnostic.set(result);
        diagnosticRunning.set(false);
        if (sender != null) {
            plugin.messages().send(sender, summaryKey);
            lines.forEach(line -> plugin.messages().send(sender, "chat.bonus.diagnostic-line",
                    Map.of("line", line)));
        }
        writeDiagnosticReport(lines, result);
        return result;
    }

    private void writeDiagnosticReport(List<String> lines, DiagnosticResult base) {
        plugin.runAsync(() -> {
            Path directory = plugin.getDataFolder().toPath().resolve("diagnostics")
                    .toAbsolutePath().normalize();
            Path report = directory.resolve("diagnostic-" + REPORT_STAMP.format(base.completedAt())
                    + ".txt");
            try {
                Files.createDirectories(directory);
                Files.write(report, lines);
                lastDiagnostic.set(new DiagnosticResult(base.healthy(), base.completedAt(),
                        base.detail(), report));
                pruneReports(directory);
            } catch (IOException exception) {
                plugin.getLogger().warning(plugin.messages().plainText(
                        DIAGNOSTIC_REPORT_WRITE_FAILURE,
                        Map.of("detail", safeText(safeMessage(exception)))));
            }
        });
    }

    private static void pruneReports(Path directory) throws IOException {
        List<Path> reports;
        try (var stream = Files.list(directory)) {
            reports = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("diagnostic-"))
                    .filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString())
                            .reversed())
                    .toList();
        }
        for (Path report : reports.stream().skip(30).toList()) {
            Files.deleteIfExists(report);
        }
    }

    private static String safeText(Object value) {
        return org.allivlisey.tianjitown.core.time.TownTime.display(value).replace('&', '＆').replace('§', '�');
    }

    private static String safeMessage(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getSimpleName() : value;
    }

    private record DiagnosticData(int days,
                                  TownDiagnosticRepository.DiagnosticSnapshot database,
                                  String schemaVersion,
                                  EconomyRepository.Reconciliation settlement,
                                  QuickShopHistoryProbe.Result history) {
    }
}

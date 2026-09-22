package org.allivlisey.tianjitown.paper.bonus;

import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.bonus.TownBonusRuntime.DiagnosticResult;
import org.allivlisey.tianjitown.paper.message.LandProtectionMessages;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.storage.diagnostics.TownDiagnosticRepository;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Coordinates database diagnostics, main-thread inspections and report retention. */
final class TownBonusDiagnostics {
    private static final String DIAGNOSTIC_REPORT_WRITE_FAILURE =
            "log.bonus.diagnostic-report-write-failure";
    private static final DateTimeFormatter REPORT_STAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss").withZone(org.allivlisey.tianjitown.core.time.TownTime.ZONE);
    private final TianjiTownPlugin plugin;
    private final TownRuntime host;
    private final TownDiagnosticRepository repository;
    private final AtomicBoolean diagnosticRunning = new AtomicBoolean();
    private final AtomicReference<DiagnosticResult> lastDiagnostic;

    TownBonusDiagnostics(TianjiTownPlugin plugin, TownRuntime host,
                         TownDiagnosticRepository repository) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.host = Objects.requireNonNull(host, "host");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.lastDiagnostic = new AtomicReference<>(new DiagnosticResult(false, null,
                plugin.messages().text("chat.bonus.diagnostic-not-run"), null));
    }

    DiagnosticResult lastDiagnostic() {
        return lastDiagnostic.get();
    }

    void diagnose(CommandSender sender) {
        if (!diagnosticRunning.compareAndSet(false, true)) {
            plugin.messages().send(sender, "chat.bonus.diagnostic-running");
            return;
        }
        submitDiagnostic(sender, null);
    }

    void diagnoseAtStartup(Consumer<DiagnosticResult> completion) {
        Objects.requireNonNull(completion, "completion");
        if (!diagnosticRunning.compareAndSet(false, true)) {
            completion.accept(failedDiagnostic(new IllegalStateException(
                    plugin.messages().plainText("chat.bonus.diagnostic-running"))));
            return;
        }
        submitDiagnostic(plugin.getServer().getConsoleSender(), completion);
    }

    private void submitDiagnostic(CommandSender sender,
                                  Consumer<DiagnosticResult> completion) {
        boolean submitted = plugin.runAsync(() -> {
            try {
                DiagnosticData data = collectDiagnosticData();
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

    private DiagnosticData collectDiagnosticData() {
        TownDiagnosticRepository.DiagnosticSnapshot database = repository.diagnose();
        String schemaVersion = host.database().schemaVersion();
        return new DiagnosticData(database, schemaVersion);
    }

    private void completeDiagnostic(CommandSender sender, DiagnosticData data,
                                    Consumer<DiagnosticResult> completion) {
        DiagnosticResult result;
        try {
            result = finishDiagnostic(sender, data, completion != null);
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

    private DiagnosticResult finishDiagnostic(CommandSender sender, DiagnosticData data, boolean startup) {
        TownDiagnosticRepository.DiagnosticSnapshot database = data.database();
        String schemaVersion = data.schemaVersion();
        List<String> lines = new ArrayList<>();
        boolean healthy = database.quickCheck().equalsIgnoreCase("ok")
                && database.foreignKeyViolations() == 0;
        // Business recovery and external reconciliation must remain available when the
        // database is usable. An unfinished payment is not a database integrity failure.
        boolean startupAllowed = healthy;
        lines.add("TianjiTown " + plugin.getPluginMeta().getVersion()
                + " unified diagnostic @ " + org.allivlisey.tianjitown.core.time.TownTime.display(Instant.now()));
        lines.add("SQLite quick_check=" + database.quickCheck()
                + ", foreign_key_violations=" + database.foreignKeyViolations()
                + ", schema=" + schemaVersion);
        database.counts().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> lines.add("SQLite " + entry.getKey() + "=" + entry.getValue()));
        for (String key : List.of("failedProjections", "accountLedgerMismatches",
                "pendingEconomy", "pendingExpansions", "pendingApplicationFees",
                "pendingIncomeTaxes", "pendingTaxSubsidies")) {
            healthy &= database.counts().getOrDefault(key, 0L) == 0;
        }
        healthy &= inspectWorldReferences(database.landStates(), lines);
        int healthyResidence = 0;
        List<String> residenceErrors = new ArrayList<>();
        for (TownDiagnosticRepository.LandState state : database.landStates()) {
            LandProtectionService.Inspection inspection;
            try {
                inspection = host.landProtection().inspect(
                        state.residenceName(), state.areas(), state.members());
            } catch (RuntimeException | LinkageError exception) {
                residenceErrors.add(state.townName() + "=" + safeMessage(exception));
                continue;
            }
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
        lines.add("Town economy: SQLite account/ledger; no external settlement account");
        String summaryKey = healthy ? "chat.bonus.diagnostic-summary-success"
                : "chat.bonus.diagnostic-summary-failure";
        String detail = plugin.messages().text(summaryKey);
        DiagnosticResult result = new DiagnosticResult(healthy, Instant.now(), detail, null,
                startupAllowed);
        lastDiagnostic.set(result);
        diagnosticRunning.set(false);
        if (sender != null && (!startup || !healthy)) {
            plugin.messages().send(sender, summaryKey);
            if (!healthy) lines.forEach(line -> plugin.messages().send(sender, "chat.bonus.diagnostic-line",
                    Map.of("line", line)));
        }
        writeDiagnosticReport(sender, lines, result);
        return result;
    }

    private boolean inspectWorldReferences(List<TownDiagnosticRepository.LandState> states,
                                           List<String> lines) {
        int checked = 0;
        int healthy = 0;
        List<String> details = new ArrayList<>();
        for (var state : states) {
            var references = state.areas().stream().map(area -> area.territory().center())
                    .map(center -> new WorldReference(center.worldId(), center.worldName()))
                    .distinct().toList();
            for (var reference : references) {
                checked++;
                World world = plugin.getServer().getWorld(reference.id());
                String context = "town=" + state.townName() + ", storedName=" + reference.name()
                        + ", expected=" + reference.id();
                if (world != null) {
                    healthy++;
                    details.add("World UUID OK " + context + ", loadedName=" + world.getName()
                            + ", actual=" + world.getUID());
                } else {
                    // A same-name world explains the mismatch, but cannot validate UUID-based indexes.
                    World sameName = plugin.getServer().getWorld(reference.name());
                    details.add("World UUID ERROR " + context + (sameName == null
                            ? ", actual=UNAVAILABLE, reason=WORLD_NOT_LOADED"
                            : ", actual=" + sameName.getUID() + ", reason=UUID_MISMATCH"));
                }
            }
        }
        lines.add("World UUID healthy=" + healthy + "/" + checked);
        lines.addAll(details);
        return healthy == checked;
    }

    private record WorldReference(UUID id, String name) {}

    private void writeDiagnosticReport(CommandSender sender, List<String> lines, DiagnosticResult base) {
        plugin.runAsync(() -> {
            Path directory = plugin.getDataFolder().toPath().resolve("diagnostics")
                    .toAbsolutePath().normalize();
            Path report = directory.resolve("diagnostic-" + REPORT_STAMP.format(base.completedAt())
                    + ".txt");
            try {
                Files.createDirectories(directory);
                Files.write(report, lines);
                lastDiagnostic.set(new DiagnosticResult(base.healthy(), base.completedAt(),
                        base.detail(), report, base.startupAllowed()));
                pruneReports(directory);
                if (!base.healthy()) {
                    plugin.getLogger().warning("统一诊断报告: " + report);
                    if (sender != null && !(sender instanceof org.bukkit.command.ConsoleCommandSender)) {
                        plugin.runMain(() -> plugin.messages().send(sender,
                                "chat.bonus.diagnostic-report", Map.of("path", report.toString())));
                    }
                }
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

    private record DiagnosticData(TownDiagnosticRepository.DiagnosticSnapshot database,
                                  String schemaVersion) {
    }
}

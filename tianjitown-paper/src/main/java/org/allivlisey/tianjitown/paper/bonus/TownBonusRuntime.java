package org.allivlisey.tianjitown.paper.bonus;
import org.allivlisey.tianjitown.paper.config.TownBonusSettings;
import org.allivlisey.tianjitown.paper.message.LandProtectionMessages;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.integrations.quickshop.QuickShopHistoryProbe;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.allivlisey.tianjitown.storage.bonus.TownBonusRepository;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.BeaconInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class TownBonusRuntime implements Listener {
    private static final String INDEX_REFRESH_FAILURE =
            "log.bonus.index-refresh-failure";
    private static final String REFUND_COUNTER_CLEANUP_FAILURE =
            "log.bonus.refund-counter-cleanup-failure";
    private static final String BEACON_REFRESH_OBJECT_FAILURE =
            "log.bonus.beacon-refresh-object-failure";
    private static final String BEACON_RECORD_OBJECT_FAILURE =
            "log.bonus.beacon-record-object-failure";
    private static final String BEACON_CLEANUP_OBJECT_FAILURE =
            "log.bonus.beacon-cleanup-object-failure";
    private static final String SETTLEMENT_ACCOUNT_UNAVAILABLE =
            "diagnostic.bonus.settlement-account-unavailable";
    private static final String QUICKSHOP_HISTORY_INCOMPLETE =
            "diagnostic.bonus.quick-shop-reconciliation-incomplete";
    private static final String DIAGNOSTIC_REPORT_WRITE_FAILURE =
            "log.bonus.diagnostic-report-write-failure";
    private static final DateTimeFormatter REPORT_STAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private final TianjiTownPlugin plugin;
    private final TownRuntime host;
    private final TownBonusRepository repository;
    private final TownBonusSettings settings;
    private final QuickShopHistoryProbe quickShopHistory;
    private final AtomicReference<TownBonusRepository.BonusIndex> index = new AtomicReference<>(
            new TownBonusRepository.BonusIndex(Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
    private final Map<PlayerEffectKey, ManagedEffect> managedEffects = new HashMap<>();
    private final AtomicBoolean indexRefreshRunning = new AtomicBoolean();
    private final AtomicBoolean diagnosticRunning = new AtomicBoolean();
    private final AtomicBoolean beaconPlayerFailureLogged = new AtomicBoolean();
    private final AtomicBoolean beaconCleanupFailureLogged = new AtomicBoolean();
    private final AtomicReference<DiagnosticResult> lastDiagnostic;

    public TownBonusRuntime(TianjiTownPlugin plugin, TownRuntime host,
                     TownBonusRepository repository, TownBonusSettings settings,
                     Plugin quickShop) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.host = Objects.requireNonNull(host, "host");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.quickShopHistory = new QuickShopHistoryProbe(quickShop,
                host.settlement().accountId(), host.settlement().scale(),
                plugin.messages()::plainText);
        this.lastDiagnostic = new AtomicReference<>(new DiagnosticResult(false, null,
                plugin.messages().text("chat.bonus.diagnostic-not-run"), null));
    }

    public TownBonusSettings settings() {
        return settings;
    }

    public DiagnosticResult lastDiagnostic() {
        return lastDiagnostic.get();
    }

    public boolean buildingRefundEnabled() {
        return plugin.getConfig().getBoolean("territory.building-refund.enabled",
                settings.buildingRefund().enabled());
    }

    public boolean beaconEnabled() {
        return plugin.getConfig().getBoolean("territory.beacon.enabled", settings.beacon().enabled());
    }

    public void refreshIndex() {
        if (!plugin.isEnabled() || !indexRefreshRunning.compareAndSet(false, true)) {
            return;
        }
        boolean submitted = plugin.runAsync(() -> {
            try {
                index.set(repository.loadBonusIndex());
            } catch (RuntimeException exception) {
                plugin.getLogger().warning(plugin.messages().plainText(INDEX_REFRESH_FAILURE,
                        Map.of("detail", safeText(safeMessage(exception)))));
            } finally {
                indexRefreshRunning.set(false);
            }
        });
        if (!submitted) {
            indexRefreshRunning.set(false);
        }
    }

    public void cleanupCounters() {
        plugin.runAsync(() -> {
            try {
                repository.cleanupRefundCounters(LocalDate.now(settings.buildingRefund().resetZone())
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .minusWeeks(settings.buildingRefund().retentionWeeks()));
            } catch (RuntimeException exception) {
                plugin.getLogger().warning(plugin.messages().plainText(
                        REFUND_COUNTER_CLEANUP_FAILURE,
                        Map.of("detail", safeText(safeMessage(exception)))));
            }
        });
    }

    public void diagnose(CommandSender sender, int days) {
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

    public void diagnoseAtStartup(Consumer<DiagnosticResult> completion) {
        Objects.requireNonNull(completion, "completion");
        int days = settings.operations().quickShopDiagnosticDays();
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
        TownBonusRepository.DiagnosticSnapshot database = repository.diagnose(since);
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

    public void refreshBeaconEffects() {
        if (!plugin.isEnabled()) {
            return;
        }
        if (!beaconEnabled()) {
            clearManagedEffects();
            return;
        }
        TownBonusSettings.BeaconEnhancement config = settings.beacon();
        TownBonusRepository.BonusIndex snapshot = index.get();
        Map<PlayerEffectKey, Integer> desiredEffects = new HashMap<>();
        boolean playerFailure = false;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                collectDesiredEffects(player, snapshot, desiredEffects);
            } catch (RuntimeException | LinkageError exception) {
                playerFailure = true;
                if (beaconPlayerFailureLogged.compareAndSet(false, true)) {
                    plugin.getLogger().warning(plugin.messages().plainText(
                            BEACON_REFRESH_OBJECT_FAILURE,
                            Map.of("detail", safeText(safeMessage(exception)))));
                }
            }
        }
        if (!playerFailure) {
            beaconPlayerFailureLogged.set(false);
        }
        applyManagedEffects(desiredEffects, config);
    }

    private void collectDesiredEffects(Player player,
                                       TownBonusRepository.BonusIndex snapshot,
                                       Map<PlayerEffectKey, Integer> desiredEffects) {
        if (!player.isOnline()) {
            return;
        }
        World world = player.getWorld();
        UUID townId = snapshot.territories().get(new TownBonusRepository.ChunkKey(
                world.getUID(), player.getChunk().getX(), player.getChunk().getZ()));
        String residenceName = townId == null ? null : snapshot.residenceNames().get(townId);
        Map<String, Integer> effects = townId == null ? null
                : snapshot.beaconEffects().get(townId);
        Location location = player.getLocation();
        if (residenceName == null || effects == null || !host.landProtection().contains(
                residenceName, world.getUID(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ())) {
            return;
        }
        for (Map.Entry<String, Integer> effect : effects.entrySet()) {
            PotionEffectType type = PotionEffectType.getByKey(
                    org.bukkit.NamespacedKey.fromString(effect.getKey()));
            if (type != null) {
                desiredEffects.merge(new PlayerEffectKey(player.getUniqueId(), type),
                        effect.getValue(), Math::max);
            }
        }
    }

    public void clearAll() {
        clearManagedEffects();
        managedEffects.clear();
    }

    public void recoverTaggedBeacons() {
        // 第七版不再修改或扫描信标方块，无需恢复方块持久化状态。
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event instanceof BlockMultiPlaceEvent || !buildingRefundEnabled()
                || event.getPlayer().getGameMode() != GameMode.SURVIVAL) {
            return;
        }
        Material material = event.getBlockPlaced().getType();
        ItemStack source = event.getItemInHand();
        if (settings.buildingRefund().blacklist().contains(material)
                || !TownBonusSettings.isSafeSingleBlock(material) || source.getType() != material
                || source.hasItemMeta()) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        TownBonusRepository.BonusIndex snapshot = index.get();
        UUID townId = snapshot.memberships().get(player.getUniqueId());
        UUID territoryTown = snapshot.territories().get(new TownBonusRepository.ChunkKey(
                block.getWorld().getUID(), block.getChunk().getX(), block.getChunk().getZ()));
        String residenceName = townId == null ? null : snapshot.residenceNames().get(townId);
        if (townId == null || !townId.equals(territoryTown) || residenceName == null
                || !host.landProtection().contains(residenceName, block.getWorld().getUID(),
                block.getX(), block.getY(), block.getZ())) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= settings.buildingRefund().chance()) {
            return;
        }
        LocalDate weekStart = LocalDate.now(settings.buildingRefund().resetZone())
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        UUID worldId = block.getWorld().getUID();
        int chunkX = block.getChunk().getX();
        int chunkZ = block.getChunk().getZ();
        String materialKey = material.getKey().toString();
        host.write(player, () -> repository.reserveBuildingRefund(townId, player.getUniqueId(),
                worldId, chunkX, chunkZ, weekStart, materialKey,
                settings.buildingRefund().weeklyLimit()), result -> {
            if (!result.granted() || !player.isOnline()) {
                return;
            }
            ItemStack refund = new ItemStack(material, 1);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(refund);
            overflow.values().forEach(item -> player.getWorld().dropItemNaturally(
                    player.getLocation(), item));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7F, 1.35F);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBeaconInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (!beaconEnabled() || event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null
                || block.getType() != Material.BEACON) {
            return;
        }
        TownBonusRepository.BonusIndex snapshot = index.get();
        UUID townId = snapshot.territories().get(new TownBonusRepository.ChunkKey(
                block.getWorld().getUID(), block.getChunk().getX(), block.getChunk().getZ()));
        String residenceName = townId == null ? null : snapshot.residenceNames().get(townId);
        if (residenceName == null || !host.landProtection().contains(residenceName,
                block.getWorld().getUID(), block.getX(), block.getY(), block.getZ())) {
            return;
        }
        Player player = event.getPlayer();
        UUID membership = snapshot.memberships().get(player.getUniqueId());
        MemberRole role = snapshot.roles().get(player.getUniqueId());
        if (townId.equals(membership) && role != null && role.isLeader()) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setCancelled(true);
        player.sendActionBar(plugin.messages().component("chat.bonus.beacon-edit-forbidden"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBeaconInventoryClose(InventoryCloseEvent event) {
        if (!beaconEnabled() || !(event.getInventory() instanceof BeaconInventory inventory)
                || !(inventory.getHolder() instanceof Beacon beacon)) {
            return;
        }
        plugin.runMain(() -> recordBeaconEffects(beacon));
    }

    private void recordBeaconEffects(Beacon beacon) {
        try {
            recordBeaconEffectsChecked(beacon);
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().warning(plugin.messages().plainText(
                    BEACON_RECORD_OBJECT_FAILURE,
                    Map.of("detail", safeText(safeMessage(exception)))));
        }
    }

    private void recordBeaconEffectsChecked(Beacon beacon) {
        Block block = beacon.getBlock();
        TownBonusRepository.BonusIndex snapshot = index.get();
        UUID townId = snapshot.territories().get(new TownBonusRepository.ChunkKey(
                block.getWorld().getUID(), block.getChunk().getX(), block.getChunk().getZ()));
        String residenceName = townId == null ? null : snapshot.residenceNames().get(townId);
        if (beacon.getTier() < 1 || residenceName == null || !host.landProtection().contains(
                residenceName, block.getWorld().getUID(), block.getX(), block.getY(),
                block.getZ())) {
            return;
        }
        List<PotionEffect> effects = java.util.stream.Stream.of(beacon.getPrimaryEffect(),
                        beacon.getSecondaryEffect()).filter(Objects::nonNull).toList();
        for (PotionEffect effect : effects) {
            String effectKey = effect.getType().getKey().toString();
            host.write(plugin.getServer().getConsoleSender(),
                    () -> repository.recordBeaconEffect(townId, effectKey,
                            effect.getAmplifier()), changed -> {
                        if (changed) {
                            refreshIndex();
                        }
                    });
        }
    }

    private void applyManagedEffects(Map<PlayerEffectKey, Integer> desired,
                                     TownBonusSettings.BeaconEnhancement config) {
        int duration = Math.toIntExact(Math.min(Integer.MAX_VALUE,
                config.refreshIntervalTicks() * 2 + 40));
        for (Map.Entry<PlayerEffectKey, Integer> entry : desired.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey().playerId());
            if (player == null) {
                continue;
            }
            int amplifier = entry.getValue();
            player.addPotionEffect(new PotionEffect(entry.getKey().type(), duration, amplifier,
                    true, true, true));
            managedEffects.put(entry.getKey(), new ManagedEffect(amplifier, duration));
        }
        for (PlayerEffectKey key : new ArrayList<>(managedEffects.keySet())) {
            if (desired.containsKey(key)) {
                continue;
            }
            removeManagedEffect(key, managedEffects.remove(key));
        }
    }

    private void clearManagedEffects() {
        boolean failed = false;
        for (Map.Entry<PlayerEffectKey, ManagedEffect> entry
                : List.copyOf(managedEffects.entrySet())) {
            try {
                removeManagedEffect(entry.getKey(), entry.getValue());
            } catch (RuntimeException | LinkageError exception) {
                if (!failed && beaconCleanupFailureLogged.compareAndSet(false, true)) {
                    plugin.getLogger().warning(plugin.messages().plainText(
                            BEACON_CLEANUP_OBJECT_FAILURE,
                            Map.of("detail", safeText(safeMessage(exception)))));
                }
                failed = true;
            }
        }
        if (!failed) {
            beaconCleanupFailureLogged.set(false);
        }
        managedEffects.clear();
    }

    private void removeManagedEffect(PlayerEffectKey key, ManagedEffect managed) {
        Player player = plugin.getServer().getPlayer(key.playerId());
        if (player == null || managed == null) {
            return;
        }
        PotionEffect current = player.getPotionEffect(key.type());
        if (current != null && current.getAmplifier() == managed.amplifier()
                && current.getDuration() <= managed.maximumDuration() + 40) {
            player.removePotionEffect(key.type());
        }
    }


    private DiagnosticResult finishDiagnostic(CommandSender sender, DiagnosticData data) {
        int days = data.days();
        TownBonusRepository.DiagnosticSnapshot database = data.database();
        String schemaVersion = data.schemaVersion();
        EconomyRepository.Reconciliation settlement = data.settlement();
        QuickShopHistoryProbe.Result history = data.history();
        List<String> lines = new ArrayList<>();
        boolean healthy = database.quickCheck().equalsIgnoreCase("ok")
                && database.foreignKeyViolations() == 0;
        lines.add("TianjiTown " + plugin.getPluginMeta().getVersion()
                + " unified diagnostic @ " + Instant.now());
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
        for (TownBonusRepository.LandState state : database.landStates()) {
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
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    private static String safeMessage(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getSimpleName() : value;
    }

    private record PlayerEffectKey(UUID playerId, PotionEffectType type) {
    }

    private record ManagedEffect(int amplifier, int maximumDuration) {
    }

    private record DiagnosticData(int days,
                                  TownBonusRepository.DiagnosticSnapshot database,
                                  String schemaVersion,
                                  EconomyRepository.Reconciliation settlement,
                                  QuickShopHistoryProbe.Result history) {
    }

    public record DiagnosticResult(boolean healthy, Instant completedAt, String detail, Path report) {
    }
}

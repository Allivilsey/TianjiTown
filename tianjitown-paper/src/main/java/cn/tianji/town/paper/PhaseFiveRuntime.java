package cn.tianji.town.paper;

import cn.tianji.town.core.ports.LandProtectionService;
import cn.tianji.town.core.town.MemberRole;
import cn.tianji.town.integrations.quickshop.QuickShopHistoryProbe;
import cn.tianji.town.storage.phase3.PhaseThreeRepository;
import cn.tianji.town.storage.phase5.PhaseFiveRepository;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.persistence.PersistentDataType;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class PhaseFiveRuntime implements Listener {
    private static final DateTimeFormatter REPORT_STAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private final TianjiTownPlugin plugin;
    private final PhaseOneRuntime host;
    private final PhaseFiveRepository repository;
    private final PhaseFiveSettings settings;
    private final QuickShopHistoryProbe quickShopHistory;
    private final PhaseFiveBackupService backups;
    private final NamespacedKey beaconManagedKey;
    private final NamespacedKey beaconOriginalRangeKey;
    private final AtomicReference<PhaseFiveRepository.BonusIndex> index = new AtomicReference<>(
            new PhaseFiveRepository.BonusIndex(Map.of(), Map.of(), Map.of(), Map.of()));
    private final Map<BeaconKey, Double> modifiedBeacons = new HashMap<>();
    private final Map<PlayerEffectKey, ManagedEffect> managedEffects = new HashMap<>();
    private final AtomicBoolean indexRefreshRunning = new AtomicBoolean();
    private final AtomicBoolean diagnosticRunning = new AtomicBoolean();
    private final AtomicReference<DiagnosticResult> lastDiagnostic = new AtomicReference<>(
            new DiagnosticResult(false, null, "尚未执行", null));

    PhaseFiveRuntime(TianjiTownPlugin plugin, PhaseOneRuntime host,
                     PhaseFiveRepository repository, PhaseFiveSettings settings,
                     Plugin quickShop) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.host = Objects.requireNonNull(host, "host");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.quickShopHistory = new QuickShopHistoryProbe(quickShop,
                host.settlement().accountId(), host.settlement().scale());
        this.backups = new PhaseFiveBackupService(plugin, host.database(),
                settings.operations().backup());
        this.beaconManagedKey = new NamespacedKey(plugin, "enhanced_beacon");
        this.beaconOriginalRangeKey = new NamespacedKey(plugin, "beacon_original_range");
    }

    PhaseFiveSettings settings() {
        return settings;
    }

    PhaseFiveBackupService.Result lastBackup() {
        return backups.lastResult();
    }

    DiagnosticResult lastDiagnostic() {
        return lastDiagnostic.get();
    }

    boolean buildingRefundEnabled() {
        return plugin.getConfig().getBoolean("phase5.building-refund.enabled",
                settings.buildingRefund().enabled());
    }

    boolean beaconEnabled() {
        return plugin.getConfig().getBoolean("phase5.beacon.enabled", settings.beacon().enabled());
    }

    void refreshIndex() {
        if (!plugin.isEnabled() || !indexRefreshRunning.compareAndSet(false, true)) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                index.set(repository.loadBonusIndex());
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("刷新第五阶段领地加成缓存失败，将继续使用旧快照: "
                        + safeMessage(exception));
            } finally {
                indexRefreshRunning.set(false);
            }
        });
    }

    void cleanupCounters() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                repository.cleanupRefundCounters(LocalDate.now(settings.buildingRefund().resetZone())
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .minusWeeks(settings.buildingRefund().retentionWeeks()));
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("清理建筑返还周计数失败: " + safeMessage(exception));
            }
        });
    }

    void createBackup(CommandSender sender) {
        if (!settings.operations().backup().enabled()) {
            sender.sendMessage("§c定时备份已在配置中关闭。");
            return;
        }
        sender.sendMessage("§e正在创建 SQLite 在线备份与配置快照……");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            PhaseFiveBackupService.Result result = backups.create();
            plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(
                    (result.success() ? "§a" : "§c") + result.detail()
                            + (result.databaseFile() == null ? ""
                            : "；文件=" + result.databaseFile())));
        });
    }

    void createScheduledBackup() {
        if (!settings.operations().backup().enabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            PhaseFiveBackupService.Result result = backups.create();
            if (result.success()) {
                plugin.getLogger().info(result.detail() + "；文件=" + result.databaseFile());
            } else {
                plugin.getLogger().severe(result.detail());
            }
        });
    }

    void diagnose(CommandSender sender, int days) {
        if (days < 1 || days > 180) {
            throw new IllegalArgumentException("诊断范围必须在 1~180 天之间");
        }
        if (!diagnosticRunning.compareAndSet(false, true)) {
            sender.sendMessage("§e已有统一诊断正在运行，请稍后查看结果。");
            return;
        }
        long externalBalance;
        try {
            externalBalance = host.settlement().balanceMinor();
        } catch (RuntimeException exception) {
            externalBalance = -1;
        }
        sender.sendMessage("§e正在检查 SQLite、Residence、Vault 与 QuickShop 历史……");
        long capturedExternal = externalBalance;
        Instant since = Instant.now().minus(java.time.Duration.ofDays(days));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PhaseFiveRepository.DiagnosticSnapshot database = repository.diagnose(since);
                String schemaVersion = host.database().schemaVersion();
                PhaseThreeRepository.Reconciliation settlement = capturedExternal < 0 ? null
                        : host.finance().inspectSettlement(capturedExternal);
                QuickShopHistoryProbe.Result history = quickShopHistory.inspect(since);
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> finishDiagnostic(sender, days, database, schemaVersion, settlement,
                                history));
            } catch (RuntimeException exception) {
                diagnosticRunning.set(false);
                DiagnosticResult failed = new DiagnosticResult(false, Instant.now(),
                        "统一诊断失败: " + safeMessage(exception), null);
                lastDiagnostic.set(failed);
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> sender.sendMessage("§c" + failed.detail()));
            }
        });
    }

    void diagnoseScheduled() {
        diagnose(plugin.getServer().getConsoleSender(), settings.operations().quickShopDiagnosticDays());
    }

    void scanBeacons() {
        if (!plugin.isEnabled()) {
            return;
        }
        if (!beaconEnabled()) {
            resetAllBeacons();
            resetTaggedBeacons();
            clearManagedEffects();
            return;
        }
        PhaseFiveSettings.BeaconEnhancement config = settings.beacon();
        PhaseFiveRepository.BonusIndex snapshot = index.get();
        Map<UUID, List<PotionEffect>> effectsByTown = new HashMap<>();
        Map<PlayerEffectKey, Integer> desiredEffects = new HashMap<>();
        for (World world : plugin.getServer().getWorlds()) {
            if (!config.allowsWorld(world.getName())) {
                continue;
            }
            for (Chunk chunk : world.getLoadedChunks()) {
                UUID townId = snapshot.territories().get(new PhaseFiveRepository.ChunkKey(
                        world.getUID(), chunk.getX(), chunk.getZ()));
                if (townId == null) {
                    continue;
                }
                String residenceName = snapshot.residenceNames().get(townId);
                if (residenceName == null) {
                    continue;
                }
                for (BlockState state : chunk.getTileEntities(
                        block -> block.getType() == Material.BEACON, false)) {
                    if (state instanceof Beacon beacon) {
                        collectTownBeaconEffects(beacon, townId, residenceName, effectsByTown);
                    }
                }
            }
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            World world = player.getWorld();
            if (!config.allowsWorld(world.getName())) {
                continue;
            }
            UUID townId = snapshot.territories().get(new PhaseFiveRepository.ChunkKey(
                    world.getUID(), player.getChunk().getX(), player.getChunk().getZ()));
            String residenceName = townId == null ? null : snapshot.residenceNames().get(townId);
            List<PotionEffect> effects = townId == null ? null : effectsByTown.get(townId);
            Location location = player.getLocation();
            if (residenceName == null || effects == null || !host.landProtection().contains(
                    residenceName, world.getUID(), location.getBlockX(), location.getBlockY(),
                    location.getBlockZ())) {
                continue;
            }
            for (PotionEffect effect : effects) {
                desiredEffects.merge(new PlayerEffectKey(player.getUniqueId(), effect.getType()),
                        effect.getAmplifier(), Math::max);
            }
        }
        applyManagedEffects(desiredEffects, config);
    }

    void clearAll() {
        resetAllBeacons();
        resetTaggedBeacons();
        clearManagedEffects();
        modifiedBeacons.clear();
        managedEffects.clear();
    }

    void recoverTaggedBeacons() {
        resetTaggedBeacons();
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
                || !PhaseFiveSettings.isSafeSingleBlock(material) || source.getType() != material
                || source.hasItemMeta()) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        PhaseFiveRepository.BonusIndex snapshot = index.get();
        UUID townId = snapshot.memberships().get(player.getUniqueId());
        UUID territoryTown = snapshot.territories().get(new PhaseFiveRepository.ChunkKey(
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
            player.sendActionBar(net.kyori.adventure.text.Component.text(
                    "建筑返还 +1（本周 " + result.used() + "/" + result.limit() + "）",
                    net.kyori.adventure.text.format.NamedTextColor.GREEN));
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBeaconInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (!beaconEnabled() || event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null
                || block.getType() != Material.BEACON) {
            return;
        }
        PhaseFiveRepository.BonusIndex snapshot = index.get();
        UUID townId = snapshot.territories().get(new PhaseFiveRepository.ChunkKey(
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
        player.sendActionBar(net.kyori.adventure.text.Component.text(
                "只有本镇镇长或副镇长可以编辑信标效果",
                net.kyori.adventure.text.format.NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBeaconBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.BEACON) {
            BeaconKey key = BeaconKey.of(event.getBlock());
            modifiedBeacons.remove(key);
            plugin.getServer().getScheduler().runTask(plugin, this::scanBeacons);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        boolean recovered = false;
        for (BlockState state : event.getChunk().getTileEntities(
                block -> block.getType() == Material.BEACON, false)) {
            if (!(state instanceof Beacon beacon)
                    || !beacon.getPersistentDataContainer().has(beaconManagedKey,
                    PersistentDataType.BYTE)) {
                continue;
            }
            Double original = beacon.getPersistentDataContainer().get(beaconOriginalRangeKey,
                    PersistentDataType.DOUBLE);
            modifiedBeacons.remove(BeaconKey.of(beacon.getBlock()));
            restoreBeacon(beacon, original == null ? -1D : original);
            recovered = true;
        }
        if (recovered) {
            // 先清理上次非正常停服留下的状态，再由正常扫描重新判断是否增强。
            plugin.getServer().getScheduler().runTask(plugin, this::scanBeacons);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        for (BlockState state : chunk.getTileEntities(
                block -> block.getType() == Material.BEACON, false)) {
            if (state instanceof Beacon beacon) {
                BeaconKey key = BeaconKey.of(beacon.getBlock());
                Double original = modifiedBeacons.remove(key);
                if (original == null && beacon.getPersistentDataContainer().has(
                        beaconManagedKey, PersistentDataType.BYTE)) {
                    original = beacon.getPersistentDataContainer().get(beaconOriginalRangeKey,
                            PersistentDataType.DOUBLE);
                    if (original == null) {
                        original = -1D;
                    }
                }
                if (original != null) {
                    restoreBeacon(beacon, original);
                }
            }
        }
    }

    private void collectTownBeaconEffects(Beacon beacon, UUID townId, String residenceName,
                                          Map<UUID, List<PotionEffect>> effectsByTown) {
        Block block = beacon.getBlock();
        if (beacon.getTier() < 1 || !host.landProtection().contains(residenceName,
                block.getWorld().getUID(), block.getX(), block.getY(), block.getZ())) {
            return;
        }
        if (beacon.getPrimaryEffect() != null) {
            effectsByTown.computeIfAbsent(townId, ignored -> new ArrayList<>())
                    .add(beacon.getPrimaryEffect());
        }
        if (beacon.getSecondaryEffect() != null) {
            effectsByTown.computeIfAbsent(townId, ignored -> new ArrayList<>())
                    .add(beacon.getSecondaryEffect());
        }
    }

    private void restoreUnseenBeacons(Set<BeaconKey> seen) {
        for (BeaconKey key : new ArrayList<>(modifiedBeacons.keySet())) {
            if (seen.contains(key)) {
                continue;
            }
            Double original = modifiedBeacons.remove(key);
            World world = plugin.getServer().getWorld(key.worldId());
            if (world == null || !world.isChunkLoaded(key.blockX() >> 4, key.blockZ() >> 4)) {
                continue;
            }
            BlockState state = world.getBlockAt(key.blockX(), key.blockY(), key.blockZ()).getState();
            if (state instanceof Beacon beacon && original != null) {
                restoreBeacon(beacon, original);
            }
        }
    }

    private void applyManagedEffects(Map<PlayerEffectKey, Integer> desired,
                                     PhaseFiveSettings.BeaconEnhancement config) {
        int duration = Math.toIntExact(Math.min(Integer.MAX_VALUE,
                config.scanIntervalTicks() * 2 + 40));
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
        managedEffects.forEach(this::removeManagedEffect);
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

    private void resetAllBeacons() {
        restoreUnseenBeacons(Set.of());
    }

    private void resetTaggedBeacons() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities(
                        block -> block.getType() == Material.BEACON, false)) {
                    if (!(state instanceof Beacon beacon)
                            || !beacon.getPersistentDataContainer().has(beaconManagedKey,
                            PersistentDataType.BYTE)) {
                        continue;
                    }
                    Double original = beacon.getPersistentDataContainer().get(
                            beaconOriginalRangeKey, PersistentDataType.DOUBLE);
                    restoreBeacon(beacon, original == null ? -1D : original);
                }
            }
        }
    }

    private void restoreBeacon(Beacon beacon, double originalRange) {
        if (originalRange < 0) {
            beacon.resetEffectRange();
        } else {
            beacon.setEffectRange(originalRange);
        }
        beacon.getPersistentDataContainer().remove(beaconManagedKey);
        beacon.getPersistentDataContainer().remove(beaconOriginalRangeKey);
        beacon.update(true, false);
    }

    private void finishDiagnostic(CommandSender sender, int days,
                                  PhaseFiveRepository.DiagnosticSnapshot database,
                                  String schemaVersion,
                                  PhaseThreeRepository.Reconciliation settlement,
                                  QuickShopHistoryProbe.Result history) {
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
        for (PhaseFiveRepository.LandState state : database.landStates()) {
            LandProtectionService.Inspection inspection = host.landProtection().inspect(
                    state.residenceName(), state.areas(), state.members());
            if (inspection.state() == LandProtectionService.ProjectionState.HEALTHY) {
                healthyResidence++;
            } else {
                residenceErrors.add(state.townName() + "=" + inspection.state() + ":"
                        + inspection.message());
            }
        }
        healthy &= residenceErrors.isEmpty();
        lines.add("Residence healthy=" + healthyResidence + "/" + database.landStates().size());
        residenceErrors.forEach(error -> lines.add("Residence ERROR " + error));
        if (settlement == null) {
            healthy = false;
            lines.add("Vault ERROR 清算账户不可读取");
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
            lines.add("QuickShop reconciliation=INCOMPLETE（历史不可用或超过 1000 条上限）");
        }
        String detail = healthy ? "统一诊断通过" : "统一诊断发现异常，请查看报告";
        DiagnosticResult result = new DiagnosticResult(healthy, Instant.now(), detail, null);
        lastDiagnostic.set(result);
        diagnosticRunning.set(false);
        sender.sendMessage((healthy ? "§a" : "§c") + detail);
        lines.forEach(line -> sender.sendMessage("§7- " + line));
        writeDiagnosticReport(lines, result);
    }

    private void writeDiagnosticReport(List<String> lines, DiagnosticResult base) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
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
                plugin.getLogger().warning("写入一键诊断报告失败: " + safeMessage(exception));
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

    private static String safeMessage(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getSimpleName() : value;
    }

    private record BeaconKey(UUID worldId, int blockX, int blockY, int blockZ) {
        private static BeaconKey of(Block block) {
            return new BeaconKey(block.getWorld().getUID(), block.getX(), block.getY(),
                    block.getZ());
        }
    }

    private record PlayerEffectKey(UUID playerId, PotionEffectType type) {
    }

    private record ManagedEffect(int amplifier, int maximumDuration) {
    }

    record DiagnosticResult(boolean healthy, Instant completedAt, String detail, Path report) {
    }
}

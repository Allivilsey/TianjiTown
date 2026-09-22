package org.allivlisey.tianjitown.paper.bonus;

import io.papermc.paper.event.block.BeaconActivatedEvent;
import io.papermc.paper.event.block.BeaconDeactivatedEvent;
import io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent;
import org.allivlisey.tianjitown.integrations.quickshop.QuickShopHistoryProbe;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.config.TownBonusSettings;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.storage.bonus.TownBonusRepository;
import org.allivlisey.tianjitown.storage.diagnostics.TownDiagnosticRepository;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Public bonus entry points and shared index refresh coordination. */
public final class TownBonusRuntime implements Listener {
    private static final String INDEX_REFRESH_FAILURE =
            "log.bonus.index-refresh-failure";
    private final TianjiTownPlugin plugin;
    private final TownBonusRepository repository;
    private final TownBonusSettings settings;
    private final AtomicReference<TownBonusRepository.BonusIndex> index = new AtomicReference<>(
            new TownBonusRepository.BonusIndex(Map.of(), Map.of(), Map.of(), Map.of()));
    private final AtomicBoolean indexRefreshRunning = new AtomicBoolean();
    private final TownBuildingRefunds buildingRefunds;
    private final TownBeaconEffects beaconEffects;
    private final TownBonusDiagnostics diagnostics;

    public TownBonusRuntime(TianjiTownPlugin plugin, TownRuntime host,
                            TownBonusRepository repository, TownDiagnosticRepository diagnosticRepository, TownBonusSettings settings,
                            Plugin quickShop) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(host, "host");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.buildingRefunds = new TownBuildingRefunds(plugin, host, repository,
                settings.buildingRefund());
        this.beaconEffects = new TownBeaconEffects(plugin, host, settings.beacon(), index::get);
        this.diagnostics = new TownBonusDiagnostics(plugin, host, diagnosticRepository, settings.operations(),
                new QuickShopHistoryProbe(quickShop,
                        host.wallet().scale(), plugin.messages()::plainText));
    }

    public TownBonusSettings settings() {
        return settings;
    }

    public DiagnosticResult lastDiagnostic() {
        return diagnostics.lastDiagnostic();
    }

    public boolean buildingRefundEnabled() {
        return buildingRefunds.buildingRefundEnabled();
    }

    public boolean beaconEnabled() {
        return beaconEffects.beaconEnabled();
    }

    public void refreshIndex() {
        if (!plugin.isEnabled() || !indexRefreshRunning.compareAndSet(false, true)) {
            return;
        }
        boolean submitted = plugin.runAsync(() -> {
            try {
                index.set(repository.loadBonusIndex());
                plugin.runMain(beaconEffects::requestRefresh);
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
        buildingRefunds.cleanupCounters();
    }

    public void diagnose(CommandSender sender, int days) {
        diagnostics.diagnose(sender, days);
    }

    public void diagnoseAtStartup(Consumer<DiagnosticResult> completion) {
        diagnostics.diagnoseAtStartup(completion);
    }

    public void refreshBeaconEffects() {
        beaconEffects.refreshBeaconEffects();
    }

    public void clearAll() {
        beaconEffects.clearAll();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        buildingRefunds.onPlace(event, index.get());
        if (event.getBlockPlaced().getType() == Material.BEACON) {
            beaconEffects.sourceChanged(event.getBlockPlaced());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBeaconInteract(PlayerInteractEvent event) {
        beaconEffects.onBeaconInteract(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBeaconEffectChange(PlayerChangeBeaconEffectEvent event) {
        beaconEffects.onBeaconEffectChange(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBeaconEffectsChanged(PlayerChangeBeaconEffectEvent event) {
        beaconEffects.sourceChanged(event.getBeacon());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBeaconBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.BEACON) beaconEffects.sourceChanged(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBeaconActivated(BeaconActivatedEvent event) {
        beaconEffects.sourceChanged(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBeaconDeactivated(BeaconDeactivatedEvent event) {
        beaconEffects.sourceChanged(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        beaconEffects.onChunkLoad(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        beaconEffects.onChunkUnload(event.getChunk());
    }

    private static String safeText(Object value) {
        return org.allivlisey.tianjitown.core.time.TownTime.display(value).replace('&', '＆').replace('§', '�');
    }

    private static String safeMessage(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getSimpleName() : value;
    }

    public record DiagnosticResult(boolean healthy, Instant completedAt, String detail, Path report,
                                   boolean startupAllowed) {
        public DiagnosticResult(boolean healthy, Instant completedAt, String detail, Path report) {
            this(healthy, completedAt, detail, report, healthy);
        }
    }
}

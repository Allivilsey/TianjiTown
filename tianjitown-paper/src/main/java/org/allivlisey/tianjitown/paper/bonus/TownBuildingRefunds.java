package org.allivlisey.tianjitown.paper.bonus;

import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.config.TownBonusSettings;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.storage.bonus.TownBonusRepository;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Reserves weekly building refunds before delivering items on the main thread. */
final class TownBuildingRefunds {
    private static final String REFUND_COUNTER_CLEANUP_FAILURE =
            "log.bonus.refund-counter-cleanup-failure";
    private final TianjiTownPlugin plugin;
    private final TownRuntime host;
    private final TownBonusRepository repository;
    private final TownBonusSettings.BuildingRefund settings;

    TownBuildingRefunds(TianjiTownPlugin plugin, TownRuntime host,
                        TownBonusRepository repository, TownBonusSettings.BuildingRefund settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.host = Objects.requireNonNull(host, "host");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    boolean buildingRefundEnabled() {
        return plugin.getConfig().getBoolean("territory.building-refund.enabled",
                settings.enabled());
    }

    void cleanupCounters() {
        plugin.runAsync(() -> {
            try {
                repository.cleanupRefundCounters(LocalDate.now(settings.resetZone())
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .minusWeeks(settings.retentionWeeks()));
            } catch (RuntimeException exception) {
                plugin.getLogger().warning(plugin.messages().plainText(
                        REFUND_COUNTER_CLEANUP_FAILURE,
                        Map.of("detail", safeText(safeMessage(exception)))));
            }
        });
    }

    void onPlace(BlockPlaceEvent event, TownBonusRepository.BonusIndex snapshot) {
        if (event instanceof BlockMultiPlaceEvent || !buildingRefundEnabled()
                || event.getPlayer().getGameMode() != GameMode.SURVIVAL) {
            return;
        }
        Material material = event.getBlockPlaced().getType();
        ItemStack source = event.getItemInHand();
        if (settings.blacklist().contains(material)
                || !TownBonusSettings.isSafeSingleBlock(material) || source.getType() != material
                || source.hasItemMeta()) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        UUID townId = snapshot.memberships().get(player.getUniqueId());
        UUID territoryTown = snapshot.territories().get(new TownBonusRepository.ChunkKey(
                block.getWorld().getUID(), block.getChunk().getX(), block.getChunk().getZ()));
        String residenceName = townId == null ? null : snapshot.residenceNames().get(townId);
        if (townId == null || !townId.equals(territoryTown) || residenceName == null
                || !host.landProtection().contains(residenceName, block.getWorld().getUID(),
                block.getX(), block.getY(), block.getZ())) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() >= settings.chance()) {
            return;
        }
        LocalDate weekStart = LocalDate.now(settings.resetZone())
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        UUID worldId = block.getWorld().getUID();
        int chunkX = block.getChunk().getX();
        int chunkZ = block.getChunk().getZ();
        String materialKey = material.getKey().toString();
        host.write(player, () -> repository.reserveBuildingRefund(townId, player.getUniqueId(),
                worldId, chunkX, chunkZ, weekStart, materialKey,
                settings.weeklyLimit()), result -> {
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

    private static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    private static String safeMessage(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getSimpleName() : value;
    }
}

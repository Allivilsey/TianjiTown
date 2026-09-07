package org.allivlisey.tianjitown.paper.station;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.allivlisey.tianjitown.storage.station.StationRecord;

/** Coordinates station creation, removal and player interaction. */
public final class ServiceStationController implements Listener {
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final Consumer<Player> openMain;
    private final BooleanSupplier dialogsActive;
    private final NamespacedKey stationKey;
    private final StationRegistry registry;
    private final HandbookService handbooks;
    private final StationProtectionListener protection;

    public ServiceStationController(TianjiTownPlugin plugin, TownRuntime runtime,
                             Consumer<Player> openMain, BooleanSupplier dialogsActive) {
        this.plugin = plugin;
        this.runtime = runtime;
        this.openMain = openMain;
        this.dialogsActive = dialogsActive;
        this.stationKey = new NamespacedKey(plugin, "service_station");
        this.registry = new StationRegistry(runtime.stations(), stationKey);
        this.handbooks = new HandbookService(plugin);
        this.protection = new StationProtectionListener(registry);
    }

    public Listener protectionListener() {
        return protection;
    }

    public void giveHandbookByAdmin(Player player) {
        handbooks.giveHandbookByAdmin(player);
    }

    public boolean giveHandbook(Player player, boolean notifyPlayer) {
        return handbooks.giveHandbook(player, notifyPlayer);
    }

    public boolean create(Player player) {
        Block block = player.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof Lectern lectern)) {
            plugin.messages().send(player, "chat.station.target-lectern");
            return false;
        }
        if (registry.isPending(block)) return false;
        List<StationRecord> stations = registry.stationRecords();
        String existingId = lectern.getPersistentDataContainer().get(stationKey,
                PersistentDataType.STRING);
        if (existingId != null && !existingId.isBlank()) {
            if (registry.registeredStation(block, existingId, stations) != null) {
                plugin.messages().send(player, "chat.station.already-exists",
                        Map.of("id", existingId));
            } else {
                plugin.messages().send(player, "chat.station.invalid-data");
            }
            return false;
        }
        StationRecord registeredLocation = stations.stream()
                .filter(station -> station.sameLocation(block.getWorld().getUID(), block.getX(),
                        block.getY(), block.getZ()))
                .findFirst().orElse(null);
        if (registeredLocation != null) {
            lectern.getPersistentDataContainer().set(stationKey, PersistentDataType.STRING,
                    registeredLocation.id());
            lectern.update(true);
            plugin.messages().send(player, "chat.station.restored",
                    Map.of("id", registeredLocation.id()));
            return true;
        }
        String stationId = UUID.randomUUID().toString();
        persistCreation(player, block, stationId, null, null,
                () -> plugin.messages().send(player, "chat.station.created", Map.of("id", stationId)));
        return true;
    }

    public boolean remove(Player player) {
        Block block = player.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof Lectern lectern)) {
            plugin.messages().send(player, "chat.station.target-station");
            return false;
        }
        String stationId = lectern.getPersistentDataContainer().get(stationKey,
                PersistentDataType.STRING);
        StationRecord registeredLocation = registry.stationAt(block);
        if ((stationId == null || stationId.isBlank()) && registeredLocation == null) {
            plugin.messages().send(player, "chat.station.unregistered");
            return false;
        }
        return persistRemoval(player, block, false);
    }

    public void showInfo(Player player) {
        Block block = player.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof Lectern lectern)) {
            plugin.messages().send(player, "chat.station.target-lectern");
            return;
        }
        String stationId = lectern.getPersistentDataContainer().get(stationKey,
                PersistentDataType.STRING);
        if (stationId == null || stationId.isBlank()) {
            plugin.messages().send(player, "chat.station.unregistered-location",
                    Map.of("location", stationLocation(block)));
            return;
        }
        if (registry.registeredStation(block, stationId, registry.stationRecords()) == null) {
            plugin.messages().send(player, "chat.station.invalid-marker");
            return;
        }
        plugin.messages().send(player, "chat.station.info-title");
        plugin.messages().send(player, "chat.station.info-id", Map.of("id", stationId));
        plugin.messages().send(player, "chat.station.info-location",
                Map.of("location", stationLocation(block)));
        plugin.messages().send(player, "chat.station.info-status");
    }

    public void list(CommandSender sender) {
        List<StationRecord> stations = registry.stationRecords();
        plugin.messages().send(sender, "chat.station.list-title",
                Map.of("count", stations.size()));
        if (stations.isEmpty()) {
            plugin.messages().send(sender, "chat.station.list-empty");
            return;
        }
        for (StationRecord station : stations) {
            String location = station.worldName() + " " + station.x() + "," + station.y()
                    + "," + station.z();
            String owner = station.townId() == null
                    ? plugin.messages().rawText("chat.station.public-owner")
                    : Objects.requireNonNullElse(station.townName(), station.townId().toString());
            if (sender instanceof Player player) {
                player.sendMessage(plugin.messages().component("chat.station.list-entry", Map.of(
                                "id", station.id(), "location", location, "owner", owner,
                                "status", plainStationStatus(station)))
                        .append(callbackButton(player, "chat.buttons.teleport",
                                () -> teleportToStation(player, station))));
            } else {
                plugin.messages().send(sender, "chat.station.list-entry", Map.of(
                        "id", station.id(), "location", location, "owner", owner,
                        "status", stationStatus(station)));
            }
        }
    }

    private void teleportToStation(Player player, StationRecord station) {
        World world = Bukkit.getWorld(station.worldId());
        if (world == null) {
            world = Bukkit.getWorld(station.worldName());
        }
        if (world == null) {
            plugin.messages().send(player, "chat.station.world-unloaded");
            return;
        }
        Block block = world.getBlockAt(station.x(), station.y(), station.z());
        StationRecord registered = registry.stationAt(block);
        if (registry.isPending(block) || registered == null || !registry.isValidStation(block)
                || !station.id().equals(registered.id())) {
            plugin.messages().send(player, "chat.station.block-changed");
            return;
        }
        org.bukkit.Location destination = block.getLocation().add(0.5, 1.0, 0.5);
        destination.setYaw(player.getYaw());
        destination.setPitch(player.getPitch());
        if (player.teleport(destination)) {
            plugin.messages().send(player, "chat.station.teleported");
        } else {
            plugin.messages().send(player, "chat.station.teleport-failed");
        }
    }

    private String plainStationStatus(StationRecord station) {
        return PlainTextComponentSerializer.plainText().serialize(
                LegacyComponentSerializer.legacySection().deserialize(stationStatus(station)));
    }

    private String stationStatus(StationRecord station) {
        World world = Bukkit.getWorld(station.worldId());
        if (world == null) {
            world = Bukkit.getWorld(station.worldName());
        }
        if (world == null) {
            return plugin.messages().text("chat.station.status-world-unloaded");
        }
        if (!world.isChunkLoaded(station.x() >> 4, station.z() >> 4)) {
            return plugin.messages().text("chat.station.status-chunk-unloaded");
        }
        Block block = world.getBlockAt(station.x(), station.y(), station.z());
        if (!(block.getState() instanceof Lectern lectern)) {
            return plugin.messages().text("chat.station.status-block-changed");
        }
        String actualId = lectern.getPersistentDataContainer().get(stationKey,
                PersistentDataType.STRING);
        return station.id().equals(actualId)
                ? plugin.messages().text("chat.station.status-valid")
                : plugin.messages().text("chat.station.status-mismatch");
    }

    private static String stationLocation(Block block) {
        return block.getWorld().getName() + " " + block.getX() + "," + block.getY() + ","
                + block.getZ();
    }

    private static boolean hasBook(Lectern lectern) {
        ItemStack book = lectern.getInventory().getItem(0);
        return book != null && !book.getType().isAir();
    }

    /**
     * Detects stale markers and registrations while creating or repairing a station. Such a
     * marker is deliberately not treated as a valid station for interaction protection.
     */
    private void createStationFromHandbook(Player player, Block block) {
        if (player.hasPermission("tianjitown.admin")) {
            completeStationCreation(player, block, null, null);
            return;
        }
        runtime.read(player, () -> runtime.repository().dashboard(player.getUniqueId()).town(), town -> {
            if (town == null || !town.mayorId().equals(player.getUniqueId())) {
                plugin.messages().send(player, "station.mayor-only");
                return;
            }
            completeStationCreation(player, block, town.id(), town.profile().name());
        });
    }

    private void completeStationCreation(Player player, Block block, UUID townId, String townName) {
        if (!(block.getState() instanceof Lectern lectern)
                || !handbooks.isHandbook(lectern.getInventory().getItem(0))
                || registry.hasStationMarkerOrRegistration(block)) {
            return;
        }
        if (townId != null && registry.stationRecords().stream().anyMatch(station ->
                townId.equals(station.townId()))) {
            plugin.messages().send(player, "station.town-limit", Map.of("town", townName));
            return;
        }
        String stationId = UUID.randomUUID().toString();
        persistCreation(player, block, stationId, townId, townName, () -> {
            plugin.messages().send(player, townId == null ? "station.created-public"
                    : "station.created-town", townId == null ? Map.of() : Map.of("town", townName));
            playSound(player, Sound.BLOCK_AMETHYST_BLOCK_CHIME);
        });
    }

    private void persistCreation(Player player, Block block, String stationId, UUID townId,
                                 String townName, Runnable success) {
        if (!registry.beginChange(block)) return;
        StationRecord record = new StationRecord(stationId, block.getWorld().getUID(),
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ(), townId, townName);
        runtime.writeAction(player, () -> runtime.stations().insert(record), inserted -> {
            registry.endChange(block);
            if (!inserted) {
                plugin.messages().send(player, "chat.station.registration-conflict");
                return;
            }
            if (!(block.getState() instanceof Lectern current)) {
                plugin.messages().send(player, "chat.station.block-changed");
                return;
            }
            current.getPersistentDataContainer().set(stationKey, PersistentDataType.STRING, stationId);
            if (!current.update(false)) {
                plugin.messages().send(player, "chat.station.block-changed");
                return;
            }
            success.run();
        }, failure -> stationWriteFailed(player, block, failure));
    }

    private boolean persistRemoval(Player player, Block block, boolean destroy) {
        if (!registry.beginChange(block)) return false;
        UUID world = block.getWorld().getUID();
        int x = block.getX(), y = block.getY(), z = block.getZ();
        runtime.writeAction(player, () -> {
            runtime.stations().deleteAt(world, x, y, z);
            return true;
        }, ignored -> {
            registry.endChange(block);
            if (block.getState() instanceof Lectern current) {
                current.getPersistentDataContainer().remove(stationKey);
                current.update(false);
                if (destroy) block.breakNaturally();
            }
            plugin.messages().send(player, "station.removed");
            if (destroy) playSound(player, Sound.BLOCK_WOOD_BREAK);
        }, failure -> stationWriteFailed(player, block, failure));
        return true;
    }

    private void stationWriteFailed(Player player, Block block, RuntimeException failure) {
        registry.endChange(block);
        plugin.messages().send(player, "chat.runtime.operation-failed",
                Map.of("detail", Objects.toString(failure.getMessage(), failure.getClass().getSimpleName())
                        .replace('&', '＆').replace('§', '�')));
    }

    private void destroyStation(Player player, Block block) {
        persistRemoval(player, block, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onStationInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        boolean rightClick = event.getAction() == Action.RIGHT_CLICK_AIR
                || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        Block block = event.getClickedBlock();
        if (registry.isPending(block)) {
            event.setCancelled(true);
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
            return;
        }
        ServiceStationInteractionPolicy.Outcome stationOutcome =
                ServiceStationInteractionPolicy.decide(registry.isValidStation(block), event.getAction(),
                        event.getHand(), event.getPlayer().hasPermission("tianjitown.admin"),
                        event.getPlayer().isSneaking());
        if (stationOutcome != ServiceStationInteractionPolicy.Outcome.PASS_THROUGH) {
            event.setCancelled(true);
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
            if (stationOutcome == ServiceStationInteractionPolicy.Outcome.DESTROY) {
                destroyStation(event.getPlayer(), block);
            } else if (stationOutcome == ServiceStationInteractionPolicy.Outcome.OPEN_MENU) {
                openMain.accept(event.getPlayer());
            }
            return;
        }
        boolean emptyUnregisteredLectern = block != null
                && block.getState() instanceof Lectern lectern
                && !hasBook(lectern) && !registry.hasStationMarkerOrRegistration(block);
        if (rightClick && handbooks.isHandbook(item) && !emptyUnregisteredLectern) {
            Player player = event.getPlayer();
            event.setCancelled(true);
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
            // 客户端会在本次交互结束时尝试打开成书，下一刻再打开菜单以覆盖该界面。
            plugin.runMain(() -> openMain.accept(player));
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && block != null
                && block.getState() instanceof Lectern lectern
                && lectern.getPersistentDataContainer().has(stationKey, PersistentDataType.STRING)) {
            String stationId = lectern.getPersistentDataContainer().get(stationKey,
                    PersistentDataType.STRING);
            if (stationId == null || stationId.isBlank()
                    || registry.registeredStation(block, stationId, registry.stationRecords()) == null) {
                // 复制或移动后的标记不享有绕过 Residence 的资格。
                plugin.messages().send(event.getPlayer(), "chat.station.invalid-data");
                return;
            }
            event.setCancelled(true);
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
            openMain.accept(event.getPlayer());
            return;
        }
        if (event.isCancelled()) {
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStationInsertLecternBook(PlayerInsertLecternBookEvent event) {
        if (!handbooks.isHandbook(event.getBook())) {
            return;
        }
        Block block = event.getBlock();
        if (!(block.getState() instanceof Lectern lectern) || hasBook(lectern)
                || registry.hasStationMarkerOrRegistration(block)) {
            return;
        }
        Player player = event.getPlayer();
        // 插书事件发生在方块真正写入之前，下一刻再检查才能确保手册已经留在讲台上。
        plugin.runMainLater(() -> {
            if (player.isOnline()) {
                createStationFromHandbook(player, block);
            }
        }, 1L);
    }

    private Component callbackButton(Player recipient, String labelKey, Runnable action) {
        return plugin.messages().component(labelKey).decorate(TextDecoration.BOLD)
                .clickEvent(plugin.chatCallbacks().create(recipient.getUniqueId(),
                        () -> dialogsActive.getAsBoolean() && recipient.isOnline(), action))
                .hoverEvent(HoverEvent.showText(
                        plugin.messages().component("chat.buttons.open-tooltip")));
    }

    private void playSound(Player player, Sound sound) {
        player.playSound(player.getLocation(), sound, SoundCategory.MASTER, 0.8F, 1.0F);
    }




}

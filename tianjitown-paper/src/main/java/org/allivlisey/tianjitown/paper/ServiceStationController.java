 package org.allivlisey.tianjitown.paper;

import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Owns service-station state, handbook data, and all associated Paper events. */
final class ServiceStationController implements Listener {
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final Consumer<Player> openMain;
    private final BooleanSupplier dialogsActive;
    private final NamespacedKey stationKey;
    private final NamespacedKey handbookKey;
    private final NamespacedKey handbookCooldownKey;

    ServiceStationController(TianjiTownPlugin plugin, TownRuntime runtime,
                             Consumer<Player> openMain, BooleanSupplier dialogsActive) {
        this.plugin = plugin;
        this.runtime = runtime;
        this.openMain = openMain;
        this.dialogsActive = dialogsActive;
        this.stationKey = new NamespacedKey(plugin, "service_station");
        this.handbookKey = new NamespacedKey(plugin, "handbook");
        this.handbookCooldownKey = new NamespacedKey(plugin, "handbook_received_at");
    }

    boolean create(Player player) {
        Block block = player.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof Lectern lectern)) {
            plugin.messages().send(player, "chat.station.target-lectern");
            return false;
        }
        List<StationRecord> stations = stationRecords();
        String existingId = lectern.getPersistentDataContainer().get(stationKey,
                PersistentDataType.STRING);
        if (existingId != null && !existingId.isBlank()) {
            if (registeredStation(block, existingId, stations) != null) {
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
        lectern.getPersistentDataContainer().set(stationKey, PersistentDataType.STRING, stationId);
        lectern.update(true);
        registerStation(block, stationId, null, null);
        plugin.messages().send(player, "chat.station.created", Map.of("id", stationId));
        return true;
    }

    boolean remove(Player player) {
        Block block = player.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof Lectern lectern)) {
            plugin.messages().send(player, "chat.station.target-station");
            return false;
        }
        String stationId = lectern.getPersistentDataContainer().get(stationKey,
                PersistentDataType.STRING);
        StationRecord registeredLocation = stationAt(block);
        if ((stationId == null || stationId.isBlank()) && registeredLocation == null) {
            plugin.messages().send(player, "chat.station.unregistered");
            return false;
        }
        lectern.getPersistentDataContainer().remove(stationKey);
        lectern.update(true);
        unregisterStationAt(block);
        plugin.messages().send(player, "station.removed");
        return true;
    }

    void showInfo(Player player) {
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
        if (registeredStation(block, stationId, stationRecords()) == null) {
            plugin.messages().send(player, "chat.station.invalid-marker");
            return;
        }
        plugin.messages().send(player, "chat.station.info-title");
        plugin.messages().send(player, "chat.station.info-id", Map.of("id", stationId));
        plugin.messages().send(player, "chat.station.info-location",
                Map.of("location", stationLocation(block)));
        plugin.messages().send(player, "chat.station.info-status");
    }

    void list(CommandSender sender) {
        List<StationRecord> stations = stationRecords();
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
        if (!(block.getState() instanceof Lectern lectern)
                || !station.id().equals(lectern.getPersistentDataContainer().get(
                stationKey, PersistentDataType.STRING))) {
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

    private void registerStation(Block block, String stationId, UUID townId, String townName) {
        List<StationRecord> stations = new ArrayList<>(stationRecords());
        stations.removeIf(station -> station.id().equals(stationId)
                || station.sameLocation(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()));
        stations.add(new StationRecord(stationId, block.getWorld().getUID(), block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), townId, townName));
        saveStationRecords(stations);
    }

    private void unregisterStationAt(Block block) {
        List<StationRecord> stations = new ArrayList<>(stationRecords());
        stations.removeIf(station -> station.sameLocation(block.getWorld().getUID(), block.getX(),
                block.getY(), block.getZ()));
        saveStationRecords(stations);
    }

    private StationRecord stationAt(Block block) {
        return stationRecords().stream().filter(station -> station.sameLocation(
                block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()))
                .findFirst().orElse(null);
    }

    private static StationRecord registeredStation(Block block, String stationId,
                                                     List<StationRecord> stations) {
        return stations.stream().filter(station -> station.id().equals(stationId)
                        && station.sameLocation(block.getWorld().getUID(), block.getX(), block.getY(),
                        block.getZ()))
                .findFirst().orElse(null);
    }

    private List<StationRecord> stationRecords() {
        List<StationRecord> result = new ArrayList<>();
        for (Map<?, ?> raw : plugin.getConfig().getMapList("town.service-stations")) {
            try {
                Object townId = raw.get("town-id");
                result.add(new StationRecord(String.valueOf(raw.get("id")),
                        UUID.fromString(String.valueOf(raw.get("world-uuid"))),
                        String.valueOf(raw.get("world")), number(raw, "x"), number(raw, "y"),
                        number(raw, "z"), townId == null ? null : UUID.fromString(String.valueOf(townId)),
                        raw.get("town-name") == null ? null : String.valueOf(raw.get("town-name"))));
            } catch (MissingIntegerException exception) {
                plugin.getLogger().warning(plugin.messages().plainText(
                        "log.station.invalid-record-missing-integer",
                        Map.of("key", safeText(exception.key()))));
            } catch (StationRecord.ValidationException exception) {
                plugin.getLogger().warning(plugin.messages().plainText(
                        "log.station.invalid-record",
                        Map.of("detail", plugin.messages().plainText(exception.messageKey()))));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning(plugin.messages().plainText(
                        "log.station.invalid-record",
                        Map.of("detail", safeText(safeMessage(exception)))));
            }
        }
        return List.copyOf(result);
    }

    private void saveStationRecords(List<StationRecord> stations) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (StationRecord station : stations) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", station.id());
            value.put("world-uuid", station.worldId().toString());
            value.put("world", station.worldName());
            value.put("x", station.x());
            value.put("y", station.y());
            value.put("z", station.z());
            if (station.townId() != null) {
                value.put("town-id", station.townId().toString());
                value.put("town-name", station.townName());
            }
            serialized.add(value);
        }
        plugin.getConfig().set("town.service-stations", serialized);
        plugin.saveConfig();
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

    private static int number(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        if (!(value instanceof Number number)) {
            throw new MissingIntegerException(key);
        }
        return number.intValue();
    }

    private static String stationLocation(Block block) {
        return block.getWorld().getName() + " " + block.getX() + "," + block.getY() + ","
                + block.getZ();
    }

    private boolean isHandbook(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(handbookKey,
                PersistentDataType.BYTE);
    }

    private static boolean hasBook(Lectern lectern) {
        ItemStack book = lectern.getInventory().getItem(0);
        return book != null && !book.getType().isAir();
    }

    /**
     * Detects stale markers and registrations while creating or repairing a station. Such a
     * marker is deliberately not treated as a valid station for interaction protection.
     */
    private boolean hasStationMarkerOrRegistration(Block block) {
        if (!(block.getState() instanceof Lectern lectern)) {
            return false;
        }
        return lectern.getPersistentDataContainer().has(stationKey, PersistentDataType.STRING)
                || stationAt(block) != null;
    }

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
                || !isHandbook(lectern.getInventory().getItem(0))
                || hasStationMarkerOrRegistration(block)) {
            return;
        }
        if (townId != null && stationRecords().stream().anyMatch(station ->
                townId.equals(station.townId()))) {
            plugin.messages().send(player, "station.town-limit", Map.of("town", townName));
            return;
        }
        String stationId = UUID.randomUUID().toString();
        lectern.getPersistentDataContainer().set(stationKey, PersistentDataType.STRING, stationId);
        lectern.update(true);
        registerStation(block, stationId, townId, townName);
        plugin.messages().send(player, townId == null ? "station.created-public"
                : "station.created-town", townId == null ? Map.of() : Map.of("town", townName));
        playSound(player, Sound.BLOCK_AMETHYST_BLOCK_CHIME);
    }

    private void destroyStation(Player player, Block block) {
        if (block.getState() instanceof Lectern lectern) {
            lectern.getPersistentDataContainer().remove(stationKey);
            lectern.update(true);
        }
        unregisterStationAt(block);
        block.breakNaturally();
        plugin.messages().send(player, "station.removed");
        playSound(player, Sound.BLOCK_WOOD_BREAK);
    }

    boolean giveHandbook(Player player, boolean notifyPlayer) {
        long now = Instant.now().toEpochMilli();
        Long lastReceived = player.getPersistentDataContainer().get(handbookCooldownKey,
                PersistentDataType.LONG);
        long cooldownMillis = Duration.ofMinutes(Math.max(1, plugin.getConfig().getLong(
                "town.handbook-cooldown-minutes", 60))).toMillis();
        if (lastReceived != null && now - lastReceived < cooldownMillis) {
            long remainingMinutes = Math.max(1,
                    (cooldownMillis - (now - lastReceived) + 59_999L) / 60_000L);
            if (notifyPlayer) {
                plugin.messages().send(player, "handbook.cooldown",
                        Map.of("minutes", remainingMinutes));
            }
            return false;
        }
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle(plugin.messages().plainText("handbook.item-title"));
        meta.setAuthor("TianjiTown");
        meta.setDisplayName(plugin.messages().text("handbook.item-display-name"));
        meta.setPages(plugin.messages().text("handbook.item-pages"));
        meta.getPersistentDataContainer().set(handbookKey, PersistentDataType.BYTE, (byte) 1);
        book.setItemMeta(meta);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(book);
        leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        player.getPersistentDataContainer().set(handbookCooldownKey, PersistentDataType.LONG, now);
        if (notifyPlayer) {
            plugin.messages().send(player, "handbook.received");
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onStationInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        boolean rightClick = event.getAction() == Action.RIGHT_CLICK_AIR
                || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        Block block = event.getClickedBlock();
        ServiceStationInteractionPolicy.Outcome stationOutcome =
                ServiceStationInteractionPolicy.decide(isValidStation(block), event.getAction(),
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
                && !hasBook(lectern) && !hasStationMarkerOrRegistration(block);
        if (rightClick && isHandbook(item) && !emptyUnregisteredLectern) {
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
                    || registeredStation(block, stationId, stationRecords()) == null) {
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onStationEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isValidStation);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onStationBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isValidStation);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onStationPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isValidStation)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onStationPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isValidStation)) {
            event.setCancelled(true);
        }
    }

    private boolean isValidStation(Block block) {
        if (block == null) {
            return false;
        }
        if (!(block.getState() instanceof Lectern lectern)) {
            return false;
        }
        String stationId = lectern.getPersistentDataContainer().get(stationKey,
                PersistentDataType.STRING);
        return stationId != null && !stationId.isBlank()
                && registeredStation(block, stationId, stationRecords()) != null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStationInsertLecternBook(PlayerInsertLecternBookEvent event) {
        if (!isHandbook(event.getBook())) {
            return;
        }
        Block block = event.getBlock();
        if (!(block.getState() instanceof Lectern lectern) || hasBook(lectern)
                || hasStationMarkerOrRegistration(block)) {
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onStationBreak(BlockBreakEvent event) {
        if (isValidStation(event.getBlock())) {
            event.setCancelled(true);
        }
    }


    private Component callbackButton(Player recipient, String labelKey, Runnable action) {
        return plugin.messages().component(labelKey).decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.callback(audience -> {
                    if (!dialogsActive.getAsBoolean() || !(audience instanceof Player clicked)
                            || !clicked.getUniqueId().equals(recipient.getUniqueId())) {
                        return;
                    }
                    plugin.runMain(() -> {
                        if (clicked.isOnline()) {
                            action.run();
                        }
                    });
                }, options -> options.uses(5).lifetime(Duration.ofDays(7))))
                .hoverEvent(HoverEvent.showText(
                        plugin.messages().component("chat.buttons.open-tooltip")));
    }

    private void playSound(Player player, Sound sound) {
        player.playSound(player.getLocation(), sound, SoundCategory.MASTER, 0.8F, 1.0F);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    private static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    private static final class MissingIntegerException extends IllegalArgumentException {
        private final String key;

        private MissingIntegerException(String key) {
            super("missing-integer:" + key);
            this.key = key;
        }

        private String key() {
            return key;
        }
    }

    private record StationRecord(String id, UUID worldId, String worldName, int x, int y, int z,
                                 UUID townId, String townName) {
        StationRecord {
            if (id == null || id.isBlank()) {
                throw new ValidationException("log.station.record-id-empty");
            }
            Objects.requireNonNull(worldId, "worldId");
            if (worldName == null || worldName.isBlank()) {
                throw new ValidationException("log.station.record-world-name-empty");
            }
        }

        private static final class ValidationException extends IllegalArgumentException {
            private final String messageKey;

            private ValidationException(String messageKey) {
                super(messageKey);
                this.messageKey = messageKey;
            }

            private String messageKey() {
                return messageKey;
            }
        }

        boolean sameLocation(UUID candidateWorld, int candidateX, int candidateY, int candidateZ) {
            return worldId.equals(candidateWorld) && x == candidateX && y == candidateY
                    && z == candidateZ;
        }
    }


}

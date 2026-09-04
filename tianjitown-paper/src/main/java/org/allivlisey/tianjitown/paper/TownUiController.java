package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.core.application.ApplicationStatus;
import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.economy.MoneyAmount;
import org.allivlisey.tianjitown.core.consumption.BuffDefinition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.land.ExpansionPricing;
import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.core.governance.VoteType;
import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.ApplicationFormDraft;
import org.allivlisey.tianjitown.storage.town.InitialMemberConfirmation;
import org.allivlisey.tianjitown.storage.town.JoinApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.allivlisey.tianjitown.storage.town.TownPlayerChange;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.allivlisey.tianjitown.storage.governance.MemberGovernanceSnapshot;
import org.allivlisey.tianjitown.storage.governance.TransferSnapshot;
import org.allivlisey.tianjitown.storage.governance.VoteSnapshot;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

final class TownUiController implements Listener {
    private static final int MENU_TIMEOUT_TICKS = 20 * 60;
    private static final int APPLICATION_TEXT_INPUT_WIDTH = 400;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownActions actions;
    private final SitePolicy sitePolicy;
    private final NamespacedKey stationKey;
    private final NamespacedKey handbookKey;
    private final NamespacedKey handbookCooldownKey;
    private final NamespacedKey actionKey;
    private final NamespacedKey targetKey;
    private final Map<UUID, UUID> menuSessions = new HashMap<>();
    private final Map<UUID, ApplicationFormSession> applicationForms = new ConcurrentHashMap<>();
    private final Map<UUID, Set<TerritoryService.GridSelection>> expansionSelections =
            new ConcurrentHashMap<>();
    private final Map<UUID, UUID> expansionBatchRequestIds = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> initialMemberReminderCooldowns = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(true);

    TownUiController(TianjiTownPlugin plugin, TownRuntime runtime, TownActions actions) {
        this.plugin = plugin;
        this.runtime = runtime;
        this.actions = actions;
        this.sitePolicy = runtime.sitePolicy();
        this.stationKey = new NamespacedKey(plugin, "service_station");
        this.handbookKey = new NamespacedKey(plugin, "handbook");
        this.handbookCooldownKey = new NamespacedKey(plugin, "handbook_received_at");
        this.actionKey = new NamespacedKey(plugin, "gui_action");
        this.targetKey = new NamespacedKey(plugin, "target_id");
    }

    void close() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        List<UUID> viewers = List.copyOf(menuSessions.keySet());
        menuSessions.clear();
        applicationForms.clear();
        expansionSelections.clear();
        expansionBatchRequestIds.clear();
        initialMemberReminderCooldowns.clear();
        for (UUID viewerId : viewers) {
            try {
                Player viewer = plugin.getServer().getPlayer(viewerId);
                if (viewer != null && viewer.isOnline()) {
                    closeUi(viewer);
                }
            } catch (RuntimeException | LinkageError exception) {
                plugin.getLogger().warning(plugin.messages().plainText(
                        "log.lifecycle.ui-viewer-close-failure", Map.of(
                                "player", viewerId,
                                "detail", safeText(safeMessage(exception)))));
            }
        }
    }

    boolean createStation(Player player) {
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

    boolean removeStation(Player player) {
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

    void showStationInfo(Player player) {
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

    void listStations(CommandSender sender) {
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

    void openMain(Player player) {
        if (maintenanceMode()) {
            plugin.messages().send(player, "system.maintenance");
            return;
        }
        UUID request = openMenu(player, 9, dialogText("main.loading-title"), DialogRoute.ROOT,
                List.of());
        runtime.read(player, () -> {
            runtime.governance().recordActivity(player.getUniqueId());
            TownRepository.PlayerDashboard dashboard = runtime.repository()
                    .dashboard(player.getUniqueId());
            EconomyRepository.TownFinance finance = dashboard.town() == null ? null
                    : runtime.finance().findFinanceByPlayer(player.getUniqueId()).orElse(null);
            return new MainView(dashboard,
                    runtime.governance().dashboard(player.getUniqueId()).orElse(null), finance,
                    player.hasPermission("tianjitown.admin")
                            ? runtime.repository().listReviewQueue(100) : List.of());
        }, view -> {
            if (isCurrent(player, request)) {
                renderMain(player, view);
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        runtime.write(player, () -> {
            runtime.governance().recordActivity(player.getUniqueId());
            return runtime.governance().dashboard(player.getUniqueId()).orElse(null);
        }, governance -> {
            if (governance == null) {
                return;
            }
            if (governance.requiresRulesConfirmation()) {
                player.sendMessage(plugin.messages().component("chat.notification.rules-updated")
                        .append(callbackButton(player, "chat.buttons.view-rules",
                                () -> openMain(player))));
            }
            if (governance.pendingTransfer() != null) {
                player.sendMessage(plugin.messages().component("chat.notification.mayor-transfer",
                                Map.of("town", governance.townName()))
                        .append(callbackButton(player, "chat.buttons.handle-transfer",
                                () -> openMain(player))));
            }
            List<VoteSnapshot> pendingVotes = governance.votes().stream()
                    .filter(vote -> vote.viewerEligible() && !vote.viewerVoted()).toList();
            if (!pendingVotes.isEmpty()) {
                player.sendMessage(plugin.messages().component("chat.notification.pending-votes",
                                Map.of("count", pendingVotes.size()))
                        .append(callbackButton(player, "chat.buttons.view-votes",
                                () -> openMain(player))));
                pendingVotes.forEach(vote -> sendVoteReminder(player, vote));
            }
        });
        runtime.read(player, () -> runtime.finance().findFinanceByPlayer(player.getUniqueId())
                .orElse(null), finance -> {
            if (runtime.taxEnabled() && finance != null && finance.hasUnreadTaxChange()) {
                player.sendMessage(plugin.messages().component("chat.notification.tax-updated", Map.of(
                                "rate", TownRuntime.percent(finance.taxRateBps())))
                        .append(callbackButton(player, "chat.buttons.view-finance",
                                () -> openFinance(player, 0))));
            }
        });
        runtime.read(player, () -> runtime.repository()
                .listPendingInitialMemberApplications(player.getUniqueId()), applications ->
                applications.forEach(application -> sendInitialMemberReminder(
                        player, application)));
    }

    void previewTownForAdmin(Player player, TownSnapshot town) {
        previewTown(player, town.id());
    }

    void showAdminApplicationList(CommandSender sender, List<ApplicationSnapshot> applications) {
        plugin.messages().send(sender, "chat.admin.pending-applications",
                Map.of("count", applications.size()));
        for (ApplicationSnapshot application : applications) {
            plugin.messages().send(sender, "chat.admin.application-entry", Map.of(
                    "town", application.text().name(), "applicant", application.applicantId()));
            if (sender instanceof Player admin) {
                admin.sendMessage(callbackButton(admin, "chat.buttons.review-application",
                        () -> openAdminApplication(admin, application.id())));
            }
        }
        if (sender instanceof Player player && !applications.isEmpty()) {
            playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
        }
    }

    void notifyApplicationDecision(ApplicationSnapshot application) {
        Player applicant = Bukkit.getPlayer(application.applicantId());
        if (applicant == null) {
            return;
        }
        switch (application.status()) {
            case ACTIVE -> {
                applicant.sendMessage(plugin.messages().component(
                        "chat.notification.application-approved",
                        Map.of("town", application.text().name())));
                playSound(applicant, Sound.ENTITY_PLAYER_LEVELUP);
            }
            case NEED_CHANGES -> {
                applicant.sendMessage(plugin.messages().component(
                                "chat.notification.application-needs-changes", Map.of(
                                        "reason", safeText(Objects.requireNonNullElse(
                                                application.reviewMessage(), plugin.messages().plainText(
                                                        "chat.notification.application-needs-changes-default-reason")))))
                        .append(callbackButton(applicant, "chat.buttons.edit-application",
                        () -> loadApplication(applicant, application.id()))));
                playSound(applicant, Sound.BLOCK_NOTE_BLOCK_PLING);
            }
            case REJECTED -> {
                applicant.sendMessage(plugin.messages().component(
                                "chat.notification.application-rejected", Map.of(
                                        "reason", safeText(Objects.requireNonNullElse(
                                                application.reviewMessage(), plugin.messages().plainText(
                                                        "chat.notification.application-rejected-default-reason")))))
                        .append(callbackButton(applicant, "chat.buttons.open-system",
                        () -> openMain(applicant))));
                playSound(applicant, Sound.ENTITY_VILLAGER_NO);
            }
            case PROVISION_FAILED -> {
                applicant.sendMessage(plugin.messages().component(
                                "chat.notification.application-provision-failed")
                        .append(callbackButton(applicant, "chat.buttons.open-system",
                                () -> openMain(applicant))));
                playSound(applicant, Sound.BLOCK_NOTE_BLOCK_BASS);
            }
            default -> {
                // 其余状态不属于需要主动推送的审批结果。
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
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
                openMain(event.getPlayer());
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
            plugin.runMain(() -> openMain(player));
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
            openMain(event.getPlayer());
            return;
        }
        if (event.isCancelled()) {
            return;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplosion(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isValidStation);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isValidStation);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isValidStation)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPistonRetract(BlockPistonRetractEvent event) {
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
    public void onInsertLecternBook(PlayerInsertLecternBookEvent event) {
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        menuSessions.remove(event.getPlayer().getUniqueId());
        applicationForms.remove(event.getPlayer().getUniqueId());
        expansionSelections.remove(event.getPlayer().getUniqueId());
        expansionBatchRequestIds.remove(event.getPlayer().getUniqueId());
        sitePolicy.stopPreview(event.getPlayer().getUniqueId());
    }

    private void renderMain(Player player, MainView view) {
        TownRepository.PlayerDashboard dashboard = view.dashboard();
        MemberGovernanceSnapshot governance = view.governance();
        if (governance != null && governance.requiresRulesConfirmation()) {
            renderRulesConfirmation(player, governance);
            return;
        }
        List<MenuItem> items = new ArrayList<>();
        if (dashboard.town() != null) {
            TownSnapshot town = dashboard.town();
            EconomyRepository.TownFinance finance = view.finance();
            int pendingVotes = governance == null ? 0 : (int) governance.votes().stream()
                    .filter(vote -> vote.viewerEligible() && !vote.viewerVoted()).count();
            int pendingJoins = governance != null && governance.canReviewApplications()
                    ? dashboard.incomingJoinApplications().size() : 0;
            int pendingTransfer = governance != null && governance.pendingTransfer() != null ? 1 : 0;
            int pendingTotal = pendingVotes + pendingJoins + pendingTransfer;
            List<String> summary = new ArrayList<>();
            summary.add(dialogText("member-role.identity", Map.of("role", governance == null
                    ? dialogText("member-role.member") : memberRoleText(governance.role()))));
            if (finance != null) {
                summary.add(dialogText("main.finance-summary", Map.of(
                        "balance", runtime.money(finance.balanceMinor()),
                        "taxRate", TownRuntime.percent(finance.taxRateBps()))));
                summary.add(dialogText("common.territory-units", Map.of(
                        "count", finance.unitCount(),
                        "maximum", runtime.economySettings().maximumUnits())));
            }
            summary.add(pendingTotal > 0
                    ? dialogText("votes.main-pending", Map.of("total", pendingTotal,
                    "joins", pendingJoins, "votes", pendingVotes, "transfers", pendingTransfer))
                    : dialogText("main.no-pending"));
            items.add(new MenuItem(0, button(Material.BELL, dialogText("common.town-name", Map.of(
                    "town", town.profile().name())),
                    summary, null, null)));
            items.add(new MenuItem(10, button(Material.WRITTEN_BOOK, dialogText("main.town-info"),
                    List.of(dialogText("tooltip.main.town")), "TOWN", town.id().toString())));
            items.add(new MenuItem(12, button(Material.EMERALD_BLOCK, dialogText("common.finance-title"),
                    List.of(dialogText("tooltip.main.finance")), "FINANCE", "0")));
            items.add(new MenuItem(14, button(Material.GOLDEN_HELMET, dialogText("main.governance"),
                    List.of(dialogText("tooltip.main.governance")), "GOVERNANCE_CENTER", null)));
            items.add(new MenuItem(16, button(pendingTotal > 0 ? Material.ENCHANTED_BOOK : Material.BOOK,
                    pendingTotal > 0 ? dialogText("main.pending-count",
                            Map.of("count", pendingTotal)) : dialogText("main.pending"),
                    List.of(dialogText("tooltip.main.pending")), "PENDING_CENTER", null)));
            items.add(new MenuItem(18, button(Material.PLAYER_HEAD, dialogText("main.personal"),
                    List.of(dialogText("tooltip.main.personal")), "PERSONAL_CENTER", null)));
        } else if (dashboard.application() != null) {
            ApplicationSnapshot application = dashboard.application();
            items.add(new MenuItem(0, button(Material.PAPER, dialogText("main.application-title"),
                    List.of(application.reviewMessage() == null
                            ? dialogText("main.application-incomplete")
                            : dialogText("common.admin-review-message", Map.of(
                                    "message", safeText(application.reviewMessage())))), null, null)));
            items.add(new MenuItem(11, button(Material.MAP, dialogText("main.application-continue"),
                    List.of(application.reviewMessage() == null
                            ? dialogText("tooltip.main.application-summary")
                            : dialogText("common.admin-review-message",
                            Map.of("message", safeText(application.reviewMessage())))),
                    "APPLICATION", application.id().toString())));
        } else {
            items.add(new MenuItem(0, button(Material.BELL, dialogText("main.title"),
                    List.of(dialogText("main.no-town"), dialogText("main.no-town-actions")),
                    null, null)));
            if (dashboard.joinApplications().isEmpty()) {
                items.add(new MenuItem(11, button(Material.WRITABLE_BOOK,
                        dialogText("main.create-application"),
                        List.of(dialogText("tooltip.main.create-application")),
                        "CREATE_APPLICATION", null)));
            }
            items.add(new MenuItem(13, button(Material.COMPASS, dialogText("main.join-application"),
                    List.of(dialogText("tooltip.main.join-towns")), "JOIN_TOWNS", null)));
            if (!dashboard.joinApplications().isEmpty()) {
                items.add(new MenuItem(15, button(Material.PAPER,
                        dialogText("main.my-join-applications"),
                        List.of(dialogText("tooltip.main.my-applications-count",
                                        Map.of("count", dashboard.joinApplications().size())),
                                dialogText("common.join-application-limit")),
                        "MY_JOIN_APPLICATIONS", null)));
            }
            items.add(new MenuItem(31, button(Material.WRITTEN_BOOK, dialogText("common.handbook"),
                    List.of(dialogText("tooltip.main.handbook")), "GIVE_HANDBOOK", null)));
        }
        if (player.hasPermission("tianjitown.admin")) {
            boolean pending = !view.reviewQueue().isEmpty();
            items.add(new MenuItem(30, button(pending ? Material.ENCHANTED_BOOK : Material.BOOK,
                    pending ? dialogText("main.admin-review-count",
                            Map.of("count", view.reviewQueue().size()))
                            : dialogText("main.admin-review"),
                    pending ? List.of(dialogText("tooltip.main.admin-review-pending"))
                            : List.of(dialogText("tooltip.main.admin-review-empty")),
                    "ADMIN_APPLICATIONS", null)));
        }
        openMenu(player, 36, dialogText("main.title"), DialogRoute.ROOT, items);
    }

    private void openGovernanceCenter(Player player) {
        runtime.read(player, () -> new GovernanceCenterView(
                runtime.repository().dashboard(player.getUniqueId()),
                runtime.governance().dashboard(player.getUniqueId()).orElse(null)), view -> {
            TownSnapshot town = view.dashboard().town();
            MemberGovernanceSnapshot governance = view.governance();
            if (town == null || governance == null) {
                openNotice(player, dialogText("notice.no-town-title"),
                        dialogText("notice.no-town-message"),
                        dialogText("common.back"), "MAIN", null);
                return;
            }
            int pendingJoins = governance.canReviewApplications()
                    ? view.dashboard().incomingJoinApplications().size() : 0;
            long pendingVotes = governance.votes().stream()
                    .filter(vote -> vote.viewerEligible() && !vote.viewerVoted()).count();
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(0, button(Material.GOLDEN_HELMET,
                    dialogText("governance.title"),
                    List.of(dialogText("governance.town-summary", Map.of(
                                    "town", town.profile().name())),
                            dialogText("member-role.current-identity", Map.of("role",
                                    memberRoleText(governance.role()))),
                            dialogText("governance.pending-joins", Map.of("count", pendingJoins)),
                            dialogText("votes.governance-pending-votes",
                                    Map.of("count", pendingVotes))), null, null)));
            items.add(new MenuItem(10, button(Material.PLAYER_HEAD, dialogText("governance.members"),
                    List.of(dialogText("tooltip.governance.members")),
                    "MEMBERS", town.id() + ":0")));
            items.add(new MenuItem(12, button(Material.BOOK, dialogText("votes.governance-title"),
                    List.of(dialogText("tooltip.governance.votes")),
                    "VOTES", town.id().toString())));
            if (governance.canReviewApplications()) {
                items.add(new MenuItem(14, button(pendingJoins > 0
                                ? Material.ENCHANTED_BOOK : Material.BOOK,
                        pendingJoins > 0 ? dialogText("common.applications-count",
                                Map.of("count", pendingJoins))
                                : dialogText("governance.applications"),
                        List.of(dialogText("tooltip.governance.applications")),
                        "JOIN_APPLICATIONS", town.id().toString())));
            }
            VisitorManagementMenuModel visitorMenu = VisitorManagementMenuModel.create(
                    plugin.messages(), governance, town.id());
            if (visitorMenu.visible()) {
                items.add(new MenuItem(16, button(Material.NAME_TAG, visitorMenu.label(),
                        visitorMenu.lore(), visitorMenu.action(), visitorMenu.target())));
            }
            openMenu(player, 27, dialogText("governance.title"),
                    new DialogRoute("MAIN", null), items);
        });
    }

    private void openVisitorCenter(Player player, UUID townId) {
        runtime.read(player, () -> new VisitorCenterView(
                runtime.governance().dashboard(player.getUniqueId()).orElse(null),
                runtime.repository().listVisitorIds(townId).size()), view -> {
            if (rejectVisitorManagement(player, view.governance(), townId)) {
                return;
            }
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(4, button(Material.NAME_TAG, dialogText("visitor.title"),
                    List.of(dialogText("visitor.current-count", Map.of("count",
                            view.visitorCount()))), null, null)));
            items.add(new MenuItem(11, button(Material.PLAYER_HEAD, dialogText("visitor.list"),
                    List.of(dialogText("tooltip.visitor.list")),
                    "VISITOR_LIST", townId + ":0")));
            items.add(new MenuItem(15, button(Material.WRITABLE_BOOK, dialogText("visitor.invite"),
                    List.of(dialogText("tooltip.visitor.invite")),
                    "VISITOR_INVITE", townId + ":0")));
            openMenu(player, 27, dialogText("visitor.title"),
                    new DialogRoute("GOVERNANCE_CENTER", null), items);
        });
    }

    private void openVisitorList(Player player, UUID townId, int page) {
        runtime.read(player, () -> new VisitorPageView(
                runtime.repository().listVisitors(townId, page, 8),
                runtime.governance().dashboard(player.getUniqueId()).orElse(null)), view -> {
            if (rejectVisitorManagement(player, view.governance(), townId)) {
                return;
            }
            List<MenuItem> items = new ArrayList<>();
            int slot = 0;
            for (TownSnapshot.Visitor visitor : view.page().visitors()) {
                String visitorName = displayName(visitor.playerId());
                String inviterName = displayName(visitor.invitedBy());
                items.add(new MenuItem(slot++, button(Material.PLAYER_HEAD,
                        dialogText("visitor.entry-player", Map.of(
                                "player", safeText(visitorName))),
                        List.of(dialogText("tooltip.visitor.entry.inviter",
                                        Map.of("player", safeText(inviterName))),
                                dialogText("tooltip.visitor.entry.added",
                                        Map.of("time", visitor.addedAt())),
                                dialogText("tooltip.visitor.entry.remove")),
                        "CONFIRM_REMOVE_VISITOR",
                        townId + ":" + visitor.playerId() + ":" + page)));
            }
            if (view.page().visitors().isEmpty()) {
                items.add(new MenuItem(4, button(Material.PAPER, dialogText("visitor.list-empty"),
                        List.of(dialogText("visitor.list-empty-hint")), null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, button(Material.ARROW, dialogText("common.previous"),
                        List.of(),
                        "VISITOR_LIST", townId + ":" + (page - 1))));
            }
            if (view.page().hasNext()) {
                items.add(new MenuItem(53, button(Material.ARROW, dialogText("common.next"), List.of(),
                        "VISITOR_LIST", townId + ":" + (page + 1))));
            }
            openMenu(player, 54, dialogText("visitor.list-title", Map.of("page", page + 1)),
                    new DialogRoute("VISITOR_CENTER", townId.toString()), items);
        });
    }

    private void openVisitorInvite(Player player, UUID townId, int page) {
        runtime.read(player, () -> new VisitorInviteView(
                runtime.governance().dashboard(player.getUniqueId()).orElse(null),
                runtime.repository().listMemberIds(townId),
                runtime.repository().listVisitorIds(townId)), view -> {
            if (rejectVisitorManagement(player, view.governance(), townId)) {
                return;
            }
            List<? extends Player> candidates = Bukkit.getOnlinePlayers().stream()
                    .filter(candidate -> !view.memberIds().contains(candidate.getUniqueId()))
                    .filter(candidate -> !view.visitorIds().contains(candidate.getUniqueId()))
                    .sorted(java.util.Comparator.comparing(Player::getName,
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
            List<? extends Player> visible = page(candidates, page, 8);
            List<MenuItem> items = new ArrayList<>();
            int slot = 0;
            for (Player candidate : visible) {
                items.add(new MenuItem(slot++, button(Material.PLAYER_HEAD,
                        dialogText("visitor.invite-player", Map.of(
                                "player", safeText(candidate.getName()))),
                        List.of(dialogText("tooltip.visitor.invite-entry.click")),
                        "CONFIRM_ADD_VISITOR",
                        townId + ":" + candidate.getUniqueId() + ":" + page)));
            }
            if (visible.isEmpty()) {
                items.add(new MenuItem(4, button(Material.PAPER, dialogText("visitor.no-candidates"),
                        List.of(), null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, button(Material.ARROW, dialogText("common.previous"),
                        List.of(),
                        "VISITOR_INVITE", townId + ":" + (page - 1))));
            }
            if (hasNext(candidates, page, 8)) {
                items.add(new MenuItem(53, button(Material.ARROW, dialogText("common.next"), List.of(),
                        "VISITOR_INVITE", townId + ":" + (page + 1))));
            }
            openMenu(player, 54, dialogText("visitor.invite-title", Map.of("page", page + 1)),
                    new DialogRoute("VISITOR_CENTER", townId.toString()), items);
        });
    }

    private boolean rejectVisitorManagement(Player player,
                                            MemberGovernanceSnapshot governance,
                                            UUID townId) {
        if (governance != null && governance.townId().equals(townId)
                && governance.role().isLeader()) {
            return false;
        }
        openNotice(player, dialogText("notice.visitor-forbidden-title"),
                dialogText("notice.visitor-forbidden-message"),
                dialogText("common.back"), "GOVERNANCE_CENTER", null);
        return true;
    }

    private void openPendingCenter(Player player) {
        runtime.read(player, () -> new GovernanceCenterView(
                runtime.repository().dashboard(player.getUniqueId()),
                runtime.governance().dashboard(player.getUniqueId()).orElse(null)), view -> {
            TownSnapshot town = view.dashboard().town();
            MemberGovernanceSnapshot governance = view.governance();
            if (town == null || governance == null) {
                openMain(player);
                return;
            }
            int pendingJoins = governance.canReviewApplications()
                    ? view.dashboard().incomingJoinApplications().size() : 0;
            int pendingVotes = (int) governance.votes().stream()
                    .filter(vote -> vote.viewerEligible() && !vote.viewerVoted()).count();
            int pendingTransfer = governance.pendingTransfer() == null ? 0 : 1;
            int total = pendingJoins + pendingVotes + pendingTransfer;
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(0, button(total > 0 ? Material.ENCHANTED_BOOK : Material.BOOK,
                    total > 0 ? dialogText("pending.count-title", Map.of("count", total))
                            : dialogText("pending.empty-title"),
                    total > 0 ? List.of(dialogText("pending.decisions-only"),
                            dialogText("votes.pending-summary", Map.of("joins", pendingJoins,
                                    "votes", pendingVotes, "transfers", pendingTransfer)))
                            : List.of(dialogText("votes.pending-empty-hint")), null, null)));
            if (pendingJoins > 0) {
                items.add(new MenuItem(10, button(Material.ENCHANTED_BOOK,
                        dialogText("common.applications-count", Map.of("count", pendingJoins)),
                        List.of(dialogText("tooltip.pending.applications")),
                        "JOIN_APPLICATIONS", town.id().toString())));
            }
            if (pendingVotes > 0) {
                items.add(new MenuItem(12, button(Material.ENCHANTED_BOOK,
                        dialogText("votes.pending-title", Map.of("count", pendingVotes)),
                        List.of(dialogText("tooltip.pending.votes")),
                        "VOTES", town.id().toString())));
            }
            if (governance.pendingTransfer() != null) {
                items.add(new MenuItem(14, button(Material.NETHER_STAR,
                        dialogText("pending.transfer"),
                        List.of(dialogText("tooltip.pending.transfer")),
                        "TRANSFER_REQUEST", governance.pendingTransfer().id().toString())));
            }
            openMenu(player, 27, dialogText("pending.title"),
                    new DialogRoute("MAIN", null), items);
        });
    }

    private void openPersonalCenter(Player player) {
        runtime.read(player, () -> runtime.repository().dashboard(player.getUniqueId()), dashboard -> {
            TownSnapshot town = dashboard.town();
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(0, button(Material.PLAYER_HEAD,
                    dialogText("personal.player", Map.of("player", safeText(player.getName()))),
                    List.of(town == null ? dialogText("personal.no-town")
                            : dialogText("personal.town", Map.of(
                                    "town", safeText(town.profile().name()))),
                            dialogText("personal.handbook-hint")), null, null)));
            items.add(new MenuItem(10, button(Material.WRITTEN_BOOK,
                    dialogText("common.handbook"),
                    List.of(dialogText("tooltip.personal.handbook")),
                    "GIVE_HANDBOOK", null)));
            if (town != null && town.mayorId().equals(player.getUniqueId())) {
                items.add(new MenuItem(16, button(Material.TNT, dialogText("personal.disband"),
                        List.of(dialogText("tooltip.personal.disband-only"),
                                dialogText("common.irreversible")),
                        "CONFIRM_DISBAND", town.id() + ":" + town.version())));
            } else if (town != null) {
                items.add(new MenuItem(16, button(Material.OAK_DOOR, dialogText("personal.leave"),
                        List.of(dialogText("tooltip.personal.leave")),
                        "CONFIRM_LEAVE", town.id().toString())));
            }
            openMenu(player, 27, dialogText("personal.title"),
                    new DialogRoute("MAIN", null), items);
        });
    }

    void openFinance(Player player, int page) {
        runtime.read(player, () -> {
            EconomyRepository.TownFinance account = runtime.finance()
                    .findFinanceByPlayer(player.getUniqueId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            plugin.messages().plainText("chat.runtime.town-required")));
            return new FinanceView(account, runtime.quickShopSubsidyQuota(account.townId()));
        }, view -> renderFinance(player, view));
    }

    private void renderFinance(Player player, FinanceView view) {
        EconomyRepository.TownFinance account = view.account();
        List<String> summary = new ArrayList<>(List.of(
                dialogText("common.town", Map.of("town", safeText(account.townName()))),
                dialogText("finance.balance", Map.of("balance",
                        safeText(runtime.money(account.balanceMinor())))),
                dialogText("finance.tax-rate", Map.of("rate",
                        safeText(TownRuntime.percent(account.taxRateBps())))),
                dialogText("common.territory-units", Map.of("count", account.unitCount(),
                        "maximum", runtime.economySettings().maximumUnits())),
                dialogText("finance.subsidy-twelve-hour", Map.of("amount", safeText(
                                runtime.money(view.subsidyQuota().twelveHourRemainingMinor())),
                        "refresh", safeText(view.subsidyQuota().twelveHourRefreshAt()))),
                dialogText("finance.subsidy-week", Map.of("amount", safeText(
                                runtime.money(view.subsidyQuota().weeklyRemainingMinor())),
                        "refresh", safeText(view.subsidyQuota().weeklyRefreshAt())))));
        if (account.locked()) {
            summary.add(dialogText("finance.locked", Map.of("reason",
                    safeText(account.lockReason()))));
        }
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem(4, button(account.locked() ? Material.REDSTONE_BLOCK
                : Material.EMERALD_BLOCK, dialogText("common.finance-title"), summary, null, null)));
        if (runtime.consumptionEnabled()) {
            items.add(new MenuItem(10, button(Material.SUNFLOWER,
                    dialogText("finance.donation"),
                    List.of(dialogText("tooltip.finance.donation"),
                            dialogText("tooltip.finance.donation-vault")),
                    "DONATION_INPUT", null)));
        }
        if (account.role().equals("MAYOR") && runtime.taxEnabled()) {
            items.add(new MenuItem(12, button(Material.GOLD_NUGGET,
                    dialogText("finance.tax"),
                    List.of(dialogText("tooltip.finance.tax")), "TAX_MENU", null)));
        }
        items.add(new MenuItem(14, button(Material.WRITTEN_BOOK,
                dialogText("finance.ledger"),
                List.of(dialogText("tooltip.finance.ledger"),
                        dialogText("tooltip.finance.ledger-subsidy")), "LEDGER", "0")));
        items.add(new MenuItem(15, button(Material.BREWING_STAND, dialogText("finance.buff"),
                List.of(runtime.buffs().buffShopEnabled()
                                ? dialogText("tooltip.finance.buff-active")
                                : dialogText("tooltip.finance.buff-paused")),
                "BUFF_SHOP", null)));
        if (account.role().equals("MAYOR") && runtime.consumptionEnabled()) {
            long expansionPriceMinor = ExpansionPricing.price(
                    runtime.economySettings().expansionCost(), runtime.settlement().scale())
                    .minorUnits();
            items.add(new MenuItem(16, button(Material.FILLED_MAP, dialogText("finance.expansion"),
                    List.of(dialogText("tooltip.finance.expansion", Map.of("price",
                            safeText(runtime.money(expansionPriceMinor))))),
                    "EXPANSION_MENU", null)));
        }
        openMenu(player, 27, dialogText("common.finance-title"),
                new DialogRoute("MAIN", null), items);
        if (account.hasUnreadTaxChange()) {
            actions.acknowledgeTaxRevision(player, account.taxRevision(), outcome ->
                    handleOutcome(player, outcome, ignored -> {
                    }));
        }
    }

    private void openTaxMenu(Player player) {
        runtime.read(player, () -> runtime.finance().findFinanceByPlayer(player.getUniqueId())
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-required"))), account -> {
            boolean editable = account.role().equals("MAYOR") && runtime.taxEnabled();
            ItemStack summary = button(Material.GOLD_INGOT, dialogText("tax.summary-title"),
                    List.of(dialogText("tax.current-rate", Map.of("rate",
                                    safeText(TownRuntime.percent(account.taxRateBps())))),
                            dialogText("tax.scope"),
                            editable ? dialogText("tax.editable-hint")
                                    : dialogText("tax.readonly-hint")),
                    null, null);
            if (!editable) {
                openMenu(player, 27, dialogText("tax.menu-title"),
                        new DialogRoute("FINANCE", "0"),
                        List.of(new MenuItem(0, summary)));
                return;
            }
            DialogInput input = DialogInput.numberRange("tax_rate", 360,
                    dialogComponent("tax.rate-label"),
                    dialogFormat("tax.rate-format"), 5.0F,
                    runtime.economySettings().maximumTaxBps() / 100.0F,
                    account.taxRateBps() / 100.0F, 1.0F);
            openDialogPage(player, dialogText("tax.title"), List.of(dialogTextBody(summary)),
                    List.of(input),
                    DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE, session ->
                            DialogType.multiAction(List.of(
                                            ActionButton.create(dialogComponent("common.save-changes"),
                                                    dialogComponent("tax.save-tooltip"),
                                                    170, dialogAction(player, session,
                                                            response -> applyTaxDialog(player, account, response))),
                                            ActionButton.create(dialogComponent("common.cancel"),
                                                    null, 170,
                                                    dialogAction(player, session, "FINANCE", "0"))))
                                    .exitAction(returnButton(player, session,
                                            new DialogRoute("FINANCE", "0")))
                                    .columns(2).build(), new DialogRoute("FINANCE", "0"));
        });
    }

    private void applyTaxDialog(Player player, EconomyRepository.TownFinance account,
                                DialogResponseView response) {
        Float selected = response.getFloat("tax_rate");
        if (selected == null) {
            openNotice(player, dialogText("tax.select-title"),
                    dialogText("tax.select-message"), dialogText("common.back"),
                    "TAX_MENU", null);
            return;
        }
        int rate = Math.round(selected) * 100;
        if (rate == account.taxRateBps()) {
            openNotice(player, dialogText("tax.unchanged-title"),
                    dialogText("tax.unchanged-message", Map.of(
                            "rate", TownRuntime.percent(rate))),
                    dialogText("common.back"), "FINANCE", "0");
            return;
        }
        actions.changeTaxRate(player, account.townId(), rate, outcome ->
                handleOutcome(player, outcome, change -> {
                    notifyTaxRateChange(change);
                    openNotice(player, dialogText("tax.saved-title"),
                            dialogText("tax.saved-message", Map.of(
                                    "rate", TownRuntime.percent(change.basisPoints()))),
                            dialogText("common.back"), "FINANCE", "0");
                }));
    }

    private void openLedger(Player player, int page) {
        runtime.read(player, () -> {
            EconomyRepository.TownFinance account = runtime.finance()
                    .findFinanceByPlayer(player.getUniqueId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            plugin.messages().plainText("chat.runtime.town-required")));
            List<EconomyRepository.LedgerEntry> entries = runtime.finance()
                    .displayLedger(account.townId(), page, 6);
            return new LedgerPage(account, entries, page);
        }, ledger -> renderLedger(player, ledger));
    }

    private void renderLedger(Player player, LedgerPage ledger) {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem(0, button(Material.WRITTEN_BOOK,
                dialogText("ledger.title", Map.of("town",
                        safeText(ledger.account().townName()))),
                List.of(dialogText("ledger.page", Map.of("page", ledger.page() + 1)),
                        dialogText("ledger.balance", Map.of("balance",
                                safeText(runtime.money(ledger.account().balanceMinor()))))),
                null, null)));
        if (ledger.entries().isEmpty()) {
            items.add(new MenuItem(1, button(Material.PAPER,
                    dialogText("ledger.empty"),
                    List.of(dialogText("ledger.empty-hint")), null, null)));
        }
        int slot = 1;
        for (EconomyRepository.LedgerEntry entry : ledger.entries()) {
            boolean income = entry.amountMinor() > 0;
            String amount = dialogText(income ? "ledger.income-amount" : "ledger.expense-amount",
                    Map.of("amount", safeText(runtime.money(Math.abs(entry.amountMinor())))));
            String actor = displayActorName(entry);
            if (actor == null) {
                String actorId = entry.actorId() == null ? "" : entry.actorId().toString()
                        .replace("-", "");
                String suffix = actorId.length() > 24 ? actorId.substring(24) : actorId;
                actor = dialogText("common.unknown-player", Map.of("playerId", suffix));
            }
            items.add(new MenuItem(slot++, button(income ? Material.LIME_DYE : Material.RED_DYE,
                    dialogText("ledger.entry-title", Map.of("amount", amount,
                            "type", ledgerLabel(entry.entryType()))),
                    List.of(dialogText("ledger.entry-balance", Map.of("balance",
                                    safeText(runtime.money(entry.balanceAfterMinor())))),
                            dialogText("ledger.entry-actor", Map.of("actor", actor)),
                            dialogText("ledger.entry-time", Map.of("time",
                                    safeText(entry.createdAt()))),
                            dialogText("ledger.entry-note", Map.of("note",
                                    safeText(entry.note())))), null, null)));
        }
        if (ledger.page() > 0) {
            items.add(new MenuItem(20, button(Material.ARROW, dialogText("common.previous"),
                    List.of(), "LEDGER", String.valueOf(ledger.page() - 1))));
        }
        if (ledger.entries().size() == 6) {
            items.add(new MenuItem(21, button(Material.ARROW, dialogText("common.next"),
                    List.of(), "LEDGER", String.valueOf(ledger.page() + 1))));
        }
        openMenu(player, 27, dialogText("ledger.page-title", Map.of("page", ledger.page() + 1)),
                new DialogRoute("FINANCE", "0"), items);
    }

    private void openExpansionMenu(Player player) {
        runtime.loadTerritoryMap(player, map -> {
            Set<TerritoryService.GridSelection> selected = expansionSelections.computeIfAbsent(
                    player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet());
            Set<TerritoryService.GridSelection> validSelected = new java.util.HashSet<>(selected);
            map.cells().stream()
                    .filter(cell -> cell.state() != org.allivlisey.tianjitown.core.land.TerritoryCellState.EXPANDABLE)
                    .map(cell -> new TerritoryService.GridSelection(cell.gridX(), cell.gridZ()))
                    .forEach(validSelected::remove);
            selected.retainAll(validSelected);
            if (selected.isEmpty()) {
                expansionBatchRequestIds.remove(player.getUniqueId());
            }
            long total = map.priceMinor() <= 0 ? 0
                    : Math.multiplyExact(map.priceMinor(), selected.size());
            String price = map.priceMinor() > 0
                    ? runtime.money(map.priceMinor()) : dialogText("territory.limit-reached");
            Component summary = dialogComponent("territory.summary", Map.of(
                            "current", map.currentUnits(), "maximum", map.maximumUnits()))
                    .append(Component.newline())
                    .append(dialogComponent("territory.batch-summary", Map.of(
                            "count", selected.size(), "price", runtime.money(total),
                            "units", map.currentUnits() + selected.size(),
                            "maximum", map.maximumUnits())))
                    .append(Component.newline())
                    .append(dialogComponent("territory.legend"));
            openDialogPage(player, dialogText("territory.title"),
                    List.of(DialogBody.plainMessage(summary, 360)), List.of(),
                    DialogBase.DialogAfterAction.NONE, session -> {
                        ActionButton back = returnButton(player, session,
                                new DialogRoute("FINANCE", "0"));
                        List<TerritoryDialogRenderer.FooterAction> footer = new ArrayList<>();
                        if (!selected.isEmpty()) {
                            Component confirmLabel = dialogComponent("territory.batch-confirm");
                            footer.add(new TerritoryDialogRenderer.FooterAction(
                                    TerritoryDialogRenderer.FooterKind.CONFIRM,
                                    confirmLabel,
                                    confirmLabel.append(Component.newline()).append(
                                            dialogComponent("territory.batch-confirm-consequence", Map.of(
                                                    "price", runtime.money(total),
                                                    "count", selected.size()))),
                                    dialogAction(player, session, "CONFIRM_EXPANSION_BATCH", null)));
                            footer.add(new TerritoryDialogRenderer.FooterAction(
                                    TerritoryDialogRenderer.FooterKind.CLEAR,
                                    dialogComponent("territory.batch-clear"),
                                    dialogComponent("territory.batch-clear"),
                                    dialogAction(player, session,
                                            "CLEAR_EXPANSION_SELECTION", null)));
                        }
                        return TerritoryDialogRenderer.render(map, price, plugin.messages(),
                                selected,
                                cell -> dialogAction(player, session, "TOGGLE_EXPANSION",
                                        cell.gridX() + "," + cell.gridZ()), back, footer);
                    }, new DialogRoute("FINANCE", "0"));
        });
    }

    private void toggleExpansionSelection(Player player, String target) {
        GridTarget grid = gridTarget(target);
        Set<TerritoryService.GridSelection> selected = expansionSelections.computeIfAbsent(
                player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet());
        TerritoryService.GridSelection choice = new TerritoryService.GridSelection(grid.x(), grid.z());
        boolean changed = selected.add(choice);
        if (!changed) {
            selected.remove(choice);
        }
        if (selected.isEmpty()) {
            expansionBatchRequestIds.remove(player.getUniqueId());
        } else {
            if (changed || !expansionBatchRequestIds.containsKey(player.getUniqueId())) {
                expansionBatchRequestIds.put(player.getUniqueId(), UUID.randomUUID());
            }
        }
        openExpansionMenu(player);
    }

    private void clearExpansionSelection(Player player) {
        expansionSelections.remove(player.getUniqueId());
        expansionBatchRequestIds.remove(player.getUniqueId());
        openExpansionMenu(player);
    }

    private void confirmExpansionBatch(Player player) {
        Set<TerritoryService.GridSelection> selected = expansionSelections.get(player.getUniqueId());
        if (selected == null || selected.isEmpty()) {
            openExpansionMenu(player);
            return;
        }
        Set<TerritoryService.GridSelection> snapshot = Set.copyOf(selected);
        UUID requestId = expansionBatchRequestIds.computeIfAbsent(player.getUniqueId(),
                ignored -> UUID.randomUUID());
        closeUi(player);
        runtime.expandBatchAction(player, snapshot, requestId.toString(), ignored -> {
            if (Objects.equals(expansionBatchRequestIds.get(player.getUniqueId()), requestId)) {
                expansionSelections.remove(player.getUniqueId());
                expansionBatchRequestIds.remove(player.getUniqueId());
            }
            openExpansionMenu(player);
        }, exception -> {
            // 已扣款但 Residence 投影失败时数据库会退款；下一次真正重试必须使用新幂等键，
            // 同时保留当前选区，避免用户重新点选整个批次。
            expansionBatchRequestIds.remove(player.getUniqueId(), requestId);
            openNotice(player, dialogText("territory.unavailable-title"),
                    exception.getMessage(), dialogText("common.back"), "EXPANSION_MENU", null);
        });
    }

    private void previewExpansion(Player player, int gridX, int gridZ) {
        runtime.read(player, () -> runtime.expansionPreview(
                player.getUniqueId(), gridX, gridZ), preview -> {
            SitePolicy.Validation validation = runtime.validateExpansionPreview(preview);
            if (!validation.valid()) {
                openNotice(player, dialogText("territory.unavailable-title"), validation.error(),
                        dialogText("common.back"), "EXPANSION_MENU", null);
                return;
            }
            sitePolicy.preview(player, preview.candidate().territory());
            String target = gridX + "," + gridZ;
            openConfirmation(player, dialogText("territory.confirm-title", Map.of(
                            "grid", target)), "EXPAND", target,
                    dialogText("territory.confirm-consequence", Map.of(
                            "price", runtime.money(preview.priceMinor()))),
                    "EXPANSION_MENU", null);
        });
    }

    void openBuffShop(Player player) {
        runtime.read(player, () -> {
            Map<String, CommerceRepository.SelectedBuffQuote> quotes = new LinkedHashMap<>();
            Map<String, String> errors = new LinkedHashMap<>();
            for (BuffDefinition definition : runtime.buffs().settings().buffs().values()) {
                try {
                    quotes.put(definition.key(), runtime.buffs().repository().quoteBuff(
                            player.getUniqueId(), definition, 1, 1,
                            runtime.settlement().scale(), Instant.now()));
                } catch (IllegalArgumentException | CommerceRepository.ConflictException exception) {
                    errors.put(definition.key(), exception.getMessage());
                }
            }
            List<CommerceRepository.ActiveBuff> active = runtime.buffs().repository()
                    .activeBuffsForPlayer(player.getUniqueId(), Instant.now());
            return new BuffShopView(active, quotes, errors);
        }, view -> {
            Map<String, CommerceRepository.ActiveBuff> active = view.active().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            CommerceRepository.ActiveBuff::buffKey, value -> value));
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(4, button(Material.NETHER_STAR,
                    dialogText("buff.shop-summary-title"),
                    List.of(BuffDialogRenderer.shopHint(plugin.messages(),
                            runtime.buffs().buffShopEnabled())), null, null)));
            int slot = 9;
            for (BuffDefinition definition : runtime.buffs().settings().buffs().values()) {
                CommerceRepository.SelectedBuffQuote quote = view.quotes().get(definition.key());
                CommerceRepository.ActiveBuff current = active.get(definition.key());
                boolean purchasable = runtime.buffs().buffShopEnabled() && quote != null;
                items.add(new MenuItem(slot++, button(purchasable ? Material.POTION
                                : Material.GLASS_BOTTLE,
                        (purchasable ? "§d" : "§7")
                                + runtime.buffs().settings().label(definition.key())
                                + (current == null ? "" : " · "
                                + BuffDialogRenderer.roman(current.level())),
                        List.of(),
                        purchasable ? "BUFF_DURATIONS" : null, definition.key())));
            }
            openMenu(player, 54, dialogText("buff.shop-title"),
                    new DialogRoute("FINANCE", "0"), items);
        });
    }

    private void openBuffDurations(Player player, String buffKey) {
        runtime.read(player, () -> {
            BuffDefinition definition = runtime.buffs().settings().requireBuff(buffKey);
            CommerceRepository.SelectedBuffQuote quote = runtime.buffs().repository().quoteBuff(
                    player.getUniqueId(), definition, 1, 1, runtime.settlement().scale(),
                    Instant.now());
            return quote;
        }, quote -> {
            BuffDefinition definition = runtime.buffs().settings().requireBuff(buffKey);
            BuffDialogRenderer.ActiveState current = quote.current() == null ? null
                    : new BuffDialogRenderer.ActiveState(quote.current().level(),
                            quote.current().expiresAt().toString());
            ItemStack summary = button(Material.POTION, "§d"
                            + runtime.buffs().settings().label(definition.key()),
                    BuffDialogRenderer.parameterSummaryLore(plugin.messages(),
                            buffEffectDescription(definition), current), null, null);
            DialogInput duration = DialogInput.numberRange("buff_weeks", 420,
                    dialogComponent("buff.duration-label"),
                    dialogFormat("buff.duration-format"),
                    1.0F, 4.0F, 1.0F, 1.0F);
            DialogInput intensity = DialogInput.numberRange("buff_level", 420,
                    dialogComponent("buff.intensity-label"),
                    dialogFormat("buff.intensity-format"),
                    1.0F, Math.min(5, definition.maximumLevel()),
                    quote.current() == null ? 1.0F
                            : Math.min(5.0F, quote.current().level()), 1.0F);
            openDialogPage(player, dialogText("buff.title"), List.of(dialogTextBody(summary)),
                    List.of(duration, intensity), DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE,
                    session -> DialogType.multiAction(List.of(
                                    ActionButton.create(dialogComponent("buff.continue"),
                                            null, 170, dialogAction(player, session,
                                                    response -> applyBuffDurationDialog(
                                                            player, buffKey, response))),
                                    ActionButton.create(dialogComponent("common.cancel"),
                                            null, 170,
                                            dialogAction(player, session, "BUFF_SHOP", null))))
                            .exitAction(returnButton(player, session,
                                    new DialogRoute("BUFF_SHOP", null)))
                            .columns(2).build(), new DialogRoute("BUFF_SHOP", null));
        });
    }

    private void applyBuffDurationDialog(Player player, String buffKey,
                                         DialogResponseView response) {
        Float selectedWeeks = response.getFloat("buff_weeks");
        Float selectedLevel = response.getFloat("buff_level");
        if (selectedWeeks == null || selectedLevel == null) {
            openNotice(player, dialogText("buff.select-title"),
                    dialogText("buff.select-message"), dialogText("common.back"),
                    "BUFF_DURATIONS", buffKey);
            return;
        }
        int weeks = Math.round(selectedWeeks);
        int level = Math.round(selectedLevel);
        runtime.read(player, () -> {
            BuffDefinition definition = runtime.buffs().settings().requireBuff(buffKey);
            return runtime.buffs().repository().quoteBuff(player.getUniqueId(), definition,
                    weeks, level, runtime.settlement().scale(), Instant.now());
        }, quote -> openConfirmation(player, dialogText("buff.confirm-title"), "BUY_BUFF",
                buffKey + ":" + weeks + ":" + level,
                dialogText("buff.confirm-consequence", Map.of(
                        "level", BuffDialogRenderer.roman(level), "weeks", weeks,
                        "price", runtime.money(quote.priceMinor()))),
                "BUFF_DURATIONS", buffKey));
    }

    private void buyBuff(Player player, String target) {
        String[] parts = target.split(":");
        String buffKey = parts[0];
        int weeks = Integer.parseInt(parts[1]);
        int level = Integer.parseInt(parts[2]);
        actions.buyBuff(player, buffKey, weeks, level, outcome ->
                handleOutcome(player, outcome, purchase -> {
                    BuffDefinition definition = runtime.buffs().settings().requireBuff(buffKey);
                    openNotice(player, dialogText("buff.success-title"),
                            dialogText("buff.success-message", Map.of(
                                    "name", runtime.buffs().settings().label(definition.key()),
                                    "level", BuffDialogRenderer.roman(purchase.buff().level()),
                                    "expires", purchase.buff().expiresAt(),
                                    "balance", runtime.money(purchase.balanceAfterMinor()))),
                            dialogText("common.back"), "FINANCE", "0");
                }));
    }

    private String buffEffectDescription(BuffDefinition definition) {
        if (definition.key().equals("health")) {
            return dialogText("buff.health-effect");
        }
        if (definition.key().equals("speed")) {
            return dialogText("buff.speed-effect");
        }
        return dialogText("buff.generic-effect", Map.of(
                "effect", definition.effectKey(),
                "amount", (definition.amountPerLevel() >= 0 ? "+" : "")
                        + definition.amountPerLevel()));
    }

    private String ledgerLabel(String type) {
        String stableType = String.valueOf(type);
        String key = switch (stableType) {
            case "QUICKSHOP_TAX" -> "ledger.type.quickshop-tax";
            case "JOBS_TAX" -> "ledger.type.jobs-tax";
            case "GLOBALMARKETPLUS_TAX" -> "ledger.type.global-market-plus-tax";
            case "SERVER_TAX_SUBSIDY" -> "ledger.type.server-tax-subsidy";
            case "APPLICATION_FEE" -> "ledger.type.application-fee";
            case "DONATION" -> "ledger.type.donation";
            case "EXPANSION" -> "ledger.type.expansion";
            case "EXPANSION_REFUND" -> "ledger.type.expansion-refund";
            case "ADMIN_ADJUSTMENT" -> "ledger.type.admin-adjustment";
            case "BUFF_PURCHASE" -> "ledger.type.buff-purchase";
            case "BUFF_REFUND" -> "ledger.type.buff-refund";
            case "RESOURCE_PURCHASE" -> "ledger.type.resource-purchase";
            case "RESOURCE_REFUND" -> "ledger.type.resource-refund";
            default -> null;
        };
        return key == null
                ? dialogText("ledger.type.unknown", Map.of("type", safeText(stableType)))
                : dialogText(key);
    }

    private void renderRulesConfirmation(Player player, MemberGovernanceSnapshot governance) {
        Component rules = dialogComponent("common.town", Map.of(
                        "town", safeText(governance.townName())))
                .append(Component.newline())
                .append(dialogComponent("rules.revision", Map.of(
                        "revision", governance.townRulesRevision())));
        for (int index = 0; index < governance.rules().size(); index++) {
            rules = rules.append(Component.newline()).append(Component.newline())
                    .append(dialogComponent("rules.item", Map.of(
                            "index", index + 1,
                            "rule", safeText(governance.rules().get(index)))));
        }
        rules = rules.append(Component.newline()).append(Component.newline())
                .append(dialogComponent("rules.locked-hint"));
        DialogInput acknowledged = DialogInput.bool("rules_acknowledged",
                dialogComponent("rules.acknowledgement"),
                false, "true", "false");
        String target = governance.townId() + ":" + governance.townRulesRevision();
        openDialogPage(player, dialogText("rules.updated-title"),
                List.of(DialogBody.plainMessage(rules, 420)),
                List.of(acknowledged), DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE,
                session -> DialogType.multiAction(List.of(
                                ActionButton.create(dialogComponent("rules.confirm"),
                                        null, 170, dialogAction(player, session,
                                                response -> acknowledgeRulesDialog(player, target, response))),
                                ActionButton.create(dialogComponent("rules.later"),
                                        dialogComponent("rules.later-tooltip"),
                                        170, dialogAction(player, session, "CLOSE", null))))
                        .exitAction(returnButton(player, session, DialogRoute.ROOT))
                        .columns(2).build(), DialogRoute.ROOT);
    }

    private void acknowledgeRulesDialog(Player player, String target,
                                        DialogResponseView response) {
        if (!Boolean.TRUE.equals(response.getBoolean("rules_acknowledged"))) {
            openNotice(player, dialogText("rules.required-title"),
                    dialogText("rules.required-message"), dialogText("common.back"),
                    "MAIN", null);
            return;
        }
        acknowledgeRules(player, target);
    }

    private void openApplication(Player player, ApplicationSnapshot application) {
        List<String> summary = new ArrayList<>(List.of(
                dialogText("common.applicant", Map.of(
                        "applicant", safeText(displayName(application.applicantId())))),
                dialogText("common.application-status", Map.of(
                        "status", dialogText(ApplicationStatusText.messageKey(application.status())))),
                dialogText("common.name", Map.of("name", safeText(application.text().name()))),
                dialogText("common.residence-name", Map.of(
                        "residence", safeText(application.text().normalizedResidenceName()))),
                dialogText("common.town-description", Map.of(
                        "description", safeText(application.text().description())))));
        for (InitialMemberConfirmation member : application.initialMembers()) {
            summary.add(dialogText("application.initial-member", Map.of(
                    "player", safeText(displayName(member.playerId())),
                    "status", initialMemberStatus(member.status()))));
        }
        for (int index = 0; index < application.text().rules().size(); index++) {
            summary.add(dialogText("application.rule", Map.of(
                    "index", index + 1,
                    "rule", safeText(application.text().rules().get(index)))));
        }
        if (application.territory() != null) {
            summary.add(dialogText("application.territory-center", Map.of(
                    "x", application.territory().center().x(),
                    "z", application.territory().center().z())));
        }
        if (application.reviewMessage() != null) {
            summary.add(dialogText("common.admin-review-message", Map.of(
                    "message", safeText(application.reviewMessage()))));
        }
        if (application.lastError() != null) {
            summary.add(dialogText("application.last-error", Map.of(
                    "error", safeText(application.lastError()))));
        }
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem(4, button(Material.PAPER,
                dialogText("application.summary-title"), summary, null, null)));
        if (application.status() == ApplicationStatus.DRAFT
                || application.status() == ApplicationStatus.SITE_SELECTED
                || application.status() == ApplicationStatus.NEED_CHANGES) {
            items.add(new MenuItem(10, button(Material.WRITABLE_BOOK,
                    dialogText("application.edit"),
                    List.of(dialogText("tooltip.application.edit")), "EDIT_APPLICATION",
                    application.id().toString())));
            if (application.initialMembers().stream().anyMatch(member ->
                    member.status() == InitialMemberConfirmation.Status.PENDING)) {
                items.add(new MenuItem(11, button(Material.BELL, dialogText("application.remind"),
                        List.of(dialogText("tooltip.application.remind"),
                                dialogText("tooltip.application.remind-cooldown")),
                        "REMIND_INITIAL_MEMBERS", application.id().toString())));
            }
            items.add(new MenuItem(12, button(Material.COMPASS,
                    dialogText("application.select-site"),
                    List.of(dialogText("tooltip.application.select-site")), "SELECT_SITE",
                    application.id().toString())));
            if (application.territory() != null) {
                items.add(new MenuItem(14, button(Material.ENDER_EYE,
                        dialogText("application.preview-site"),
                        List.of(dialogText("common.preview-site")), "PREVIEW_SITE",
                        application.id().toString())));
                boolean confirmed = application.initialMembersConfirmed();
                items.add(new MenuItem(16, button(confirmed ? Material.LIME_CONCRETE
                                : Material.GRAY_CONCRETE,
                        confirmed ? dialogText("application.submit")
                                : dialogText("application.waiting-members"),
                        ApplicationSubmissionDialogRenderer.submitTooltipKeys(confirmed).stream()
                                .map(this::dialogText).toList(),
                        confirmed ? "CONFIRM_SUBMIT" : null,
                        confirmed ? application.id().toString() : null)));
            }
            items.add(new MenuItem(22, button(Material.BARRIER, dialogText("application.cancel"),
                    List.of(dialogText("tooltip.application.cancel-draft")), "CONFIRM_CANCEL",
                    application.id().toString())));
        } else if (application.status() == ApplicationStatus.SUBMITTED
                || application.status() == ApplicationStatus.UNDER_REVIEW) {
            items.add(new MenuItem(22, button(Material.BARRIER, dialogText("application.cancel"),
                    List.of(dialogText("tooltip.application.cancel-submitted")), "CONFIRM_CANCEL",
                    application.id().toString())));
        }
        openMenu(player, 27, dialogText("application.summary-title"),
                new DialogRoute("MAIN", null), items);
    }

    private String initialMemberStatus(InitialMemberConfirmation.Status status) {
        return switch (status) {
            case PENDING -> dialogText("application.initial-member-status.pending");
            case CONFIRMED -> dialogText("application.initial-member-status.confirmed");
            case REJECTED -> dialogText("application.initial-member-status.rejected");
        };
    }

    private void openTown(Player player, UUID townId) {
        runtime.read(player, () -> new TownDetailsView(runtime.repository().findTown(townId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                plugin.messages().plainText("chat.runtime.town-not-found"))),
                runtime.governance().dashboard(player.getUniqueId()).orElse(null)), view -> {
            TownSnapshot town = view.town();
            MemberGovernanceSnapshot governance = view.governance();
            TownDetailsMenuModel townDetails = TownDetailsMenuModel.create(
                    plugin.messages(), town);
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(4, button(Material.BELL, dialogText("common.town-name", Map.of(
                            "town", safeText(town.profile().name()))),
                    townDetails.summaryLore(), null, null)));
            TownDetailsMenuModel.RulesEntry rulesEntry = townDetails.rulesEntry();
            items.add(new MenuItem(rulesEntry.slot(), button(Material.WRITTEN_BOOK,
                    dialogText(rulesEntry.labelKey()), List.of(dialogText(rulesEntry.tooltipKey())),
                    rulesEntry.action(), rulesEntry.target())));
            items.add(new MenuItem(12, button(Material.PLAYER_HEAD, dialogText("town.members"),
                    List.of(dialogText("tooltip.town.members")), "TOWN_MEMBER_OVERVIEW",
                    town.id() + ":0")));
            if (governance != null && governance.role().isLeader()) {
                items.add(new MenuItem(13, button(Material.WRITABLE_BOOK,
                        dialogText("town.edit-description"),
                        List.of(dialogText("tooltip.town.edit-description")),
                        "EDIT_TOWN_DESCRIPTION", town.id().toString())));
                items.add(new MenuItem(14, button(Material.PAPER, dialogText("town.edit-rules"),
                        List.of(dialogText("tooltip.town.edit-rules")),
                        "EDIT_TOWN_RULES", town.id().toString())));
            }
            if (town.territory() != null) {
                items.add(new MenuItem(16, button(Material.MAP, dialogText("town.territory"),
                        townTerritoryLore(plugin.messages(), town.territory()),
                        "PREVIEW_TOWN",
                        town.id().toString())));
            }
            if (governance != null && governance.role() == MemberRole.MAYOR) {
                items.add(new MenuItem(17, button(Material.ENDER_PEARL,
                        dialogText("town.set-teleport"),
                        List.of(dialogText("tooltip.town.set-teleport")),
                        "SET_TOWN_TELEPORT", town.id().toString())));
            }
            openMenu(player, 27, dialogText("town.title"),
                    new DialogRoute("MAIN", null), items);
        });
    }

    static List<String> townTerritoryLore(PluginMessages messages, InitialTerritory territory) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(territory, "territory");
        return List.of(
                messages.rawText("dialog.tooltip.town.territory-center", Map.of(
                        "x", territory.center().x(), "z", territory.center().z())),
                messages.rawText("dialog.tooltip.town.territory-preview"));
    }

    private void openTownRules(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-not-found"))), town ->
                openReadOnlyTownRules(player, town,
                        new DialogRoute("TOWN", town.id().toString())));
    }

    private void openJoinTownRules(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .filter(town -> town.status() == TownStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-unavailable"))), town ->
                openReadOnlyTownRules(player, town,
                        new DialogRoute("JOIN_TOWN", town.id().toString())));
    }

    private void openReadOnlyTownRules(Player player, TownSnapshot town, DialogRoute returnRoute) {
        ReadOnlyRulesDialogRenderer.Layout layout = ReadOnlyRulesDialogRenderer.layout(
                town.profile().name(), town.profile().rules(), returnRoute);
        openDialogPage(player, dialogText("common.rules-title"), List.of(
                        DialogBody.plainMessage(ReadOnlyRulesDialogRenderer.content(
                                plugin.messages(), layout), ReadOnlyRulesDialogRenderer.CONTENT_WIDTH)),
                List.of(), DialogBase.DialogAfterAction.NONE,
                session -> DialogType.notice(returnButton(player, session, layout.returnRoute())),
                layout.returnRoute());
    }

    private void openTownRuleEditor(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-not-found"))), town -> {
            RuleEditorDialogRenderer.Layout layout = RuleEditorDialogRenderer.layout(town.id(),
                    town.version(), town.profile().rules());
            DialogRoute parent = new DialogRoute("TOWN", town.id().toString());
            openRuleEditorAddDialog(player, dialogText("rules.edit-title"),
                    dialogComponent("rules.edit-heading", Map.of(
                            "town", safeText(town.profile().name()))),
                    layout, parent,
                    new DialogRoute("EDIT_TOWN_RULE_DELETIONS", town.id().toString()),
                    response -> addTownRule(player, town.id(), town.version(), response),
                    session -> List.of());
        });
    }

    private void openTownRuleDeletionDialog(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-not-found"))), town -> {
            RuleEditorDialogRenderer.Layout layout = RuleEditorDialogRenderer.layout(town.id(),
                    town.version(), town.profile().rules());
            openRuleEditorDeletionDialog(player, dialogText("rules.edit-title"),
                    dialogComponent("rules.edit-heading", Map.of(
                            "town", safeText(town.profile().name()))),
                    layout, new DialogRoute("EDIT_TOWN_RULES", town.id().toString()),
                    deleteTarget -> deleteTownRule(player, deleteTarget.encode()));
        });
    }

    private void openRuleEditorAddDialog(Player player, String title, Component heading,
                                         RuleEditorDialogRenderer.Layout layout, DialogRoute parent,
                                         DialogRoute deletePage,
                                         Consumer<DialogResponseView> addRule,
                                         Function<UUID, List<ActionButton>> trailingActions) {
        DialogInput input = DialogInput.text("rule_text", 400,
                dialogComponent("rules.input-label"), false, "", 300, null);
        openDialogPage(player, title, List.of(DialogBody.plainMessage(
                        ruleEditorPreview(heading, layout), 420)), List.of(input),
                DialogBase.DialogAfterAction.NONE, session -> {
                    List<ActionButton> actions = new ArrayList<>();
                    actions.add(ActionButton.create(dialogComponent("rules.add"),
                            dialogComponent("rules.add-tooltip"), RuleEditorDialogRenderer.ADD_WIDTH,
                            dialogAction(player, session, addRule)));
                    actions.addAll(trailingActions.apply(session));
                    actions.add(ActionButton.create(dialogComponent("rules.delete-page"),
                            dialogComponent("rules.delete-page-tooltip"),
                            RuleEditorDialogRenderer.ADD_WIDTH,
                            dialogAction(player, session, deletePage.action(), deletePage.target())));
                    return DialogType.multiAction(actions)
                            .exitAction(returnButton(player, session, parent))
                            .columns(RuleEditorDialogRenderer.COLUMNS).build();
                }, parent);
    }

    private void openRuleEditorDeletionDialog(Player player, String title, Component heading,
                                               RuleEditorDialogRenderer.Layout layout,
                                               DialogRoute parent,
                                               Consumer<RuleEditorDialogRenderer.DeleteTarget> deleteRule) {
        Component deleteHeading = heading.append(Component.newline()).append(Component.newline())
                .append(dialogComponent("rules.delete-page-guidance"));
        openDialogPage(player, title, List.of(DialogBody.plainMessage(deleteHeading, 420)), List.of(),
                DialogBase.DialogAfterAction.NONE, session -> {
                    List<ActionButton> actions = new ArrayList<>();
                    // The two columns are emitted as complete pairs, so even a wrapped rule label
                    // remains coupled to its own square barrier button.
                    for (RuleEditorDialogRenderer.Row row : layout.rows()) {
                        actions.add(ActionButton.create(dialogComponent("rules.item", Map.of(
                                        "index", row.displayIndex(), "rule", safeText(row.rule()))),
                                dialogComponent("rules.preview-tooltip"),
                                RuleEditorDialogRenderer.PREVIEW_WIDTH, null));
                        actions.add(ActionButton.create(RuleEditorDialogRenderer.deleteIcon(),
                                dialogComponent("rules.delete-tooltip", Map.of("index",
                                        row.displayIndex())),
                                RuleEditorDialogRenderer.DELETE_SIZE,
                                dialogAction(player, session,
                                        response -> deleteRule.accept(row.deleteTarget()))));
                    }
                    return DialogType.multiAction(actions)
                            .exitAction(returnButton(player, session, parent))
                            .columns(RuleEditorDialogRenderer.COLUMNS).build();
                }, parent);
    }

    private Component ruleEditorPreview(Component heading, RuleEditorDialogRenderer.Layout layout) {
        Component preview = heading;
        if (layout.rows().isEmpty()) {
            return preview.append(Component.newline()).append(Component.newline())
                    .append(dialogComponent("rules.empty"));
        }
        for (RuleEditorDialogRenderer.Row row : layout.rows()) {
            preview = preview.append(Component.newline()).append(Component.newline())
                    .append(dialogComponent("rules.item", Map.of("index", row.displayIndex(),
                            "rule", safeText(row.rule()))));
        }
        return preview;
    }

    private void addTownRule(Player player, UUID townId, long pageVersion,
                             DialogResponseView response) {
        String rule = responseText(response, "rule_text");
        if (rule.isBlank()) {
            openNotice(player, dialogText("rules.invalid-title"),
                    dialogText("rules.invalid-empty"), dialogText("common.back"),
                    "EDIT_TOWN_RULES", townId.toString());
            return;
        }
        updateTownRules(player, townId, pageVersion, rules -> {
            if (rules.size() >= 50) {
                throw new IllegalArgumentException(dialogText("rules.limit-reached"));
            }
            List<String> updated = new ArrayList<>(rules);
            updated.add(rule);
            return updated;
        });
    }

    private void deleteTownRule(Player player, String target) {
        RuleEditorDialogRenderer.DeleteTarget deleteTarget =
                RuleEditorDialogRenderer.DeleteTarget.decode(target, plugin.messages()::rawText);
        updateTownRules(player, deleteTarget.pageId(), deleteTarget.pageVersion(), rules -> {
            if (rules.size() <= 1) {
                throw new IllegalArgumentException(dialogText("rules.minimum-one"));
            }
            if (deleteTarget.ruleIndex() < 0 || deleteTarget.ruleIndex() >= rules.size()) {
                throw new IllegalArgumentException(dialogText("rules.delete-missing"));
            }
            if (!rules.get(deleteTarget.ruleIndex()).equals(deleteTarget.expectedRule())) {
                throw new IllegalArgumentException(dialogText("rules.delete-conflict"));
            }
            List<String> updated = new ArrayList<>(rules);
            updated.remove(deleteTarget.ruleIndex());
            return updated;
        });
    }

    private void updateTownRules(Player player, UUID townId, long expectedVersion,
                                 Function<List<String>, List<String>> transform) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-not-found"))), town -> {
            if (town.version() != expectedVersion) {
                openTownRuleEditorRefreshNotice(player, town.id());
                return;
            }
            List<String> rules;
            try {
                rules = transform.apply(town.profile().rules());
            } catch (IllegalArgumentException exception) {
                openNotice(player, dialogText("rules.invalid-title"), exception.getMessage(),
                        dialogText("common.back"), "EDIT_TOWN_RULES", town.id().toString());
                return;
            }
            ApplicationText profile = new ApplicationText(town.profile().name(),
                    town.profile().shortName(), town.profile().residenceName(),
                    town.profile().description(), rules);
            actions.updateTownProfile(player, town.id(), profile, expectedVersion, outcome -> {
                if (outcome.result().success()) {
                    openTownRuleEditor(player, outcome.value().id());
                } else if ("CONFLICT".equals(outcome.result().reason())) {
                    openTownRuleEditorRefreshNotice(player, town.id());
                } else {
                    handleOutcome(player, outcome, ignored -> { });
                }
            });
        });
    }

    private void openTownRuleEditorRefreshNotice(Player player, UUID townId) {
        openNotice(player, dialogText("rules.refresh-title"),
                dialogText("rules.refresh-message"), dialogText("common.back"),
                "EDIT_TOWN_RULES", townId.toString());
    }

    private void openTownMemberOverview(Player player, UUID townId, int page) {
        runtime.read(player, () -> new TownMemberOverview(
                runtime.repository().findTown(townId)
                        .orElseThrow(() -> new IllegalArgumentException(
                                plugin.messages().plainText("chat.runtime.town-not-found"))),
                runtime.repository().listAllMembers(townId)), view -> {
            TownSnapshot.Page memberPage = memberPage(view.members(), page);
            Component content = dialogComponent("town-members.heading", Map.of(
                    "town", safeText(view.town().profile().name()), "page", page + 1));
            if (memberPage.members().isEmpty()) {
                content = content.append(Component.newline()).append(Component.newline())
                        .append(dialogComponent("town-members.empty"));
            } else {
                for (int index = 0; index < memberPage.members().size(); index++) {
                    TownSnapshot.Member member = memberPage.members().get(index);
                    content = content.append(Component.newline()).append(Component.newline())
                            .append(dialogComponent("town-members.item", Map.of(
                                    "index", page * 8 + index + 1,
                                    "name", displayName(member.playerId()),
                                    "role", memberRoleText(member.role()))));
                }
            }
            DialogRoute parent = new DialogRoute("TOWN", townId.toString());
            openDialogPage(player, dialogText("town-members.title", Map.of("page", page + 1)),
                    List.of(DialogBody.plainMessage(content, 420)), List.of(),
                    DialogBase.DialogAfterAction.NONE, session -> {
                        List<ActionButton> actions = new ArrayList<>();
                        if (page > 0) {
                            actions.add(ActionButton.create(dialogComponent("common.previous"),
                                    null, 170, dialogAction(player, session,
                                            "TOWN_MEMBER_OVERVIEW",
                                            townId + ":" + (page - 1))));
                        }
                        if (memberPage.hasNext()) {
                            actions.add(ActionButton.create(dialogComponent("common.next"),
                                    null, 170, dialogAction(player, session,
                                            "TOWN_MEMBER_OVERVIEW",
                                            townId + ":" + (page + 1))));
                        }
                        ActionButton back = returnButton(player, session, parent);
                        if (actions.isEmpty()) {
                            return DialogType.notice(back);
                        }
                        return DialogType.multiAction(actions)
                                .exitAction(back)
                                .columns(actions.size() == 1 ? 1 : 2)
                                .build();
                    }, parent);
        });
    }

    private void openMembers(Player player, UUID townId, int page) {
        runtime.read(player, () -> new MemberPage(runtime.repository().listAllMembers(townId),
                runtime.governance().dashboard(player.getUniqueId()).orElse(null)), view -> {
            TownSnapshot.Page memberPage = memberPage(view.members(), page);
            List<MenuItem> items = new ArrayList<>();
            int slot = 0;
            for (TownSnapshot.Member member : memberPage.members()) {
                String name = displayName(member.playerId());
                String color = switch (member.role()) {
                    case MAYOR -> "§6";
                    case DEPUTY_MAYOR -> "§a";
                    case MEMBER -> "§f";
                };
                boolean sameTown = view.governance() != null
                        && view.governance().townId().equals(townId);
                items.add(new MenuItem(slot++, button(Material.PLAYER_HEAD,
                        color + name,
                        List.of(dialogText("tooltip.members.role",
                                        Map.of("role", memberRoleText(member.role()))),
                                dialogText("tooltip.members.joined",
                                        Map.of("time", member.joinedAt())),
                                sameTown ? dialogText("tooltip.members.manage")
                                        : dialogText("tooltip.members.readonly")),
                        sameTown ? "MEMBER_DETAIL" : null,
                        sameTown ? townId + ":" + member.playerId() + ":" + page : null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, button(Material.ARROW,
                        dialogText("common.previous"), List.of(),
                        "MEMBERS", townId + ":" + (page - 1))));
            }
            if (memberPage.hasNext()) {
                items.add(new MenuItem(53, button(Material.ARROW,
                        dialogText("common.next"), List.of(),
                        "MEMBERS", townId + ":" + (page + 1))));
            }
            openMenu(player, 54, dialogText("town-members.title", Map.of("page", page + 1)),
                    new DialogRoute("GOVERNANCE_CENTER", null), items);
        });
    }

    private void openMemberDetail(Player player, UUID townId, UUID targetId, int page) {
        runtime.read(player, () -> new MemberDetail(
                runtime.governance().dashboard(player.getUniqueId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                plugin.messages().plainText("chat.runtime.town-required"))),
                runtime.governance().memberRole(townId, targetId)), view -> {
            if (!view.viewer().townId().equals(townId)) {
                openNotice(player, dialogText("notice.member-forbidden-title"),
                        dialogText("notice.member-forbidden-message"),
                        dialogText("common.back"), "MAIN", null);
                return;
            }
            String name = displayName(targetId);
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(4, button(Material.PLAYER_HEAD, "§6" + name,
                    List.of(dialogText("member-role.identity", Map.of("role",
                            memberRoleText(view.targetRole()))),
                            dialogText("member-detail.uuid", Map.of("id", targetId))), null, null)));
            boolean targetIsMayor = view.targetRole() == MemberRole.MAYOR;
            boolean viewerIsMayor = view.viewer().role() == MemberRole.MAYOR;
            if (viewerIsMayor && !targetIsMayor) {
                MemberRole nextRole = view.targetRole() == MemberRole.DEPUTY_MAYOR
                        ? MemberRole.MEMBER : MemberRole.DEPUTY_MAYOR;
                items.add(new MenuItem(10, button(Material.GOLDEN_HELMET,
                        nextRole == MemberRole.DEPUTY_MAYOR
                                ? dialogText("member-detail.promote-deputy")
                                : dialogText("member-detail.demote-member"),
                        List.of(dialogText("tooltip.member-detail.max-deputies")), "CONFIRM_ROLE",
                        townId + ":" + targetId + ":" + nextRole + ":" + page)));
            }
            boolean viewerCanRemove = view.viewer().role().isLeader() && !targetIsMayor
                    && (viewerIsMayor || view.targetRole() == MemberRole.MEMBER);
            if (viewerCanRemove) {
                items.add(new MenuItem(12, button(Material.RED_CONCRETE,
                        dialogText("member-detail.kick"),
                        List.of(dialogText("tooltip.member-detail.kick-now"),
                            dialogText("common.confirmation-required")),
                        "CONFIRM_KICK_MEMBER", townId + ":" + targetId + ":" + page)));
            }
            if (viewerIsMayor && !targetIsMayor) {
                items.add(new MenuItem(14, button(Material.NETHER_STAR,
                        dialogText("member-detail.transfer"),
                        List.of(dialogText("tooltip.member-detail.transfer-expiry")),
                        "CONFIRM_TRANSFER_MAYOR",
                        townId + ":" + targetId + ":" + page)));
            }
            if (!targetIsMayor && !targetId.equals(player.getUniqueId())) {
                items.add(new MenuItem(16, button(Material.PAPER,
                        dialogText("votes.action-kick-member"),
                        List.of(dialogText("tooltip.member-detail.vote-kick-threshold")),
                        "CONFIRM_CREATE_VOTE", townId + ":KICK_MEMBER:" + targetId
                                + ":" + page)));
            }
            if (!targetIsMayor && view.viewer().role().isLeader()) {
                items.add(new MenuItem(22, button(Material.ENCHANTED_BOOK,
                        dialogText("votes.action-replace-mayor"),
                        List.of(dialogText("tooltip.member-detail.replace-mayor-threshold")),
                        "CONFIRM_CREATE_VOTE", townId + ":REPLACE_MAYOR:" + targetId
                                + ":" + page)));
            }
            openMenu(player, 27, dialogText("member-detail.title", Map.of("name", name)),
                    new DialogRoute("MEMBERS", townId + ":" + page), items);
        });
    }

    private void openTransferRequest(Player player, UUID transferId) {
        runtime.read(player, () -> runtime.governance().dashboard(player.getUniqueId())
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-required"))), governance -> {
            TransferSnapshot transfer = governance.pendingTransfer();
            if (transfer == null || !transfer.id().equals(transferId)) {
                openNotice(player, dialogText("notice.transfer-expired-title"),
                        dialogText("notice.transfer-expired-message"),
                        dialogText("common.back"), "MAIN", null);
                return;
            }
            List<MenuItem> items = List.of(
                    new MenuItem(4, button(Material.NETHER_STAR,
                            dialogText("transfer.summary-title"),
                            List.of(dialogText("common.town", Map.of(
                                            "town", safeText(governance.townName()))),
                                    dialogText("transfer.expires", Map.of(
                                            "time", safeText(transfer.expiresAt()))),
                                    dialogText("transfer.consequence")), null, null)),
                    new MenuItem(11, button(Material.LIME_CONCRETE,
                            dialogText("transfer.accept"),
                            List.of(dialogText("common.confirmation-required")),
                            "CONFIRM_TRANSFER_DECISION",
                            transfer.id() + ":true")),
                    new MenuItem(15, button(Material.RED_CONCRETE,
                            dialogText("common.reject"),
                            List.of(dialogText("tooltip.transfer.reject-close")),
                            "CONFIRM_TRANSFER_DECISION",
                            transfer.id() + ":false")));
            openMenu(player, 27, dialogText("transfer.title"),
                    new DialogRoute("MAIN", null), items);
        });
    }

    private void openVotes(Player player, UUID townId) {
        openVotes(player, townId, 0);
    }

    private void openVotes(Player player, UUID townId, int requestedPage) {
        int page = Math.max(0, requestedPage);
        runtime.read(player, () -> runtime.governance().listTownVotes(townId,
                player.getUniqueId(), true), votes -> {
            List<MenuItem> items = new ArrayList<>();
            List<VoteSnapshot> visible = page(votes, page, 8);
            for (int index = 0; index < visible.size(); index++) {
                VoteSnapshot vote = visible.get(index);
                String target = vote.type() == VoteType.KICK_MEMBER
                        ? displayName(vote.subjectId()) : displayName(vote.candidateId());
                boolean pending = vote.viewerEligible() && !vote.viewerVoted();
                items.add(new MenuItem(index, button(
                        pending ? Material.ENCHANTED_BOOK : Material.PAPER,
                        dialogText(pending ? "votes.pending-entry-title" : "votes.entry-title",
                                Map.of("pending", dialogText("votes.pending"),
                                        "type", voteLabel(vote.type()), "target", target)),
                        List.of(dialogText("votes.entry.approve-count", Map.of(
                                        "yes", vote.yesVotes(), "required", vote.requiredYes())),
                                dialogText("votes.entry.oppose-count",
                                        Map.of("no", vote.noVotes())),
                                dialogText("common.expires",
                                        Map.of("time", vote.endsAt()))),
                        "VOTE_DETAIL", vote.id().toString())));
            }
            if (votes.isEmpty()) {
                items.add(new MenuItem(22, button(Material.PAPER, dialogText("votes.empty"),
                        List.of(dialogText("votes.empty-hint")), null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, button(Material.ARROW, dialogText("common.previous"), List.of(),
                        "VOTES_PAGE", townId + ":" + (page - 1))));
            }
            if (hasNext(votes, page, 8)) {
                items.add(new MenuItem(53, button(Material.ARROW, dialogText("common.next"), List.of(),
                        "VOTES_PAGE", townId + ":" + (page + 1))));
            }
            openMenu(player, 54, dialogText("votes.list-title", Map.of("page", page + 1)),
                    new DialogRoute("GOVERNANCE_CENTER", null), items);
        });
    }

    private void openVote(Player player, UUID voteId) {
        runtime.read(player, () -> runtime.governance().dashboard(player.getUniqueId())
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-required"))), governance -> {
            VoteSnapshot vote = governance.votes().stream().filter(item -> item.id().equals(voteId))
                    .findFirst().orElse(null);
            if (vote == null) {
                openNotice(player, dialogText("notice.vote-ended-title"),
                        dialogText("notice.vote-ended-message"),
                        dialogText("common.back"), "VOTES",
                        governance.townId().toString());
                return;
            }
            String target = vote.type() == VoteType.KICK_MEMBER
                    ? displayName(vote.subjectId()) : displayName(vote.candidateId());
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(4, button(Material.PAPER,
                    dialogText("votes.detail-type", Map.of("type", voteLabel(vote.type()))),
                    List.of(dialogText("votes.status-line", Map.of("status",
                                    dialogText("votes.status-" + vote.status().name()
                                            .toLowerCase(java.util.Locale.ROOT)))),
                            dialogText("votes.target", Map.of("target", target)),
                            dialogText("votes.voters", Map.of("count", vote.eligibleVoters())),
                            dialogText("votes.threshold", Map.of("required", vote.requiredYes())),
                            dialogText("votes.tally", Map.of("yes", vote.yesVotes(),
                                    "no", vote.noVotes())),
                            dialogText("common.expires", Map.of("time", vote.endsAt()))),
                    null, null)));
            if (vote.viewerEligible() && !vote.viewerVoted()) {
                items.add(new MenuItem(11, button(Material.LIME_CONCRETE,
                        dialogText("votes.approve"),
                        List.of(dialogText("votes.once")), "CAST_VOTE", vote.id() + ":true")));
                items.add(new MenuItem(15, button(Material.RED_CONCRETE,
                        dialogText("votes.reject"),
                        List.of(dialogText("votes.once")), "CAST_VOTE", vote.id() + ":false")));
            } else {
                items.add(new MenuItem(13, button(Material.GRAY_DYE,
                        vote.viewerVoted() ? dialogText("votes.already-voted")
                                : dialogText("votes.ineligible"),
                        List.of(), null, null)));
            }
            if (vote.createdBy().equals(player.getUniqueId())) {
                items.add(new MenuItem(18, button(Material.BARRIER,
                        dialogText("votes.cancel"),
                        List.of(dialogText("votes.cancel-only"),
                                dialogText("votes.cancel-irreversible")),
                        "CONFIRM_CANCEL_VOTE", vote.id().toString())));
            }
            openMenu(player, 27, dialogText("votes.detail-title"),
                    new DialogRoute("VOTES", vote.townId().toString()), items);
        });
    }

    private String voteLabel(VoteType type) {
        return dialogText(type == VoteType.KICK_MEMBER
                ? "votes.type-kick" : "votes.type-replace-mayor");
    }

    private String displayName(UUID playerId) {
        if (playerId == null) {
            return plugin.messages().plainText("dialog.common.unknown");
        }
        String name = Bukkit.getOfflinePlayer(playerId).getName();
        if (name != null && !name.isBlank()) {
            return safeText(name);
        }
        String compactId = playerId.toString().replace("-", "");
        String suffix = compactId.substring(Math.max(0, compactId.length() - 8));
        return plugin.messages().plainText("dialog.common.unknown-player",
                Map.of("playerId", suffix));
    }

    private static String displayActorName(EconomyRepository.LedgerEntry entry) {
        if (entry.actorId() == null) {
            return safeText(entry.actorName());
        }
        try {
            UUID stored = UUID.fromString(entry.actorName());
            if (!stored.equals(entry.actorId())) {
                return safeText(entry.actorName());
            }
            String current = Bukkit.getOfflinePlayer(entry.actorId()).getName();
            return current == null || current.isBlank() ? null : safeText(current);
        } catch (IllegalArgumentException ignored) {
            return safeText(entry.actorName());
        }
    }

    private void openJoinTowns(Player player) {
        openJoinTowns(player, 0);
    }

    private void openJoinTowns(Player player, int requestedPage) {
        int page = Math.max(0, requestedPage);
        runtime.read(player, () -> runtime.repository().listTowns(false).stream()
                .filter(town -> town.status() == TownStatus.ACTIVE)
                .toList(), towns -> {
            List<MenuItem> items = new ArrayList<>();
            List<TownSnapshot> visible = page(towns, page, 8);
            for (int index = 0; index < visible.size(); index++) {
                TownSnapshot town = visible.get(index);
                items.add(new MenuItem(index, button(Material.BELL,
                        dialogText("common.town-name", Map.of(
                                "town", safeText(town.profile().name()))),
                        List.of(dialogText("common.town-code", Map.of(
                                        "code", safeText(town.profile().residenceName()))),
                                dialogText("common.town-description", Map.of(
                                        "description", safeText(preview(
                                                town.profile().description(), 80)))),
                                dialogText("tooltip.join.town-apply")),
                        "JOIN_TOWN", town.id().toString())));
            }
            if (towns.isEmpty()) {
                items.add(new MenuItem(0, button(Material.BELL,
                        dialogText("join.empty-title"),
                        List.of(dialogText("join.empty-hint")), null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, button(Material.ARROW,
                        dialogText("common.previous"), List.of(),
                        "JOIN_TOWNS_PAGE", String.valueOf(page - 1))));
            }
            if (hasNext(towns, page, 8)) {
                items.add(new MenuItem(53, button(Material.ARROW,
                        dialogText("common.next"), List.of(),
                        "JOIN_TOWNS_PAGE", String.valueOf(page + 1))));
            }
            openMenu(player, 54, dialogText("join.list-title", Map.of("page", page + 1)),
                    new DialogRoute("MAIN", null), items);
        });
    }

    private void openJoinTown(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .filter(town -> town.status() == TownStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-unavailable"))), town -> {
            JoinTownMenuModel joinTown = JoinTownMenuModel.create(plugin.messages(), town);
            JoinTownMenuModel.Entry rulesEntry = joinTown.rulesEntry();
            JoinTownMenuModel.Entry applyEntry = joinTown.applyEntry();
            List<MenuItem> items = List.of(
                    new MenuItem(4, button(Material.BELL,
                            dialogText("common.town-name", Map.of(
                                    "town", safeText(town.profile().name()))),
                            joinTown.summaryLore(),
                            null, null)),
                    new MenuItem(rulesEntry.slot(), button(Material.WRITTEN_BOOK,
                            dialogText(rulesEntry.labelKey()), rulesEntry.loreKeys().stream()
                                    .map(this::dialogText).toList(), rulesEntry.action(),
                            rulesEntry.target())),
                    new MenuItem(applyEntry.slot(), button(Material.LIME_CONCRETE,
                            dialogText(applyEntry.labelKey()), applyEntry.loreKeys().stream()
                                    .map(this::dialogText).toList(), applyEntry.action(),
                            applyEntry.target())));
            openMenu(player, 27, dialogText("join.town-title", Map.of(
                            "town", safeText(town.profile().name()))),
                    new DialogRoute("JOIN_TOWNS", null), items);
        });
    }

    private void openMyJoinApplications(Player player) {
        runtime.read(player, () -> runtime.repository().listJoinApplications(player.getUniqueId()),
                applications -> {
                    List<MenuItem> items = new ArrayList<>();
                    for (int index = 0; index < Math.min(applications.size(), 45); index++) {
                        JoinApplicationSnapshot application = applications.get(index);
                        items.add(new MenuItem(index, button(Material.PAPER,
                                dialogText("common.town-entry-title", Map.of(
                                        "town", safeText(application.townName()))),
                                List.of(dialogText("common.expires", Map.of(
                                                "time", safeText(application.expiresAt()))),
                                        dialogText("tooltip.my-join.withdraw")),
                                "CONFIRM_CANCEL_JOIN", application.id().toString())));
                    }
                    openMenu(player, 54, dialogText("my-join.list-title", Map.of(
                                    "count", applications.size())),
                            new DialogRoute("MAIN", null), items);
                });
    }

    private void openTownJoinApplications(Player mayor, UUID townId) {
        openTownJoinApplications(mayor, townId, 0);
    }

    private void openTownJoinApplications(Player mayor, UUID townId, int requestedPage) {
        int page = Math.max(0, requestedPage);
        runtime.read(mayor, () -> runtime.repository().listTownJoinApplications(
                townId, mayor.getUniqueId()), applications -> {
            List<MenuItem> items = new ArrayList<>();
            List<JoinApplicationSnapshot> visible = page(applications, page, 8);
            for (int index = 0; index < visible.size(); index++) {
                JoinApplicationSnapshot application = visible.get(index);
                String name = displayName(application.applicantId());
                items.add(new MenuItem(index, button(Material.PLAYER_HEAD,
                        dialogText("town-join.entry-title", Map.of(
                                "applicant", safeText(name))),
                        List.of(dialogText("tooltip.town-join.entry-created", Map.of(
                                        "time", safeText(application.createdAt()))),
                                dialogText("common.expires", Map.of(
                                        "time", safeText(application.expiresAt()))),
                                dialogText("tooltip.town-join.entry-review")),
                        "JOIN_APPLICATION", application.id().toString())));
            }
            if (applications.isEmpty()) {
                items.add(new MenuItem(0, button(Material.BOOK,
                        dialogText("town-join.list-empty"),
                        List.of(dialogText("town-join.list-empty-hint")), null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, button(Material.ARROW,
                        dialogText("common.previous"), List.of(),
                        "JOIN_APPLICATIONS_PAGE", townId + ":" + (page - 1))));
            }
            if (hasNext(applications, page, 8)) {
                items.add(new MenuItem(53, button(Material.ARROW,
                        dialogText("common.next"), List.of(),
                        "JOIN_APPLICATIONS_PAGE", townId + ":" + (page + 1))));
            }
            openMenu(mayor, 54, dialogText("town-join.list-title", Map.of(
                            "count", applications.size(), "page", page + 1)),
                    new DialogRoute("GOVERNANCE_CENTER", null), items);
        });
    }

    private void openTownJoinApplication(Player mayor, UUID applicationId) {
        runtime.read(mayor, () -> runtime.repository().dashboard(mayor.getUniqueId()), dashboard -> {
            JoinApplicationSnapshot application = dashboard.incomingJoinApplications().stream()
                    .filter(candidate -> candidate.id().equals(applicationId))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("申请已过期或已处理"));
            String name = Objects.requireNonNullElse(
                    Bukkit.getOfflinePlayer(application.applicantId()).getName(),
                    application.applicantId().toString());
            List<MenuItem> items = List.of(
                    new MenuItem(4, button(Material.PLAYER_HEAD, "§6" + name,
                            List.of("§7玩家 UUID: " + application.applicantId(),
                                    "§7申请时间: " + application.createdAt(),
                                    "§7到期: " + application.expiresAt()), null, null)),
                    new MenuItem(11, button(Material.LIME_CONCRETE, "§a批准加入",
                            List.of(dialogText("tooltip.town-join.approve")),
                            "CONFIRM_APPROVE_JOIN",
                            application.id().toString())),
                    new MenuItem(15, button(Material.RED_CONCRETE, "§c拒绝申请",
                            List.of(dialogText("tooltip.town-join.reject")),
                            "CONFIRM_REJECT_JOIN", application.id().toString())));
            openMenu(mayor, 27, "审核入镇申请",
                    new DialogRoute("JOIN_APPLICATIONS", application.townId().toString()), items);
        });
    }

    private void openAdminApplications(Player admin) {
        openAdminApplications(admin, 0);
    }

    private void openAdminApplications(Player admin, int requestedPage) {
        if (!admin.hasPermission("tianjitown.admin")) {
            openNotice(admin, dialogText("notice.no-permission-title"),
                    dialogText("notice.review-list-forbidden"), dialogText("common.back"),
                    "MAIN", null);
            return;
        }
        int page = Math.max(0, requestedPage);
        runtime.read(admin, () -> runtime.repository().listReviewQueue(45), applications -> {
            List<MenuItem> items = new ArrayList<>();
            List<ApplicationSnapshot> visible = page(applications, page, 8);
            for (int index = 0; index < visible.size(); index++) {
                ApplicationSnapshot application = visible.get(index);
                items.add(new MenuItem(index, button(Material.WRITABLE_BOOK,
                        dialogText("common.town-entry-title", Map.of(
                                "town", safeText(application.text().name()))),
                        List.of(dialogText("common.town-code", Map.of(
                                        "code", safeText(application.text().residenceName()))),
                                dialogText("tooltip.admin.entry-review")),
                        "ADMIN_APPLICATION", application.id().toString())));
            }
            if (applications.isEmpty()) {
                items.add(new MenuItem(0, button(Material.BOOK, dialogText("admin.list-empty"),
                        List.of(dialogText("admin.list-empty-hint")), null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, button(Material.ARROW, dialogText("common.previous"),
                        List.of(),
                        "ADMIN_APPLICATIONS_PAGE", String.valueOf(page - 1))));
            }
            if (hasNext(applications, page, 8)) {
                items.add(new MenuItem(53, button(Material.ARROW, dialogText("common.next"),
                        List.of(),
                        "ADMIN_APPLICATIONS_PAGE", String.valueOf(page + 1))));
            }
            openMenu(admin, 54, dialogText("admin.list-title", Map.of(
                            "count", applications.size(), "page", page + 1)),
                    new DialogRoute("MAIN", null), items);
        });
    }

    private void openAdminApplication(Player admin, UUID applicationId) {
        if (!admin.hasPermission("tianjitown.admin")) {
            openNotice(admin, dialogText("notice.no-permission-title"),
                    dialogText("notice.review-detail-forbidden"), dialogText("common.back"),
                    "MAIN", null);
            return;
        }
        runtime.read(admin, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.application.not-found"))), application -> {
            String submittedAt = application.submittedAt() == null
                    ? dialogText("admin.not-submitted") : safeText(application.submittedAt());
            String rules = application.text().rules().stream().map(TownUiController::safeText)
                    .collect(java.util.stream.Collectors.joining(" | "));
            List<String> summary = new ArrayList<>(List.of(
                    dialogText("common.applicant", Map.of(
                            "applicant", safeText(displayName(application.applicantId())))),
                    dialogText("common.application-status", Map.of("status",
                            dialogText(ApplicationStatusText.messageKey(application.status())))),
                    dialogText("admin.submitted-at", Map.of("time", submittedAt)),
                    dialogText("admin.updated-at", Map.of("time", safeText(application.updatedAt()))),
                    dialogText("common.name", Map.of("name", safeText(application.text().name()))),
                    dialogText("common.residence-name", Map.of(
                            "residence", safeText(application.text().normalizedResidenceName()))),
                    dialogText("common.town-description", Map.of(
                            "description", safeText(application.text().description()))),
                    dialogText("common.rules", Map.of("rules", rules))));
            if (application.territory() != null) {
                summary.add(dialogText("admin.territory", Map.of(
                        "world", safeText(application.territory().center().worldName()),
                        "x", application.territory().center().x(),
                        "z", application.territory().center().z())));
            }
            if (application.lastError() != null) {
                summary.add(dialogText("admin.creation-error", Map.of(
                        "error", safeText(application.lastError()))));
            }
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(4, button(Material.PAPER,
                    dialogText("admin.summary-title"), summary, null, null)));
            if (application.status() == ApplicationStatus.SUBMITTED
                    || application.status() == ApplicationStatus.UNDER_REVIEW) {
                items.add(new MenuItem(10, button(Material.LIME_CONCRETE,
                        dialogText("admin.approve"),
                        List.of(dialogText("tooltip.admin.approve")), "CONFIRM_ADMIN_APPROVE",
                        application.id().toString())));
                items.add(new MenuItem(12, button(Material.RED_CONCRETE,
                        dialogText("common.reject"),
                        List.of(dialogText("tooltip.admin.reject")), "CONFIRM_ADMIN_REJECT",
                        application.id().toString())));
                items.add(new MenuItem(14, button(Material.ORANGE_CONCRETE,
                        dialogText("admin.request-changes"),
                        List.of(dialogText("tooltip.admin.change")), "CONFIRM_ADMIN_CHANGE",
                        application.id().toString())));
            } else if (application.status() == ApplicationStatus.PROVISION_FAILED) {
                items.add(new MenuItem(10, button(Material.LIME_CONCRETE,
                        dialogText("admin.retry-approve"),
                        List.of(dialogText("tooltip.admin.retry")), "CONFIRM_ADMIN_APPROVE",
                        application.id().toString())));
                items.add(new MenuItem(11, button(Material.ORANGE_CONCRETE,
                        dialogText("admin.unlock-for-changes"),
                        List.of(dialogText("admin.unlock-for-changes-hint")),
                        "CONFIRM_FAILED_RECOVERY",
                        "UNLOCK_FOR_CHANGES:" + application.id())));
                items.add(new MenuItem(12, button(Material.GOLD_INGOT,
                        dialogText("admin.cancel-and-refund"),
                        List.of(dialogText("admin.cancel-and-refund-hint")),
                        "CONFIRM_FAILED_RECOVERY",
                        "CANCEL_AND_REFUND:" + application.id())));
                items.add(new MenuItem(14, button(Material.BARRIER,
                        dialogText("admin.force-cleanup"),
                        List.of(dialogText("admin.force-cleanup-hint")),
                        "CONFIRM_FAILED_RECOVERY", "FORCE_CLEANUP:" + application.id())));
            }
            if (application.territory() != null) {
                items.add(new MenuItem(16, button(Material.ENDER_EYE,
                        dialogText("admin.preview-site"),
                        List.of(dialogText("common.preview-site")), "ADMIN_PREVIEW_SITE",
                        application.id().toString())));
            }
            openMenu(admin, 27, dialogText("admin.detail-title", Map.of(
                            "town", safeText(application.text().name()))),
                    new DialogRoute("ADMIN_APPLICATIONS", null), items);
        });
    }

    private void handleAction(Player player, String action, String target) {
        if (maintenanceMode()) {
            openNotice(player, dialogText("notice.maintenance-title"),
                    plugin.messages().text("system.maintenance"), dialogText("common.close"),
                    "CLOSE", null);
            return;
        }
        try {
            switch (action) {
                case "MAIN" -> openMain(player);
                case "CLOSE" -> closeUi(player);
                case "GIVE_HANDBOOK" -> {
                    if (giveHandbook(player, false)) {
                        openNotice(player, dialogText("notice.handbook-received-title"),
                                plugin.messages().text("handbook.received"),
                                dialogText("common.back"), "MAIN", null);
                    } else {
                        openNotice(player, dialogText("notice.handbook-cooldown-title"),
                                dialogText("notice.handbook-cooldown-message"),
                                dialogText("common.back"), "MAIN", null);
                    }
                }
                case "CREATE_APPLICATION" -> loadApplicationFormDraft(player);
                case "APPLICATION" -> loadApplication(player, UUID.fromString(target));
                case "EDIT_APPLICATION" -> loadApplicationForForm(player, UUID.fromString(target));
                case "APPLICATION_BASICS_FORM" -> renderApplicationFormStage(player,
                        UUID.fromString(target), 1);
                case "APPLICATION_CONTENT_FORM" -> renderApplicationFormStage(player,
                        UUID.fromString(target), 2);
                case "APPLICATION_CONTENT_RULE_DELETIONS" -> renderApplicationRuleDeletionDialog(
                        player, UUID.fromString(target));
                case "APPLICATION_MEMBERS_FORM" -> renderApplicationFormStage(player,
                        UUID.fromString(target), 3);
                case "APPLICATION_MEMBERS_PREVIOUS" -> membersPrevious(player,
                        UUID.fromString(target));
                case "SELECT_INITIAL_MEMBER" -> {
                    String[] parts = target.split(":");
                    openInitialMemberOptions(player, UUID.fromString(parts[0]),
                            Integer.parseInt(parts[1]));
                }
                case "CHOOSE_INITIAL_MEMBER" -> chooseInitialMember(player, target);
                case "SAVE_APPLICATION_DRAFT" -> saveApplicationForm(player,
                        UUID.fromString(target));
                case "SAVE_FORM_DRAFT" -> {
                    ApplicationFormSession form = requireApplicationForm(player,
                            UUID.fromString(target));
                    if (form != null) {
                        persistApplicationForm(player, form, 3, true);
                    }
                }
                case "DISCARD_FORM_DRAFT" -> discardApplicationForm(player,
                        UUID.fromString(target));
                case "REMIND_INITIAL_MEMBERS" -> remindInitialMembers(player,
                        UUID.fromString(target));
                case "SELECT_SITE" -> selectSite(player, UUID.fromString(target));
                case "PREVIEW_SITE" -> previewApplication(player, UUID.fromString(target));
                case "CONFIRM_SUBMIT" -> openConfirmation(player,
                        dialogText("confirmation.submit-application-title"), "SUBMIT", target,
                        ApplicationSubmissionDialogRenderer.confirmationConsequence(
                                plugin.messages(), runtime.money(runtime.applicationFeeMinor())),
                        "APPLICATION", target);
                case "SUBMIT" -> submit(player, UUID.fromString(target));
                case "CONFIRM_CANCEL" -> openConfirmation(player,
                        dialogText("confirmation.cancel-application-title"), "CANCEL", target,
                        dialogText("confirmation.cancel-application-consequence"),
                        "APPLICATION", target);
                case "CANCEL" -> cancel(player, UUID.fromString(target));
                case "TOWN" -> openTown(player, UUID.fromString(target));
                case "TOWN_RULES" -> openTownRules(player, UUID.fromString(target));
                case "JOIN_TOWN_RULES" -> openJoinTownRules(player, UUID.fromString(target));
                case "TOWN_MEMBER_OVERVIEW" -> {
                    String[] parts = target.split(":");
                    openTownMemberOverview(player, UUID.fromString(parts[0]),
                            Integer.parseInt(parts[1]));
                }
                case "FINANCE" -> openFinance(player, Integer.parseInt(target));
                case "GOVERNANCE_CENTER" -> openGovernanceCenter(player);
                case "VISITOR_CENTER" -> openVisitorCenter(player, UUID.fromString(target));
                case "VISITOR_LIST" -> {
                    String[] parts = target.split(":");
                    openVisitorList(player, UUID.fromString(parts[0]),
                            Integer.parseInt(parts[1]));
                }
                case "VISITOR_INVITE" -> {
                    String[] parts = target.split(":");
                    openVisitorInvite(player, UUID.fromString(parts[0]),
                            Integer.parseInt(parts[1]));
                }
                case "CONFIRM_ADD_VISITOR" -> {
                    String[] parts = target.split(":");
                    openConfirmation(player, dialogText("confirmation.add-visitor-title"),
                            "ADD_VISITOR", target,
                            dialogText("confirmation.add-visitor-consequence"), "VISITOR_INVITE",
                            parts[0] + ":" + parts[2]);
                }
                case "ADD_VISITOR" -> addVisitor(player, target);
                case "CONFIRM_REMOVE_VISITOR" -> {
                    String[] parts = target.split(":");
                    openConfirmation(player, dialogText("confirmation.remove-visitor-title"),
                            "REMOVE_VISITOR", target,
                            dialogText("confirmation.remove-visitor-consequence"), "VISITOR_LIST",
                            parts[0] + ":" + parts[2]);
                }
                case "REMOVE_VISITOR" -> removeVisitor(player, target);
                case "PENDING_CENTER" -> openPendingCenter(player);
                case "PERSONAL_CENTER" -> openPersonalCenter(player);
                case "TAX_MENU" -> openTaxMenu(player);
                case "LEDGER" -> openLedger(player, Integer.parseInt(target));
                case "BUFF_SHOP" -> openBuffShop(player);
                case "BUFF_DURATIONS" -> openBuffDurations(player, target);
                case "BUY_BUFF" -> buyBuff(player, target);
                case "DONATION_INPUT" -> startDonationInput(player);
                case "EXPANSION_MENU" -> openExpansionMenu(player);
                case "TOGGLE_EXPANSION" -> toggleExpansionSelection(player, target);
                case "CLEAR_EXPANSION_SELECTION" -> clearExpansionSelection(player);
                case "CONFIRM_EXPANSION_BATCH" -> confirmExpansionBatch(player);
                case "PREVIEW_EXPANSION" -> {
                    GridTarget grid = gridTarget(target);
                    previewExpansion(player, grid.x(), grid.z());
                }
                case "EXPAND" -> {
                    GridTarget grid = gridTarget(target);
                    actions.expandTown(player, grid.x(), grid.z(),
                            outcome -> handleOutcome(player, outcome, operation ->
                                    openNotice(player, dialogText("notice.expansion-complete-title"),
                                            dialogText("notice.expansion-complete-message"),
                                            dialogText("common.back"),
                                            "EXPANSION_MENU", null)));
                }
                case "MEMBERS" -> {
                    String[] parts = target.split(":");
                    openMembers(player, UUID.fromString(parts[0]), Integer.parseInt(parts[1]));
                }
                case "MEMBER_DETAIL" -> {
                    String[] parts = target.split(":");
                    openMemberDetail(player, UUID.fromString(parts[0]), UUID.fromString(parts[1]),
                            Integer.parseInt(parts[2]));
                }
                case "CONFIRM_ROLE" -> {
                    String[] parts = target.split(":");
                    openConfirmation(player, dialogText("confirmation.change-role-title"),
                            "SET_ROLE", target, dialogText(
                                    "confirmation.change-role-consequence", Map.of("role",
                                            memberRoleText(MemberRole.valueOf(parts[2])))),
                            "MEMBER_DETAIL",
                            parts[0] + ":" + parts[1] + ":" + parts[3]);
                }
                case "SET_ROLE" -> changeMemberRole(player, target);
                case "CONFIRM_KICK_MEMBER" -> openConfirmation(player,
                        dialogText("confirmation.kick-member-title"), "KICK_MEMBER", target,
                        dialogText("confirmation.kick-member-consequence"),
                        "MEMBER_DETAIL", target);
                case "KICK_MEMBER" -> kickMember(player, target);
                case "CONFIRM_TRANSFER_MAYOR" -> openConfirmation(player,
                        dialogText("confirmation.transfer-mayor-title"),
                        "REQUEST_TRANSFER_MAYOR", target,
                        dialogText("confirmation.transfer-mayor-consequence"),
                        "MEMBER_DETAIL", target);
                case "REQUEST_TRANSFER_MAYOR" -> requestMayorTransfer(player, target);
                case "TRANSFER_REQUEST" -> openTransferRequest(player, UUID.fromString(target));
                case "CONFIRM_TRANSFER_DECISION" -> {
                    String[] parts = target.split(":");
                    boolean accept = Boolean.parseBoolean(parts[1]);
                    openConfirmation(player, accept
                                    ? dialogText("confirmation.accept-transfer-title")
                                    : dialogText("confirmation.reject-transfer-title"),
                            "TRANSFER_DECISION", target,
                            accept ? dialogText("confirmation.accept-transfer-consequence")
                                    : dialogText("confirmation.reject-transfer-consequence"),
                            "TRANSFER_REQUEST", parts[0]);
                }
                case "TRANSFER_DECISION" -> decideMayorTransfer(player, target);
                case "VOTES" -> openVotes(player, UUID.fromString(target));
                case "VOTES_PAGE" -> {
                    String[] parts = target.split(":");
                    openVotes(player, UUID.fromString(parts[0]), Integer.parseInt(parts[1]));
                }
                case "VOTE_DETAIL" -> openVote(player, UUID.fromString(target));
                case "CONFIRM_CREATE_VOTE" -> openConfirmation(player,
                        dialogText("confirmation.create-vote-title"), "CREATE_VOTE", target,
                        dialogText("confirmation.create-vote-consequence"),
                        "MEMBER_DETAIL", memberTarget(target));
                case "CREATE_VOTE" -> createVote(player, target);
                case "CAST_VOTE" -> castVote(player, target);
                case "CONFIRM_CANCEL_VOTE" -> openConfirmation(player,
                        dialogText("confirmation.cancel-vote-title"), "CANCEL_VOTE", target,
                        dialogText("confirmation.cancel-vote-consequence"),
                        "VOTE_DETAIL", target);
                case "CANCEL_VOTE" -> cancelVote(player, UUID.fromString(target));
                case "PREVIEW_TOWN" -> {
                    closeUi(player);
                    previewTown(player, UUID.fromString(target));
                }
                case "SET_TOWN_TELEPORT" -> setTownTeleportPoint(player,
                        UUID.fromString(target));
                case "JOIN_TOWNS" -> openJoinTowns(player);
                case "JOIN_TOWNS_PAGE" -> openJoinTowns(player, Integer.parseInt(target));
                case "JOIN_TOWN" -> openJoinTown(player, UUID.fromString(target));
                case "CONFIRM_APPLY_JOIN" -> openConfirmation(player,
                        dialogText("confirmation.apply-join-title"), "APPLY_JOIN", target,
                        dialogText("confirmation.apply-join-consequence"), "JOIN_TOWN", target);
                case "APPLY_JOIN" -> applyJoin(player, UUID.fromString(target));
                case "MY_JOIN_APPLICATIONS" -> openMyJoinApplications(player);
                case "CONFIRM_CANCEL_JOIN" -> openConfirmation(player,
                        dialogText("confirmation.cancel-join-title"), "CANCEL_JOIN", target,
                        dialogText("confirmation.cancel-join-consequence"),
                        "MY_JOIN_APPLICATIONS", null);
                case "CANCEL_JOIN" -> cancelJoin(player, UUID.fromString(target));
                case "JOIN_APPLICATIONS" -> openTownJoinApplications(player,
                        UUID.fromString(target));
                case "JOIN_APPLICATIONS_PAGE" -> {
                    String[] parts = target.split(":");
                    openTownJoinApplications(player, UUID.fromString(parts[0]),
                            Integer.parseInt(parts[1]));
                }
                case "JOIN_APPLICATION" -> openTownJoinApplication(player,
                        UUID.fromString(target));
                case "CONFIRM_APPROVE_JOIN" -> openConfirmation(player,
                        dialogText("confirmation.approve-join-title"), "APPROVE_JOIN", target,
                        dialogText("confirmation.approve-join-consequence"),
                        "JOIN_APPLICATION", target);
                case "APPROVE_JOIN" -> approveJoin(player, UUID.fromString(target));
                case "CONFIRM_REJECT_JOIN" -> openConfirmation(player,
                        dialogText("confirmation.reject-join-title"), "REJECT_JOIN", target,
                        dialogText("confirmation.reject-join-consequence"),
                        "JOIN_APPLICATION", target);
                case "REJECT_JOIN" -> rejectJoin(player, UUID.fromString(target));
                case "EDIT_TOWN" -> loadTownForForm(player, UUID.fromString(target));
                case "EDIT_TOWN_DESCRIPTION" -> loadTownForForm(player,
                        UUID.fromString(target));
                case "EDIT_TOWN_RULES" -> openTownRuleEditor(player,
                        UUID.fromString(target));
                case "EDIT_TOWN_RULE_DELETIONS" -> openTownRuleDeletionDialog(player,
                        UUID.fromString(target));
                case "DELETE_TOWN_RULE" -> deleteTownRule(player, target);
                case "CONFIRM_LEAVE" -> openConfirmation(player,
                        dialogText("confirmation.leave-town-title"), "LEAVE", target,
                        dialogText("confirmation.leave-town-consequence"), "TOWN", target);
                case "LEAVE" -> leave(player, UUID.fromString(target));
                case "CONFIRM_DISBAND" -> openConfirmation(player,
                        dialogText("confirmation.disband-town-title"), "DISBAND", target,
                        dialogText("confirmation.disband-town-consequence"), "MAIN", null);
                case "DISBAND" -> disband(player, target);
                case "ADMIN_APPLICATIONS" -> openAdminApplications(player);
                case "ADMIN_APPLICATIONS_PAGE" -> openAdminApplications(player,
                        Integer.parseInt(target));
                case "ADMIN_APPLICATION" -> openAdminApplication(player, UUID.fromString(target));
                case "CONFIRM_ADMIN_APPROVE" -> openConfirmation(player,
                        dialogText("confirmation.admin-approve-title"), "ADMIN_APPROVE", target,
                        dialogText("confirmation.admin-approve-consequence"),
                        "ADMIN_APPLICATION", target);
                case "CONFIRM_ADMIN_REJECT" -> beginAdminDecision(player,
                        UUID.fromString(target), false);
                case "CONFIRM_ADMIN_CHANGE" -> beginAdminDecision(player,
                        UUID.fromString(target), true);
                case "ADMIN_APPROVE" -> adminApprove(player, UUID.fromString(target));
                case "CONFIRM_FAILED_RECOVERY" -> openConfirmation(player,
                        dialogText("confirmation.failed-recovery-title"), "RECOVER_FAILED", target,
                        dialogText("confirmation.failed-recovery-consequence"),
                        "ADMIN_APPLICATION", target.substring(target.indexOf(':') + 1));
                case "RECOVER_FAILED" -> recoverFailedApplication(player, target);
                case "ADMIN_PREVIEW_SITE" -> adminPreviewSite(player, UUID.fromString(target));
                default -> openNotice(player, dialogText("notice.expired-title"),
                        plugin.messages().text("system.menu-expired"),
                        dialogText("common.reopen"), "MAIN", null);
            }
        } catch (IllegalArgumentException exception) {
            openNotice(player, dialogText("notice.stale-data-title"),
                    plugin.messages().text("system.invalid-menu-data"),
                    dialogText("common.reopen"), "MAIN", null);
        }
    }

    private void changeMemberRole(Player mayor, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        UUID playerId = UUID.fromString(parts[1]);
        MemberRole role = MemberRole.valueOf(parts[2]);
        int page = Integer.parseInt(parts[3]);
        actions.changeMemberRole(mayor, townId, playerId, role, outcome ->
                handleOutcome(mayor, outcome, changed -> {
            openNotice(mayor, dialogText("notice.role-updated-title"),
                    dialogText("notice.role-updated-message", Map.of("role",
                            memberRoleText(changed))),
                    dialogText("common.back"),
                    "MEMBER_DETAIL", townId + ":" + playerId + ":" + page);
        }));
    }

    private void kickMember(Player mayor, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        UUID playerId = UUID.fromString(parts[1]);
        int page = Integer.parseInt(parts[2]);
        actions.kickMember(mayor, townId, playerId, outcome ->
                handleOutcome(mayor, outcome, change -> {
            Player removed = Bukkit.getPlayer(change.playerId());
            if (removed != null && removed.isOnline()) {
                plugin.messages().send(removed, "chat.notification.member-removed",
                        townNotificationPlaceholders(change));
            }
            openNotice(mayor, dialogText("notice.member-removed-title"),
                    dialogText("notice.member-removed-message"),
                    dialogText("common.back"), "MEMBERS", change.townId() + ":" + page);
        }));
    }

    private void addVisitor(Player manager, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        UUID playerId = UUID.fromString(parts[1]);
        actions.addVisitor(manager, townId, playerId, outcome ->
                handleOutcome(manager, outcome, change -> {
            Player invited = Bukkit.getPlayer(change.playerId());
            if (invited != null && invited.isOnline()) {
                plugin.messages().send(invited, "chat.notification.visitor-added",
                        townNotificationPlaceholders(change));
                playSound(invited, Sound.BLOCK_NOTE_BLOCK_PLING);
            }
            openNotice(manager, dialogText("notice.visitor-added-title"),
                    dialogText("notice.visitor-added-message", Map.of(
                            "player", displayName(change.playerId()))),
                    dialogText("common.back"), "VISITOR_LIST", change.townId() + ":0");
        }));
    }

    private void removeVisitor(Player manager, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        UUID playerId = UUID.fromString(parts[1]);
        int page = Integer.parseInt(parts[2]);
        actions.removeVisitor(manager, townId, playerId, outcome ->
                handleOutcome(manager, outcome, change -> {
            Player visitor = Bukkit.getPlayer(change.playerId());
            if (visitor != null && visitor.isOnline()) {
                plugin.messages().send(visitor, "chat.notification.visitor-removed",
                        townNotificationPlaceholders(change));
            }
            openNotice(manager, dialogText("notice.visitor-removed-title"),
                    dialogText("notice.visitor-removed-message", Map.of(
                            "player", displayName(change.playerId()))),
                    dialogText("common.back"), "VISITOR_LIST", change.townId() + ":" + page);
        }));
    }

    private void requestMayorTransfer(Player mayor, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        UUID candidateId = UUID.fromString(parts[1]);
        actions.requestMayorTransfer(mayor, townId, candidateId, outcome ->
                handleOutcome(mayor, outcome, transfer -> {
            Player candidate = Bukkit.getPlayer(candidateId);
            if (candidate != null) {
                candidate.sendMessage(plugin.messages().component(
                                "chat.notification.transfer-request")
                        .append(callbackButton(candidate, "chat.buttons.handle",
                                () -> openTransferRequest(candidate, transfer.id()))));
            }
            openNotice(mayor, dialogText("notice.transfer-requested-title"),
                    dialogText("notice.transfer-requested-message", Map.of(
                            "expires", transfer.expiresAt())),
                    dialogText("common.back"), "MAIN", null);
        }));
    }

    private void decideMayorTransfer(Player candidate, String target) {
        String[] parts = target.split(":");
        UUID transferId = UUID.fromString(parts[0]);
        boolean accept = Boolean.parseBoolean(parts[1]);
        actions.decideMayorTransfer(candidate, transferId, accept, outcome ->
                handleOutcome(candidate, outcome, transfer -> {
            Player oldMayor = Bukkit.getPlayer(transfer.requestedBy());
            if (oldMayor != null) {
                plugin.messages().send(oldMayor, accept
                        ? "chat.notification.transfer-accepted"
                        : "chat.notification.transfer-rejected");
            }
            openNotice(candidate, accept ? dialogText("notice.transfer-complete-title")
                            : dialogText("notice.transfer-rejected-title"),
                    accept ? dialogText("notice.transfer-complete-message")
                            : dialogText("notice.transfer-rejected-message"),
                    dialogText("common.back"), "MAIN", null);
        }));
    }

    private void acknowledgeRules(Player player, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        long revision = Long.parseLong(parts[1]);
        actions.acknowledgeRules(player, townId, revision, outcome ->
                handleOutcome(player, outcome, confirmed -> {
            openNotice(player, dialogText("notice.rules-confirmed-title"),
                    dialogText("notice.rules-confirmed-message", Map.of(
                            "revision", confirmed)),
                    dialogText("common.enter-town-service"), "MAIN", null);
        }));
    }

    private void createVote(Player player, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        VoteType type = VoteType.valueOf(parts[1]);
        UUID targetId = UUID.fromString(parts[2]);
        actions.createVote(player, townId, type, targetId, outcome ->
                handleOutcome(player, outcome, vote -> {
            notifyVoteCreated(player, vote);
            openNotice(player, dialogText("notice.vote-created-title"),
                    dialogText("notice.vote-created-message", Map.of(
                            "voters", vote.eligibleVoters(), "required", vote.requiredYes())),
                    dialogText("common.view-vote"),
                    "VOTE_DETAIL", vote.id().toString());
        }));
    }

    private void notifyVoteCreated(Player creator, VoteSnapshot vote) {
        runtime.read(creator, () -> runtime.governance().listVoteVoterIds(vote.id()), voters -> {
            String target = vote.type() == VoteType.KICK_MEMBER
                        ? displayName(vote.subjectId()) : displayName(vote.candidateId());
            for (UUID voterId : voters) {
                Player voter = Bukkit.getPlayer(voterId);
                if (voter == null || !voter.isOnline()) {
                    continue;
                }
                voter.sendMessage(plugin.messages().component("chat.notification.vote-created",
                                Map.of("creator", displayName(vote.createdBy()),
                                        "type", voteLabel(vote.type()), "target", target,
                                        "ends", vote.endsAt(), "required", vote.requiredYes()))
                        .append(callbackButton(voter, "chat.buttons.view-votes",
                                () -> openVote(voter, vote.id()))));
                playSound(voter, Sound.BLOCK_AMETHYST_BLOCK_CHIME);
            }
        });
    }

    private void sendVoteReminder(Player player, VoteSnapshot vote) {
        String target = vote.type() == VoteType.KICK_MEMBER
                ? displayName(vote.subjectId()) : displayName(vote.candidateId());
        player.sendMessage(plugin.messages().component("chat.notification.vote-created", Map.of(
                        "creator", displayName(vote.createdBy()),
                        "type", voteLabel(vote.type()), "target", target,
                        "ends", vote.endsAt(), "required", vote.requiredYes()))
                .append(callbackButton(player, "chat.buttons.view-votes",
                        () -> openVote(player, vote.id()))));
    }

    private void castVote(Player player, String target) {
        String[] parts = target.split(":");
        UUID voteId = UUID.fromString(parts[0]);
        boolean approve = Boolean.parseBoolean(parts[1]);
        actions.castVote(player, voteId, approve, outcome ->
                handleOutcome(player, outcome, vote -> {
            openNotice(player, dialogText("notice.vote-recorded-title"),
                    dialogText("notice.vote-recorded-message", Map.of(
                            "yes", vote.yesVotes(), "required", vote.requiredYes(),
                            "status", dialogText("votes.status-" + vote.status().name()
                                    .toLowerCase(java.util.Locale.ROOT)))), dialogText("common.back"),
                    "VOTES", vote.townId().toString());
        }));
    }

    private void cancelVote(Player player, UUID voteId) {
        actions.cancelOwnVote(player, voteId, outcome ->
                handleOutcome(player, outcome, vote -> {
                    openNotice(player, dialogText("notice.vote-cancelled-title"),
                            dialogText("notice.vote-cancelled-message"),
                            dialogText("common.back"), "VOTES",
                            vote.townId().toString());
                }));
    }

    private void notifyTaxRateChange(EconomyRepository.TaxChange change) {
        runtime.read(Bukkit.getConsoleSender(),
                () -> runtime.repository().listMemberIds(change.townId()), memberIds -> {
            for (UUID memberId : memberIds) {
                Player member = Bukkit.getPlayer(memberId);
                if (member == null) {
                    continue;
                }
                member.sendMessage(plugin.messages().component("chat.notification.tax-updated", Map.of(
                                "rate", TownRuntime.percent(change.basisPoints())))
                        .append(callbackButton(member, "chat.buttons.view-tax",
                                () -> openTaxMenu(member)))
                        .append(Component.space())
                        .append(callbackButton(member, "chat.buttons.view-finance",
                                () -> openFinance(member, 0))));
                playSound(member, Sound.BLOCK_BELL_USE);
            }
        });
    }

    private static String memberTarget(String voteTarget) {
        String[] parts = voteTarget.split(":");
        return parts[0] + ":" + parts[2] + ":" + parts[3];
    }

    private void loadApplication(Player player, UUID applicationId) {
        runtime.read(player, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.application.not-found"))),
                application -> openApplication(player, application));
    }

    private void loadApplicationForForm(Player player, UUID applicationId) {
        runtime.read(player, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.application.not-found"))), application ->
                startApplicationForm(player, application.id(), application.version(),
                        application.text(), application.initialMembers().stream()
                                .map(member -> displayName(member.playerId())).toList()));
    }

    private void loadTownForForm(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-not-found"))), town -> {
            startTownProfileForm(player, town.id(), town.version(), town.profile());
        });
    }

    private void selectSite(Player player, UUID applicationId) {
        actions.selectApplicationSite(player, applicationId, outcome ->
                handleOutcome(player, outcome, application -> {
            sitePolicy.previewSilently(player, application.territory());
            openApplication(player, application);
        }));
    }

    private void previewApplication(Player player, UUID applicationId) {
        runtime.read(player, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.application.not-found"))), application -> {
            if (application.territory() == null) {
                openNotice(player, dialogText("notice.site-missing-title"),
                        dialogText("notice.site-missing-message"),
                        dialogText("common.back"), "APPLICATION",
                        applicationId.toString());
            } else {
                sitePolicy.teleportAndPreviewSilently(player, application.territory());
            }
        });
    }

    private void previewTown(Player player, UUID townId) {
        runtime.read(player, () -> {
            TownSnapshot town = runtime.repository().findTown(townId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            plugin.messages().plainText("chat.runtime.town-not-found")));
            List<InitialTerritory> territories = runtime.finance().territoryUnits(townId).stream()
                    .filter(unit -> unit.projectionStatus().equals("ACTIVE"))
                    .map(unit -> unit.unit().territory())
                    .toList();
            if (territories.isEmpty()) {
                throw new IllegalArgumentException(plugin.messages().plainText(
                        "chat.site.no-active-territory"));
            }
            return new TownTerritoryPreview(town, territories);
        }, preview -> teleportTownAndPreview(player, preview));
    }

    private void setTownTeleportPoint(Player player, UUID townId) {
        runtime.read(player, () -> {
            TownSnapshot town = runtime.repository().findTown(townId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            plugin.messages().plainText("chat.runtime.town-not-found")));
            if (!town.mayorId().equals(player.getUniqueId())) {
                throw new IllegalArgumentException(plugin.messages().plainText(
                        "chat.site.teleport-point-mayor-required"));
            }
            return town;
        }, town -> {
            org.bukkit.Location location = player.getLocation();
            if (!runtime.landProtection().contains(town.residenceName(),
                    location.getWorld().getUID(), location.getBlockX(), location.getBlockY(),
                    location.getBlockZ())) {
                openNotice(player, dialogText("site.teleport-point-outside-title"),
                        dialogText("site.teleport-point-outside-message"),
                        dialogText("common.back"), "TOWN", town.id().toString());
                return;
            }
            String unsafeMessageKey = unsafeTeleportReason(location);
            if (unsafeMessageKey != null) {
                openNotice(player, dialogText("site.teleport-point-unsafe-title"),
                        dialogText(unsafeMessageKey), dialogText("common.back"),
                        "TOWN", town.id().toString());
                return;
            }
            runtime.setTownTeleportPoint(player, town, location, result ->
                    openNotice(player, result.success()
                                    ? dialogText("site.teleport-point-set-title")
                                    : dialogText("site.teleport-point-failed-title"),
                            LandProtectionMessages.text(plugin.messages(), result),
                            dialogText("common.back"), "TOWN",
                            town.id().toString()));
        });
    }

    private static String unsafeTeleportReason(org.bukkit.Location location) {
        Block feet = location.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block floor = feet.getRelative(0, -1, 0);
        if (!feet.isPassable() || !head.isPassable()) {
            return "site.teleport-point-space-occupied";
        }
        if (floor.isPassable() || !floor.getType().isSolid()) {
            return "site.teleport-point-floor-unsafe";
        }
        java.util.Set<Material> dangerous = java.util.Set.of(Material.LAVA, Material.FIRE,
                Material.SOUL_FIRE, Material.CACTUS, Material.MAGMA_BLOCK,
                Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW,
                Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE);
        if (dangerous.contains(feet.getType()) || dangerous.contains(head.getType())
                || dangerous.contains(floor.getType())) {
            return "site.teleport-point-dangerous-block";
        }
        return null;
    }

    private void teleportTownAndPreview(Player player, TownTerritoryPreview preview) {
        if (preview.town().status() != TownStatus.ACTIVE) {
            openNotice(player, dialogText("site.territory-teleport-unavailable-title"),
                    dialogText("site.territory-teleport-unavailable-message"),
                    dialogText("common.back"), "MAIN", null);
            return;
        }
        closeUi(player);
        if (!player.performCommand("res tp " + preview.town().residenceName())) {
            openNotice(player, dialogText("site.territory-teleport-failed-title"),
                    dialogText("site.territory-teleport-failed-message"),
                    dialogText("common.back"), "TOWN", preview.town().id().toString());
            return;
        }
        waitForTownTeleport(player, preview, 0);
    }

    private void waitForTownTeleport(Player player, TownTerritoryPreview preview, int attempt) {
        if (!player.isOnline()) {
            return;
        }
        org.bukkit.Location location = player.getLocation();
        if (runtime.landProtection().contains(preview.town().residenceName(),
                location.getWorld().getUID(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ())) {
            sitePolicy.previewSilently(player, preview.territories());
            return;
        }
        if (attempt >= 120) {
            openNotice(player, dialogText("site.territory-teleport-timeout-title"),
                    dialogText("site.territory-teleport-timeout-message"),
                    dialogText("common.back"), "TOWN", preview.town().id().toString());
            return;
        }
        plugin.runMainLater(() -> waitForTownTeleport(player, preview, attempt + 1), 10L);
    }

    private void submit(Player player, UUID applicationId) {
        actions.submitApplication(player, applicationId, outcome ->
                handleOutcome(player, outcome, application -> {
                    playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
                    notifyApplicationSubmitted(application);
                    openNotice(player, dialogText("notice.application-submitted-title"),
                            dialogText("notice.application-submitted-message"),
                            dialogText("common.view-application"), "APPLICATION",
                            application.id().toString());
                }));
    }

    private void cancel(Player player, UUID applicationId) {
        actions.cancelApplication(player, applicationId, outcome ->
                handleOutcome(player, outcome, application -> {
            openNotice(player, dialogText("notice.application-cancelled-title"),
                    dialogText("notice.application-cancelled-message"),
                    dialogText("common.back"), "MAIN", null);
        }));
    }

    private void applyJoin(Player player, UUID townId) {
        actions.applyToTown(player, townId, outcome ->
                handleOutcome(player, outcome, application -> {
                    playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
                    notifyMayorJoinApplication(application);
                    openNotice(player, dialogText("notice.join-submitted-title"),
                            dialogText("notice.join-submitted-message", Map.of(
                                    "expires", application.expiresAt())),
                            dialogText("common.view-my-applications"),
                            "MY_JOIN_APPLICATIONS", null);
                }));
    }

    private void cancelJoin(Player player, UUID applicationId) {
        actions.cancelJoinApplication(player, applicationId, outcome ->
                handleOutcome(player, outcome, ignored -> {
            openNotice(player, dialogText("notice.join-cancelled-title"),
                    dialogText("notice.join-cancelled-message"),
                    dialogText("common.back"),
                    "MY_JOIN_APPLICATIONS", null);
        }));
    }

    private void approveJoin(Player mayor, UUID applicationId) {
        actions.approveJoinApplication(mayor, applicationId, outcome ->
                handleOutcome(mayor, outcome, application -> {
            playSound(mayor, Sound.ENTITY_PLAYER_LEVELUP);
            notifyJoinDecision(application, true);
            openNotice(mayor, dialogText("notice.join-approved-title"),
                    dialogText("notice.join-approved-message"),
                    dialogText("common.back"), "JOIN_APPLICATIONS",
                    application.townId().toString());
        }));
    }

    private void rejectJoin(Player mayor, UUID applicationId) {
        actions.rejectJoinApplication(mayor, applicationId, outcome ->
                handleOutcome(mayor, outcome, application -> {
            notifyJoinDecision(application, false);
            openNotice(mayor, dialogText("notice.join-rejected-title"),
                    dialogText("notice.join-rejected-message"),
                    dialogText("common.back"), "JOIN_APPLICATIONS",
                    application.townId().toString());
        }));
    }

    void notifyMayorJoinApplication(JoinApplicationSnapshot application) {
        runtime.read(Bukkit.getConsoleSender(), () -> new ManagerNotification(
                runtime.repository().findTown(application.townId()).orElse(null),
                runtime.governance().listManagerIds(application.townId())), notification -> {
            if (notification.town() == null) {
                return;
            }
            String applicant = Objects.requireNonNullElse(
                    Bukkit.getOfflinePlayer(application.applicantId()).getName(),
                    application.applicantId().toString());
            for (UUID managerId : notification.managerIds()) {
                Player manager = Bukkit.getPlayer(managerId);
                if (manager == null) {
                    continue;
                }
                manager.sendMessage(plugin.messages().component("chat.notification.join-request", Map.of(
                                "applicant", applicant, "town", application.townName()))
                        .append(callbackButton(manager, "chat.buttons.review-join",
                                () -> openTownJoinApplication(manager, application.id()))));
                playSound(manager, Sound.BLOCK_AMETHYST_BLOCK_CHIME);
            }
        });
    }

    private void notifyJoinDecision(JoinApplicationSnapshot application, boolean approved) {
        Player applicant = Bukkit.getPlayer(application.applicantId());
        if (applicant == null) {
            return;
        }
        applicant.sendMessage(plugin.messages().component(approved
                        ? "chat.notification.join-approved"
                        : "chat.notification.join-rejected",
                Map.of("town", application.townName()))
                .append(callbackButton(applicant, "chat.buttons.open-system",
                        () -> openMain(applicant))));
        playSound(applicant, approved ? Sound.ENTITY_PLAYER_LEVELUP : Sound.ENTITY_VILLAGER_NO);
    }

    private void adminApprove(Player admin, UUID applicationId) {
        if (!admin.hasPermission("tianjitown.admin")) {
            openNotice(admin, dialogText("notice.no-permission-title"),
                    dialogText("notice.review-forbidden"), dialogText("common.back"),
                    "MAIN", null);
            return;
        }
        runtime.read(admin, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.application.not-found"))), application -> {
            String idempotencyKey = application.status() == ApplicationStatus.PROVISION_FAILED
                    ? "town:retry:" + application.id() + ":" + application.version()
                    : "town:approve:" + application.id();
            UUID progressSession = openDialogPage(admin, dialogText("provision.progress-title"),
                    List.of(DialogBody.plainMessage(
                            legacyComponent(dialogText("provision.progress-message")), 420)),
                    List.of(), DialogBase.DialogAfterAction.NONE,
                    session -> DialogType.notice(returnButton(admin, session,
                            new DialogRoute("ADMIN_APPLICATIONS", null))),
                    new DialogRoute("ADMIN_APPLICATIONS", null));
            java.util.concurrent.atomic.AtomicBoolean timedOut = new AtomicBoolean(false);
            plugin.runMainLater(() -> {
                if (isCurrent(admin, progressSession) && timedOut.compareAndSet(false, true)) {
                    openNotice(admin, dialogText("provision.timeout-title"),
                            dialogText("provision.timeout-message"),
                            dialogText("provision.back-to-list"), "ADMIN_APPLICATIONS", null);
                }
            }, 20L * 30);
            runtime.provision(admin, application.id(), admin.getUniqueId(), admin.getName(),
                    plugin.messages().plainText("log.provision.admin-approval-reason"),
                    idempotencyKey, result -> {
                        ApplicationSnapshot completed = result.application();
                        if (completed != null) {
                            notifyApplicationDecision(completed);
                        }
                        playSound(admin, result.status() == ProvisionResult.Status.SUCCESS
                                ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_NOTE_BLOCK_BASS);
                        if (timedOut.get() || !isCurrent(admin, progressSession)) {
                            plugin.getLogger().info(plugin.messages().plainText(
                                    "log.provision.ui-callback-after-close",
                                    Map.of("application", applicationId, "time", Instant.now())));
                            return;
                        }
                        String message = result.status() == ProvisionResult.Status.SUCCESS
                                ? result.detail(plugin.messages())
                                : plugin.messages().rawText("dialog.provision.failure-with-recovery",
                                Map.of("detail", result.detail(plugin.messages()),
                                        "recovery", result.recoveryAction(plugin.messages())));
                        openNotice(admin,
                                result.status() == ProvisionResult.Status.SUCCESS
                                        ? dialogText("notice.application-created-title")
                                        : dialogText("provision.application-failed-title"),
                                message, dialogText("provision.back-to-list"),
                                "ADMIN_APPLICATIONS", null);
                    });
        });
    }

    private void recoverFailedApplication(Player admin, String target) {
        if (!admin.hasPermission("tianjitown.admin")) {
            openNotice(admin, dialogText("notice.no-permission-title"),
                    dialogText("notice.review-forbidden"), dialogText("common.back"),
                    "MAIN", null);
            return;
        }
        String[] parts = target.split(":", 2);
        TownRepository.RecoveryMode mode = TownRepository.RecoveryMode.valueOf(parts[0]);
        UUID applicationId = UUID.fromString(parts[1]);
        openNotice(admin, dialogText("provision.recovery-title"),
                dialogText("provision.recovery-message"),
                dialogText("provision.back-to-list"), "ADMIN_APPLICATIONS", null);
        runtime.recoverFailedApplication(admin, applicationId, mode, result -> {
            if (result.application() != null) {
                notifyApplicationDecision(result.application());
            }
            boolean success = result.status() == ProvisionResult.Status.SUCCESS;
            String message = success
                    ? mode == TownRepository.RecoveryMode.UNLOCK_FOR_CHANGES
                    ? dialogText("provision.recovery-unlocked-message")
                    : dialogText("provision.recovery-cancelled-message")
                    : plugin.messages().rawText("dialog.provision.failure-with-recovery",
                    Map.of("detail", result.detail(plugin.messages()),
                            "recovery", result.recoveryAction(plugin.messages())));
            openNotice(admin,
                    success ? dialogText("provision.recovery-completed-title")
                            : dialogText("provision.recovery-failed-title"),
                    message, dialogText("provision.back-to-list"),
                    "ADMIN_APPLICATIONS", null);
        });
    }

    private void beginAdminDecision(Player admin, UUID applicationId, boolean requestChanges) {
        openAdminDecisionDialog(admin, applicationId, requestChanges, "", null);
    }

    private void openAdminDecisionDialog(Player admin, UUID applicationId, boolean requestChanges,
                                         String initialReason, String error) {
        if (!admin.hasPermission("tianjitown.admin")) {
            openNotice(admin, dialogText("notice.no-permission-title"),
                    dialogText("notice.review-forbidden"), dialogText("common.back"),
                    "MAIN", null);
            return;
        }
        runtime.read(admin, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.application.not-found"))), application -> {
            Component explanation = dialogComponent(requestChanges
                            ? "review.change-heading" : "review.reject-heading")
                    .append(Component.newline())
                    .append(dialogComponent("common.town", Map.of(
                            "town", application.text().name())))
                    .append(Component.newline())
                    .append(dialogComponent(requestChanges
                            ? "review.change-guidance" : "review.reject-guidance"));
            if (error != null) {
                explanation = explanation.append(Component.newline()).append(Component.newline())
                        .append(dialogComponent("common.error", Map.of("error", error)));
            }
            DialogInput reasonInput = DialogInput.text("review_reason", 400,
                    dialogComponent(requestChanges ? "review.change-label" : "review.reject-label"),
                    true, initialReason, 500,
                    TextDialogInput.MultilineOptions.create(6, 110));
            openDialogPage(admin, requestChanges ? dialogText("review.change-title")
                            : dialogText("review.reject-title"),
                    List.of(DialogBody.plainMessage(explanation, 420)),
                    List.of(reasonInput), DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE,
                    session -> DialogType.multiAction(List.of(
                                    ActionButton.create(dialogComponent(requestChanges
                                                    ? "review.send-change" : "review.confirm-reject"),
                                            dialogComponent("review.submit-tooltip"), 190,
                                            dialogAction(admin, session, response -> applyReviewReason(
                                                    admin, applicationId, requestChanges, response))),
                                    ActionButton.create(dialogComponent("common.cancel"),
                                            null, 150, dialogAction(admin, session,
                                                    "ADMIN_APPLICATION", applicationId.toString()))))
                            .exitAction(returnButton(admin, session,
                                    new DialogRoute("ADMIN_APPLICATION", applicationId.toString())))
                            .columns(2).build(),
                    new DialogRoute("ADMIN_APPLICATION", applicationId.toString()));
        });
    }

    private void applyReviewReason(Player admin, UUID applicationId, boolean requestChanges,
                                   DialogResponseView response) {
        if (!admin.hasPermission("tianjitown.admin")) {
            openNotice(admin, dialogText("notice.no-permission-title"),
                    dialogText("notice.review-not-executed"), dialogText("common.back"),
                    "MAIN", null);
            return;
        }
        String reason = responseText(response, "review_reason");
        if (reason.isBlank() || reason.length() > 500) {
            openAdminDecisionDialog(admin, applicationId, requestChanges, reason,
                    reason.isBlank() ? dialogText("review.empty-error")
                            : dialogText("review.too-long-error"));
            return;
        }
        adminDecision(admin, applicationId, requestChanges, reason);
    }

    private void adminDecision(Player admin, UUID applicationId, boolean requestChanges,
                               String reason) {
        actions.reviewApplication(admin, applicationId, requestChanges, reason, outcome ->
                handleOutcome(admin, outcome, application -> {
            notifyApplicationDecision(application);
            openNotice(admin, requestChanges
                            ? dialogText("notice.review-change-sent-title")
                            : dialogText("notice.review-rejected-title"),
                    requestChanges ? dialogText("notice.review-change-sent-message")
                            : dialogText("notice.review-rejected-message"),
                    dialogText("common.back"), "ADMIN_APPLICATIONS", null);
        }));
    }

    private void adminPreviewSite(Player admin, UUID applicationId) {
        if (!admin.hasPermission("tianjitown.admin")) {
            openNotice(admin, dialogText("notice.no-permission-title"),
                    dialogText("notice.preview-forbidden"), dialogText("common.back"),
                    "MAIN", null);
            return;
        }
        runtime.read(admin, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.application.not-found"))), application -> {
            if (application.territory() == null) {
                openNotice(admin, dialogText("notice.review-site-missing-title"),
                        dialogText("notice.review-site-missing-message"),
                        dialogText("common.back"), "ADMIN_APPLICATION",
                        applicationId.toString());
                return;
            }
            closeUi(admin);
            sitePolicy.teleportAndPreviewSilently(admin, application.territory());
        });
    }

    private void notifyApplicationSubmitted(ApplicationSnapshot application) {
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (!admin.hasPermission("tianjitown.admin")) {
                continue;
            }
            admin.sendMessage(plugin.messages().component("chat.notification.new-application", Map.of(
                            "town", application.text().name()))
                    .append(callbackButton(admin, "chat.buttons.review-join",
                            () -> openAdminApplication(admin, application.id()))));
            playSound(admin, Sound.BLOCK_BELL_USE);
        }
    }

    private void leave(Player player, UUID townId) {
        actions.leaveTown(player, townId, outcome -> handleOutcome(player, outcome, result -> {
            openNotice(player, dialogText("notice.left-town-title"),
                    dialogText("notice.left-town-message"),
                    dialogText("common.back"), "MAIN", null);
        }));
    }

    private void disband(Player mayor, String target) {
        String[] parts = target.split(":", 2);
        UUID townId = UUID.fromString(parts[0]);
        long expectedVersion = Long.parseLong(parts[1]);
        actions.disbandTown(mayor, townId, expectedVersion, outcome ->
                handleOutcome(mayor, outcome, completed -> {
                playSound(mayor, Sound.BLOCK_ANVIL_LAND);
                openNotice(mayor, dialogText("notice.disbanded-title"),
                        dialogText("notice.disbanded-message", Map.of(
                                "town", completed.profile().name())),
                        dialogText("common.back"), "MAIN", null);
            }));
    }

    private void startApplicationForm(Player player, UUID targetId, long version,
                                      ApplicationText text, List<String> initialMemberNames) {
        startApplicationForm(player, targetId, version, text, initialMemberNames, 1);
    }

    private void startApplicationForm(Player player, UUID targetId, long version,
                                      ApplicationText text, List<String> initialMemberNames,
                                      int step) {
        if (maintenanceMode()) {
            openNotice(player, dialogText("notice.maintenance-title"),
                    plugin.messages().text("system.maintenance"), dialogText("common.close"),
                    "CLOSE", null);
            return;
        }
        UUID formId = UUID.randomUUID();
        applicationForms.put(player.getUniqueId(),
                new ApplicationFormSession(formId, FormPurpose.APPLICATION, targetId, version, text,
                        normalizedMemberNames(initialMemberNames)));
        closeUi(player);
        renderApplicationFormStage(player, formId, step);
    }

    private void loadApplicationFormDraft(Player player) {
        runtime.read(player, () -> runtime.repository().findFormDraft(player.getUniqueId())
                .orElse(null), draft -> {
            if (draft == null) {
                startApplicationForm(player, null, 0,
                        new ApplicationText("", "", "", "", List.of()), List.of(), 1);
                return;
            }
            ApplicationText text = new ApplicationText(draft.name(), draft.shortName(),
                    draft.residenceName(), draft.description(), draft.rules());
            startApplicationForm(player, draft.applicationId(), draft.applicationVersion(), text,
                    List.of(draft.memberOneName(), draft.memberTwoName()), draft.currentStep());
        });
    }

    private void persistApplicationForm(Player player, ApplicationFormSession form, int step,
                                        boolean exitAfterSave) {
        persistApplicationForm(player, form, step, ignored -> {
            if (exitAfterSave) {
                applicationForms.remove(player.getUniqueId(), form);
                openNotice(player, dialogText("common.draft-saved-title"),
                        dialogText("notice.form-draft-saved-message"),
                        dialogText("common.back"), "MAIN", null);
            } else {
                renderApplicationFormStage(player, form.id(), step);
            }
        });
    }

    private void persistApplicationForm(Player player, ApplicationFormSession form, int step,
                                        Consumer<ApplicationFormDraft> afterSave) {
        List<String> names = normalizedMemberNames(form.initialMemberNames());
        UUID first = playerIdForDraft(names.get(0));
        UUID second = playerIdForDraft(names.get(1));
        ApplicationFormDraft draft = new ApplicationFormDraft(player.getUniqueId(),
                form.targetId(), form.version(), step, form.text().name(), form.text().shortName(),
                form.text().residenceName(), form.text().description(), form.text().rules(),
                first, names.get(0), second, names.get(1), null);
        runtime.write(player, () -> runtime.repository().saveFormDraft(draft), afterSave);
    }

    private static UUID playerIdForDraft(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        org.bukkit.OfflinePlayer cached = Bukkit.getOfflinePlayer(name);
        return cached.hasPlayedBefore() ? cached.getUniqueId() : null;
    }

    private void discardApplicationForm(Player player, UUID formId) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        applicationForms.remove(player.getUniqueId(), form);
        runtime.write(player, () -> {
            runtime.repository().deleteFormDraft(player.getUniqueId());
            return true;
        }, ignored -> openMain(player));
    }

    private void startTownProfileForm(Player player, UUID townId, long version,
                                      ApplicationText text) {
        if (maintenanceMode()) {
            openNotice(player, dialogText("notice.maintenance-title"),
                    plugin.messages().text("system.maintenance"), dialogText("common.close"),
                    "CLOSE", null);
            return;
        }
        UUID formId = UUID.randomUUID();
        applicationForms.put(player.getUniqueId(), new ApplicationFormSession(formId,
                FormPurpose.TOWN_PROFILE, townId, version, text, List.of()));
        closeUi(player);
        renderApplicationForm(player, formId);
    }

    private void renderApplicationForm(Player player, UUID formId) {
        ApplicationFormSession form = applicationForms.get(player.getUniqueId());
        if (form == null || !form.id().equals(formId)) {
            openNotice(player, dialogText("notice.edit-expired-title"),
                    dialogText("notice.edit-expired-message"), dialogText("common.reopen"),
                    "MAIN", null);
            return;
        }
        if (form.purpose() == FormPurpose.TOWN_PROFILE) {
            renderTownProfileDialog(player, form);
        } else {
            renderApplicationBasicsDialog(player, form);
        }
    }

    private void renderApplicationFormStage(Player player, UUID formId, int stage) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        switch (stage) {
            case 1 -> renderApplicationBasicsDialog(player, form);
            case 2 -> renderApplicationContentDialog(player, form);
            case 3 -> renderApplicationMembersDialog(player, form);
            default -> throw new IllegalArgumentException(
                    plugin.messages().plainText("chat.application.invalid-form-step"));
        }
    }

    private void renderApplicationBasicsDialog(Player player, ApplicationFormSession form) {
        ApplicationText text = form.text();
        TextDialogInput.MultilineOptions descriptionLines = TextDialogInput.MultilineOptions
                .create(6, 90);
        List<DialogInput> inputs = List.of(
                DialogInput.text("town_name", APPLICATION_TEXT_INPUT_WIDTH,
                        dialogComponent("application.name-label"), true,
                        text.name(), 24, null),
                DialogInput.text("residence_name", APPLICATION_TEXT_INPUT_WIDTH,
                        dialogComponent("application.code-label"), true,
                        text.residenceName(), 12, null),
                DialogInput.text("description", APPLICATION_TEXT_INPUT_WIDTH,
                        dialogComponent("application.description-label"), true,
                        text.description(), 500, descriptionLines));
        Component guidance = dialogComponent("application.basics-heading")
                .append(Component.newline())
                .append(dialogComponent("application.basics-guidance"));
        openDialogPage(player, dialogText("application.title"), List.of(
                        DialogBody.plainMessage(guidance, 400)),
                inputs, DialogBase.DialogAfterAction.NONE, session -> DialogType.multiAction(List.of(
                                ActionButton.create(dialogComponent("common.next-step"),
                                        null, 170, dialogAction(player, session,
                                                response -> applyApplicationBasics(
                                                        player, form.id(), response))),
                                ActionButton.create(dialogComponent("application.save-draft"),
                                        dialogComponent("application.save-draft-tooltip"), 170,
                                        dialogAction(player, session, response ->
                                                saveApplicationStage(player, form.id(), 1, response)))))
                        .exitAction(returnButton(player, session,
                                new DialogRoute("MAIN", null)))
                        .columns(2).build(), new DialogRoute("MAIN", null));
    }

    private void applyApplicationBasics(Player player, UUID formId,
                                        DialogResponseView response) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        String townCode = responseText(response, "residence_name");
        ApplicationText updated = new ApplicationText(
                responseText(response, "town_name"), townCode, townCode,
                responseText(response, "description"), form.text().rules());
        ApplicationFormSession candidate = new ApplicationFormSession(form.id(), form.purpose(),
                form.targetId(), form.version(), updated, form.initialMemberNames());
        applicationForms.put(player.getUniqueId(), candidate);
        List<String> errors = new ArrayList<>();
        errors.addAll(fieldErrors(player, candidate, ApplicationField.NAME));
        errors.addAll(fieldErrors(player, candidate, ApplicationField.RESIDENCE_NAME));
        errors.addAll(fieldErrors(player, candidate, ApplicationField.DESCRIPTION));
        persistApplicationForm(player, candidate, 1, saved -> {
            if (!errors.isEmpty()) {
                openNotice(player, dialogText("notice.basics-invalid-title"),
                        String.join("\n", errors), dialogText("common.back"),
                        "APPLICATION_BASICS_FORM", form.id().toString());
            } else {
                renderApplicationContentDialog(player, candidate);
            }
        });
    }

    private void renderApplicationContentDialog(Player player, ApplicationFormSession form) {
        RuleEditorDialogRenderer.Layout layout = RuleEditorDialogRenderer.layout(form.id(),
                form.version(), form.text().rules());
        DialogRoute parent = new DialogRoute("APPLICATION_BASICS_FORM", form.id().toString());
        Component guidance = dialogComponent("application.content-heading")
                .append(Component.newline())
                .append(dialogComponent("application.content-guidance"));
        openRuleEditorAddDialog(player, dialogText("application.title"), guidance, layout, parent,
                new DialogRoute("APPLICATION_CONTENT_RULE_DELETIONS", form.id().toString()),
                response -> addApplicationRule(player, form.id(), response),
                session -> List.of(
                        ActionButton.create(dialogComponent("common.previous-step"), null, 150,
                                dialogAction(player, session,
                                        response -> saveContentAndGoBack(player, form.id()))),
                        ActionButton.create(dialogComponent("common.next-step"), null, 150,
                                dialogAction(player, session,
                                        response -> applyApplicationContent(player, form.id()))),
                        ActionButton.create(dialogComponent("application.save-draft"),
                                dialogComponent("application.save-draft-tooltip"), 170,
                                dialogAction(player, session,
                                        response -> saveApplicationStage(player, form.id(), 2,
                                                response)))));
    }

    private void renderApplicationRuleDeletionDialog(Player player, UUID formId) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        RuleEditorDialogRenderer.Layout layout = RuleEditorDialogRenderer.layout(form.id(),
                form.version(), form.text().rules());
        Component heading = dialogComponent("application.content-heading")
                .append(Component.newline())
                .append(dialogComponent("application.content-guidance"));
        openRuleEditorDeletionDialog(player, dialogText("application.title"), heading, layout,
                new DialogRoute("APPLICATION_CONTENT_FORM", form.id().toString()),
                deleteTarget -> deleteApplicationRule(player, deleteTarget));
    }

    private void addApplicationRule(Player player, UUID formId, DialogResponseView response) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        String rule = responseText(response, "rule_text");
        if (rule.isBlank()) {
            openNotice(player, dialogText("rules.invalid-title"),
                    dialogText("rules.invalid-empty"), dialogText("common.back"),
                    "APPLICATION_CONTENT_FORM", form.id().toString());
            return;
        }
        if (form.text().rules().size() >= 50) {
            openNotice(player, dialogText("rules.invalid-title"),
                    dialogText("rules.limit-reached"),
                    dialogText("common.back"), "APPLICATION_CONTENT_FORM", form.id().toString());
            return;
        }
        List<String> rules = new ArrayList<>(form.text().rules());
        rules.add(rule);
        updateApplicationRules(player, form, rules);
    }

    private void deleteApplicationRule(Player player,
                                       RuleEditorDialogRenderer.DeleteTarget deleteTarget) {
        ApplicationFormSession form = requireApplicationForm(player, deleteTarget.pageId());
        if (form == null) {
            return;
        }
        if (form.version() != deleteTarget.pageVersion()
                || deleteTarget.ruleIndex() < 0
                || deleteTarget.ruleIndex() >= form.text().rules().size()
                || !form.text().rules().get(deleteTarget.ruleIndex())
                .equals(deleteTarget.expectedRule())) {
            openApplicationRuleEditorRefreshNotice(player, form.id());
            return;
        }
        if (form.text().rules().size() <= 1) {
            openNotice(player, dialogText("rules.invalid-title"),
                    dialogText("rules.minimum-one"), dialogText("common.back"),
                    "APPLICATION_CONTENT_RULE_DELETIONS", form.id().toString());
            return;
        }
        List<String> rules = new ArrayList<>(form.text().rules());
        rules.remove(deleteTarget.ruleIndex());
        updateApplicationRules(player, form, rules);
    }

    private void updateApplicationRules(Player player, ApplicationFormSession form,
                                        List<String> rules) {
        ApplicationText updated = new ApplicationText(form.text().name(), form.text().shortName(),
                form.text().residenceName(), form.text().description(), rules);
        ApplicationFormSession candidate = new ApplicationFormSession(form.id(), form.purpose(),
                form.targetId(), form.version(), updated, form.initialMemberNames());
        applicationForms.put(player.getUniqueId(), candidate);
        persistApplicationForm(player, candidate, 2,
                saved -> renderApplicationContentDialog(player, candidate));
    }

    private void applyApplicationContent(Player player, UUID formId) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        List<String> errors = new ArrayList<>();
        errors.addAll(fieldErrors(player, form, ApplicationField.RULES));
        persistApplicationForm(player, form, 2, saved -> {
            if (!errors.isEmpty()) {
                openNotice(player, dialogText("notice.content-invalid-title"),
                        String.join("\n", errors), dialogText("common.back"),
                        "APPLICATION_CONTENT_FORM", form.id().toString());
            } else {
                renderApplicationMembersDialog(player, form);
            }
        });
    }

    private void saveApplicationStage(Player player, UUID formId, int step,
                                       DialogResponseView response) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        ApplicationFormSession updated = switch (step) {
            case 1 -> applicationBasicsCandidate(form, response);
            case 2 -> form;
            case 3 -> form;
            default -> throw new IllegalArgumentException(
                    plugin.messages().plainText("chat.application.invalid-form-step"));
        };
        applicationForms.put(player.getUniqueId(), updated);
        persistApplicationForm(player, updated, step, true);
    }

    private void saveContentAndGoBack(Player player, UUID formId) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        persistApplicationForm(player, form, 2,
                saved -> renderApplicationBasicsDialog(player, form));
    }

    private static ApplicationFormSession applicationBasicsCandidate(
            ApplicationFormSession form, DialogResponseView response) {
        String townCode = responseText(response, "residence_name");
        ApplicationText updated = new ApplicationText(
                responseText(response, "town_name"), townCode, townCode,
                responseText(response, "description"), form.text().rules());
        return new ApplicationFormSession(form.id(), form.purpose(), form.targetId(),
                form.version(), updated, form.initialMemberNames());
    }

    private void openApplicationRuleEditorRefreshNotice(Player player, UUID formId) {
        openNotice(player, dialogText("rules.refresh-title"),
                dialogText("rules.refresh-message"), dialogText("common.back"),
                "APPLICATION_CONTENT_FORM", formId.toString());
    }

    private void renderApplicationMembersDialog(Player player, ApplicationFormSession form) {
        String first = form.initialMemberNames().get(0);
        String second = form.initialMemberNames().get(1);
        Map<InitialMemberDialogLayout.Action, ItemStack> items = Map.of(
                InitialMemberDialogLayout.Action.FIRST_MEMBER, button(Material.PLAYER_HEAD,
                first.isBlank() ? dialogText("application.member-one-placeholder")
                        : "§a" + first,
                List.of(dialogText("common.application-member-select")),
                "SELECT_INITIAL_MEMBER",
                form.id() + ":0"),
                InitialMemberDialogLayout.Action.SECOND_MEMBER, button(Material.PLAYER_HEAD,
                second.isBlank() ? dialogText("application.member-two-placeholder")
                        : "§a" + second,
                List.of(dialogText("common.application-member-select")),
                "SELECT_INITIAL_MEMBER",
                form.id() + ":1"),
                InitialMemberDialogLayout.Action.PREVIOUS, button(Material.ARROW,
                dialogText("common.previous-step"), List.of(),
                "APPLICATION_MEMBERS_PREVIOUS", form.id().toString()),
                InitialMemberDialogLayout.Action.COMPLETE, button(Material.WRITABLE_BOOK,
                dialogText("application.complete"),
                List.of(dialogText("common.application-member-save")),
                "SAVE_APPLICATION_DRAFT",
                form.id().toString()),
                InitialMemberDialogLayout.Action.SAVE_DRAFT, button(Material.CHEST,
                        dialogText("application.save-draft"),
                        List.of(dialogText("application.incomplete-members-hint")),
                        "SAVE_FORM_DRAFT", form.id().toString()),
                InitialMemberDialogLayout.Action.DISCARD_DRAFT, button(Material.BARRIER,
                        dialogText("application.discard-draft"),
                        List.of(dialogText("application.discard-draft-hint")),
                        "DISCARD_FORM_DRAFT", form.id().toString()));
        InitialMemberDialogLayout.Layout layout = InitialMemberDialogLayout.layout();
        DialogRoute parent = new DialogRoute("APPLICATION_CONTENT_FORM", form.id().toString());
        openDialogPage(player, dialogText("application.members-title"), List.of(), List.of(),
                DialogBase.DialogAfterAction.NONE, session -> DialogType.multiAction(
                                layout.actions().stream()
                                        .map(action -> dialogButton(player, items.get(action), session))
                                        .toList())
                        .columns(InitialMemberDialogLayout.COLUMNS)
                        .build(), parent);
    }

    private void openInitialMemberOptions(Player player, UUID formId, int memberIndex) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        List<? extends Player> candidates = Bukkit.getOnlinePlayers().stream()
                .filter(candidate -> !candidate.getUniqueId().equals(player.getUniqueId()))
                .filter(candidate -> !candidate.getName().equalsIgnoreCase(
                        form.initialMemberNames().get(1 - memberIndex)))
                .sorted(java.util.Comparator.comparing(Player::getName,
                        String.CASE_INSENSITIVE_ORDER)).toList();
        if (candidates.isEmpty()) {
            openNotice(player, dialogText("notice.no-candidates-title"),
                    dialogText("notice.no-candidates-message"),
                    dialogText("common.back"), "APPLICATION_MEMBERS_FORM",
                    formId.toString());
            return;
        }
        List<MenuItem> items = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            Player candidate = candidates.get(index);
            items.add(new MenuItem(index, button(Material.PLAYER_HEAD, "§e" + candidate.getName(),
                    List.of(), "CHOOSE_INITIAL_MEMBER",
                    formId + ":" + memberIndex + ":" + candidate.getUniqueId())));
        }
        openMenu(player, 54, memberIndex == 0
                ? dialogText("application.select-member-one-title")
                : dialogText("application.select-member-two-title"),
                new DialogRoute("APPLICATION_MEMBERS_FORM", formId.toString()), items);
    }

    private void chooseInitialMember(Player player, String target) {
        String[] parts = target.split(":");
        UUID formId = UUID.fromString(parts[0]);
        int memberIndex = Integer.parseInt(parts[1]);
        UUID candidateId = UUID.fromString(parts[2]);
        ApplicationFormSession form = requireApplicationForm(player, formId);
        Player candidate = Bukkit.getPlayer(candidateId);
        if (form == null) {
            return;
        }
        if (candidate == null || candidate.getUniqueId().equals(player.getUniqueId())) {
            openNotice(player, dialogText("notice.player-unavailable-title"),
                    dialogText("notice.player-unavailable-message"),
                    dialogText("common.select-again"),
                    "SELECT_INITIAL_MEMBER", formId + ":" + memberIndex);
            return;
        }
        List<String> members = new ArrayList<>(form.initialMemberNames());
        members.set(memberIndex, candidate.getName());
        ApplicationFormSession updated = new ApplicationFormSession(form.id(), form.purpose(),
                form.targetId(), form.version(), form.text(), members);
        applicationForms.put(player.getUniqueId(), updated);
        persistApplicationForm(player, updated, 3,
                saved -> renderApplicationMembersDialog(player, updated));
    }

    private void membersPrevious(Player player, UUID formId) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        persistApplicationForm(player, form, 3,
                saved -> renderApplicationContentDialog(player, form));
    }

    private void renderTownProfileDialog(Player player, ApplicationFormSession form) {
        List<DialogInput> inputs = List.of(
                DialogInput.text("description", APPLICATION_TEXT_INPUT_WIDTH,
                        dialogComponent("application.description-label"), true,
                        form.text().description(), 500,
                        TextDialogInput.MultilineOptions.create(6, 100)));
        Component guidance = dialogComponent("application.profile-name",
                        Map.of("name", form.text().name()))
                .append(Component.newline())
                .append(dialogComponent("application.profile-description-guidance"));
        openDialogPage(player, dialogText("application.profile-title"),
                List.of(DialogBody.plainMessage(guidance, 420)),
                inputs, DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE,
                session -> DialogType.multiAction(List.of(
                                ActionButton.create(dialogComponent("common.save-changes"),
                                        null, 170, dialogAction(player, session,
                                                response -> applyTownProfileDialog(
                                                        player, form.id(), response)))))
                        .exitAction(returnButton(player, session,
                                new DialogRoute("TOWN", form.targetId().toString())))
                        .columns(1).build(), new DialogRoute("TOWN", form.targetId().toString()));
    }

    private void applyTownProfileDialog(Player player, UUID formId,
                                        DialogResponseView response) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        ApplicationText updated = updateField(form.text(), ApplicationField.DESCRIPTION,
                responseText(response, "description"));
        ApplicationFormSession candidate = new ApplicationFormSession(form.id(), form.purpose(),
                form.targetId(), form.version(), updated, form.initialMemberNames());
        applicationForms.put(player.getUniqueId(), candidate);
        saveApplicationForm(player, formId);
    }

    private ApplicationFormSession requireApplicationForm(Player player, UUID formId) {
        ApplicationFormSession form = applicationForms.get(player.getUniqueId());
        if (form == null || !form.id().equals(formId)) {
            openNotice(player, dialogText("notice.edit-expired-title"),
                    dialogText("notice.edit-expired-message"), dialogText("common.reopen"),
                    "MAIN", null);
            return null;
        }
        return form;
    }

    private static String responseText(DialogResponseView response, String key) {
        return Objects.requireNonNullElse(response.getText(key), "").strip();
    }

    private void startDonationInput(Player player) {
        if (!runtime.consumptionEnabled()) {
            openNotice(player, dialogText("donation.unavailable-title"),
                    dialogText("donation.unavailable-message"),
                    dialogText("common.back"), "FINANCE", "0");
            return;
        }
        openDonationDialog(player, null, "");
    }

    private void openDonationDialog(Player player, String error, String initial) {
        runtime.read(player, () -> runtime.finance().findFinanceByPlayer(player.getUniqueId())
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-required"))), account -> {
            List<String> description = new ArrayList<>(List.of(
                    dialogText("common.town", Map.of("town", safeText(account.townName()))),
                    dialogText("donation.balance", Map.of(
                            "balance", safeText(runtime.money(account.balanceMinor())))),
                    dialogText("donation.amount-hint", Map.of(
                            "scale", runtime.settlement().scale()))));
            if (error != null && !error.isBlank()) {
                description.add(dialogText("common.error", Map.of("error", safeText(error))));
            }
            ItemStack summary = button(Material.SUNFLOWER,
                    dialogText("donation.title"),
                    description, null, null);
            DialogInput amount = DialogInput.text("donation_amount", 360,
                    dialogComponent("donation.amount-label"), true,
                    initial, 64, null);
            openDialogPage(player, dialogText("donation.title"),
                    List.of(dialogTextBody(summary)), List.of(amount),
                    DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE, session ->
                            DialogType.multiAction(List.of(
                                            ActionButton.create(dialogComponent("donation.confirm"),
                                                    dialogComponent("donation.confirm-tooltip"),
                                                    170, dialogAction(player, session,
                                                            response -> applyDonationDialog(player, response))),
                                            ActionButton.create(dialogComponent("common.cancel"),
                                                    null, 170,
                                                    dialogAction(player, session, "FINANCE", "0"))))
                                    .exitAction(returnButton(player, session,
                                            new DialogRoute("FINANCE", "0")))
                                    .columns(2).build(), new DialogRoute("FINANCE", "0"));
        });
    }

    private void applyDonationDialog(Player player, DialogResponseView response) {
        String value = Objects.requireNonNullElse(response.getText("donation_amount"), "").strip();
        try {
            BigDecimal decimal = new BigDecimal(value);
            MoneyAmount amount = MoneyAmount.from(decimal, runtime.settlement().scale());
            if (!amount.positive()) {
                throw new IllegalArgumentException(plugin.messages().plainText(
                        "validation.vault.donation-amount-positive"));
            }
            actions.donate(player, amount.minorUnits(), outcome ->
                    handleOutcome(player, outcome, mutation ->
                            openNotice(player, dialogText("notice.donation-success-title"),
                                    dialogText("notice.donation-success-message", Map.of(
                                            "amount", runtime.money(amount.minorUnits()),
                                            "balance", runtime.money(
                                                    mutation.balanceAfterMinor()))),
                                    dialogText("common.back"), "FINANCE", "0")));
        } catch (ArithmeticException | NumberFormatException exception) {
            openDonationDialog(player, plugin.messages().plainText(
                    "dialog.donation.invalid-amount"), value);
        } catch (IllegalArgumentException exception) {
            openDonationDialog(player, safeText(safeMessage(exception)), value);
        }
    }

    private void saveApplicationForm(Player player, UUID formId) {
        if (maintenanceMode()) {
            applicationForms.remove(player.getUniqueId());
            openNotice(player, dialogText("notice.draft-not-saved-title"),
                    plugin.messages().text("system.maintenance"), dialogText("common.close"),
                    "CLOSE", null);
            return;
        }
        ApplicationFormSession form = applicationForms.get(player.getUniqueId());
        if (form == null || !form.id().equals(formId)) {
            openNotice(player, dialogText("notice.edit-expired-title"),
                    dialogText("notice.edit-expired-message"), dialogText("common.reopen"),
                    "MAIN", null);
            return;
        }
        try {
            form.text().requireValid();
        } catch (ApplicationText.ValidationException exception) {
            openNotice(player, dialogText("notice.draft-incomplete-title"),
                    ApplicationTextMessages.join(plugin.messages(), exception.issues()),
                    dialogText("common.back"), "APPLICATION_MEMBERS_FORM",
                    form.id().toString());
            return;
        }
        if (form.purpose() == FormPurpose.APPLICATION) {
            try {
                requireInitialMemberIds(player, form.initialMemberNames());
            } catch (IllegalArgumentException exception) {
                openNotice(player, dialogText("notice.draft-incomplete-title"),
                        exception.getMessage(), dialogText("common.back"),
                        "APPLICATION_MEMBERS_FORM", form.id().toString());
                return;
            }
        }
        if (form.purpose() == FormPurpose.TOWN_PROFILE) {
            actions.updateTownProfile(player, form.targetId(), form.text(), form.version(), outcome ->
                    handleOutcome(player, outcome, town -> {
                applicationForms.remove(player.getUniqueId(), form);
                openNotice(player, dialogText("notice.profile-saved-title"),
                        dialogText("notice.profile-saved-message"),
                        dialogText("common.back"), "TOWN", town.id().toString());
            }));
            return;
        }
        List<UUID> initialMemberIds = requireInitialMemberIds(player,
                form.initialMemberNames());
        runtime.readAction(player,
                () -> runtime.repository().initialMemberConflicts(initialMemberIds),
                conflicts -> {
                    if (!conflicts.isEmpty()) {
                        openNotice(player, dialogText(
                                        "application.initial-members-unavailable-title"),
                                conflicts.stream().map(conflict -> dialogText(
                                                "application.initial-member-conflict", Map.of(
                                                        "player", safeText(displayName(
                                                                conflict.playerId())),
                                                        "town", safeText(Objects.requireNonNullElse(
                                                                conflict.townName(),
                                                                plugin.messages().plainText(
                                                                        "dialog.application.unknown-town"))))))
                                        .collect(java.util.stream.Collectors.joining("\n")),
                                dialogText("common.back"), "APPLICATION_MEMBERS_FORM",
                                form.id().toString());
                        return;
                    }
                    saveFormalApplication(player, form, initialMemberIds);
                }, exception -> openNotice(player, dialogText(
                                "application.initial-members-precheck-failed-title"),
                        safeText(safeMessage(exception)),
                        dialogText("common.back"), "APPLICATION_MEMBERS_FORM",
                        form.id().toString()));
    }

    private void saveFormalApplication(Player player, ApplicationFormSession form,
                                       List<UUID> initialMemberIds) {
        if (form.targetId() == null) {
            actions.createApplication(player, form.text(), initialMemberIds, outcome ->
                    handleApplicationSaveOutcome(player, form, outcome, application -> {
                clearPersistedDraft(player);
                notifyInitialMembers(application);
                openNotice(player, dialogText("common.draft-saved-title"),
                        plugin.messages().text("application.draft-saved"),
                        dialogText("common.continue-processing"),
                        "APPLICATION", application.id().toString());
            }));
        } else {
            actions.updateApplication(player, form.targetId(), form.text(), initialMemberIds,
                    form.version(), outcome ->
                    handleApplicationSaveOutcome(player, form, outcome, application -> {
                clearPersistedDraft(player);
                notifyInitialMembers(application);
                openNotice(player, dialogText("common.draft-saved-title"),
                        plugin.messages().text("application.draft-saved"),
                        dialogText("common.continue-processing"),
                        "APPLICATION", application.id().toString());
            }));
        }
    }

    private void handleApplicationSaveOutcome(Player player, ApplicationFormSession form,
                                              TownActionOutcome<ApplicationSnapshot> outcome,
                                              Consumer<ApplicationSnapshot> success) {
        if (outcome.result().success()) {
            applicationForms.remove(player.getUniqueId(), form);
            success.accept(outcome.value());
            return;
        }
        if (outcome.result().reason().equals("MEMBER_CONFLICT")) {
            String playerId = outcome.result().data().get("player_id");
            String townName = outcome.result().data().get("town_name");
            String display = playerId == null
                    ? plugin.messages().plainText("dialog.application.unknown-player")
                    : displayName(UUID.fromString(playerId));
            String town = Objects.requireNonNullElse(townName,
                    plugin.messages().plainText("dialog.application.unknown-town"));
            openNotice(player, dialogText("application.initial-members-unavailable-title"),
                    dialogText("application.initial-member-conflict-retry", Map.of(
                            "player", safeText(display), "town", safeText(town))),
                    dialogText("common.back"), "APPLICATION_MEMBERS_FORM", form.id().toString());
            return;
        }
        handleOutcome(player, outcome, success);
    }

    private void clearPersistedDraft(Player player) {
        plugin.runAsync(() -> {
            try {
                runtime.repository().deleteFormDraft(player.getUniqueId());
            } catch (RuntimeException exception) {
                plugin.getLogger().warning(plugin.messages().plainText(
                        "log.application.draft-cleanup-failure", Map.of(
                                "player", safeText(player.getUniqueId()),
                                "detail", safeText(safeMessage(exception)))));
            }
        });
    }

    private void cancelApplicationForm(Player player, UUID formId) {
        ApplicationFormSession form = applicationForms.get(player.getUniqueId());
        if (form == null || !form.id().equals(formId)) {
            openNotice(player, dialogText("notice.edit-expired-title"),
                    dialogText("notice.edit-expired-message"), dialogText("common.reopen"),
                    "MAIN", null);
            return;
        }
        applicationForms.remove(player.getUniqueId(), form);
        if (form.purpose() == FormPurpose.TOWN_PROFILE) {
            openTown(player, form.targetId());
        } else if (form.targetId() == null) {
            openMain(player);
        } else {
            loadApplication(player, form.targetId());
        }
    }

    private static ApplicationText updateField(ApplicationText text, ApplicationField field,
                                               String value) {
        List<String> rules = field == ApplicationField.RULES
                ? java.util.Arrays.stream(value.split("[|｜\\r\\n]+", -1)).map(String::strip)
                .filter(rule -> !rule.isBlank()).toList() : text.rules();
        return new ApplicationText(
                field == ApplicationField.NAME ? value : text.name(),
                text.shortName(),
                field == ApplicationField.RESIDENCE_NAME ? value : text.residenceName(),
                field == ApplicationField.DESCRIPTION ? value : text.description(),
                rules);
    }

    private static List<String> normalizedMemberNames(List<String> names) {
        List<String> result = new ArrayList<>(List.of("", ""));
        if (names != null) {
            for (int index = 0; index < Math.min(2, names.size()); index++) {
                result.set(index, Objects.requireNonNullElse(names.get(index), "").strip());
            }
        }
        return List.copyOf(result);
    }

    private static List<String> updateInitialMemberNames(List<String> current,
                                                         ApplicationField field,
                                                         String value) {
        List<String> result = new ArrayList<>(normalizedMemberNames(current));
        if (field == ApplicationField.INITIAL_MEMBER_ONE) {
            result.set(0, value.strip());
        } else if (field == ApplicationField.INITIAL_MEMBER_TWO) {
            result.set(1, value.strip());
        }
        return List.copyOf(result);
    }

    private List<UUID> requireInitialMemberIds(Player applicant, List<String> names) {
        List<String> normalized = normalizedMemberNames(names);
        List<UUID> ids = new ArrayList<>(2);
        for (String name : normalized) {
            Player member = Bukkit.getPlayerExact(name);
            if (member == null) {
                throw new IllegalArgumentException(dialogText(
                        "application.initial-members-online-required"));
            }
            if (member.getUniqueId().equals(applicant.getUniqueId())) {
                throw new IllegalArgumentException(dialogText(
                        "application.initial-members-applicant-forbidden"));
            }
            ids.add(member.getUniqueId());
        }
        if (ids.stream().distinct().count() != 2) {
            throw new IllegalArgumentException(dialogText(
                    "application.initial-members-distinct-validation"));
        }
        return List.copyOf(ids);
    }

    private void notifyInitialMembers(ApplicationSnapshot application) {
        for (InitialMemberConfirmation confirmation : application.initialMembers()) {
            if (confirmation.status() != InitialMemberConfirmation.Status.PENDING) {
                continue;
            }
            Player member = Bukkit.getPlayer(confirmation.playerId());
            if (member == null) {
                continue;
            }
            sendInitialMemberReminder(member, application);
        }
    }

    private void remindInitialMembers(Player applicant, UUID applicationId) {
        Instant now = Instant.now();
        Instant availableAt = initialMemberReminderCooldowns.get(applicationId);
        if (availableAt != null && availableAt.isAfter(now)) {
            long remaining = Math.max(1, Duration.between(now, availableAt).toSeconds());
            openNotice(applicant, dialogText("notice.reminder-cooldown-title"), plugin.messages().text(
                            "application.reminder-cooldown", Map.of("seconds", remaining)),
                    dialogText("common.back"), "APPLICATION",
                    applicationId.toString());
            return;
        }
        runtime.read(applicant, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.application.not-found"))), application -> {
            if (!application.applicantId().equals(applicant.getUniqueId())) {
                openNotice(applicant, dialogText("notice.reminder-forbidden-title"),
                        dialogText("notice.reminder-forbidden-message"),
                        dialogText("common.back"), "MAIN", null);
                return;
            }
            if (application.initialMembers().stream().noneMatch(member ->
                    member.status() == InitialMemberConfirmation.Status.PENDING)) {
                openNotice(applicant, dialogText("notice.reminder-unneeded-title"),
                        dialogText("notice.reminder-unneeded-message"),
                        dialogText("common.back"), "APPLICATION",
                        applicationId.toString());
                return;
            }
            notifyInitialMembers(application);
            initialMemberReminderCooldowns.put(applicationId, now.plus(Duration.ofMinutes(5)));
            openNotice(applicant, dialogText("notice.reminder-sent-title"),
                    plugin.messages().text("application.reminder-sent"),
                    dialogText("common.back"),
                    "APPLICATION", applicationId.toString());
        });
    }

    private void sendInitialMemberReminder(Player member, ApplicationSnapshot application) {
        Component message = dialogComponent("invitation.message", Map.of(
                "player", displayName(application.applicantId()),
                "town", safeText(application.text().name())));
        openDialogPage(member, dialogText("invitation.title"),
                List.of(DialogBody.plainMessage(message, 400)), List.of(),
                DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE, session ->
                        DialogType.multiAction(List.of(
                                ActionButton.create(dialogComponent("invitation.accept"),
                                        null, 170, dialogAction(member, session,
                                                response -> respondInitialMember(member,
                                                        application.id(), true))),
                                ActionButton.create(dialogComponent("invitation.reject"),
                                        null, 170, dialogAction(member, session,
                                                response -> respondInitialMember(member,
                                                         application.id(), false)))))
                                .exitAction(returnButton(member, session, DialogRoute.ROOT))
                                .columns(2).build(), DialogRoute.ROOT);
    }

    private void respondInitialMember(Player member, UUID applicationId, boolean confirm) {
        actions.respondInitialMember(member, applicationId, confirm, outcome ->
                handleOutcome(member, outcome, application -> {
                    openNotice(member, confirm
                                    ? dialogText("notice.invitation-accepted-title")
                                    : dialogText("notice.invitation-rejected-title"),
                            confirm ? dialogText("notice.invitation-accepted-message")
                                    : dialogText("notice.invitation-rejected-message"),
                            dialogText("common.close"), "CLOSE", null);
                    Player applicant = Bukkit.getPlayer(application.applicantId());
                    if (applicant != null) {
                        plugin.messages().send(applicant, confirm
                                        ? "chat.notification.initial-member-response-confirmed"
                                        : "chat.notification.initial-member-response-rejected",
                                Map.of("member", safeText(member.getName())));
                    }
                }));
    }

    private List<String> fieldErrors(Player player, ApplicationFormSession form,
                                     ApplicationField field) {
        if (field == ApplicationField.INITIAL_MEMBER_ONE
                || field == ApplicationField.INITIAL_MEMBER_TWO) {
            int index = field == ApplicationField.INITIAL_MEMBER_ONE ? 0 : 1;
            String name = form.initialMemberNames().get(index);
            List<String> errors = new ArrayList<>();
            if (name.isBlank()) {
                errors.add(dialogText("application.initial-member-required"));
            } else {
                Player candidate = Bukkit.getPlayerExact(name);
                if (candidate == null) {
                    errors.add(dialogText("application.initial-member-online-required"));
                } else if (candidate.getUniqueId().equals(player.getUniqueId())) {
                    errors.add(dialogText("application.initial-member-applicant-forbidden"));
                }
            }
            if (!name.isBlank() && form.initialMemberNames().stream()
                    .filter(name::equalsIgnoreCase).count() > 1) {
                errors.add(dialogText("application.initial-members-distinct-required"));
            }
            return List.copyOf(errors);
        }
        ApplicationText.ValidationIssue.Field validationField = switch (field) {
            case NAME -> ApplicationText.ValidationIssue.Field.NAME;
            case RESIDENCE_NAME -> ApplicationText.ValidationIssue.Field.RESIDENCE_NAME;
            case DESCRIPTION -> ApplicationText.ValidationIssue.Field.DESCRIPTION;
            case RULES -> ApplicationText.ValidationIssue.Field.RULES;
            case INITIAL_MEMBER_ONE, INITIAL_MEMBER_TWO -> null;
        };
        return form.text().validate().stream()
                .filter(issue -> issue.field() == validationField)
                .map(issue -> ApplicationTextMessages.render(plugin.messages(), issue))
                .toList();
    }

    private static String preview(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum - 1) + "…";
    }

    private static <T> List<T> page(List<T> values, int page, int pageSize) {
        int start = Math.min(values.size(), Math.max(0, page) * pageSize);
        int end = Math.min(values.size(), start + pageSize);
        return values.subList(start, end);
    }

    private TownSnapshot.Page memberPage(List<TownSnapshot.Member> members, int requestedPage) {
        int page = Math.max(0, requestedPage);
        List<TownSnapshot.Member> sorted = MemberDisplayOrder.sort(members, this::displayName);
        return new TownSnapshot.Page(page(sorted, page, 8), hasNext(sorted, page, 8));
    }

    private String memberRoleText(MemberRole role) {
        return dialogText(MemberRoleText.messageKey(role));
    }

    static Map<String, String> townNotificationPlaceholders(TownPlayerChange change) {
        Objects.requireNonNull(change, "change");
        return Map.of("town", safeText(change.townName()));
    }

    private static boolean hasNext(List<?> values, int page, int pageSize) {
        return (Math.max(0, page) + 1) * pageSize < values.size();
    }

    private <T> void handleOutcome(Player player, TownActionOutcome<T> outcome,
                                   Consumer<T> success) {
        if (outcome.result().success()) {
            success.accept(outcome.value());
            return;
        }
        String detail = outcome.result().data().get("detail");
        String friendly = detail == null || detail.isBlank()
                ? outcomeFailureDetail(outcome.result().reason()) : safeText(detail);
        openNotice(player, dialogText("notice.operation-failed-title"),
                plugin.messages().text("system.operation-failed", Map.of("detail", friendly)),
                dialogText("common.back"), "MAIN", null);
    }

    private String outcomeFailureDetail(String reason) {
        return switch (reason) {
            case "FEATURE_DISABLED" -> dialogText("notice.operation-failed-feature-disabled");
            case "STORAGE_UNAVAILABLE" -> dialogText(
                    "notice.operation-failed-storage-unavailable");
            case "INSUFFICIENT_BALANCE" -> dialogText(
                    "notice.operation-failed-insufficient-balance");
            case "FORBIDDEN" -> dialogText("notice.operation-failed-forbidden");
            case "NOT_FOUND" -> dialogText("notice.operation-failed-not-found");
            default -> dialogText("notice.operation-failed-default");
        };
    }

    private void openConfirmation(Player player, String title, String confirmedAction,
                                  String target, String consequence, String returnAction,
                                  String returnTarget) {
        boolean irreversible = confirmedAction.equals("DISBAND")
                || confirmedAction.equals("CANCEL_VOTE");
        String body = consequence + "\n" + (irreversible
                ? dialogText("common.irreversible")
                : dialogText("confirmation.check-details"));
        DialogRoute parent = new DialogRoute(returnAction, returnTarget);
        openDialogPage(player, title,
                List.of(DialogBody.plainMessage(legacyComponent(body), 420)), List.of(),
                DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE, session ->
                        DialogType.multiAction(List.of(
                                ActionButton.create(dialogComponent("common.confirm"),
                                        legacyComponent(consequence), 170,
                                        dialogAction(player, session, confirmedAction, target)),
                                ActionButton.create(dialogComponent("common.cancel"),
                                        null, 170,
                                         dialogAction(player, session, returnAction, returnTarget))))
                        .exitAction(returnButton(player, session, parent))
                        .columns(2).build(), parent);
    }

    private UUID openMenu(Player player, int size, String title, DialogRoute parent,
                          List<MenuItem> items) {
        List<MenuItem> ordered = items.stream()
                .sorted(java.util.Comparator.comparingInt(MenuItem::slot))
                .toList();
        title = dialogMenuTitle(title);
        List<DialogBody> bodies = ordered.stream()
                .filter(item -> itemAction(item.item()) == null)
                .map(item -> dialogTextBody(item.item()))
                .toList();
        List<MenuItem> actions = ordered.stream()
                .filter(item -> itemAction(item.item()) != null)
                .toList();
        return openDialogPage(player, title, bodies, List.of(),
                DialogBase.DialogAfterAction.NONE, session -> {
                    // ESC only closes the dialog. The bottom control follows this page's route.
                    ActionButton exit = returnButton(player, session, parent);
                    if (actions.isEmpty()) {
                        return DialogType.notice(exit);
                    }
                    List<ActionButton> buttons = actions.stream()
                            .map(item -> dialogButton(player, item.item(), session))
                            .toList();
                    return DialogType.multiAction(buttons)
                            .exitAction(exit)
                            .columns(buttons.size() == 1 ? 1 : 2)
                            .build();
                }, parent);
    }

    private UUID openDialogPage(Player player, String title, List<? extends DialogBody> bodies,
                                List<? extends DialogInput> inputs,
                                DialogBase.DialogAfterAction afterAction,
        Function<UUID, DialogType> typeFactory, DialogRoute parent) {
        if (!active.get()) {
            throw new IllegalStateException(plugin.messages().plainText(
                    "log.scheduler.dialog-closed"));
        }
        UUID session = UUID.randomUUID();
        menuSessions.put(player.getUniqueId(), session);
        DialogType dialogType = typeFactory.apply(session);
        Component titleComponent = legacyComponent(title);
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(titleComponent)
                        .externalTitle(titleComponent)
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(afterAction)
                        .body(bodies)
                        .inputs(inputs)
                        .build())
                .type(dialogType));
        player.showDialog(dialog);
        plugin.runMainLater(() -> {
            if (isCurrent(player, session)) {
                menuSessions.remove(player.getUniqueId());
                closeUi(player);
            }
        }, MENU_TIMEOUT_TICKS);
        return session;
    }

    private void openNotice(Player player, String title, String message, String actionLabel,
                            String action, String target) {
        DialogRoute parent = DialogRoute.parent(action, target);
        openDialogPage(player, title,
                List.of(DialogBody.plainMessage(legacyComponent(message), 380)), List.of(),
                DialogBase.DialogAfterAction.NONE, session -> DialogType.notice(
                        returnButton(player, session, parent)), parent);
    }

    private DialogBody dialogTextBody(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        Component message = meta != null && meta.hasDisplayName() && meta.displayName() != null
                ? meta.displayName() : Component.empty();
        Component details = dialogTooltip(meta);
        if (details != null) {
            message = message.append(Component.newline()).append(details);
        }
        return DialogBody.plainMessage(message, 400);
    }

    private ActionButton returnButton(Player player, UUID session, DialogRoute parent) {
        DialogNavigation navigation = DialogNavigation.bottom(parent);
        String action = parent.isRoot() ? "CLOSE" : parent.action();
        return ActionButton.create(dialogComponent(navigation.labelKey()),
                dialogComponent(navigation.tooltipKey()), 140,
                dialogAction(player, session, action, parent.target()));
    }

    private ActionButton dialogButton(Player player, ItemStack item, UUID session) {
        ItemMeta meta = item.getItemMeta();
        Component label = meta != null && meta.hasDisplayName() && meta.displayName() != null
                ? meta.displayName() : Component.text(item.getType().name());
        Component tooltip = dialogTooltip(meta);
        String action = itemAction(item);
        String target = meta == null ? null : meta.getPersistentDataContainer()
                .get(targetKey, PersistentDataType.STRING);
        return ActionButton.create(label, tooltip, 180,
                action == null ? null : dialogAction(player, session, action, target));
    }

    private Component dialogTooltip(ItemMeta meta) {
        if (meta == null || !meta.hasLore() || meta.lore() == null || meta.lore().isEmpty()) {
            return null;
        }
        Component tooltip = Component.empty();
        List<Component> lore = meta.lore();
        for (int index = 0; index < lore.size(); index++) {
            if (index > 0) {
                tooltip = tooltip.append(Component.newline());
            }
            tooltip = tooltip.append(lore.get(index));
        }
        return tooltip;
    }

    private DialogAction dialogAction(Player recipient, UUID session, String action,
                                      String target) {
        return dialogAction(recipient, session, response -> handleAction(recipient, action, target));
    }

    private DialogAction dialogAction(Player recipient, UUID session,
                                      Consumer<DialogResponseView> handler) {
        return DialogAction.customClick((response, audience) -> {
            if (!active.get() || !(audience instanceof Player clicked)
                    || !clicked.getUniqueId().equals(recipient.getUniqueId())) {
                return;
            }
            plugin.runMain(() -> {
                if (!clicked.isOnline() || !isCurrent(clicked, session)) {
                    return;
                }
                // 首次有效响应立即消耗会话，避免重复数据包产生两个业务幂等键。
                menuSessions.remove(clicked.getUniqueId(), session);
                handler.accept(response);
            });
        }, ClickCallback.Options.builder().uses(1)
                .lifetime(Duration.ofMinutes(1)).build());
    }

    private void closeUi(Player player) {
        player.closeDialog();
    }

    private String dialogText(String key) {
        // Dialog 文案保留配置中的 & 颜色码，由各个渲染入口统一转换。
        return plugin.messages().rawText("dialog." + key);
    }

    private String dialogText(String key, Map<String, ?> placeholders) {
        return plugin.messages().rawText("dialog." + key, placeholders);
    }

    private String dialogFormat(String key) {
        return plugin.messages().text("dialog." + key);
    }

    private Component dialogComponent(String key) {
        return plugin.messages().component("dialog." + key);
    }

    private Component dialogComponent(String key, Map<String, ?> placeholders) {
        return plugin.messages().component("dialog." + key, placeholders);
    }

    private String dialogMenuTitle(String title) {
        // 动态菜单标题仍由菜单代码统一使用金色；已带颜色的文案保持原样。
        if (title.indexOf('&') >= 0 || title.indexOf('§') >= 0) {
            return title;
        }
        return "§6" + title;
    }

    private static Component legacyComponent(String value) {
        return LegacyComponentSerializer.legacySection().deserialize(value.replace('&', '§'));
    }

    private String dialogItemText(String value) {
        // 菜单代码中的 § 颜色和 messages.yml 中的 & 颜色各自直接生效。
        return value.replace('&', '§');
    }

    private String itemAction(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer()
                .get(actionKey, PersistentDataType.STRING);
    }

    private Component callbackButton(Player recipient, String labelKey, Runnable action) {
        return plugin.messages().component(labelKey).decorate(TextDecoration.BOLD)
                .clickEvent(callbackEvent(recipient, action))
                .hoverEvent(HoverEvent.showText(
                        plugin.messages().component("chat.buttons.open-tooltip")));
    }

    private ClickEvent callbackEvent(Player recipient, Runnable action) {
        return ClickEvent.callback(audience -> {
            if (!active.get() || !(audience instanceof Player clicked)
                    || !clicked.getUniqueId().equals(recipient.getUniqueId())) {
                return;
            }
            plugin.runMain(() -> {
                if (!clicked.isOnline()) {
                    return;
                }
                playSound(clicked, Sound.UI_BUTTON_CLICK);
                action.run();
            });
        }, options -> options.uses(5).lifetime(Duration.ofDays(7)));
    }

    private void playSound(Player player, Sound sound) {
        player.playSound(player.getLocation(), sound, SoundCategory.MASTER, 0.8F, 1.0F);
    }

    private ItemStack button(Material material, String name, List<String> lore,
                             String action, String target) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(dialogItemText(name));
        meta.setLore(lore.stream().map(this::dialogItemText).toList());
        if (action != null) {
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        }
        if (target != null) {
            meta.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, target);
        }
        item.setItemMeta(meta);
        return item;
    }

    private boolean isCurrent(Player player, UUID session) {
        return session.equals(menuSessions.get(player.getUniqueId()));
    }

    private boolean maintenanceMode() {
        return plugin.getConfig().getBoolean("town.maintenance-mode", false);
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

    private record MenuItem(int slot, ItemStack item) {
    }

    private record MainView(TownRepository.PlayerDashboard dashboard,
                            MemberGovernanceSnapshot governance,
                            EconomyRepository.TownFinance finance,
                            List<ApplicationSnapshot> reviewQueue) {
    }

    private record GovernanceCenterView(TownRepository.PlayerDashboard dashboard,
                                        MemberGovernanceSnapshot governance) {
    }

    private record FinanceView(EconomyRepository.TownFinance account,
                               EconomyRepository.SubsidyQuota subsidyQuota) {
    }

    private record LedgerPage(EconomyRepository.TownFinance account,
                              List<EconomyRepository.LedgerEntry> entries, int page) {
    }

    private GridTarget gridTarget(String target) {
        String[] coordinates = target == null ? new String[0] : target.split(",", -1);
        if (coordinates.length != 2) {
            throw new IllegalArgumentException(plugin.messages().plainText(
                    "chat.site.invalid-grid-target"));
        }
        return new GridTarget(Integer.parseInt(coordinates[0]),
                Integer.parseInt(coordinates[1]));
    }

    private record GridTarget(int x, int z) {
    }

    private record BuffShopView(List<CommerceRepository.ActiveBuff> active,
                                Map<String, CommerceRepository.SelectedBuffQuote> quotes,
                                Map<String, String> errors) {
    }

    private record TownDetailsView(TownSnapshot town, MemberGovernanceSnapshot governance) {
    }

    private record TownTerritoryPreview(TownSnapshot town,
                                        List<InitialTerritory> territories) {
        private TownTerritoryPreview {
            territories = List.copyOf(territories);
        }
    }

    private record TownMemberOverview(TownSnapshot town, List<TownSnapshot.Member> members) {
        private TownMemberOverview {
            members = List.copyOf(members);
        }
    }

    private record MemberPage(List<TownSnapshot.Member> members,
                              MemberGovernanceSnapshot governance) {
        private MemberPage {
            members = List.copyOf(members);
        }
    }

    private record MemberDetail(MemberGovernanceSnapshot viewer, MemberRole targetRole) {
    }

    private record VisitorCenterView(MemberGovernanceSnapshot governance, int visitorCount) {
    }

    private record VisitorPageView(TownSnapshot.VisitorPage page,
                                   MemberGovernanceSnapshot governance) {
    }

    private record VisitorInviteView(MemberGovernanceSnapshot governance,
                                     List<UUID> memberIds,
                                     List<UUID> visitorIds) {
        private VisitorInviteView {
            memberIds = List.copyOf(memberIds);
            visitorIds = List.copyOf(visitorIds);
        }
    }

    private record ManagerNotification(TownSnapshot town, List<UUID> managerIds) {
    }

    private record ApplicationFormSession(UUID id, FormPurpose purpose, UUID targetId, long version,
                                          ApplicationText text,
                                          List<String> initialMemberNames) {
        private ApplicationFormSession {
            initialMemberNames = normalizedMemberNames(initialMemberNames);
        }
    }

    private enum FormPurpose {
        APPLICATION,
        TOWN_PROFILE
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

    private enum ApplicationField {
        NAME("name"),
        RESIDENCE_NAME("residence-name"),
        DESCRIPTION("description"),
        RULES("rules"),
        INITIAL_MEMBER_ONE("initial-member-one"),
        INITIAL_MEMBER_TWO("initial-member-two");

        private final String key;

        ApplicationField(String key) {
            this.key = key;
        }

        String label(PluginMessages messages) {
            return message(messages, "label");
        }

        String requirement(PluginMessages messages) {
            return message(messages, "requirement");
        }

        String suggestion(PluginMessages messages) {
            return message(messages, "suggestion");
        }

        private String message(PluginMessages messages, String property) {
            return messages.rawText("dialog.application.field." + key + "." + property);
        }
    }

}

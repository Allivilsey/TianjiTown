package cn.tianji.town.paper;

import cn.tianji.town.core.application.ApplicationStatus;
import cn.tianji.town.core.application.ApplicationText;
import cn.tianji.town.core.economy.MoneyAmount;
import cn.tianji.town.core.consumption.BuffDefinition;
import cn.tianji.town.core.land.InitialTerritory;
import cn.tianji.town.core.town.MemberRole;
import cn.tianji.town.core.town.TownStatus;
import cn.tianji.town.core.governance.VoteType;
import cn.tianji.town.storage.town.ApplicationSnapshot;
import cn.tianji.town.storage.town.InitialMemberConfirmation;
import cn.tianji.town.storage.town.JoinApplicationSnapshot;
import cn.tianji.town.storage.town.TownRepository;
import cn.tianji.town.storage.town.TownSnapshot;
import cn.tianji.town.storage.governance.MemberGovernanceSnapshot;
import cn.tianji.town.storage.governance.TransferSnapshot;
import cn.tianji.town.storage.governance.VoteSnapshot;
import cn.tianji.town.storage.economy.EconomyRepository;
import cn.tianji.town.storage.commerce.CommerceRepository;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

final class TownUiController implements Listener {
    private static final int MENU_TIMEOUT_TICKS = 20 * 60;
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
        initialMemberReminderCooldowns.clear();
        for (UUID viewerId : viewers) {
            try {
                Player viewer = plugin.getServer().getPlayer(viewerId);
                if (viewer != null && viewer.isOnline()) {
                    closeUi(viewer);
                }
            } catch (RuntimeException | LinkageError exception) {
                plugin.getLogger().warning("关闭玩家小镇界面失败 " + viewerId + ": "
                        + safeMessage(exception));
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
                plugin.messages().send(player, "chat.station.invalid-copy");
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
            String owner = station.townId() == null ? "公共"
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
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("忽略无效的小镇服务台登记: " + exception.getMessage());
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
            throw new IllegalArgumentException("缺少整数 " + key);
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

    private boolean isStation(Block block) {
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
                || !isHandbook(lectern.getInventory().getItem(0)) || isStation(block)) {
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
        meta.setTitle("小镇手册");
        meta.setAuthor("TianjiTown");
        meta.setDisplayName("§6小镇手册");
        meta.setPages("§6小镇服务\n\n§0右键本手册可打开小镇菜单。\n\n§8本物品通过内部标识识别，改名不会复制其功能。");
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
        UUID request = openMenu(player, 9, "小镇服务 · 正在读取", List.of());
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
            long pendingVotes = governance.votes().stream()
                    .filter(vote -> vote.viewerEligible() && !vote.viewerVoted()).count();
            if (pendingVotes > 0) {
                player.sendMessage(plugin.messages().component("chat.notification.pending-votes",
                                Map.of("count", pendingVotes))
                        .append(callbackButton(player, "chat.buttons.view-votes",
                                () -> openMain(player))));
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
        sitePolicy.preview(player, town.territory());
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
                                        "reason", Objects.requireNonNullElse(
                                                application.reviewMessage(), "请查看申请详情")))
                        .append(callbackButton(applicant, "chat.buttons.edit-application",
                        () -> loadApplication(applicant, application.id()))));
                playSound(applicant, Sound.BLOCK_NOTE_BLOCK_PLING);
            }
            case REJECTED -> {
                applicant.sendMessage(plugin.messages().component(
                                "chat.notification.application-rejected", Map.of(
                                        "reason", Objects.requireNonNullElse(
                                                application.reviewMessage(), "未提供原因")))
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

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        boolean rightClick = event.getAction() == Action.RIGHT_CLICK_AIR
                || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        Block block = event.getClickedBlock();
        boolean emptyUnregisteredLectern = block != null
                && block.getState() instanceof Lectern lectern
                && !hasBook(lectern) && !isStation(block);
        if (rightClick && isHandbook(item) && !emptyUnregisteredLectern) {
            Player player = event.getPlayer();
            event.setCancelled(true);
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
            // 客户端会在本次交互结束时尝试打开成书，下一刻再打开菜单以覆盖该界面。
            plugin.runMain(() -> openMain(player));
            return;
        }
        if (event.getAction() == Action.LEFT_CLICK_BLOCK && block != null
                && event.getPlayer().isSneaking()
                && event.getPlayer().hasPermission("tianjitown.admin")
                && isStation(block)) {
            event.setCancelled(true);
            destroyStation(event.getPlayer(), block);
            return;
        }
        if (event.isCancelled()) {
            return;
        }
        if (block != null && block.getState() instanceof Lectern lectern
                && lectern.getPersistentDataContainer().has(stationKey, PersistentDataType.STRING)) {
            String stationId = lectern.getPersistentDataContainer().get(stationKey,
                    PersistentDataType.STRING);
            if (stationId == null || stationId.isBlank()
                    || registeredStation(block, stationId, stationRecords()) == null) {
                plugin.messages().send(event.getPlayer(), "chat.station.invalid-interaction");
                return;
            }
            event.setCancelled(true);
            openMain(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInsertLecternBook(PlayerInsertLecternBookEvent event) {
        if (!isHandbook(event.getBook())) {
            return;
        }
        Block block = event.getBlock();
        if (!(block.getState() instanceof Lectern lectern) || hasBook(lectern)
                || isStation(block)) {
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStationBreak(BlockBreakEvent event) {
        if (event.getPlayer().isSneaking() && event.getPlayer().hasPermission("tianjitown.admin")
                && isStation(event.getBlock())) {
            unregisterStationAt(event.getBlock());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        menuSessions.remove(event.getPlayer().getUniqueId());
        applicationForms.remove(event.getPlayer().getUniqueId());
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
            summary.add("§7身份: §f" + (governance == null ? "成员" : governance.role()));
            if (finance != null) {
                summary.add("§7公共资金: §f" + runtime.money(finance.balanceMinor())
                        + " §8| §7税率: §f" + TownRuntime.percent(finance.taxRateBps()));
                summary.add("§7领地单元: §f" + finance.unitCount() + "/"
                        + runtime.economySettings().maximumUnits());
            }
            summary.add(pendingTotal > 0
                    ? "§e待办 " + pendingTotal + " · 入镇 " + pendingJoins + " / 投票 "
                    + pendingVotes + " / 转让 " + pendingTransfer
                    : "§7当前没有待办事项");
            items.add(new MenuItem(0, button(Material.BELL, "§6" + town.profile().name(),
                    summary, null, null)));
            items.add(new MenuItem(10, button(Material.WRITTEN_BOOK, "§e小镇资料",
                    List.of(dialogText("tooltip.main.town")), "TOWN", town.id().toString())));
            items.add(new MenuItem(12, button(Material.EMERALD_BLOCK, "§6公共资产",
                    List.of(dialogText("tooltip.main.finance")), "FINANCE", "0")));
            items.add(new MenuItem(14, button(Material.GOLDEN_HELMET, "§e成员治理",
                    List.of(dialogText("tooltip.main.governance")), "GOVERNANCE_CENTER", null)));
            items.add(new MenuItem(16, button(pendingTotal > 0 ? Material.ENCHANTED_BOOK : Material.BOOK,
                    pendingTotal > 0 ? "§b待办中心 · " + pendingTotal : "§7待办中心",
                    List.of(dialogText("tooltip.main.pending")), "PENDING_CENTER", null)));
            items.add(new MenuItem(18, button(Material.PLAYER_HEAD, "§f个人与帮助",
                    List.of(dialogText("tooltip.main.personal")), "PERSONAL_CENTER", null)));
        } else if (dashboard.application() != null) {
            ApplicationSnapshot application = dashboard.application();
            items.add(new MenuItem(0, button(Material.PAPER, "§6小镇申请",
                    List.of(application.reviewMessage() == null ? "§7请继续完成申请流程"
                            : "§c管理员意见: " + application.reviewMessage()), null, null)));
            items.add(new MenuItem(11, button(Material.MAP, "§e继续小镇申请",
                    List.of(application.reviewMessage() == null
                            ? dialogText("tooltip.main.application-summary")
                            : dialogText("tooltip.main.application-review",
                            Map.of("message", application.reviewMessage()))),
                    "APPLICATION", application.id().toString())));
        } else {
            items.add(new MenuItem(0, button(Material.BELL, "§6小镇服务",
                    List.of("§7你当前尚未加入任何小镇",
                            "§7可以申请建立小镇或加入现有小镇"), null, null)));
            if (dashboard.joinApplications().isEmpty()) {
                items.add(new MenuItem(11, button(Material.WRITABLE_BOOK, "§a申请建立小镇",
                        List.of(dialogText("tooltip.main.create-application")),
                        "CREATE_APPLICATION", null)));
            }
            items.add(new MenuItem(13, button(Material.COMPASS, "§a申请加入小镇",
                    List.of(dialogText("tooltip.main.join-towns")), "JOIN_TOWNS", null)));
            if (!dashboard.joinApplications().isEmpty()) {
                items.add(new MenuItem(15, button(Material.PAPER, "§e我的入镇申请",
                        List.of(dialogText("tooltip.main.my-applications-count",
                                        Map.of("count", dashboard.joinApplications().size())),
                                dialogText("tooltip.main.my-applications-limit")),
                        "MY_JOIN_APPLICATIONS", null)));
            }
            items.add(new MenuItem(31, button(Material.WRITTEN_BOOK, "§6领取小镇手册",
                    List.of(dialogText("tooltip.main.handbook")), "GIVE_HANDBOOK", null)));
        }
        if (player.hasPermission("tianjitown.admin")) {
            boolean pending = !view.reviewQueue().isEmpty();
            items.add(new MenuItem(30, button(pending ? Material.ENCHANTED_BOOK : Material.BOOK,
                    pending ? "§e管理员审核 · " + view.reviewQueue().size() : "§7管理员审核",
                    pending ? List.of(dialogText("tooltip.main.admin-review-pending"))
                            : List.of(dialogText("tooltip.main.admin-review-empty")),
                    "ADMIN_APPLICATIONS", null)));
        }
        openMenu(player, 36, "小镇服务", items);
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
            items.add(new MenuItem(0, button(Material.GOLDEN_HELMET, "§6成员治理",
                    List.of("§7小镇: §f" + town.profile().name(),
                            "§7你的身份: §f" + governance.role(),
                            "§7待处理入镇申请: §f" + pendingJoins,
                            "§7待参与治理投票: §f" + pendingVotes), null, null)));
            items.add(new MenuItem(10, button(Material.PLAYER_HEAD, "§e小镇成员",
                    List.of(dialogText("tooltip.governance.members")),
                    "MEMBERS", town.id() + ":0")));
            items.add(new MenuItem(12, button(Material.BOOK, "§e治理投票",
                    List.of(dialogText("tooltip.governance.votes")),
                    "VOTES", town.id().toString())));
            if (governance.canReviewApplications()) {
                items.add(new MenuItem(14, button(pendingJoins > 0
                                ? Material.ENCHANTED_BOOK : Material.BOOK,
                        pendingJoins > 0 ? "§b入镇申请 · " + pendingJoins : "§7入镇申请",
                        List.of(dialogText("tooltip.governance.applications")),
                        "JOIN_APPLICATIONS", town.id().toString())));
                items.add(new MenuItem(16, button(Material.NAME_TAG, "§e访客管理",
                        List.of(dialogText("tooltip.governance.visitors"),
                                dialogText("tooltip.governance.visitor-permission")),
                        "VISITOR_CENTER", town.id().toString())));
            }
            items.add(new MenuItem(22, button(Material.ARROW, "§7返回主菜单",
                    List.of(), "MAIN", null)));
            openMenu(player, 27, "成员治理", items);
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
            items.add(new MenuItem(4, button(Material.NAME_TAG, "§6访客管理",
                    List.of("§7当前访客: §f" + view.visitorCount(),
                            "§7访客只获得本镇 Residence 领地权限",
                            "§7不计入成员、投票、税务或公共 Buff"), null, null)));
            items.add(new MenuItem(11, button(Material.PLAYER_HEAD, "§e访客列表",
                    List.of(dialogText("tooltip.visitor.list")),
                    "VISITOR_LIST", townId + ":0")));
            items.add(new MenuItem(15, button(Material.WRITABLE_BOOK, "§a邀请",
                    List.of(dialogText("tooltip.visitor.invite")),
                    "VISITOR_INVITE", townId + ":0")));
            items.add(new MenuItem(22, button(Material.ARROW, "§7返回成员治理",
                    List.of(), "GOVERNANCE_CENTER", null)));
            openMenu(player, 27, "访客管理", items);
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
                        "§f" + visitorName,
                        List.of(dialogText("tooltip.visitor.entry.inviter",
                                        Map.of("player", inviterName)),
                                dialogText("tooltip.visitor.entry.added",
                                        Map.of("time", visitor.addedAt())),
                                dialogText("tooltip.visitor.entry.remove")),
                        "CONFIRM_REMOVE_VISITOR",
                        townId + ":" + visitor.playerId() + ":" + page)));
            }
            if (view.page().visitors().isEmpty()) {
                items.add(new MenuItem(4, button(Material.PAPER, "§7暂无访客",
                        List.of("§7可返回访客管理页面邀请在线玩家"), null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, button(Material.ARROW, "§e上一页", List.of(),
                        "VISITOR_LIST", townId + ":" + (page - 1))));
            }
            if (view.page().hasNext()) {
                items.add(new MenuItem(53, button(Material.ARROW, "§e下一页", List.of(),
                        "VISITOR_LIST", townId + ":" + (page + 1))));
            }
            items.add(new MenuItem(48, button(Material.ARROW, "§7返回访客管理", List.of(),
                    "VISITOR_CENTER", townId.toString())));
            openMenu(player, 54, "小镇访客 · 第 " + (page + 1) + " 页", items);
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
                        "§e" + candidate.getName(),
                        List.of(dialogText("tooltip.visitor.invite-entry.click"),
                                dialogText("tooltip.visitor.invite-entry.constraint")),
                        "CONFIRM_ADD_VISITOR",
                        townId + ":" + candidate.getUniqueId() + ":" + page)));
            }
            if (visible.isEmpty()) {
                items.add(new MenuItem(4, button(Material.PAPER, "§7没有可邀请的在线玩家",
                        List.of("§7本镇成员和已有访客不会显示"), null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, button(Material.ARROW, "§e上一页", List.of(),
                        "VISITOR_INVITE", townId + ":" + (page - 1))));
            }
            if (hasNext(candidates, page, 8)) {
                items.add(new MenuItem(53, button(Material.ARROW, "§e下一页", List.of(),
                        "VISITOR_INVITE", townId + ":" + (page + 1))));
            }
            items.add(new MenuItem(48, button(Material.ARROW, "§7返回访客管理", List.of(),
                    "VISITOR_CENTER", townId.toString())));
            openMenu(player, 54, "邀请访客 · 第 " + (page + 1) + " 页", items);
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
                    total > 0 ? "§6待办中心 · " + total : "§7当前没有待办",
                    total > 0 ? List.of("§7只显示需要你作出决定的事项",
                            "§7入镇 " + pendingJoins + " / 投票 " + pendingVotes
                                    + " / 转让 " + pendingTransfer)
                            : List.of("§7新的申请、投票或邀请会显示在这里"), null, null)));
            if (pendingJoins > 0) {
                items.add(new MenuItem(10, button(Material.ENCHANTED_BOOK,
                        "§b入镇申请 · " + pendingJoins,
                        List.of(dialogText("tooltip.pending.applications")),
                        "JOIN_APPLICATIONS", town.id().toString())));
            }
            if (pendingVotes > 0) {
                items.add(new MenuItem(12, button(Material.ENCHANTED_BOOK,
                        "§b治理投票 · " + pendingVotes,
                        List.of(dialogText("tooltip.pending.votes")),
                        "VOTES", town.id().toString())));
            }
            if (governance.pendingTransfer() != null) {
                items.add(new MenuItem(14, button(Material.NETHER_STAR, "§e镇长转让邀请",
                        List.of(dialogText("tooltip.pending.transfer")),
                        "TRANSFER_REQUEST", governance.pendingTransfer().id().toString())));
            }
            items.add(new MenuItem(22, button(Material.ARROW, "§7返回主菜单",
                    List.of(), "MAIN", null)));
            openMenu(player, 27, "待办中心", items);
        });
    }

    private void openPersonalCenter(Player player) {
        runtime.read(player, () -> runtime.repository().dashboard(player.getUniqueId()), dashboard -> {
            TownSnapshot town = dashboard.town();
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(0, button(Material.PLAYER_HEAD, "§6" + player.getName(),
                    List.of(town == null ? "§7当前没有小镇身份"
                            : "§7所在小镇: §f" + town.profile().name(),
                            "§7手册丢失后可以在这里重新领取"), null, null)));
            items.add(new MenuItem(10, button(Material.WRITTEN_BOOK, "§6领取小镇手册",
                    List.of(dialogText("tooltip.personal.handbook")),
                    "GIVE_HANDBOOK", null)));
            if (town != null && town.mayorId().equals(player.getUniqueId())) {
                items.add(new MenuItem(16, button(Material.TNT, "§4解散小镇",
                        List.of(dialogText("tooltip.personal.disband-only"),
                                dialogText("tooltip.personal.disband-irreversible")),
                        "CONFIRM_DISBAND", town.id() + ":" + town.version())));
            } else if (town != null) {
                items.add(new MenuItem(16, button(Material.OAK_DOOR, "§c退出小镇",
                        List.of(dialogText("tooltip.personal.leave")),
                        "CONFIRM_LEAVE", town.id().toString())));
            }
            items.add(new MenuItem(22, button(Material.ARROW, "§7返回主菜单",
                    List.of(), "MAIN", null)));
            openMenu(player, 27, "个人与帮助", items);
        });
    }

    void openFinance(Player player, int page) {
        runtime.read(player, () -> {
            EconomyRepository.TownFinance account = runtime.finance()
                    .findFinanceByPlayer(player.getUniqueId())
                    .orElseThrow(() -> new IllegalArgumentException("你不属于任何小镇"));
            return new FinanceView(account);
        }, view -> renderFinance(player, view));
    }

    private void renderFinance(Player player, FinanceView view) {
        EconomyRepository.TownFinance account = view.account();
        List<String> summary = new ArrayList<>(List.of(
                "§7小镇: " + account.townName(),
                "§7公共余额: §f" + runtime.money(account.balanceMinor()),
                "§7统一收入税率: §f" + TownRuntime.percent(account.taxRateBps()),
                "§7领地单元: §f" + account.unitCount() + "/"
                        + runtime.economySettings().maximumUnits(),
                "§8适用于 QuickShop 实际收款、Jobs 工资与 GlobalMarketPlus 成交收入。",
                "§8普通转账、管理员调整及其他 Vault 变动不征税。"));
        if (account.locked()) {
            summary.add("§c资金已因清算差异锁定: " + account.lockReason());
        }
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem(4, button(account.locked() ? Material.REDSTONE_BLOCK
                : Material.EMERALD_BLOCK, "§6公共资产", summary, null, null)));
        if (runtime.consumptionEnabled()) {
            items.add(new MenuItem(10, button(Material.SUNFLOWER, "§a自定义金额捐款",
                    List.of(dialogText("tooltip.finance.donation"),
                            dialogText("tooltip.finance.donation-vault")),
                    "DONATION_INPUT", null)));
        }
        if (account.role().equals("MAYOR") && runtime.taxEnabled()) {
            items.add(new MenuItem(12, button(Material.GOLD_NUGGET, "§e税率设置",
                    List.of(dialogText("tooltip.finance.tax")), "TAX_MENU", null)));
        }
        items.add(new MenuItem(14, button(Material.WRITTEN_BOOK, "§6小镇账本",
                List.of(dialogText("tooltip.finance.ledger"),
                        dialogText("tooltip.finance.ledger-subsidy")), "LEDGER", "0")));
        items.add(new MenuItem(15, button(Material.BREWING_STAND, "§d公共 Buff",
                List.of(runtime.buffs().buffShopEnabled()
                                ? dialogText("tooltip.finance.buff-active")
                                : dialogText("tooltip.finance.buff-paused")),
                "BUFF_SHOP", null)));
        if (account.role().equals("MAYOR") && runtime.consumptionEnabled()) {
            items.add(new MenuItem(16, button(Material.FILLED_MAP, "§b领地扩张",
                    List.of(dialogText("tooltip.finance.expansion")), "EXPANSION_MENU", null)));
        }
        items.add(new MenuItem(22, button(Material.ARROW, "§7返回主菜单", List.of(),
                "MAIN", null)));
        openMenu(player, 27, "公共资产", items);
        if (account.hasUnreadTaxChange()) {
            actions.acknowledgeTaxRevision(player, account.taxRevision(), outcome ->
                    handleOutcome(player, outcome, ignored -> {
                    }));
        }
    }

    private void openTaxMenu(Player player) {
        runtime.read(player, () -> runtime.finance().findFinanceByPlayer(player.getUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("你不属于任何小镇")), account -> {
            boolean editable = account.role().equals("MAYOR") && runtime.taxEnabled();
            ItemStack summary = button(Material.GOLD_INGOT, "§6税率设置",
                    List.of("§7当前税率: §f" + TownRuntime.percent(account.taxRateBps()),
                            "§7同步作用于 QuickShop、Jobs 与全球市场收入",
                            editable ? "§e请选择新的税率后保存" : "§8只有镇长可以调整税率"),
                    null, null);
            if (!editable) {
                openMenu(player, 27, "小镇税率", List.of(
                        new MenuItem(0, summary),
                        new MenuItem(22, button(Material.ARROW, "§7返回公共资产",
                                List.of(), "FINANCE", "0"))));
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
                            DialogType.confirmation(
                                    ActionButton.create(dialogComponent("common.save-changes"),
                                            dialogComponent("tax.save-tooltip"),
                                            170, dialogAction(player, session,
                                                    response -> applyTaxDialog(player, account, response))),
                                    ActionButton.create(dialogComponent("common.cancel"),
                                            null, 170,
                                            dialogAction(player, session, "FINANCE", "0"))));
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
                    .orElseThrow(() -> new IllegalArgumentException("你不属于任何小镇"));
            List<EconomyRepository.LedgerEntry> entries = runtime.finance()
                    .displayLedger(account.townId(), page, 6);
            return new LedgerPage(account, entries, page);
        }, ledger -> renderLedger(player, ledger));
    }

    private void renderLedger(Player player, LedgerPage ledger) {
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem(0, button(Material.WRITTEN_BOOK, "§6"
                        + ledger.account().townName() + " · 小镇账本",
                List.of("§7第 " + (ledger.page() + 1) + " 页",
                        "§7当前公共余额: §f"
                                + runtime.money(ledger.account().balanceMinor())), null, null)));
        if (ledger.entries().isEmpty()) {
            items.add(new MenuItem(1, button(Material.PAPER, "§7本页没有资金流水",
                    List.of("§8返回上一页或公共资产"), null, null)));
        }
        int slot = 1;
        for (EconomyRepository.LedgerEntry entry : ledger.entries()) {
            boolean income = entry.amountMinor() > 0;
            String amount = (income ? "§a+" : "§c-")
                    + runtime.money(Math.abs(entry.amountMinor()));
            items.add(new MenuItem(slot++, button(income ? Material.LIME_DYE : Material.RED_DYE,
                    amount + " §f" + ledgerLabel(entry.entryType()),
                    List.of("§7变动后余额: §f"
                                    + runtime.money(entry.balanceAfterMinor()),
                            "§7操作人: §f" + entry.actorName(),
                            "§7时间: §f" + entry.createdAt(),
                            "§8" + entry.note()), null, null)));
        }
        if (ledger.page() > 0) {
            items.add(new MenuItem(20, button(Material.ARROW, "§e上一页",
                    List.of(), "LEDGER", String.valueOf(ledger.page() - 1))));
        }
        if (ledger.entries().size() == 6) {
            items.add(new MenuItem(21, button(Material.ARROW, "§e下一页",
                    List.of(), "LEDGER", String.valueOf(ledger.page() + 1))));
        }
        items.add(new MenuItem(22, button(Material.ARROW, "§7返回公共资产",
                List.of(), "FINANCE", "0")));
        openMenu(player, 27, "小镇账本 · 第 " + (ledger.page() + 1) + " 页", items);
    }

    private void openExpansionMenu(Player player) {
        runtime.loadTerritoryMap(player, map -> {
            String price = map.priceMinor() > 0
                    ? runtime.money(map.priceMinor()) : dialogText("territory.limit-reached");
            Component summary = dialogComponent("territory.summary", Map.of(
                            "current", map.currentUnits(), "maximum", map.maximumUnits()))
                    .append(Component.newline())
                    .append(dialogComponent("territory.legend"));
            openDialogPage(player, dialogText("territory.title"),
                    List.of(DialogBody.plainMessage(summary, 360)), List.of(),
                    DialogBase.DialogAfterAction.NONE, session -> {
                        ActionButton back = ActionButton.create(
                                dialogComponent("common.back"),
                                dialogComponent("common.back-tooltip"), 140,
                                dialogAction(player, session, "FINANCE", "0"));
                        return TerritoryDialogRenderer.render(map, price, plugin.messages(),
                                cell -> dialogAction(player, session, "PREVIEW_EXPANSION",
                                        cell.gridX() + "," + cell.gridZ()), back);
                    });
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
            items.add(new MenuItem(4, button(Material.NETHER_STAR, "§d小镇公共 Buff",
                    List.of(runtime.buffs().buffShopEnabled()
                            ? "§7使用公共资金购买，效果作用于全体成员且不限制世界"
                                    : "§e商店已暂停新购买，现有效果仍持续到期"), null, null)));
            int slot = 9;
            for (BuffDefinition definition : runtime.buffs().settings().buffs().values()) {
                CommerceRepository.SelectedBuffQuote quote = view.quotes().get(definition.key());
                CommerceRepository.ActiveBuff current = active.get(definition.key());
                boolean purchasable = runtime.buffs().buffShopEnabled() && quote != null;
                items.add(new MenuItem(slot++, button(purchasable ? Material.POTION
                                : Material.GLASS_BOTTLE,
                        (purchasable ? "§d" : "§7") + definition.displayName()
                                + (current == null ? "" : " · " + roman(current.level())),
                        List.of(),
                        purchasable ? "BUFF_DURATIONS" : null, definition.key())));
            }
            items.add(new MenuItem(49, button(Material.ARROW, "§7返回公共资产", List.of(),
                    "FINANCE", "0")));
            openMenu(player, 54, "公共 Buff 商店", items);
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
            ItemStack summary = button(Material.POTION, "§d" + definition.displayName(),
                    List.of(dialogText("buff.effect", Map.of(
                                    "effect", buffEffectDescription(definition))),
                            quote.current() == null
                                    ? dialogText("buff.inactive")
                                    : dialogText("buff.active", Map.of(
                                            "level", roman(quote.current().level()),
                                            "expires", quote.current().expiresAt())),
                            dialogText("buff.intensity-hint"),
                            dialogText("buff.price-hint")), null, null);
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
            openDialogPage(player, dialogText("buff.title"), List.of(dialogBody(summary)),
                    List.of(duration, intensity), DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE,
                    session -> DialogType.confirmation(
                            ActionButton.create(dialogComponent("buff.continue"),
                                    null, 170, dialogAction(player, session,
                                            response -> applyBuffDurationDialog(
                                                    player, buffKey, response))),
                            ActionButton.create(dialogComponent("common.cancel"),
                                    null, 170,
                                    dialogAction(player, session, "BUFF_SHOP", null))));
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
                        "level", roman(level), "weeks", weeks,
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
                                    "name", definition.displayName(),
                                    "level", roman(purchase.buff().level()),
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

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(level);
        };
    }

    private static String ledgerLabel(String type) {
        return switch (type) {
            case "QUICKSHOP_TAX" -> "QuickShop 税收";
            case "JOBS_TAX" -> "Jobs 税收";
            case "GLOBALMARKETPLUS_TAX" -> "全球市场税收";
            case "SERVER_TAX_SUBSIDY" -> "服务器税收补贴";
            case "APPLICATION_FEE" -> "建镇初始资金";
            case "DONATION" -> "成员捐款";
            case "EXPANSION" -> "领地扩张";
            case "EXPANSION_REFUND" -> "扩张退款";
            case "ADMIN_ADJUSTMENT" -> "管理员调整";
            case "BUFF_PURCHASE" -> "Buff 购买";
            case "BUFF_REFUND" -> "Buff 退款";
            case "RESOURCE_PURCHASE" -> "资源采购";
            case "RESOURCE_REFUND" -> "资源退款";
            default -> type;
        };
    }

    private void renderRulesConfirmation(Player player, MemberGovernanceSnapshot governance) {
        Component rules = dialogComponent("rules.town", Map.of(
                        "town", governance.townName()))
                .append(Component.newline())
                .append(dialogComponent("rules.revision", Map.of(
                        "revision", governance.townRulesRevision())));
        for (int index = 0; index < governance.rules().size(); index++) {
            rules = rules.append(Component.newline()).append(Component.newline())
                    .append(dialogComponent("rules.item", Map.of(
                            "index", index + 1, "rule", governance.rules().get(index))));
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
                session -> DialogType.confirmation(
                        ActionButton.create(dialogComponent("rules.confirm"),
                                null, 170, dialogAction(player, session,
                                        response -> acknowledgeRulesDialog(player, target, response))),
                        ActionButton.create(dialogComponent("rules.later"),
                                dialogComponent("rules.later-tooltip"),
                                170, dialogAction(player, session, "CLOSE", null))));
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
        List<String> summary = new ArrayList<>(List.of("§7名称: " + application.text().name(),
                "§7小镇领地名: " + application.text().normalizedResidenceName(),
                "§7建镇申请费: §f2000"));
        for (InitialMemberConfirmation member : application.initialMembers()) {
            summary.add("§7初始成员: " + displayName(member.playerId()));
        }
        if (application.territory() != null) {
            summary.add("§7中心区块: " + application.territory().center().x() + ", "
                    + application.territory().center().z());
        }
        if (application.reviewMessage() != null) {
            summary.add("§c管理员意见: " + application.reviewMessage());
        }
        if (application.lastError() != null) {
            summary.add("§c自动创建错误: " + application.lastError());
        }
        List<MenuItem> items = new ArrayList<>();
        items.add(new MenuItem(4, button(Material.PAPER, "§6申请摘要", summary, null, null)));
        if (application.status() == ApplicationStatus.DRAFT
                || application.status() == ApplicationStatus.SITE_SELECTED
                || application.status() == ApplicationStatus.NEED_CHANGES) {
            items.add(new MenuItem(10, button(Material.WRITABLE_BOOK, "§e编辑申请资料",
                    List.of(dialogText("tooltip.application.edit")), "EDIT_APPLICATION",
                    application.id().toString())));
            if (application.initialMembers().stream().anyMatch(member ->
                    member.status() == InitialMemberConfirmation.Status.PENDING)) {
                items.add(new MenuItem(11, button(Material.BELL, "§e提醒初始成员",
                        List.of(dialogText("tooltip.application.remind"),
                                dialogText("tooltip.application.remind-cooldown")),
                        "REMIND_INITIAL_MEMBERS", application.id().toString())));
            }
            items.add(new MenuItem(12, button(Material.COMPASS, "§e选择小镇领地",
                    List.of(dialogText("tooltip.application.select-site")), "SELECT_SITE",
                    application.id().toString())));
            if (application.territory() != null) {
                items.add(new MenuItem(14, button(Material.ENDER_EYE, "§b预览已选领地",
                        List.of(dialogText("tooltip.application.preview-site")), "PREVIEW_SITE",
                        application.id().toString())));
                boolean confirmed = application.initialMembersConfirmed();
                items.add(new MenuItem(16, button(confirmed ? Material.LIME_CONCRETE
                                : Material.GRAY_CONCRETE,
                        confirmed ? "§a提交申请" : "§7等待初始成员确认",
                        confirmed ? List.of(dialogText("tooltip.application.submit-ready"),
                                        dialogText("tooltip.application.submit-fee"))
                                : List.of(dialogText("tooltip.application.submit-waiting")),
                        confirmed ? "CONFIRM_SUBMIT" : null,
                        confirmed ? application.id().toString() : null)));
            }
            items.add(new MenuItem(22, button(Material.BARRIER, "§c撤回小镇申请",
                    List.of(dialogText("tooltip.application.cancel-draft")), "CONFIRM_CANCEL",
                    application.id().toString())));
        } else if (application.status() == ApplicationStatus.SUBMITTED
                || application.status() == ApplicationStatus.UNDER_REVIEW) {
            items.add(new MenuItem(22, button(Material.BARRIER, "§c撤回小镇申请",
                    List.of(dialogText("tooltip.application.cancel-submitted")), "CONFIRM_CANCEL",
                    application.id().toString())));
        }
        items.add(new MenuItem(26, button(Material.ARROW, "§7返回", List.of(), "MAIN", null)));
        openMenu(player, 27, "小镇申请摘要", items);
    }

    private void openTown(Player player, UUID townId) {
        runtime.read(player, () -> new TownDetailsView(runtime.repository().findTown(townId)
                        .orElseThrow(() -> new IllegalArgumentException("小镇不存在")),
                runtime.governance().dashboard(player.getUniqueId()).orElse(null)), view -> {
            TownSnapshot town = view.town();
            MemberGovernanceSnapshot governance = view.governance();
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(4, button(Material.BELL, "§6" + town.profile().name(),
                    List.of("§7小镇代码: " + town.profile().residenceName(),
                            "§7Residence 领地名: " + town.residenceName(),
                            "§7简介: " + town.profile().description(),
                            "§7规则数: " + town.profile().rules().size(),
                            "§7状态: " + town.status()), null, null)));
            items.add(new MenuItem(10, button(Material.WRITTEN_BOOK, "§e阅读小镇规则",
                    List.of(dialogText("tooltip.town.rules")), "TOWN_RULES",
                    town.id().toString())));
            items.add(new MenuItem(12, button(Material.PLAYER_HEAD, "§e成员列表",
                    List.of(dialogText("tooltip.town.members")), "MEMBERS", town.id() + ":0")));
            if (governance != null && governance.role().isLeader()) {
                items.add(new MenuItem(13, button(Material.WRITABLE_BOOK, "§e修改简介和规则",
                        List.of(dialogText("tooltip.town.edit")),
                        "EDIT_TOWN", town.id().toString())));
            }
            if (town.territory() != null) {
                items.add(new MenuItem(14, button(Material.MAP, "§e小镇领地",
                        List.of(dialogText("tooltip.town.territory"),
                                dialogText("tooltip.town.territory-center", Map.of(
                                        "x", town.territory().center().x(),
                                        "z", town.territory().center().z())),
                                dialogText("tooltip.town.territory-preview")),
                        "PREVIEW_TOWN",
                        town.id().toString())));
            }
            items.add(new MenuItem(26, button(Material.ARROW, "§7返回", List.of(), "MAIN", null)));
            openMenu(player, 27, "小镇详情", items);
        });
    }

    private void openTownRules(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException("小镇不存在")), town -> {
            Component content = dialogComponent("rules.current-heading", Map.of(
                    "town", town.profile().name()));
            if (town.profile().rules().isEmpty()) {
                content = content.append(Component.newline()).append(Component.newline())
                        .append(dialogComponent("rules.empty"));
            } else {
                for (int index = 0; index < town.profile().rules().size(); index++) {
                    content = content.append(Component.newline()).append(Component.newline())
                            .append(dialogComponent("rules.item", Map.of(
                                    "index", index + 1, "rule", town.profile().rules().get(index))));
                }
            }
            openDialogPage(player, dialogText("rules.title"), List.of(
                            DialogBody.plainMessage(content, 420)),
                    List.of(), DialogBase.DialogAfterAction.NONE,
                    session -> DialogType.notice(ActionButton.create(
                            dialogComponent("common.back"), null, 220,
                            dialogAction(player, session, "TOWN", town.id().toString()))));
        });
    }

    private void openMembers(Player player, UUID townId, int page) {
        runtime.read(player, () -> new MemberPage(runtime.repository().listMembers(townId, page, 8),
                runtime.governance().dashboard(player.getUniqueId()).orElse(null)), view -> {
            List<MenuItem> items = new ArrayList<>();
            int slot = 0;
            for (TownSnapshot.Member member : view.page().members()) {
                String name = Objects.requireNonNullElse(Bukkit.getOfflinePlayer(member.playerId()).getName(),
                        member.playerId().toString());
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
                                        Map.of("role", member.role())),
                                dialogText("tooltip.members.joined",
                                        Map.of("time", member.joinedAt())),
                                sameTown ? dialogText("tooltip.members.manage")
                                        : dialogText("tooltip.members.readonly")),
                        sameTown ? "MEMBER_DETAIL" : null,
                        sameTown ? townId + ":" + member.playerId() : null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, button(Material.ARROW, "§e上一页", List.of(),
                        "MEMBERS", townId + ":" + (page - 1))));
            }
            if (view.page().hasNext()) {
                items.add(new MenuItem(53, button(Material.ARROW, "§e下一页", List.of(),
                        "MEMBERS", townId + ":" + (page + 1))));
            }
            items.add(new MenuItem(48, button(Material.ARROW, "§7返回小镇详情", List.of(),
                    "TOWN", townId.toString())));
            openMenu(player, 54, "小镇成员 · 第 " + (page + 1) + " 页", items);
        });
    }

    private void openMemberDetail(Player player, UUID townId, UUID targetId) {
        runtime.read(player, () -> new MemberDetail(
                runtime.governance().dashboard(player.getUniqueId())
                        .orElseThrow(() -> new IllegalArgumentException("你不属于任何小镇")),
                runtime.governance().memberRole(townId, targetId)), view -> {
            if (!view.viewer().townId().equals(townId)) {
                openNotice(player, dialogText("notice.member-forbidden-title"),
                        dialogText("notice.member-forbidden-message"),
                        dialogText("common.back"), "MAIN", null);
                return;
            }
            String name = Objects.requireNonNullElse(Bukkit.getOfflinePlayer(targetId).getName(),
                    targetId.toString());
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(4, button(Material.PLAYER_HEAD, "§6" + name,
                    List.of("§7身份: " + view.targetRole(), "§7UUID: " + targetId), null, null)));
            boolean targetIsMayor = view.targetRole() == MemberRole.MAYOR;
            boolean viewerIsMayor = view.viewer().role() == MemberRole.MAYOR;
            if (viewerIsMayor && !targetIsMayor) {
                MemberRole nextRole = view.targetRole() == MemberRole.DEPUTY_MAYOR
                        ? MemberRole.MEMBER : MemberRole.DEPUTY_MAYOR;
                items.add(new MenuItem(10, button(Material.GOLDEN_HELMET,
                        nextRole == MemberRole.DEPUTY_MAYOR
                                ? "§a任命为副镇长" : "§e降为普通镇员",
                        List.of(dialogText("tooltip.member-detail.max-deputies")), "CONFIRM_ROLE",
                        townId + ":" + targetId + ":" + nextRole)));
            }
            boolean viewerCanRemove = view.viewer().role().isLeader() && !targetIsMayor
                    && (viewerIsMayor || view.targetRole() == MemberRole.MEMBER);
            if (viewerCanRemove) {
                items.add(new MenuItem(12, button(Material.RED_CONCRETE, "§c移除成员",
                        List.of(dialogText("tooltip.member-detail.kick-now"),
                                dialogText("tooltip.member-detail.kick-confirm")),
                        "CONFIRM_KICK_MEMBER", townId + ":" + targetId)));
            }
            if (viewerIsMayor && !targetIsMayor) {
                items.add(new MenuItem(14, button(Material.NETHER_STAR, "§e发起镇长转让",
                        List.of(dialogText("tooltip.member-detail.transfer-expiry")),
                        "CONFIRM_TRANSFER_MAYOR",
                        townId + ":" + targetId)));
            }
            if (!targetIsMayor && !targetId.equals(player.getUniqueId())) {
                items.add(new MenuItem(16, button(Material.PAPER, "§e发起投票移除",
                        List.of(dialogText("tooltip.member-detail.vote-kick-threshold")),
                        "CONFIRM_CREATE_VOTE", townId + ":KICK_MEMBER:" + targetId)));
            }
            if (!targetIsMayor && view.viewer().role().isLeader()) {
                items.add(new MenuItem(22, button(Material.ENCHANTED_BOOK, "§e提名为新镇长",
                        List.of(dialogText("tooltip.member-detail.replace-mayor-threshold")),
                        "CONFIRM_CREATE_VOTE", townId + ":REPLACE_MAYOR:" + targetId)));
            }
            items.add(new MenuItem(26, button(Material.ARROW, "§7返回成员列表", List.of(),
                    "MEMBERS", townId + ":0")));
            openMenu(player, 27, "成员治理 · " + name, items);
        });
    }

    private void openTransferRequest(Player player, UUID transferId) {
        runtime.read(player, () -> runtime.governance().dashboard(player.getUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("你不属于任何小镇")), governance -> {
            TransferSnapshot transfer = governance.pendingTransfer();
            if (transfer == null || !transfer.id().equals(transferId)) {
                openNotice(player, dialogText("notice.transfer-expired-title"),
                        dialogText("notice.transfer-expired-message"),
                        dialogText("common.back"), "MAIN", null);
                return;
            }
            List<MenuItem> items = List.of(
                    new MenuItem(4, button(Material.NETHER_STAR, "§6接任镇长邀请",
                            List.of("§7小镇: " + governance.townName(),
                                    "§7有效期至: " + transfer.expiresAt(),
                                    "§c接受后原镇长降为普通成员"), null, null)),
                    new MenuItem(11, button(Material.LIME_CONCRETE, "§a接受并接任",
                            List.of(dialogText("tooltip.transfer.accept-confirm")),
                            "CONFIRM_TRANSFER_DECISION",
                            transfer.id() + ":true")),
                    new MenuItem(15, button(Material.RED_CONCRETE, "§c拒绝",
                            List.of(dialogText("tooltip.transfer.reject-close")),
                            "CONFIRM_TRANSFER_DECISION",
                            transfer.id() + ":false")),
                    new MenuItem(22, button(Material.ARROW, "§7返回", List.of(), "MAIN", null)));
            openMenu(player, 27, "镇长转让确认", items);
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
                        (pending ? "§b待投票 · " : "§e") + voteLabel(vote.type()) + " · " + target,
                        List.of(dialogText("tooltip.votes.entry.yes", Map.of(
                                        "yes", vote.yesVotes(), "required", vote.requiredYes())),
                                dialogText("tooltip.votes.entry.no",
                                        Map.of("no", vote.noVotes())),
                                dialogText("tooltip.votes.entry.expires",
                                        Map.of("time", vote.endsAt()))),
                        "VOTE_DETAIL", vote.id().toString())));
            }
            if (votes.isEmpty()) {
                items.add(new MenuItem(22, button(Material.PAPER, "§7暂无进行中的投票",
                        List.of("§7从成员列表选择目标后可发起治理投票"), null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, button(Material.ARROW, "§e上一页", List.of(),
                        "VOTES_PAGE", townId + ":" + (page - 1))));
            }
            if (hasNext(votes, page, 8)) {
                items.add(new MenuItem(53, button(Material.ARROW, "§e下一页", List.of(),
                        "VOTES_PAGE", townId + ":" + (page + 1))));
            }
            items.add(new MenuItem(48, button(Material.ARROW, "§7返回成员治理", List.of(),
                    "GOVERNANCE_CENTER", null)));
            openMenu(player, 54, "小镇治理投票 · 第 " + (page + 1) + " 页", items);
        });
    }

    private void openVote(Player player, UUID voteId) {
        runtime.read(player, () -> runtime.governance().dashboard(player.getUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("你不属于任何小镇")), governance -> {
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
            items.add(new MenuItem(4, button(Material.PAPER, "§6" + voteLabel(vote.type()),
                    List.of("§7目标: " + target,
                            "§7有效选民: " + vote.eligibleVoters(),
                            "§7通过门槛: " + vote.requiredYes(),
                            "§7当前赞成/反对: " + vote.yesVotes() + "/" + vote.noVotes(),
                            "§7到期: " + vote.endsAt()), null, null)));
            if (vote.viewerEligible() && !vote.viewerVoted()) {
                items.add(new MenuItem(11, button(Material.LIME_CONCRETE, "§a赞成",
                        List.of(dialogText("tooltip.votes.once")), "CAST_VOTE", vote.id() + ":true")));
                items.add(new MenuItem(15, button(Material.RED_CONCRETE, "§c反对",
                        List.of(dialogText("tooltip.votes.once")), "CAST_VOTE", vote.id() + ":false")));
            } else {
                items.add(new MenuItem(13, button(Material.GRAY_DYE,
                        vote.viewerVoted() ? "§7你已经投票" : "§7你不在冻结选民快照中",
                        List.of(), null, null)));
            }
            if (vote.createdBy().equals(player.getUniqueId())) {
                items.add(new MenuItem(18, button(Material.BARRIER, "§c终止本次投票",
                        List.of(dialogText("tooltip.votes.cancel-only"),
                                dialogText("tooltip.votes.cancel-irreversible")),
                        "CONFIRM_CANCEL_VOTE", vote.id().toString())));
            }
            items.add(new MenuItem(22, button(Material.ARROW, "§7返回投票列表", List.of(),
                    "VOTES", vote.townId().toString())));
            openMenu(player, 27, "治理投票详情", items);
        });
    }

    private static String voteLabel(VoteType type) {
        return type == VoteType.KICK_MEMBER ? "投票移除成员" : "强制更换镇长";
    }

    private static String displayName(UUID playerId) {
        if (playerId == null) {
            return "未知";
        }
        return Objects.requireNonNullElse(Bukkit.getOfflinePlayer(playerId).getName(),
                "未知玩家");
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
                items.add(new MenuItem(index, button(Material.BELL, "§6" + town.profile().name(),
                        List.of(dialogText("tooltip.join.town-code", Map.of(
                                        "code", town.profile().residenceName())),
                                dialogText("tooltip.join.town-description", Map.of(
                                        "description", preview(town.profile().description(), 80))),
                                dialogText("tooltip.join.town-apply")),
                        "JOIN_TOWN", town.id().toString())));
            }
            if (towns.isEmpty()) {
                items.add(new MenuItem(0, button(Material.BELL, "§7暂无开放的小镇",
                        List.of("§7稍后再来查看"), null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, button(Material.ARROW, "§e上一页", List.of(),
                        "JOIN_TOWNS_PAGE", String.valueOf(page - 1))));
            }
            if (hasNext(towns, page, 8)) {
                items.add(new MenuItem(53, button(Material.ARROW, "§e下一页", List.of(),
                        "JOIN_TOWNS_PAGE", String.valueOf(page + 1))));
            }
            items.add(new MenuItem(48, button(Material.ARROW, "§7返回主菜单", List.of(),
                    "MAIN", null)));
            openMenu(player, 54, "申请加入小镇 · 第 " + (page + 1) + " 页", items);
        });
    }

    private void openJoinTown(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .filter(town -> town.status() == TownStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("小镇不存在或已停止运行")), town -> {
            List<MenuItem> items = List.of(
                    new MenuItem(4, button(Material.BELL, "§6" + town.profile().name(),
                            List.of("§7小镇代码: " + town.profile().residenceName(),
                                    "§7简介: " + town.profile().description(),
                                    "§7规则:", "§f" + String.join(" | ", town.profile().rules())),
                            null, null)),
                    new MenuItem(13, button(Material.LIME_CONCRETE, "§a提交入镇申请",
                            List.of(dialogText("tooltip.join.submit-expiry"),
                                    dialogText("tooltip.join.submit-limit")),
                            "CONFIRM_APPLY_JOIN", town.id().toString())),
                    new MenuItem(22, button(Material.ARROW, "§7返回小镇列表", List.of(),
                            "JOIN_TOWNS", null)));
            openMenu(player, 27, "申请加入 · " + town.profile().name(), items);
        });
    }

    private void openMyJoinApplications(Player player) {
        runtime.read(player, () -> runtime.repository().listJoinApplications(player.getUniqueId()),
                applications -> {
                    List<MenuItem> items = new ArrayList<>();
                    for (int index = 0; index < Math.min(applications.size(), 45); index++) {
                        JoinApplicationSnapshot application = applications.get(index);
                        items.add(new MenuItem(index, button(Material.PAPER,
                                "§e" + application.townName(),
                                List.of(dialogText("tooltip.my-join.expires", Map.of(
                                                "time", application.expiresAt())),
                                        dialogText("tooltip.my-join.withdraw")),
                                "CONFIRM_CANCEL_JOIN", application.id().toString())));
                    }
                    items.add(new MenuItem(48, button(Material.ARROW, "§7返回主菜单", List.of(),
                            "MAIN", null)));
                    openMenu(player, 54, "我的入镇申请 · " + applications.size(), items);
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
                String name = Objects.requireNonNullElse(
                        Bukkit.getOfflinePlayer(application.applicantId()).getName(),
                        application.applicantId().toString());
                items.add(new MenuItem(index, button(Material.PLAYER_HEAD, "§e" + name,
                        List.of(dialogText("tooltip.town-join.entry-created", Map.of(
                                        "time", application.createdAt())),
                                dialogText("tooltip.town-join.entry-expires", Map.of(
                                        "time", application.expiresAt())),
                                dialogText("tooltip.town-join.entry-review")),
                        "JOIN_APPLICATION", application.id().toString())));
            }
            if (applications.isEmpty()) {
                items.add(new MenuItem(0, button(Material.BOOK, "§7暂无待处理入镇申请",
                        List.of("§7新的申请会出现在待办中心"), null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, button(Material.ARROW, "§e上一页", List.of(),
                        "JOIN_APPLICATIONS_PAGE", townId + ":" + (page - 1))));
            }
            if (hasNext(applications, page, 8)) {
                items.add(new MenuItem(53, button(Material.ARROW, "§e下一页", List.of(),
                        "JOIN_APPLICATIONS_PAGE", townId + ":" + (page + 1))));
            }
            items.add(new MenuItem(48, button(Material.ARROW, "§7返回成员治理", List.of(),
                    "GOVERNANCE_CENTER", null)));
            openMenu(mayor, 54, "入镇申请 · " + applications.size()
                    + " · 第 " + (page + 1) + " 页", items);
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
                            "CONFIRM_REJECT_JOIN", application.id().toString())),
                    new MenuItem(22, button(Material.ARROW, "§7返回申请列表", List.of(),
                            "JOIN_APPLICATIONS", application.townId().toString())));
            openMenu(mayor, 27, "审核入镇申请", items);
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
                        "§e" + application.text().name(),
                        List.of(dialogText("tooltip.admin.entry-code", Map.of(
                                        "code", application.text().residenceName())),
                                dialogText("tooltip.admin.entry-review")),
                        "ADMIN_APPLICATION", application.id().toString())));
            }
            if (applications.isEmpty()) {
                items.add(new MenuItem(0, button(Material.BOOK, "§7暂无待审核申请",
                        List.of("§7收到新申请时会播放提醒音效"), null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, button(Material.ARROW, "§e上一页", List.of(),
                        "ADMIN_APPLICATIONS_PAGE", String.valueOf(page - 1))));
            }
            if (hasNext(applications, page, 8)) {
                items.add(new MenuItem(53, button(Material.ARROW, "§e下一页", List.of(),
                        "ADMIN_APPLICATIONS_PAGE", String.valueOf(page + 1))));
            }
            items.add(new MenuItem(48, button(Material.ARROW, "§7返回主菜单", List.of(),
                    "MAIN", null)));
            openMenu(admin, 54, "申请审核 · " + applications.size()
                    + " · 第 " + (page + 1) + " 页", items);
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
                .orElseThrow(() -> new IllegalArgumentException("申请不存在")), application -> {
            List<String> summary = new ArrayList<>(List.of(
                    "§7名称: " + application.text().name(),
                    "§7小镇代码: " + application.text().residenceName(),
                    "§7Residence 领地名: " + application.text().normalizedResidenceName(),
                    "§7简介: " + application.text().description(),
                    "§7规则: " + String.join(" | ", application.text().rules()),
                    "§7申请费: 2000（批准后成为初始公共资金）"));
            for (InitialMemberConfirmation member : application.initialMembers()) {
                summary.add("§7初始成员: " + displayName(member.playerId()));
            }
            if (application.territory() != null) {
                summary.add("§7选址: " + application.territory().center().worldName() + " "
                        + application.territory().center().x() + ","
                        + application.territory().center().z());
            }
            if (application.lastError() != null) {
                summary.add("§c创建错误: " + application.lastError());
            }
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(4, button(Material.PAPER, "§6申请详情", summary, null, null)));
            if (application.status() == ApplicationStatus.SUBMITTED
                    || application.status() == ApplicationStatus.UNDER_REVIEW) {
                items.add(new MenuItem(10, button(Material.LIME_CONCRETE, "§a批准",
                        List.of(dialogText("tooltip.admin.approve")), "CONFIRM_ADMIN_APPROVE",
                        application.id().toString())));
                items.add(new MenuItem(12, button(Material.RED_CONCRETE, "§c拒绝",
                        List.of(dialogText("tooltip.admin.reject")), "CONFIRM_ADMIN_REJECT",
                        application.id().toString())));
                items.add(new MenuItem(14, button(Material.ORANGE_CONCRETE, "§e要求补件",
                        List.of(dialogText("tooltip.admin.change")), "CONFIRM_ADMIN_CHANGE",
                        application.id().toString())));
            } else if (application.status() == ApplicationStatus.PROVISION_FAILED) {
                items.add(new MenuItem(12, button(Material.LIME_CONCRETE, "§a重试批准",
                        List.of(dialogText("tooltip.admin.retry")), "CONFIRM_ADMIN_APPROVE",
                        application.id().toString())));
            }
            if (application.territory() != null) {
                items.add(new MenuItem(16, button(Material.ENDER_EYE, "§b预览选址",
                        List.of(dialogText("tooltip.admin.preview")), "ADMIN_PREVIEW_SITE",
                        application.id().toString())));
            }
            items.add(new MenuItem(22, button(Material.ARROW, "§7返回审核列表", List.of(),
                    "ADMIN_APPLICATIONS", null)));
            openMenu(admin, 27, "审核 · " + application.text().name(), items);
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
                case "CREATE_APPLICATION" -> startApplicationForm(player, null, 0,
                        new ApplicationText("", "", "", "", List.of()), List.of());
                case "APPLICATION" -> loadApplication(player, UUID.fromString(target));
                case "EDIT_APPLICATION" -> loadApplicationForForm(player, UUID.fromString(target));
                case "APPLICATION_BASICS_FORM" -> renderApplicationFormStage(player,
                        UUID.fromString(target), 1);
                case "APPLICATION_CONTENT_FORM" -> renderApplicationFormStage(player,
                        UUID.fromString(target), 2);
                case "APPLICATION_MEMBERS_FORM" -> renderApplicationFormStage(player,
                        UUID.fromString(target), 3);
                case "SELECT_INITIAL_MEMBER" -> {
                    String[] parts = target.split(":");
                    openInitialMemberOptions(player, UUID.fromString(parts[0]),
                            Integer.parseInt(parts[1]));
                }
                case "CHOOSE_INITIAL_MEMBER" -> chooseInitialMember(player, target);
                case "SAVE_APPLICATION_DRAFT" -> saveApplicationForm(player,
                        UUID.fromString(target));
                case "REMIND_INITIAL_MEMBERS" -> remindInitialMembers(player,
                        UUID.fromString(target));
                case "SELECT_SITE" -> selectSite(player, UUID.fromString(target));
                case "PREVIEW_SITE" -> previewApplication(player, UUID.fromString(target));
                case "CONFIRM_SUBMIT" -> openConfirmation(player,
                        dialogText("confirmation.submit-application-title"), "SUBMIT", target,
                        dialogText("confirmation.submit-application-consequence"),
                        "APPLICATION", target);
                case "SUBMIT" -> submit(player, UUID.fromString(target));
                case "CONFIRM_CANCEL" -> openConfirmation(player,
                        dialogText("confirmation.cancel-application-title"), "CANCEL", target,
                        dialogText("confirmation.cancel-application-consequence"),
                        "APPLICATION", target);
                case "CANCEL" -> cancel(player, UUID.fromString(target));
                case "TOWN" -> openTown(player, UUID.fromString(target));
                case "TOWN_RULES" -> openTownRules(player, UUID.fromString(target));
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
                    openMemberDetail(player, UUID.fromString(parts[0]), UUID.fromString(parts[1]));
                }
                case "CONFIRM_ROLE" -> {
                    String[] parts = target.split(":");
                    openConfirmation(player, dialogText("confirmation.change-role-title"),
                            "SET_ROLE", target, dialogText(
                                    "confirmation.change-role-consequence", Map.of("role", parts[2])),
                            "MEMBER_DETAIL",
                            parts[0] + ":" + parts[1]);
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
        actions.changeMemberRole(mayor, townId, playerId, role, outcome ->
                handleOutcome(mayor, outcome, changed -> {
            openNotice(mayor, dialogText("notice.role-updated-title"),
                    dialogText("notice.role-updated-message", Map.of("role", changed)),
                    dialogText("common.back"),
                    "MEMBER_DETAIL", townId + ":" + playerId);
        }));
    }

    private void kickMember(Player mayor, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        UUID playerId = UUID.fromString(parts[1]);
        actions.kickMember(mayor, townId, playerId, outcome ->
                handleOutcome(mayor, outcome, changedTown -> {
            Player removed = Bukkit.getPlayer(playerId);
            if (removed != null) {
                plugin.messages().send(removed, "chat.notification.member-removed");
            }
            openNotice(mayor, dialogText("notice.member-removed-title"),
                    dialogText("notice.member-removed-message"),
                    dialogText("common.back"), "MEMBERS", changedTown + ":0");
        }));
    }

    private void addVisitor(Player manager, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        UUID playerId = UUID.fromString(parts[1]);
        actions.addVisitor(manager, townId, playerId, outcome ->
                handleOutcome(manager, outcome, visitor -> {
            Player invited = Bukkit.getPlayer(playerId);
            if (invited != null) {
                plugin.messages().send(invited, "chat.notification.visitor-added");
                playSound(invited, Sound.BLOCK_NOTE_BLOCK_PLING);
            }
            openNotice(manager, dialogText("notice.visitor-added-title"),
                    dialogText("notice.visitor-added-message", Map.of(
                            "player", displayName(playerId))),
                    dialogText("common.back"), "VISITOR_LIST", townId + ":0");
        }));
    }

    private void removeVisitor(Player manager, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        UUID playerId = UUID.fromString(parts[1]);
        int page = Integer.parseInt(parts[2]);
        actions.removeVisitor(manager, townId, playerId, outcome ->
                handleOutcome(manager, outcome, removed -> {
            Player visitor = Bukkit.getPlayer(playerId);
            if (visitor != null) {
                plugin.messages().send(visitor, "chat.notification.visitor-removed");
            }
            openNotice(manager, dialogText("notice.visitor-removed-title"),
                    dialogText("notice.visitor-removed-message", Map.of(
                            "player", displayName(playerId))),
                    dialogText("common.back"), "VISITOR_LIST", townId + ":" + page);
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
            openNotice(player, dialogText("notice.vote-created-title"),
                    dialogText("notice.vote-created-message", Map.of(
                            "voters", vote.eligibleVoters(), "required", vote.requiredYes())),
                    dialogText("common.view-vote"),
                    "VOTE_DETAIL", vote.id().toString());
        }));
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
                            "status", vote.status())), dialogText("common.back"),
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
        return parts[0] + ":" + parts[2];
    }

    private void loadApplication(Player player, UUID applicationId) {
        runtime.read(player, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("申请不存在")),
                application -> openApplication(player, application));
    }

    private void loadApplicationForForm(Player player, UUID applicationId) {
        runtime.read(player, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("申请不存在")), application ->
                startApplicationForm(player, application.id(), application.version(),
                        application.text(), application.initialMembers().stream()
                                .map(member -> displayName(member.playerId())).toList()));
    }

    private void loadTownForForm(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException("小镇不存在")), town -> {
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
                .orElseThrow(() -> new IllegalArgumentException("申请不存在")), application -> {
            if (application.territory() == null) {
                openNotice(player, dialogText("notice.site-missing-title"),
                        dialogText("notice.site-missing-message"),
                        dialogText("common.back"), "APPLICATION",
                        applicationId.toString());
            } else {
                sitePolicy.teleportAndPreview(player, application.territory());
            }
        });
    }

    private void previewTown(Player player, UUID townId) {
        runtime.read(player, () -> {
            TownSnapshot town = runtime.repository().findTown(townId)
                    .orElseThrow(() -> new IllegalArgumentException("小镇不存在"));
            List<InitialTerritory> territories = runtime.finance().territoryUnits(townId).stream()
                    .filter(unit -> unit.projectionStatus().equals("ACTIVE"))
                    .map(unit -> unit.unit().territory())
                    .toList();
            if (territories.isEmpty()) {
                throw new IllegalArgumentException("小镇没有已生效的领地单元");
            }
            return new TownTerritoryPreview(town, territories);
        }, preview -> sitePolicy.teleportAndPreview(player, preview.town().territory(),
                preview.territories()));
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
                .orElseThrow(() -> new IllegalArgumentException("申请不存在")), application -> {
            String idempotencyKey = application.status() == ApplicationStatus.PROVISION_FAILED
                    ? "town:retry:" + application.id() + ":" + UUID.randomUUID()
                    : "town:approve:" + application.id();
            runtime.provision(admin, application.id(), admin.getUniqueId(), admin.getName(),
                    "管理员通过玩家界面批准申请", idempotencyKey, approved -> {
                        notifyApplicationDecision(approved);
                        playSound(admin, approved.status() == ApplicationStatus.ACTIVE
                                ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_NOTE_BLOCK_BASS);
                        openAdminApplications(admin);
                    });
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
                .orElseThrow(() -> new IllegalArgumentException("申请不存在")), application -> {
            Component explanation = dialogComponent(requestChanges
                            ? "review.change-heading" : "review.reject-heading")
                    .append(Component.newline())
                    .append(dialogComponent("review.town", Map.of(
                            "town", application.text().name())))
                    .append(Component.newline())
                    .append(dialogComponent(requestChanges
                            ? "review.change-guidance" : "review.reject-guidance"));
            if (error != null) {
                explanation = explanation.append(Component.newline()).append(Component.newline())
                        .append(dialogComponent("review.error", Map.of("error", error)));
            }
            DialogInput reasonInput = DialogInput.text("review_reason", 400,
                    dialogComponent(requestChanges ? "review.change-label" : "review.reject-label"),
                    true, initialReason, 500,
                    TextDialogInput.MultilineOptions.create(6, 110));
            openDialogPage(admin, requestChanges ? dialogText("review.change-title")
                            : dialogText("review.reject-title"),
                    List.of(DialogBody.plainMessage(explanation, 420)),
                    List.of(reasonInput), DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE,
                    session -> DialogType.confirmation(
                            ActionButton.create(dialogComponent(requestChanges
                                            ? "review.send-change" : "review.confirm-reject"),
                                    dialogComponent("review.submit-tooltip"), 190,
                                    dialogAction(admin, session, response -> applyReviewReason(
                                            admin, applicationId, requestChanges, response))),
                            ActionButton.create(dialogComponent("common.cancel"),
                                    null, 150, dialogAction(admin, session,
                                            "ADMIN_APPLICATION", applicationId.toString()))));
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
                .orElseThrow(() -> new IllegalArgumentException("申请不存在")), application -> {
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
        renderApplicationForm(player, formId);
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
            default -> throw new IllegalArgumentException("未知申请表单步骤");
        }
    }

    private void renderApplicationBasicsDialog(Player player, ApplicationFormSession form) {
        ApplicationText text = form.text();
        List<DialogInput> inputs = List.of(
                DialogInput.text("town_name", 380,
                        dialogComponent("application.name-label"), true,
                        text.name(), 24, null),
                DialogInput.text("residence_name", 380,
                        dialogComponent("application.code-label"), true,
                        text.residenceName(), 12, null));
        Component guidance = dialogComponent("application.basics-heading")
                .append(Component.newline())
                .append(dialogComponent("application.basics-guidance"));
        openDialogPage(player, dialogText("application.title"), List.of(
                        DialogBody.plainMessage(guidance, 400)),
                inputs, DialogBase.DialogAfterAction.NONE, session ->
                        DialogType.confirmation(
                                ActionButton.create(dialogComponent("common.next-step"),
                                        null, 170, dialogAction(player, session,
                                                response -> applyApplicationBasics(
                                                        player, form.id(), response))),
                                ActionButton.create(dialogComponent("common.cancel-edit"),
                                        null, 170, dialogAction(player, session,
                                                response -> cancelApplicationForm(player, form.id())))));
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
                form.text().description(), form.text().rules());
        ApplicationFormSession candidate = new ApplicationFormSession(form.id(), form.purpose(),
                form.targetId(), form.version(), updated, form.initialMemberNames());
        applicationForms.put(player.getUniqueId(), candidate);
        List<String> errors = new ArrayList<>();
        errors.addAll(fieldErrors(player, candidate, ApplicationField.NAME));
        errors.addAll(fieldErrors(player, candidate, ApplicationField.RESIDENCE_NAME));
        if (!errors.isEmpty()) {
            openNotice(player, dialogText("notice.basics-invalid-title"),
                    String.join("\n", errors), dialogText("common.back"),
                    "APPLICATION_BASICS_FORM", form.id().toString());
            return;
        }
        renderApplicationContentDialog(player, candidate);
    }

    private void renderApplicationContentDialog(Player player, ApplicationFormSession form) {
        TextDialogInput.MultilineOptions descriptionLines = TextDialogInput.MultilineOptions
                .create(6, 90);
        TextDialogInput.MultilineOptions ruleLines = TextDialogInput.MultilineOptions
                .create(20, 150);
        List<DialogInput> inputs = List.of(
                DialogInput.text("description", 400,
                        dialogComponent("application.description-label"), true,
                        form.text().description(), 500, descriptionLines),
                DialogInput.text("rules", 400,
                        dialogComponent("application.rules-label"), true,
                        String.join("\n", form.text().rules()), 5000, ruleLines));
        Component guidance = dialogComponent("application.content-heading")
                .append(Component.newline())
                .append(dialogComponent("application.content-guidance"));
        openDialogPage(player, dialogText("application.title"), List.of(
                        DialogBody.plainMessage(guidance, 420)),
                inputs, DialogBase.DialogAfterAction.NONE, session -> {
                    List<ActionButton> actions = List.of(
                            ActionButton.create(dialogComponent("common.previous-step"),
                                    null, 150, dialogAction(player, session,
                                            response -> renderApplicationBasicsDialog(player, form))),
                            ActionButton.create(dialogComponent("common.next-step"),
                                    null, 150, dialogAction(player, session,
                                            response -> applyApplicationContent(
                                                    player, form.id(), response))));
                    return DialogType.multiAction(actions)
                            .exitAction(ActionButton.create(
                                    dialogComponent("common.cancel-edit"), null, 140,
                                    dialogAction(player, session,
                                            response -> cancelApplicationForm(player, form.id()))))
                            .columns(2).build();
                });
    }

    private void applyApplicationContent(Player player, UUID formId,
                                         DialogResponseView response) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        ApplicationText updated = updateField(form.text(), ApplicationField.DESCRIPTION,
                responseText(response, "description"));
        updated = updateField(updated, ApplicationField.RULES,
                responseText(response, "rules"));
        ApplicationFormSession candidate = new ApplicationFormSession(form.id(), form.purpose(),
                form.targetId(), form.version(), updated, form.initialMemberNames());
        applicationForms.put(player.getUniqueId(), candidate);
        List<String> errors = new ArrayList<>();
        errors.addAll(fieldErrors(player, candidate, ApplicationField.DESCRIPTION));
        errors.addAll(fieldErrors(player, candidate, ApplicationField.RULES));
        if (!errors.isEmpty()) {
            openNotice(player, dialogText("notice.content-invalid-title"),
                    String.join("\n", errors), dialogText("common.back"),
                    "APPLICATION_CONTENT_FORM", form.id().toString());
            return;
        }
        renderApplicationMembersDialog(player, candidate);
    }

    private void renderApplicationMembersDialog(Player player, ApplicationFormSession form) {
        List<MenuItem> items = new ArrayList<>();
        String first = form.initialMemberNames().get(0);
        String second = form.initialMemberNames().get(1);
        items.add(new MenuItem(10, button(Material.PLAYER_HEAD,
                first.isBlank() ? dialogText("application.member-one-placeholder")
                        : "§a" + first,
                List.of(dialogText("application.member-select-hint")),
                "SELECT_INITIAL_MEMBER",
                form.id() + ":0")));
        items.add(new MenuItem(12, button(Material.PLAYER_HEAD,
                second.isBlank() ? dialogText("application.member-two-placeholder")
                        : "§a" + second,
                List.of(dialogText("application.member-select-hint")),
                "SELECT_INITIAL_MEMBER",
                form.id() + ":1")));
        items.add(new MenuItem(20, button(Material.ARROW,
                dialogText("common.previous-step"), List.of(),
                "APPLICATION_CONTENT_FORM", form.id().toString())));
        items.add(new MenuItem(22, button(Material.WRITABLE_BOOK,
                dialogText("application.save"),
                List.of(dialogText("application.save-hint")),
                "SAVE_APPLICATION_DRAFT",
                form.id().toString())));
        openMenu(player, 27, dialogText("application.members-title"), items);
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
        items.add(new MenuItem(53, button(Material.ARROW,
                dialogText("common.back"), List.of(),
                "APPLICATION_MEMBERS_FORM", formId.toString())));
        openMenu(player, 54, memberIndex == 0
                ? dialogText("application.select-member-one-title")
                : dialogText("application.select-member-two-title"), items);
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
        renderApplicationMembersDialog(player, updated);
    }

    private void renderTownProfileDialog(Player player, ApplicationFormSession form) {
        List<DialogInput> inputs = List.of(
                DialogInput.text("description", 400,
                        dialogComponent("application.description-label"), true,
                        form.text().description(), 500,
                        TextDialogInput.MultilineOptions.create(6, 100)),
                DialogInput.text("rules", 400,
                        dialogComponent("application.rules-label"), true,
                        String.join("\n", form.text().rules()), 5000,
                        TextDialogInput.MultilineOptions.create(20, 170)));
        Component guidance = dialogComponent("application.profile-name",
                        Map.of("name", form.text().name()))
                .append(Component.newline())
                .append(dialogComponent("application.profile-guidance"));
        openDialogPage(player, dialogText("application.profile-title"),
                List.of(DialogBody.plainMessage(guidance, 420)),
                inputs, DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE,
                session -> DialogType.confirmation(
                        ActionButton.create(dialogComponent("common.save-changes"),
                                null, 170, dialogAction(player, session,
                                        response -> applyTownProfileDialog(
                                                player, form.id(), response))),
                        ActionButton.create(dialogComponent("common.cancel"),
                                null, 170, dialogAction(player, session,
                                        response -> cancelApplicationForm(player, form.id())))));
    }

    private void applyTownProfileDialog(Player player, UUID formId,
                                        DialogResponseView response) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        ApplicationText updated = updateField(form.text(), ApplicationField.DESCRIPTION,
                responseText(response, "description"));
        updated = updateField(updated, ApplicationField.RULES, responseText(response, "rules"));
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
                .orElseThrow(() -> new IllegalArgumentException("你不属于任何小镇")), account -> {
            List<String> description = new ArrayList<>(List.of(
                    dialogText("donation.town", Map.of("town", account.townName())),
                    dialogText("donation.balance", Map.of(
                            "balance", runtime.money(account.balanceMinor()))),
                    dialogText("donation.amount-hint", Map.of(
                            "scale", runtime.settlement().scale()))));
            if (error != null && !error.isBlank()) {
                description.add(dialogText("donation.error", Map.of("error", error)));
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
                            DialogType.confirmation(
                                    ActionButton.create(dialogComponent("donation.confirm"),
                                            dialogComponent("donation.confirm-tooltip"),
                                            170, dialogAction(player, session,
                                                    response -> applyDonationDialog(player, response))),
                                    ActionButton.create(dialogComponent("common.cancel"),
                                            null, 170,
                                            dialogAction(player, session, "FINANCE", "0"))));
        });
    }

    private void applyDonationDialog(Player player, DialogResponseView response) {
        String value = Objects.requireNonNullElse(response.getText("donation_amount"), "").strip();
        try {
            BigDecimal decimal = new BigDecimal(value);
            MoneyAmount amount = MoneyAmount.from(decimal, runtime.settlement().scale());
            if (!amount.positive()) {
                throw new IllegalArgumentException("捐款金额必须大于 0");
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
            openDonationDialog(player, dialogText("donation.invalid-amount"), value);
        } catch (IllegalArgumentException exception) {
            openDonationDialog(player, exception.getMessage(), value);
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
            if (form.purpose() == FormPurpose.APPLICATION) {
                requireInitialMemberIds(player, form.initialMemberNames());
            }
        } catch (IllegalArgumentException exception) {
            openNotice(player, dialogText("notice.draft-incomplete-title"),
                    exception.getMessage(), dialogText("common.back"),
                    "APPLICATION_MEMBERS_FORM", form.id().toString());
            return;
        }
        applicationForms.remove(player.getUniqueId(), form);
        if (form.purpose() == FormPurpose.TOWN_PROFILE) {
            actions.updateTownProfile(player, form.targetId(), form.text(), form.version(), outcome ->
                    handleOutcome(player, outcome, town -> {
                openNotice(player, dialogText("notice.profile-saved-title"),
                        dialogText("notice.profile-saved-message"),
                        dialogText("common.back"), "TOWN", town.id().toString());
            }));
            return;
        }
        List<UUID> initialMemberIds = requireInitialMemberIds(player,
                form.initialMemberNames());
        if (form.targetId() == null) {
            actions.createApplication(player, form.text(), initialMemberIds, outcome ->
                    handleOutcome(player, outcome, application -> {
                notifyInitialMembers(application);
                openNotice(player, dialogText("notice.draft-saved-title"),
                        plugin.messages().text("application.draft-saved"),
                        dialogText("common.continue-processing"),
                        "APPLICATION", application.id().toString());
            }));
        } else {
            actions.updateApplication(player, form.targetId(), form.text(), initialMemberIds,
                    form.version(), outcome ->
                    handleOutcome(player, outcome, application -> {
                notifyInitialMembers(application);
                openNotice(player, dialogText("notice.draft-saved-title"),
                        plugin.messages().text("application.draft-saved"),
                        dialogText("common.continue-processing"),
                        "APPLICATION", application.id().toString());
            }));
        }
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

    private static List<UUID> requireInitialMemberIds(Player applicant, List<String> names) {
        List<String> normalized = normalizedMemberNames(names);
        List<UUID> ids = new ArrayList<>(2);
        for (String name : normalized) {
            Player member = Bukkit.getPlayerExact(name);
            if (member == null) {
                throw new IllegalArgumentException("两名初始成员必须在线并使用准确玩家名");
            }
            if (member.getUniqueId().equals(applicant.getUniqueId())) {
                throw new IllegalArgumentException("初始成员不能包含申请人");
            }
            ids.add(member.getUniqueId());
        }
        if (ids.stream().distinct().count() != 2) {
            throw new IllegalArgumentException("必须填写两名不同的初始成员");
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
                .orElseThrow(() -> new IllegalArgumentException("申请不存在")), application -> {
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
                "town", application.text().name()));
        openDialogPage(member, dialogText("invitation.title"),
                List.of(DialogBody.plainMessage(message, 400)), List.of(),
                DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE, session ->
                        DialogType.confirmation(
                                ActionButton.create(dialogComponent("invitation.accept"),
                                        null, 170, dialogAction(member, session,
                                                response -> respondInitialMember(member,
                                                        application.id(), true))),
                                ActionButton.create(dialogComponent("invitation.reject"),
                                        null, 170, dialogAction(member, session,
                                                response -> respondInitialMember(member,
                                                        application.id(), false)))));
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
                                Map.of("member", member.getName()));
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
                errors.add("必须填写初始成员");
            } else {
                Player candidate = Bukkit.getPlayerExact(name);
                if (candidate == null) {
                    errors.add("初始成员必须在线并使用准确玩家名");
                } else if (candidate.getUniqueId().equals(player.getUniqueId())) {
                    errors.add("初始成员不能是申请人本人");
                }
            }
            if (!name.isBlank() && form.initialMemberNames().stream()
                    .filter(name::equalsIgnoreCase).count() > 1) {
                errors.add("两名初始成员不能相同");
            }
            return List.copyOf(errors);
        }
        return form.text().validate().stream().filter(error -> switch (field) {
            case NAME -> error.startsWith("名称");
            case RESIDENCE_NAME -> error.startsWith("小镇代码");
            case DESCRIPTION -> error.startsWith("简介");
            case RULES -> error.startsWith("规则");
            case INITIAL_MEMBER_ONE, INITIAL_MEMBER_TWO -> false;
        }).toList();
    }

    private static String preview(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum - 1) + "…";
    }

    private static <T> List<T> page(List<T> values, int page, int pageSize) {
        int start = Math.min(values.size(), Math.max(0, page) * pageSize);
        int end = Math.min(values.size(), start + pageSize);
        return values.subList(start, end);
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
                ? switch (outcome.result().reason()) {
                    case "FEATURE_DISABLED" -> "这项功能目前暂停使用。";
                    case "STORAGE_UNAVAILABLE" -> "小镇数据暂时不可用，请稍后再试。";
                    case "INSUFFICIENT_BALANCE" -> "余额不足，无法完成这项操作。";
                    case "FORBIDDEN" -> "你没有执行这项操作的权限。";
                    case "NOT_FOUND" -> "目标已经不存在，请刷新界面。";
                    default -> "暂时无法完成这项操作，请刷新后重试。";
                } : detail;
        openNotice(player, dialogText("notice.operation-failed-title"),
                plugin.messages().text("system.operation-failed", Map.of("detail", friendly)),
                dialogText("common.back"), "MAIN", null);
    }

    private void openConfirmation(Player player, String title, String confirmedAction,
                                  String target, String consequence, String returnAction,
                                  String returnTarget) {
        // 使用内部操作标识决定图标和不可撤销提示，避免自定义标题影响确认页行为。
        boolean disband = confirmedAction.equals("DISBAND");
        boolean irreversible = disband || confirmedAction.equals("CANCEL_VOTE");
        Material material = disband ? Material.TNT
                : confirmedAction.equals("EXPAND") ? Material.FILLED_MAP
                : confirmedAction.equals("BUY_BUFF") ? Material.POTION
                : confirmedAction.equals("REMOVE_VISITOR")
                        || confirmedAction.equals("KICK_MEMBER")
                        || confirmedAction.equals("CANCEL_VOTE") ? Material.BARRIER
                : Material.PAPER;
        ItemStack summary = button(material, title,
                List.of(consequence,
                        irreversible ? dialogText("confirmation.irreversible")
                                : dialogText("confirmation.check-details")),
                null, null);
        boolean applicationConfirmation = switch (confirmedAction) {
            case "SUBMIT", "CANCEL", "APPLY_JOIN", "CANCEL_JOIN" -> true;
            default -> false;
        };
        DialogBody summaryBody = disband || applicationConfirmation
                ? dialogTextBody(summary) : dialogBody(summary);
        openDialogPage(player, title, List.of(summaryBody), List.of(),
                DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE, session ->
                        DialogType.confirmation(
                                ActionButton.create(dialogComponent("common.confirm"),
                                        legacyComponent(consequence), 170,
                                        dialogAction(player, session, confirmedAction, target)),
                                ActionButton.create(dialogComponent("common.cancel"),
                                        null, 170,
                                        dialogAction(player, session, returnAction, returnTarget))));
    }

    private UUID openMenu(Player player, int size, String title, List<MenuItem> items) {
        List<MenuItem> ordered = items.stream()
                .sorted(java.util.Comparator.comparingInt(MenuItem::slot))
                .toList();
        title = dialogMenuTitle(title);
        String plainTitle = PlainTextComponentSerializer.plainText().serialize(
                legacyComponent(title));
        List<DialogBody> bodies = ordered.stream()
                .filter(item -> itemAction(item.item()) == null)
                .map(item -> plainTitle.equals("小镇服务") || plainTitle.equals("小镇详情")
                        || plainTitle.equals("小镇申请摘要") || plainTitle.equals("公共资产")
                        || plainTitle.startsWith("小镇账本") || plainTitle.equals("公共 Buff 商店")
                        || plainTitle.equals("成员治理") || plainTitle.startsWith("小镇治理投票")
                        || plainTitle.equals("访客管理") || plainTitle.startsWith("小镇访客")
                        || plainTitle.startsWith("邀请访客")
                        || plainTitle.startsWith("申请加入")
                        || plainTitle.startsWith("入镇申请") || plainTitle.equals("待办中心")
                        || plainTitle.equals("个人与帮助") || plainTitle.startsWith("申请审核")
                        || plainTitle.startsWith("审核")
                        ? dialogTextBody(item.item()) : dialogBody(item.item()))
                .toList();
        List<MenuItem> actions = ordered.stream()
                .filter(item -> itemAction(item.item()) != null)
                .toList();
        // 返回项改用 Dialog 底部导航，避免与内容操作重复显示。
        MenuItem returnItem = actions.stream()
                .filter(item -> isBackButton(item.item()))
                .findFirst().orElse(null);
        List<MenuItem> contentActions = returnItem == null ? actions : actions.stream()
                .filter(item -> item != returnItem)
                .toList();
        return openDialogPage(player, title, bodies, List.of(),
                DialogBase.DialogAfterAction.NONE, session -> {
                    ActionButton exit = returnItem == null
                            ? exitButton(player, session, dialogText("common.close"),
                                    dialogText("common.close-tooltip"))
                            : returnButton(player, session, returnItem.item());
                    if (contentActions.isEmpty()) {
                        return DialogType.notice(exit);
                    }
                    List<ActionButton> buttons = contentActions.stream()
                            .map(item -> dialogButton(player, item.item(), session))
                            .toList();
                    return DialogType.multiAction(buttons)
                            .exitAction(exit)
                            .columns(buttons.size() == 1 ? 1 : 2)
                            .build();
                });
    }

    private UUID openDialogPage(Player player, String title, List<? extends DialogBody> bodies,
                                List<? extends DialogInput> inputs,
                                DialogBase.DialogAfterAction afterAction,
                                Function<UUID, DialogType> typeFactory) {
        if (!active.get()) {
            throw new IllegalStateException("玩家界面已经关闭");
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
        openDialogPage(player, title,
                List.of(DialogBody.plainMessage(legacyComponent(message), 380)), List.of(),
                DialogBase.DialogAfterAction.NONE, session -> DialogType.notice(
                        ActionButton.create(legacyComponent(actionLabel),
                                null, 200, dialogAction(player, session, action, target))));
    }

    private DialogBody dialogBody(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        Component description = meta != null && meta.hasDisplayName() && meta.displayName() != null
                ? meta.displayName() : Component.text(item.getType().name());
        Component tooltip = dialogTooltip(meta);
        if (tooltip != null) {
            description = description.append(Component.newline()).append(tooltip);
        }
        return DialogBody.item(item, DialogBody.plainMessage(description, 360),
                false, false, 48, 48);
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

    private ActionButton exitButton(Player player, UUID session, String label, String tooltip) {
        return ActionButton.create(legacyComponent(label), legacyComponent(tooltip), 140,
                dialogAction(player, session, "CLOSE", null));
    }

    private ActionButton returnButton(Player player, UUID session, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        String action = itemAction(item);
        String target = meta == null ? null : meta.getPersistentDataContainer()
                .get(targetKey, PersistentDataType.STRING);
        // 返回按钮只显示统一文案，具体导航目标仍从原菜单项的动作数据中读取。
        return ActionButton.create(dialogComponent("common.back"),
                dialogComponent("common.back-tooltip"), 140,
                dialogAction(player, session, action, target));
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

    private static boolean isBackButton(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        Component name = meta == null ? null : meta.displayName();
        if (name == null) {
            return false;
        }
        String label = PlainTextComponentSerializer.plainText().serialize(name).strip();
        return label.startsWith("返回") || label.equals("上一步");
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

    private record FinanceView(EconomyRepository.TownFinance account) {
    }

    private record LedgerPage(EconomyRepository.TownFinance account,
                              List<EconomyRepository.LedgerEntry> entries, int page) {
    }

    private static GridTarget gridTarget(String target) {
        String[] coordinates = target == null ? new String[0] : target.split(",", -1);
        if (coordinates.length != 2) {
            throw new IllegalArgumentException("领地网格坐标无效");
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

    private record MemberPage(TownSnapshot.Page page, MemberGovernanceSnapshot governance) {
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
                throw new IllegalArgumentException("服务台 ID 为空");
            }
            Objects.requireNonNull(worldId, "worldId");
            if (worldName == null || worldName.isBlank()) {
                throw new IllegalArgumentException("服务台世界名为空");
            }
        }

        boolean sameLocation(UUID candidateWorld, int candidateX, int candidateY, int candidateZ) {
            return worldId.equals(candidateWorld) && x == candidateX && y == candidateY
                    && z == candidateZ;
        }
    }

    private enum ApplicationField {
        NAME("小镇名称", "2–24 个文字、数字、空格、下划线、连字符或间隔点",
                "使用易于辨认的完整中文名称"),
        RESIDENCE_NAME("小镇代码", "必须为 1–12 个英文字母，不允许空格、数字和特殊符号",
                "优先使用三个英文字母，例如 SKY"),
        DESCRIPTION("小镇简介", "最多 500 个字符，不允许格式代码或 MiniMessage 标签",
                "简要说明定位、风格和发展目标"),
        RULES("小镇规则", "至少 1 条、最多 50 条；多条规则使用竖线 | 分隔",
                "每条规则保持简短清晰"),
        INITIAL_MEMBER_ONE("初始成员一", "必须填写一名在线且无镇籍的其他玩家",
                "先与玩家沟通，保存后系统会发送确认按钮"),
        INITIAL_MEMBER_TWO("初始成员二", "必须填写另一名在线且无镇籍的其他玩家",
                "两名成员都确认后才可提交申请");

        private final String label;
        private final String requirement;
        private final String suggestion;

        ApplicationField(String label, String requirement, String suggestion) {
            this.label = label;
            this.requirement = requirement;
            this.suggestion = suggestion;
        }

        String label() {
            return label;
        }

        String requirement() {
            return requirement;
        }

        String suggestion() {
            return suggestion;
        }
    }

}

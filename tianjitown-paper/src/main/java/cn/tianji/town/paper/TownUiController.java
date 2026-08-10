package cn.tianji.town.paper;

import cn.tianji.town.core.application.ApplicationStatus;
import cn.tianji.town.core.application.ApplicationText;
import cn.tianji.town.core.ports.LandProtectionService;
import cn.tianji.town.core.town.MemberRole;
import cn.tianji.town.core.town.TownStatus;
import cn.tianji.town.core.governance.VoteType;
import cn.tianji.town.storage.phase1.ApplicationSnapshot;
import cn.tianji.town.storage.phase1.JoinApplicationSnapshot;
import cn.tianji.town.storage.phase1.PhaseOneRepository;
import cn.tianji.town.storage.phase1.TownSnapshot;
import cn.tianji.town.storage.phase2.MemberGovernanceSnapshot;
import cn.tianji.town.storage.phase2.TransferSnapshot;
import cn.tianji.town.storage.phase2.VoteSnapshot;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class TownUiController implements Listener {
    private static final int MENU_TIMEOUT_TICKS = 20 * 60;
    private final TianjiTownPlugin plugin;
    private final PhaseOneRuntime runtime;
    private final SitePolicy sitePolicy;
    private final NamespacedKey stationKey;
    private final NamespacedKey handbookKey;
    private final NamespacedKey actionKey;
    private final NamespacedKey sessionKey;
    private final NamespacedKey targetKey;
    private final Map<UUID, UUID> menuSessions = new HashMap<>();
    private final Map<UUID, FormSession> formSessions = new HashMap<>();
    private final Map<UUID, ApplicationFormSession> applicationForms = new ConcurrentHashMap<>();
    private final Map<UUID, ChatInputSession> chatInputs = new ConcurrentHashMap<>();
    private final Map<UUID, ReviewReasonSession> reviewReasonInputs = new ConcurrentHashMap<>();

    TownUiController(TianjiTownPlugin plugin, PhaseOneRuntime runtime) {
        this.plugin = plugin;
        this.runtime = runtime;
        this.sitePolicy = runtime.sitePolicy();
        this.stationKey = new NamespacedKey(plugin, "service_station");
        this.handbookKey = new NamespacedKey(plugin, "handbook");
        this.actionKey = new NamespacedKey(plugin, "gui_action");
        this.sessionKey = new NamespacedKey(plugin, "session_id");
        this.targetKey = new NamespacedKey(plugin, "target_id");
    }

    boolean createStation(Player player) {
        Block block = player.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof Lectern lectern)) {
            player.sendMessage("§c请看向 6 格内的讲台后重试。");
            return false;
        }
        String existingId = lectern.getPersistentDataContainer().get(stationKey,
                PersistentDataType.STRING);
        if (existingId != null && !existingId.isBlank()) {
            registerStation(block, existingId);
            player.sendMessage("§e该讲台已经是小镇服务台，未重复创建。ID: " + existingId);
            return false;
        }
        String stationId = UUID.randomUUID().toString();
        lectern.getPersistentDataContainer().set(stationKey, PersistentDataType.STRING, stationId);
        lectern.update(true);
        registerStation(block, stationId);
        player.sendMessage("§a已创建小镇服务台，ID: " + stationId);
        return true;
    }

    boolean removeStation(Player player) {
        Block block = player.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof Lectern lectern)) {
            player.sendMessage("§c请看向 6 格内的小镇服务台后重试。");
            return false;
        }
        String stationId = lectern.getPersistentDataContainer().get(stationKey,
                PersistentDataType.STRING);
        if (stationId == null || stationId.isBlank()) {
            player.sendMessage("§c当前讲台未注册为小镇服务台。");
            return false;
        }
        lectern.getPersistentDataContainer().remove(stationKey);
        lectern.update(true);
        unregisterStation(block, stationId);
        player.sendMessage("§a已移除小镇服务台，ID: " + stationId
                + "；该讲台不再触发小镇菜单。");
        return true;
    }

    void showStationInfo(Player player) {
        Block block = player.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof Lectern lectern)) {
            player.sendMessage("§c请看向 6 格内的讲台后重试。");
            return;
        }
        String stationId = lectern.getPersistentDataContainer().get(stationKey,
                PersistentDataType.STRING);
        if (stationId == null || stationId.isBlank()) {
            player.sendMessage("§e当前讲台未注册为小镇服务台。位置: "
                    + stationLocation(block));
            return;
        }
        registerStation(block, stationId);
        player.sendMessage("§6小镇服务台详情");
        player.sendMessage("§7ID: §f" + stationId);
        player.sendMessage("§7位置: §f" + stationLocation(block));
        player.sendMessage("§7状态: §a有效");
    }

    void listStations(CommandSender sender) {
        List<StationRecord> stations = stationRecords();
        sender.sendMessage("§6小镇服务台列表（" + stations.size() + "）");
        if (stations.isEmpty()) {
            sender.sendMessage("§7当前没有已登记的服务台。");
            return;
        }
        for (StationRecord station : stations) {
            String location = station.worldName() + " " + station.x() + "," + station.y()
                    + "," + station.z();
            if (sender instanceof Player player) {
                player.sendMessage(Component.text(station.id() + " " + location + " ["
                                + plainStationStatus(station) + "] ", NamedTextColor.YELLOW)
                        .append(callbackButton(player, "[传送]",
                                () -> teleportToStation(player, station))));
            } else {
                sender.sendMessage("§e" + station.id() + " §7" + location + " §8["
                        + stationStatus(station) + "§8]");
            }
        }
    }

    private void teleportToStation(Player player, StationRecord station) {
        World world = Bukkit.getWorld(station.worldId());
        if (world == null) {
            world = Bukkit.getWorld(station.worldName());
        }
        if (world == null) {
            player.sendMessage("§c服务台所在世界当前未加载。");
            return;
        }
        Block block = world.getBlockAt(station.x(), station.y(), station.z());
        if (!(block.getState() instanceof Lectern lectern)
                || !station.id().equals(lectern.getPersistentDataContainer().get(
                stationKey, PersistentDataType.STRING))) {
            player.sendMessage("§c服务台方块已变化或登记不一致，无法传送。");
            return;
        }
        org.bukkit.Location destination = block.getLocation().add(0.5, 1.0, 0.5);
        destination.setYaw(player.getYaw());
        destination.setPitch(player.getPitch());
        if (player.teleport(destination)) {
            player.sendMessage("§a已传送至小镇服务台。");
        } else {
            player.sendMessage("§c无法传送至小镇服务台。");
        }
    }

    private String plainStationStatus(StationRecord station) {
        return stationStatus(station).replace("§a", "");
    }

    private void registerStation(Block block, String stationId) {
        List<StationRecord> stations = new ArrayList<>(stationRecords());
        stations.removeIf(station -> station.id().equals(stationId)
                || station.sameLocation(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()));
        stations.add(new StationRecord(stationId, block.getWorld().getUID(), block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ()));
        saveStationRecords(stations);
    }

    private void unregisterStation(Block block, String stationId) {
        List<StationRecord> stations = new ArrayList<>(stationRecords());
        stations.removeIf(station -> station.id().equals(stationId)
                || station.sameLocation(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()));
        saveStationRecords(stations);
    }

    private List<StationRecord> stationRecords() {
        List<StationRecord> result = new ArrayList<>();
        for (Map<?, ?> raw : plugin.getConfig().getMapList("phase1.service-stations")) {
            try {
                result.add(new StationRecord(String.valueOf(raw.get("id")),
                        UUID.fromString(String.valueOf(raw.get("world-uuid"))),
                        String.valueOf(raw.get("world")), number(raw, "x"), number(raw, "y"),
                        number(raw, "z")));
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
            serialized.add(value);
        }
        plugin.getConfig().set("phase1.service-stations", serialized);
        plugin.saveConfig();
    }

    private String stationStatus(StationRecord station) {
        World world = Bukkit.getWorld(station.worldId());
        if (world == null) {
            world = Bukkit.getWorld(station.worldName());
        }
        if (world == null) {
            return "世界未加载";
        }
        if (!world.isChunkLoaded(station.x() >> 4, station.z() >> 4)) {
            return "区块未加载";
        }
        Block block = world.getBlockAt(station.x(), station.y(), station.z());
        if (!(block.getState() instanceof Lectern lectern)) {
            return "方块已变化";
        }
        String actualId = lectern.getPersistentDataContainer().get(stationKey,
                PersistentDataType.STRING);
        return station.id().equals(actualId) ? "§a有效" : "登记不一致";
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

    void giveHandbook(Player player) {
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
        player.sendMessage("§a已领取小镇手册。");
    }

    void openMain(Player player) {
        if (maintenanceMode()) {
            player.sendMessage("§c小镇系统正在维护，玩家操作暂时停用。");
            return;
        }
        UUID request = openMenu(player, 9, "小镇服务 · 正在读取", List.of());
        runtime.read(player, () -> {
            runtime.governance().recordActivity(player.getUniqueId());
            return new MainView(runtime.repository().dashboard(player.getUniqueId()),
                    runtime.governance().dashboard(player.getUniqueId()).orElse(null),
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
                player.sendMessage(Component.text("小镇规则已有更新，请阅读并重新确认后继续使用小镇菜单。 ",
                                NamedTextColor.YELLOW)
                        .append(callbackButton(player, "[查看新规则]", () -> openMain(player))));
            }
            if (governance.pendingTransfer() != null) {
                player.sendMessage(Component.text("镇长邀请你接任“" + governance.townName() + "”。 ",
                                NamedTextColor.GOLD)
                        .append(callbackButton(player, "[处理转让]", () -> openMain(player))));
            }
            long pendingVotes = governance.votes().stream()
                    .filter(vote -> vote.viewerEligible() && !vote.viewerVoted()).count();
            if (pendingVotes > 0) {
                player.sendMessage(Component.text("你有 " + pendingVotes + " 个小镇治理投票待处理。 ",
                                NamedTextColor.AQUA)
                        .append(callbackButton(player, "[前往投票]", () -> openMain(player))));
            }
        });
    }

    void previewTownForAdmin(Player player, TownSnapshot town) {
        sitePolicy.preview(player, town.territory());
    }

    void showAdminApplicationList(CommandSender sender, List<ApplicationSnapshot> applications) {
        sender.sendMessage("§6待处理申请: " + applications.size());
        for (ApplicationSnapshot application : applications) {
            sender.sendMessage("§e" + application.text().name() + " §7["
                    + application.status() + "] 申请人=" + application.applicantId());
            if (sender instanceof Player admin) {
                admin.sendMessage(callbackButton(admin, "[打开审核界面]",
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
                applicant.sendMessage(Component.text("你的小镇“" + application.text().name()
                                + "”已获批准并创建完成 ", NamedTextColor.GREEN)
                        .append(callbackButton(applicant, "[打开小镇系统]",
                                () -> openMain(applicant))));
                playSound(applicant, Sound.ENTITY_PLAYER_LEVELUP);
            }
            case NEED_CHANGES -> {
                applicant.sendMessage(Component.text("你的小镇申请需要补充资料："
                                + Objects.requireNonNullElse(application.reviewMessage(), "请查看申请详情") + " ",
                        NamedTextColor.YELLOW).append(callbackButton(applicant, "[修改申请]",
                        () -> loadApplication(applicant, application.id()))));
                playSound(applicant, Sound.BLOCK_NOTE_BLOCK_PLING);
            }
            case REJECTED -> {
                applicant.sendMessage(Component.text("你的小镇申请已被拒绝："
                                + Objects.requireNonNullElse(application.reviewMessage(), "未提供原因") + " ",
                        NamedTextColor.RED).append(callbackButton(applicant, "[打开小镇系统]",
                        () -> openMain(applicant))));
                playSound(applicant, Sound.ENTITY_VILLAGER_NO);
            }
            case PROVISION_FAILED -> {
                applicant.sendMessage(Component.text("你的小镇已通过审核，但自动创建暂时失败；管理员会处理。 ",
                                NamedTextColor.RED)
                        .append(callbackButton(applicant, "[查看状态]", () -> openMain(applicant))));
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
        if (rightClick && item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(handbookKey,
                PersistentDataType.BYTE)) {
            Player player = event.getPlayer();
            event.setCancelled(true);
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
            // 客户端会在本次交互结束时尝试打开成书，下一刻再打开菜单以覆盖该界面。
            plugin.getServer().getScheduler().runTask(plugin, () -> openMain(player));
            return;
        }
        if (event.isCancelled()) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block != null && block.getState() instanceof Lectern lectern
                && lectern.getPersistentDataContainer().has(stationKey, PersistentDataType.STRING)) {
            event.setCancelled(true);
            openMain(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta() || !isCurrent(player, holder.sessionId())) {
            return;
        }
        PersistentDataContainer data = clicked.getItemMeta().getPersistentDataContainer();
        String session = data.get(sessionKey, PersistentDataType.STRING);
        String action = data.get(actionKey, PersistentDataType.STRING);
        if (!holder.sessionId().toString().equals(session) || action == null) {
            return;
        }
        String target = data.get(targetKey, PersistentDataType.STRING);
        handleAction(player, action, target);
    }

    @EventHandler
    public void onMenuClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder holder) {
            menuSessions.remove(event.getPlayer().getUniqueId(), holder.sessionId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        menuSessions.remove(event.getPlayer().getUniqueId());
        formSessions.remove(event.getPlayer().getUniqueId());
        applicationForms.remove(event.getPlayer().getUniqueId());
        chatInputs.remove(event.getPlayer().getUniqueId());
        reviewReasonInputs.remove(event.getPlayer().getUniqueId());
        sitePolicy.stopPreview(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChatInput(AsyncChatEvent event) {
        Player player = event.getPlayer();
        ChatInputSession input = chatInputs.remove(player.getUniqueId());
        ReviewReasonSession review = input == null
                ? reviewReasonInputs.remove(player.getUniqueId()) : null;
        if (input == null && review == null) {
            return;
        }
        event.setCancelled(true);
        String value = PlainTextComponentSerializer.plainText().serialize(event.message()).strip();
        if (input != null) {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> applyChatInput(player, input, value));
        } else {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> applyReviewReason(player, review, value));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBookEdit(PlayerEditBookEvent event) {
        if (!event.isSigning()) {
            return;
        }
        Player player = event.getPlayer();
        if (maintenanceMode()) {
            event.setCancelled(true);
            player.sendMessage("§c小镇系统正在维护，表单未提交。");
            return;
        }
        FormSession form = formSessions.get(player.getUniqueId());
        String itemSession = event.getPreviousBookMeta().getPersistentDataContainer()
                .get(sessionKey, PersistentDataType.STRING);
        if (form == null || !form.id().toString().equals(itemSession)) {
            return;
        }
        ApplicationText parsed;
        try {
            parsed = parseForm(event.getNewBookMeta(), form);
            parsed.requireValid();
        } catch (IllegalArgumentException exception) {
            event.setCancelled(true);
            player.sendMessage("§c表单未提交: " + exception.getMessage());
            return;
        }
        formSessions.remove(player.getUniqueId());
        runtime.write(player, () -> runtime.repository().updateTownProfile(form.targetId(),
                parsed, form.version(), player.getUniqueId(), player.getName(),
                "镇长通过书本界面修改简介和规则"), town -> {
            removeFormBook(player, form.id());
            player.sendMessage("§a小镇简介和规则已保存。");
            openTown(player, town.id());
        });
    }

    private void renderMain(Player player, MainView view) {
        PhaseOneRepository.PlayerDashboard dashboard = view.dashboard();
        MemberGovernanceSnapshot governance = view.governance();
        if (governance != null && governance.requiresRulesConfirmation()) {
            renderRulesConfirmation(player, governance);
            return;
        }
        List<MenuItem> items = new ArrayList<>();
        if (player.hasPermission("tianjitown.admin")) {
            boolean pending = !view.reviewQueue().isEmpty();
            items.add(new MenuItem(4, button(pending ? Material.ENCHANTED_BOOK : Material.BOOK,
                    pending ? "§e待审核申请 · " + view.reviewQueue().size() : "§7申请审核 · 暂无待办",
                    pending ? List.of("§6有未完成审批，点击处理") : List.of("§7点击查看申请列表"),
                    "ADMIN_APPLICATIONS", null)));
        }
        if (dashboard.town() != null) {
            TownSnapshot town = dashboard.town();
            items.add(new MenuItem(10, button(Material.BELL, "§a" + town.profile().name(),
                    List.of("§7查看小镇资料与成员"), "TOWN", town.id().toString())));
            if (governance != null && governance.canReviewApplications()) {
                int pending = dashboard.incomingJoinApplications().size();
                items.add(new MenuItem(12, button(pending > 0 ? Material.ENCHANTED_BOOK : Material.BOOK,
                        pending > 0 ? "§e入镇申请 · " + pending : "§7入镇申请 · 暂无待办",
                        List.of("§7查看并审批玩家的入镇申请"), "JOIN_APPLICATIONS",
                        town.id().toString())));
            }
            if (town.mayorId().equals(player.getUniqueId())) {
                items.add(new MenuItem(14, button(Material.WRITABLE_BOOK, "§e修改简介和规则",
                        List.of("§7名称和简称需要管理员代办"), "EDIT_TOWN", town.id().toString())));
                items.add(new MenuItem(16, button(Material.GOLDEN_HELMET, "§6成员治理",
                        List.of("§7设置官员、移除成员和发起镇长转让"),
                        "MEMBERS", town.id() + ":0")));
                items.add(new MenuItem(24, button(Material.TNT, "§4解散小镇",
                        List.of("§c仅剩镇长一人时可执行", "§c需要再次确认"),
                        "CONFIRM_DISBAND", town.id() + ":" + town.version())));
            } else {
                items.add(new MenuItem(24, button(Material.OAK_DOOR, "§c退出小镇",
                        List.of("§7需要再次确认"), "CONFIRM_LEAVE", town.id().toString())));
            }
            if (governance != null && governance.pendingTransfer() != null) {
                items.add(new MenuItem(20, button(Material.NETHER_STAR, "§e镇长转让待确认",
                        List.of("§7镇长邀请你接任本镇", "§7点击接受或拒绝"),
                        "TRANSFER_REQUEST", governance.pendingTransfer().id().toString())));
            }
            int pendingVotes = governance == null ? 0 : (int) governance.votes().stream()
                    .filter(vote -> vote.viewerEligible() && !vote.viewerVoted()).count();
            items.add(new MenuItem(22, button(pendingVotes > 0 ? Material.ENCHANTED_BOOK : Material.BOOK,
                    pendingVotes > 0 ? "§b治理投票 · 待处理 " + pendingVotes : "§e治理投票",
                    List.of("§7查看、发起和参与成员治理投票"), "VOTES", town.id().toString())));
        } else if (dashboard.application() != null) {
            ApplicationSnapshot application = dashboard.application();
            items.add(new MenuItem(11, button(Material.MAP, "§e继续小镇申请",
                    List.of("§7状态: " + application.status(),
                            application.reviewMessage() == null ? "§7点击查看摘要"
                                    : "§c管理员意见: " + application.reviewMessage()),
                    "APPLICATION", application.id().toString())));
        } else {
            if (dashboard.joinApplications().isEmpty()) {
                items.add(new MenuItem(11, button(Material.WRITABLE_BOOK, "§a申请建立小镇",
                        List.of("§7在聊天栏点击并填写各项申请资料"),
                        "CREATE_APPLICATION", null)));
            }
            items.add(new MenuItem(13, button(Material.COMPASS, "§a申请加入小镇",
                    List.of("§7浏览小镇并提交 48 小时有效申请"), "JOIN_TOWNS", null)));
            if (!dashboard.joinApplications().isEmpty()) {
                items.add(new MenuItem(15, button(Material.PAPER, "§e我的入镇申请",
                        List.of("§7待处理: " + dashboard.joinApplications().size(),
                                "§7同时最多申请 3 个小镇"), "MY_JOIN_APPLICATIONS", null)));
            }
        }
        items.add(new MenuItem(31, button(Material.WRITTEN_BOOK, "§6领取小镇手册",
                List.of("§7手册丢失后可在服务台重新领取"), "GIVE_HANDBOOK", null)));
        openMenu(player, 36, "小镇服务", items);
    }

    private void renderRulesConfirmation(Player player, MemberGovernanceSnapshot governance) {
        List<String> lore = new ArrayList<>();
        lore.add("§7小镇: " + governance.townName());
        lore.add("§7新规则版本: " + governance.townRulesRevision());
        for (int index = 0; index < governance.rules().size(); index++) {
            lore.add("§f" + (index + 1) + ". " + governance.rules().get(index));
        }
        lore.add("§c确认前不能继续使用其他小镇功能");
        List<MenuItem> items = List.of(
                new MenuItem(4, button(Material.WRITTEN_BOOK, "§6规则变更", lore, null, null)),
                new MenuItem(13, button(Material.LIME_CONCRETE, "§a我已阅读并确认",
                        List.of("§7记录本次确认的规则版本"), "ACK_RULES",
                        governance.townId() + ":" + governance.townRulesRevision())));
        openMenu(player, 27, "确认小镇规则更新", items);
    }

    private void openApplication(Player player, ApplicationSnapshot application) {
        List<String> summary = new ArrayList<>(List.of("§7名称: " + application.text().name(),
                "§7简称: " + application.text().shortName(),
                "§7领地名称: " + application.text().residenceName(),
                "§7状态: " + application.status()));
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
                    List.of("§7在聊天栏预览并点击修改项目"), "EDIT_APPLICATION",
                    application.id().toString())));
            items.add(new MenuItem(12, button(Material.COMPASS, "§e选择当前区块",
                    List.of("§7当前区块将成为 3×3 初始领地中心"), "SELECT_SITE",
                    application.id().toString())));
            if (application.territory() != null) {
                items.add(new MenuItem(14, button(Material.ENDER_EYE, "§b预览已选领地",
                        List.of("§7传送至领地中心并显示火焰边界"), "PREVIEW_SITE",
                        application.id().toString())));
                items.add(new MenuItem(16, button(Material.LIME_CONCRETE, "§a提交申请",
                        List.of("§7进入确认页面"), "CONFIRM_SUBMIT",
                        application.id().toString())));
            }
            items.add(new MenuItem(22, button(Material.BARRIER, "§c撤回申请",
                    List.of("§7撤回后进入申请冷却"), "CONFIRM_CANCEL",
                    application.id().toString())));
        } else if (application.status() == ApplicationStatus.SUBMITTED
                || application.status() == ApplicationStatus.UNDER_REVIEW) {
            items.add(new MenuItem(22, button(Material.BARRIER, "§c撤回申请",
                    List.of("§7批准建镇前仍可撤回"), "CONFIRM_CANCEL",
                    application.id().toString())));
        }
        items.add(new MenuItem(26, button(Material.ARROW, "§7返回", List.of(), "MAIN", null)));
        openMenu(player, 27, "小镇申请摘要", items);
    }

    private void openTown(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException("小镇不存在")), town -> {
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(4, button(Material.BELL, "§6" + town.profile().name(),
                    List.of("§7简称: " + town.profile().shortName(),
                            "§7简介: " + town.profile().description(),
                            "§7规则数: " + town.profile().rules().size(),
                            "§7状态: " + town.status()), null, null)));
            items.add(new MenuItem(12, button(Material.PLAYER_HEAD, "§e成员列表",
                    List.of("§7分页查看全部成员"), "MEMBERS", town.id() + ":0")));
            if (town.territory() != null) {
                items.add(new MenuItem(14, button(Material.MAP, "§e初始领地",
                        List.of("§7固定 3×3 区块", "§7中心: "
                                + town.territory().center().x() + ", "
                                + town.territory().center().z(), "§a点击传送并显示火焰边界"),
                        "PREVIEW_TOWN",
                        town.id().toString())));
            }
            items.add(new MenuItem(26, button(Material.ARROW, "§7返回", List.of(), "MAIN", null)));
            openMenu(player, 27, "小镇详情", items);
        });
    }

    private void openMembers(Player player, UUID townId, int page) {
        runtime.read(player, () -> new MemberPage(runtime.repository().listMembers(townId, page, 45),
                runtime.governance().dashboard(player.getUniqueId()).orElse(null)), view -> {
            List<MenuItem> items = new ArrayList<>();
            int slot = 0;
            for (TownSnapshot.Member member : view.page().members()) {
                String name = Objects.requireNonNullElse(Bukkit.getOfflinePlayer(member.playerId()).getName(),
                        member.playerId().toString());
                String color = switch (member.role()) {
                    case MAYOR -> "§6";
                    case OFFICER -> "§a";
                    case MEMBER -> "§f";
                };
                boolean sameTown = view.governance() != null
                        && view.governance().townId().equals(townId);
                items.add(new MenuItem(slot++, button(Material.PLAYER_HEAD,
                        color + name,
                        List.of("§7身份: " + member.role(), "§7加入: " + member.joinedAt(),
                                sameTown ? "§e点击查看治理操作" : "§7只读"),
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
                player.sendMessage("§c只能管理自己小镇的成员。");
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
                MemberRole nextRole = view.targetRole() == MemberRole.OFFICER
                        ? MemberRole.MEMBER : MemberRole.OFFICER;
                items.add(new MenuItem(10, button(Material.GOLDEN_HELMET,
                        nextRole == MemberRole.OFFICER ? "§a任命为官员" : "§e降为普通成员",
                        List.of("§7官员可审核入镇申请"), "CONFIRM_ROLE",
                        townId + ":" + targetId + ":" + nextRole)));
                items.add(new MenuItem(12, button(Material.RED_CONCRETE, "§c镇长移除成员",
                        List.of("§c立即移出小镇并同步 Residence", "§7需要再次确认"),
                        "CONFIRM_KICK_MEMBER", townId + ":" + targetId)));
                items.add(new MenuItem(14, button(Material.NETHER_STAR, "§e发起镇长转让",
                        List.of("§7候选成员必须在 24 小时内接受"), "CONFIRM_TRANSFER_MAYOR",
                        townId + ":" + targetId)));
            }
            if (!targetIsMayor && !targetId.equals(player.getUniqueId())) {
                items.add(new MenuItem(16, button(Material.PAPER, "§e发起投票移除",
                        List.of("§7赞成票必须严格超过有效选民的 50%"),
                        "CONFIRM_CREATE_VOTE", townId + ":KICK_MEMBER:" + targetId)));
            }
            if (!targetIsMayor) {
                items.add(new MenuItem(22, button(Material.ENCHANTED_BOOK, "§e提名为新镇长",
                        List.of("§7发起 2/3 强制更换镇长投票"),
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
                player.sendMessage("§c镇长转让请求已经失效。");
                openMain(player);
                return;
            }
            List<MenuItem> items = List.of(
                    new MenuItem(4, button(Material.NETHER_STAR, "§6接任镇长邀请",
                            List.of("§7小镇: " + governance.townName(),
                                    "§7有效期至: " + transfer.expiresAt(),
                                    "§c接受后原镇长降为普通成员"), null, null)),
                    new MenuItem(11, button(Material.LIME_CONCRETE, "§a接受并接任",
                            List.of("§7需要再次确认"), "CONFIRM_TRANSFER_DECISION",
                            transfer.id() + ":true")),
                    new MenuItem(15, button(Material.RED_CONCRETE, "§c拒绝",
                            List.of("§7本次请求将立即关闭"), "CONFIRM_TRANSFER_DECISION",
                            transfer.id() + ":false")),
                    new MenuItem(22, button(Material.ARROW, "§7返回", List.of(), "MAIN", null)));
            openMenu(player, 27, "镇长转让确认", items);
        });
    }

    private void openVotes(Player player, UUID townId) {
        runtime.read(player, () -> runtime.governance().listTownVotes(townId,
                player.getUniqueId(), true), votes -> {
            List<MenuItem> items = new ArrayList<>();
            for (int index = 0; index < Math.min(votes.size(), 45); index++) {
                VoteSnapshot vote = votes.get(index);
                String target = vote.type() == VoteType.KICK_MEMBER
                        ? displayName(vote.subjectId()) : displayName(vote.candidateId());
                boolean pending = vote.viewerEligible() && !vote.viewerVoted();
                items.add(new MenuItem(index, button(
                        pending ? Material.ENCHANTED_BOOK : Material.PAPER,
                        (pending ? "§b待投票 · " : "§e") + voteLabel(vote.type()) + " · " + target,
                        List.of("§7赞成: " + vote.yesVotes() + "/" + vote.requiredYes(),
                                "§7反对: " + vote.noVotes(), "§7到期: " + vote.endsAt()),
                        "VOTE_DETAIL", vote.id().toString())));
            }
            if (votes.isEmpty()) {
                items.add(new MenuItem(22, button(Material.PAPER, "§7暂无进行中的投票",
                        List.of("§7从成员列表选择目标后可发起治理投票"), null, null)));
            }
            items.add(new MenuItem(48, button(Material.ARROW, "§7返回主菜单", List.of(),
                    "MAIN", null)));
            openMenu(player, 54, "小镇治理投票", items);
        });
    }

    private void openVote(Player player, UUID voteId) {
        runtime.read(player, () -> runtime.governance().dashboard(player.getUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("你不属于任何小镇")), governance -> {
            VoteSnapshot vote = governance.votes().stream().filter(item -> item.id().equals(voteId))
                    .findFirst().orElse(null);
            if (vote == null) {
                player.sendMessage("§e投票不存在或已经结束。");
                openVotes(player, governance.townId());
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
                        List.of("§7每个 UUID 只能投一次"), "CAST_VOTE", vote.id() + ":true")));
                items.add(new MenuItem(15, button(Material.RED_CONCRETE, "§c反对",
                        List.of("§7每个 UUID 只能投一次"), "CAST_VOTE", vote.id() + ":false")));
            } else {
                items.add(new MenuItem(13, button(Material.GRAY_DYE,
                        vote.viewerVoted() ? "§7你已经投票" : "§7你不在冻结选民快照中",
                        List.of(), null, null)));
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
                playerId.toString());
    }

    private void openJoinTowns(Player player) {
        runtime.read(player, () -> runtime.repository().listTowns(false).stream()
                .filter(town -> town.status() == TownStatus.ACTIVE)
                .toList(), towns -> {
            List<MenuItem> items = new ArrayList<>();
            for (int index = 0; index < Math.min(towns.size(), 45); index++) {
                TownSnapshot town = towns.get(index);
                items.add(new MenuItem(index, button(Material.BELL, "§6" + town.profile().name(),
                        List.of("§7简称: " + town.profile().shortName(),
                                "§7简介: " + preview(town.profile().description(), 80),
                                "§7点击查看规则并申请加入"),
                        "JOIN_TOWN", town.id().toString())));
            }
            items.add(new MenuItem(48, button(Material.ARROW, "§7返回主菜单", List.of(),
                    "MAIN", null)));
            openMenu(player, 54, "申请加入小镇", items);
        });
    }

    private void openJoinTown(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .filter(town -> town.status() == TownStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("小镇不存在或已停止运行")), town -> {
            List<MenuItem> items = List.of(
                    new MenuItem(4, button(Material.BELL, "§6" + town.profile().name(),
                            List.of("§7简称: " + town.profile().shortName(),
                                    "§7简介: " + town.profile().description(),
                                    "§7规则:", "§f" + String.join(" | ", town.profile().rules())),
                            null, null)),
                    new MenuItem(13, button(Material.LIME_CONCRETE, "§a提交入镇申请",
                            List.of("§7申请有效期 48 小时", "§7同时最多申请 3 个小镇"),
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
                                List.of("§7到期: " + application.expiresAt(),
                                        "§7点击可撤回申请"),
                                "CONFIRM_CANCEL_JOIN", application.id().toString())));
                    }
                    items.add(new MenuItem(48, button(Material.ARROW, "§7返回主菜单", List.of(),
                            "MAIN", null)));
                    openMenu(player, 54, "我的入镇申请 · " + applications.size(), items);
                });
    }

    private void openTownJoinApplications(Player mayor, UUID townId) {
        runtime.read(mayor, () -> runtime.repository().listTownJoinApplications(
                townId, mayor.getUniqueId()), applications -> {
            List<MenuItem> items = new ArrayList<>();
            for (int index = 0; index < Math.min(applications.size(), 45); index++) {
                JoinApplicationSnapshot application = applications.get(index);
                String name = Objects.requireNonNullElse(
                        Bukkit.getOfflinePlayer(application.applicantId()).getName(),
                        application.applicantId().toString());
                items.add(new MenuItem(index, button(Material.PLAYER_HEAD, "§e" + name,
                        List.of("§7申请时间: " + application.createdAt(),
                                "§7到期: " + application.expiresAt(), "§7点击审核"),
                        "JOIN_APPLICATION", application.id().toString())));
            }
            items.add(new MenuItem(48, button(Material.ARROW, "§7返回主菜单", List.of(),
                    "MAIN", null)));
            openMenu(mayor, 54, "入镇申请 · 待处理 " + applications.size(), items);
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
                            List.of("§7批准后立即成为小镇成员"), "CONFIRM_APPROVE_JOIN",
                            application.id().toString())),
                    new MenuItem(15, button(Material.RED_CONCRETE, "§c拒绝申请",
                            List.of("§7拒绝后 24 小时内不能再次申请本镇"),
                            "CONFIRM_REJECT_JOIN", application.id().toString())),
                    new MenuItem(22, button(Material.ARROW, "§7返回申请列表", List.of(),
                            "JOIN_APPLICATIONS", application.townId().toString())));
            openMenu(mayor, 27, "审核入镇申请", items);
        });
    }

    private void openAdminApplications(Player admin) {
        if (!admin.hasPermission("tianjitown.admin")) {
            admin.sendMessage("§c没有管理员权限。");
            return;
        }
        runtime.read(admin, () -> runtime.repository().listReviewQueue(45), applications -> {
            List<MenuItem> items = new ArrayList<>();
            for (int index = 0; index < applications.size(); index++) {
                ApplicationSnapshot application = applications.get(index);
                Material material = application.status() == ApplicationStatus.PROVISION_FAILED
                        ? Material.REDSTONE_BLOCK : Material.WRITABLE_BOOK;
                items.add(new MenuItem(index, button(material, "§e" + application.text().name(),
                        List.of("§7简称: " + application.text().shortName(),
                                "§7状态: " + application.status(), "§7点击查看并处理"),
                        "ADMIN_APPLICATION", application.id().toString())));
            }
            items.add(new MenuItem(48, button(Material.ARROW, "§7返回主菜单", List.of(),
                    "MAIN", null)));
            openMenu(admin, 54, "申请审核 · 待处理 " + applications.size(), items);
        });
    }

    private void openAdminApplication(Player admin, UUID applicationId) {
        if (!admin.hasPermission("tianjitown.admin")) {
            admin.sendMessage("§c没有管理员权限。");
            return;
        }
        runtime.read(admin, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("申请不存在")), application -> {
            List<String> summary = new ArrayList<>(List.of(
                    "§7名称: " + application.text().name(),
                    "§7简称: " + application.text().shortName(),
                    "§7领地名称: " + application.text().residenceName(),
                    "§7简介: " + application.text().description(),
                    "§7规则: " + String.join(" | ", application.text().rules()),
                    "§7状态: " + application.status()));
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
                        List.of("§7创建小镇并投影 3×3 Residence"), "CONFIRM_ADMIN_APPROVE",
                        application.id().toString())));
                items.add(new MenuItem(12, button(Material.RED_CONCRETE, "§c拒绝",
                        List.of("§7点击后在聊天栏填写拒绝原因"), "CONFIRM_ADMIN_REJECT",
                        application.id().toString())));
                items.add(new MenuItem(14, button(Material.ORANGE_CONCRETE, "§e要求补件",
                        List.of("§7点击后在聊天栏填写修改要求"), "CONFIRM_ADMIN_CHANGE",
                        application.id().toString())));
            } else if (application.status() == ApplicationStatus.PROVISION_FAILED) {
                items.add(new MenuItem(12, button(Material.LIME_CONCRETE, "§a重试批准",
                        List.of("§7重新执行 Residence 投影"), "CONFIRM_ADMIN_APPROVE",
                        application.id().toString())));
            }
            if (application.territory() != null) {
                items.add(new MenuItem(16, button(Material.ENDER_EYE, "§b预览选址",
                        List.of("§7传送至领地中心并显示火焰边界"), "ADMIN_PREVIEW_SITE",
                        application.id().toString())));
            }
            items.add(new MenuItem(22, button(Material.ARROW, "§7返回审核列表", List.of(),
                    "ADMIN_APPLICATIONS", null)));
            openMenu(admin, 27, "审核 · " + application.text().name(), items);
        });
    }

    private void handleAction(Player player, String action, String target) {
        if (maintenanceMode()) {
            player.closeInventory();
            player.sendMessage("§c小镇系统已进入维护模式，本次操作未执行。");
            return;
        }
        try {
            switch (action) {
                case "MAIN" -> openMain(player);
                case "CLOSE" -> player.closeInventory();
                case "GIVE_HANDBOOK" -> {
                    giveHandbook(player);
                    openMain(player);
                }
                case "CREATE_APPLICATION" -> startApplicationForm(player, null, 0,
                        new ApplicationText("", "", "", "", List.of()));
                case "APPLICATION" -> loadApplication(player, UUID.fromString(target));
                case "EDIT_APPLICATION" -> loadApplicationForChatForm(player, UUID.fromString(target));
                case "SELECT_SITE" -> selectSite(player, UUID.fromString(target));
                case "PREVIEW_SITE" -> previewApplication(player, UUID.fromString(target));
                case "CONFIRM_SUBMIT" -> openConfirmation(player, "确认提交申请", "SUBMIT", target,
                        "提交后需等待管理员审核", "APPLICATION", target);
                case "SUBMIT" -> submit(player, UUID.fromString(target));
                case "CONFIRM_CANCEL" -> openConfirmation(player, "确认撤回申请", "CANCEL", target,
                        "撤回会释放选址并进入冷却", "APPLICATION", target);
                case "CANCEL" -> cancel(player, UUID.fromString(target));
                case "TOWN" -> openTown(player, UUID.fromString(target));
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
                    openConfirmation(player, "确认调整成员角色", "SET_ROLE", target,
                            "目标角色将变更为 " + parts[2], "MEMBER_DETAIL",
                            parts[0] + ":" + parts[1]);
                }
                case "SET_ROLE" -> changeMemberRole(player, target);
                case "CONFIRM_KICK_MEMBER" -> openConfirmation(player, "确认移除成员",
                        "KICK_MEMBER", target, "目标将立即离镇并失去 Residence 权限",
                        "MEMBER_DETAIL", target);
                case "KICK_MEMBER" -> kickMember(player, target);
                case "CONFIRM_TRANSFER_MAYOR" -> openConfirmation(player, "确认发起镇长转让",
                        "REQUEST_TRANSFER_MAYOR", target, "候选人接受后才会变更镇长",
                        "MEMBER_DETAIL", target);
                case "REQUEST_TRANSFER_MAYOR" -> requestMayorTransfer(player, target);
                case "TRANSFER_REQUEST" -> openTransferRequest(player, UUID.fromString(target));
                case "CONFIRM_TRANSFER_DECISION" -> {
                    String[] parts = target.split(":");
                    boolean accept = Boolean.parseBoolean(parts[1]);
                    openConfirmation(player, accept ? "确认接任镇长" : "确认拒绝转让",
                            "TRANSFER_DECISION", target,
                            accept ? "你将立即成为新镇长" : "本次转让请求将关闭",
                            "TRANSFER_REQUEST", parts[0]);
                }
                case "TRANSFER_DECISION" -> decideMayorTransfer(player, target);
                case "ACK_RULES" -> acknowledgeRules(player, target);
                case "VOTES" -> openVotes(player, UUID.fromString(target));
                case "VOTE_DETAIL" -> openVote(player, UUID.fromString(target));
                case "CONFIRM_CREATE_VOTE" -> openConfirmation(player, "确认发起治理投票",
                        "CREATE_VOTE", target, "选民快照和通过门槛将在创建时冻结",
                        "MEMBER_DETAIL", memberTarget(target));
                case "CREATE_VOTE" -> createVote(player, target);
                case "CAST_VOTE" -> castVote(player, target);
                case "PREVIEW_TOWN" -> previewTown(player, UUID.fromString(target));
                case "JOIN_TOWNS" -> openJoinTowns(player);
                case "JOIN_TOWN" -> openJoinTown(player, UUID.fromString(target));
                case "CONFIRM_APPLY_JOIN" -> openConfirmation(player, "确认提交入镇申请",
                        "APPLY_JOIN", target, "申请将在 48 小时后过期", "JOIN_TOWN", target);
                case "APPLY_JOIN" -> applyJoin(player, UUID.fromString(target));
                case "MY_JOIN_APPLICATIONS" -> openMyJoinApplications(player);
                case "CONFIRM_CANCEL_JOIN" -> openConfirmation(player, "确认撤回入镇申请",
                        "CANCEL_JOIN", target, "撤回后本次申请立即失效",
                        "MY_JOIN_APPLICATIONS", null);
                case "CANCEL_JOIN" -> cancelJoin(player, UUID.fromString(target));
                case "JOIN_APPLICATIONS" -> openTownJoinApplications(player,
                        UUID.fromString(target));
                case "JOIN_APPLICATION" -> openTownJoinApplication(player,
                        UUID.fromString(target));
                case "CONFIRM_APPROVE_JOIN" -> openConfirmation(player, "确认批准入镇申请",
                        "APPROVE_JOIN", target, "申请人将立即成为成员",
                        "JOIN_APPLICATION", target);
                case "APPROVE_JOIN" -> approveJoin(player, UUID.fromString(target));
                case "CONFIRM_REJECT_JOIN" -> openConfirmation(player, "确认拒绝入镇申请",
                        "REJECT_JOIN", target, "申请人 24 小时内不能再次申请本镇",
                        "JOIN_APPLICATION", target);
                case "REJECT_JOIN" -> rejectJoin(player, UUID.fromString(target));
                case "EDIT_TOWN" -> loadTownForForm(player, UUID.fromString(target));
                case "CONFIRM_LEAVE" -> openConfirmation(player, "确认退出小镇", "LEAVE", target,
                        "退出后 24 小时内不能申请加入新镇", "TOWN", target);
                case "LEAVE" -> leave(player, UUID.fromString(target));
                case "CONFIRM_DISBAND" -> openConfirmation(player, "确认解散小镇", "DISBAND",
                        target, "将删除 Residence 领地并释放名称，此操作不可撤销", "MAIN", null);
                case "DISBAND" -> disband(player, target);
                case "ADMIN_APPLICATIONS" -> openAdminApplications(player);
                case "ADMIN_APPLICATION" -> openAdminApplication(player, UUID.fromString(target));
                case "CONFIRM_ADMIN_APPROVE" -> openConfirmation(player, "确认批准申请",
                        "ADMIN_APPROVE", target, "将创建小镇并建立 Residence 投影",
                        "ADMIN_APPLICATION", target);
                case "CONFIRM_ADMIN_REJECT" -> beginAdminDecision(player,
                        UUID.fromString(target), false);
                case "CONFIRM_ADMIN_CHANGE" -> beginAdminDecision(player,
                        UUID.fromString(target), true);
                case "ADMIN_APPROVE" -> adminApprove(player, UUID.fromString(target));
                case "ADMIN_PREVIEW_SITE" -> adminPreviewSite(player, UUID.fromString(target));
                default -> player.sendMessage("§c菜单操作已失效，请重新打开。");
            }
        } catch (IllegalArgumentException exception) {
            player.sendMessage("§c菜单数据无效，请重新打开。");
        }
    }

    private void changeMemberRole(Player mayor, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        UUID playerId = UUID.fromString(parts[1]);
        MemberRole role = MemberRole.valueOf(parts[2]);
        runtime.write(mayor, () -> runtime.governance().changeRoleByMayor(townId, playerId,
                role, mayor.getUniqueId(), mayor.getName()), changed -> {
            mayor.sendMessage("§a成员角色已调整为 " + changed + "，正在复核 Residence 权限。");
            syncResidence(mayor, townId, true);
            openMemberDetail(mayor, townId, playerId);
        });
    }

    private void kickMember(Player mayor, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        UUID playerId = UUID.fromString(parts[1]);
        runtime.write(mayor, () -> {
            runtime.governance().removeMemberByMayor(townId, playerId, mayor.getUniqueId(),
                    mayor.getName());
            return townId;
        }, changedTown -> {
            mayor.sendMessage("§a成员已移出小镇，正在同步 Residence 权限。");
            Player removed = Bukkit.getPlayer(playerId);
            if (removed != null) {
                removed.sendMessage("§c你已被镇长移出小镇。");
            }
            syncResidence(mayor, changedTown, true);
            openMembers(mayor, changedTown, 0);
        });
    }

    private void requestMayorTransfer(Player mayor, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        UUID candidateId = UUID.fromString(parts[1]);
        Duration lifetime = Duration.ofHours(plugin.getConfig().getLong(
                "phase2.governance.transfer-confirmation-hours", 24));
        runtime.write(mayor, () -> runtime.governance().requestMayorTransfer(townId,
                candidateId, mayor.getUniqueId(), lifetime), transfer -> {
            mayor.sendMessage("§a镇长转让请求已发出，有效期至 " + transfer.expiresAt() + "。");
            Player candidate = Bukkit.getPlayer(candidateId);
            if (candidate != null) {
                candidate.sendMessage(Component.text("你收到了一项镇长转让请求。 ",
                                NamedTextColor.GOLD)
                        .append(callbackButton(candidate, "[处理]",
                                () -> openTransferRequest(candidate, transfer.id()))));
            }
            openMain(mayor);
        });
    }

    private void decideMayorTransfer(Player candidate, String target) {
        String[] parts = target.split(":");
        UUID transferId = UUID.fromString(parts[0]);
        boolean accept = Boolean.parseBoolean(parts[1]);
        runtime.write(candidate, () -> runtime.governance().decideMayorTransfer(transferId,
                candidate.getUniqueId(), accept), transfer -> {
            candidate.sendMessage(accept ? "§a你已接任镇长。" : "§e你已拒绝本次镇长转让。");
            if (accept) {
                syncResidence(candidate, transfer.townId(), true);
            }
            Player oldMayor = Bukkit.getPlayer(transfer.requestedBy());
            if (oldMayor != null) {
                oldMayor.sendMessage(accept ? "§e镇长转让已被接受，你现在是普通成员。"
                        : "§e候选成员拒绝了镇长转让。");
            }
            openMain(candidate);
        });
    }

    private void acknowledgeRules(Player player, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        long revision = Long.parseLong(parts[1]);
        runtime.write(player, () -> {
            runtime.governance().acknowledgeRules(townId, player.getUniqueId(), revision);
            return revision;
        }, confirmed -> {
            player.sendMessage("§a已记录你对规则版本 " + confirmed + " 的确认。");
            openMain(player);
        });
    }

    private void createVote(Player player, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        VoteType type = VoteType.valueOf(parts[1]);
        UUID targetId = UUID.fromString(parts[2]);
        Duration activeWindow = Duration.ofDays(plugin.getConfig().getLong(
                "phase2.voting.active-member-days", 30));
        Duration minimumMembership = Duration.ofDays(plugin.getConfig().getLong(
                "phase2.voting.minimum-membership-days", 0));
        Duration lifetime = Duration.ofHours(plugin.getConfig().getLong(
                "phase2.voting.duration-hours", 72));
        runtime.write(player, () -> runtime.governance().createVote(townId, type, targetId,
                player.getUniqueId(), activeWindow, minimumMembership, lifetime, false), vote -> {
            player.sendMessage("§a治理投票已创建：有效选民 " + vote.eligibleVoters()
                    + " 人，通过需 " + vote.requiredYes() + " 票。");
            openVote(player, vote.id());
        });
    }

    private void castVote(Player player, String target) {
        String[] parts = target.split(":");
        UUID voteId = UUID.fromString(parts[0]);
        boolean approve = Boolean.parseBoolean(parts[1]);
        runtime.write(player, () -> runtime.governance().castVote(voteId,
                player.getUniqueId(), approve), vote -> {
            player.sendMessage("§a投票已记录。当前赞成/门槛：" + vote.yesVotes()
                    + "/" + vote.requiredYes() + "，状态：" + vote.status());
            if (vote.passed() && vote.type() == VoteType.KICK_MEMBER) {
                syncResidence(player, vote.townId(), true);
            }
            openVotes(player, vote.townId());
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

    private void loadApplicationForChatForm(Player player, UUID applicationId) {
        runtime.read(player, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("申请不存在")), application ->
                startApplicationForm(player, application.id(), application.version(),
                        application.text()));
    }

    private void loadTownForForm(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException("小镇不存在")), town -> {
            if (!town.mayorId().equals(player.getUniqueId())) {
                player.sendMessage("§c只有镇长可以修改简介和规则。");
                return;
            }
            issueTownForm(player, town.id(), town.version(), town.profile());
        });
    }

    private void selectSite(Player player, UUID applicationId) {
        SitePolicy.Validation validation = sitePolicy.validate(player);
        if (!validation.valid()) {
            player.sendMessage("§c选址失败: " + validation.error());
            return;
        }
        long minutes = plugin.getConfig().getLong("phase1.application.reservation-minutes", 60);
        int buffer = plugin.getConfig().getInt("phase1.site.minimum-buffer-chunks", 1);
        runtime.write(player, () -> runtime.repository().selectSite(applicationId,
                player.getUniqueId(), validation.territory(), Instant.now().plusSeconds(minutes * 60),
                buffer), application -> {
            sitePolicy.preview(player, application.territory());
            openApplication(player, application);
        });
    }

    private void previewApplication(Player player, UUID applicationId) {
        runtime.read(player, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("申请不存在")), application -> {
            if (application.territory() == null) {
                player.sendMessage("§c该申请尚未选址。");
            } else {
                sitePolicy.teleportAndPreview(player, application.territory());
            }
        });
    }

    private void previewTown(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException("小镇不存在")), town ->
                sitePolicy.teleportAndPreview(player, town.territory()));
    }

    private void submit(Player player, UUID applicationId) {
        runtime.write(player, () -> runtime.repository().submit(applicationId, player.getUniqueId()),
                application -> {
                    player.sendMessage("§a申请已提交，等待管理员审核。");
                    playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
                    notifyApplicationSubmitted(application);
                    openApplication(player, application);
                });
    }

    private void cancel(Player player, UUID applicationId) {
        runtime.write(player, () -> runtime.repository().cancel(applicationId, player.getUniqueId(),
                "玩家通过 GUI 撤回"), application -> {
            player.sendMessage("§e申请已撤回，选址预留已释放。");
            openMain(player);
        });
    }

    private void applyJoin(Player player, UUID townId) {
        Duration lifetime = Duration.ofHours(plugin.getConfig().getLong(
                "phase1.membership.application-lifetime-hours", 48));
        Duration rejectionCooldown = Duration.ofHours(plugin.getConfig().getLong(
                "phase1.membership.rejection-cooldown-hours", 24));
        Duration leaveCooldown = Duration.ofHours(plugin.getConfig().getLong(
                "phase1.membership.leave-cooldown-hours", 24));
        int maximumPending = plugin.getConfig().getInt(
                "phase1.membership.maximum-pending-applications", 3);
        runtime.write(player, () -> runtime.repository().applyToTown(townId,
                player.getUniqueId(), lifetime, rejectionCooldown, leaveCooldown, maximumPending),
                application -> {
                    player.sendMessage("§a入镇申请已提交，有效期至 " + application.expiresAt() + "。");
                    playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
                    notifyMayorJoinApplication(application);
                    openMyJoinApplications(player);
                });
    }

    private void cancelJoin(Player player, UUID applicationId) {
        runtime.write(player, () -> runtime.repository().cancelJoinApplication(applicationId,
                player.getUniqueId()), ignored -> {
            player.sendMessage("§e入镇申请已撤回。");
            playSound(player, Sound.UI_BUTTON_CLICK);
            openMyJoinApplications(player);
        });
    }

    private void approveJoin(Player mayor, UUID applicationId) {
        runtime.write(mayor, () -> runtime.repository().approveJoinApplication(applicationId,
                mayor.getUniqueId()), application -> {
            mayor.sendMessage("§a已批准入镇申请，正在同步 Residence 成员权限。");
            playSound(mayor, Sound.ENTITY_PLAYER_LEVELUP);
            notifyJoinDecision(application, true);
            syncResidence(mayor, application.townId(), true);
            openTownJoinApplications(mayor, application.townId());
        });
    }

    private void rejectJoin(Player mayor, UUID applicationId) {
        runtime.write(mayor, () -> runtime.repository().rejectJoinApplication(applicationId,
                mayor.getUniqueId()), application -> {
            mayor.sendMessage("§e已拒绝该入镇申请。");
            playSound(mayor, Sound.UI_BUTTON_CLICK);
            notifyJoinDecision(application, false);
            openTownJoinApplications(mayor, application.townId());
        });
    }

    private void notifyMayorJoinApplication(JoinApplicationSnapshot application) {
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
                manager.sendMessage(Component.text(applicant + " 申请加入“"
                                + application.townName() + "” ", NamedTextColor.GOLD)
                        .append(callbackButton(manager, "[立即审核]",
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
        applicant.sendMessage(Component.text(approved
                        ? "你加入“" + application.townName() + "”的申请已获批准 "
                        : "你加入“" + application.townName() + "”的申请已被拒绝，24 小时后可再次申请 ",
                approved ? NamedTextColor.GREEN : NamedTextColor.RED)
                .append(callbackButton(applicant, "[打开小镇系统]", () -> openMain(applicant))));
        playSound(applicant, approved ? Sound.ENTITY_PLAYER_LEVELUP : Sound.ENTITY_VILLAGER_NO);
    }

    private void adminApprove(Player admin, UUID applicationId) {
        if (!admin.hasPermission("tianjitown.admin")) {
            admin.sendMessage("§c没有管理员权限。");
            return;
        }
        runtime.read(admin, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("申请不存在")), application -> {
            String idempotencyKey = application.status() == ApplicationStatus.PROVISION_FAILED
                    ? "phase1:retry:" + application.id() + ":" + UUID.randomUUID()
                    : "phase1:approve:" + application.id();
            runtime.provision(admin, application.id(), admin.getUniqueId(), admin.getName(),
                    "管理员通过 GUI 批准申请", idempotencyKey, approved -> {
                        notifyApplicationDecision(approved);
                        playSound(admin, approved.status() == ApplicationStatus.ACTIVE
                                ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_NOTE_BLOCK_BASS);
                        openAdminApplications(admin);
                    });
        });
    }

    private void beginAdminDecision(Player admin, UUID applicationId, boolean requestChanges) {
        if (!admin.hasPermission("tianjitown.admin")) {
            admin.sendMessage("§c没有管理员权限。");
            return;
        }
        applicationForms.remove(admin.getUniqueId());
        chatInputs.remove(admin.getUniqueId());
        reviewReasonInputs.put(admin.getUniqueId(),
                new ReviewReasonSession(applicationId, requestChanges));
        admin.closeInventory();
        admin.sendMessage(requestChanges
                ? "§e请在聊天栏输入具体修改要求；输入“取消”放弃，本次操作不会改变申请状态。"
                : "§e请在聊天栏输入拒绝原因；输入“取消”放弃，本次操作不会改变申请状态。");
        admin.sendMessage("§7原因不能为空，最多 500 个字符。");
    }

    private void applyReviewReason(Player admin, ReviewReasonSession input, String reason) {
        if (!admin.hasPermission("tianjitown.admin")) {
            admin.sendMessage("§c没有管理员权限，本次审批未执行。");
            return;
        }
        if (reason.equals("取消") || reason.equalsIgnoreCase("cancel")) {
            admin.sendMessage("§7已取消，本次审批未改变申请状态。");
            openAdminApplication(admin, input.applicationId());
            return;
        }
        if (reason.isBlank() || reason.length() > 500) {
            reviewReasonInputs.put(admin.getUniqueId(), input);
            admin.sendMessage(reason.isBlank()
                    ? "§c原因不能为空，请直接重新输入；或输入“取消”放弃。"
                    : "§c原因不能超过 500 个字符，请直接重新输入；或输入“取消”放弃。");
            return;
        }
        adminDecision(admin, input.applicationId(), input.requestChanges(), reason);
    }

    private void adminDecision(Player admin, UUID applicationId, boolean requestChanges,
                               String reason) {
        runtime.write(admin, () -> requestChanges
                        ? runtime.repository().requestChanges(applicationId, admin.getUniqueId(),
                        admin.getName(), reason)
                        : runtime.repository().reject(applicationId, admin.getUniqueId(),
                        admin.getName(), reason), application -> {
            admin.sendMessage(requestChanges ? "§a已要求申请人补充资料。" : "§a申请已拒绝。");
            playSound(admin, Sound.UI_BUTTON_CLICK);
            notifyApplicationDecision(application);
            openAdminApplications(admin);
        });
    }

    private void adminPreviewSite(Player admin, UUID applicationId) {
        if (!admin.hasPermission("tianjitown.admin")) {
            admin.sendMessage("§c没有管理员权限。");
            return;
        }
        runtime.read(admin, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("申请不存在")), application -> {
            if (application.territory() == null) {
                admin.sendMessage("§c该申请尚未选址。");
                return;
            }
            admin.closeInventory();
            sitePolicy.teleportAndPreview(admin, application.territory());
        });
    }

    private void notifyApplicationSubmitted(ApplicationSnapshot application) {
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (!admin.hasPermission("tianjitown.admin")) {
                continue;
            }
            admin.sendMessage(Component.text("收到新的小镇申请：“" + application.text().name() + "” ",
                            NamedTextColor.GOLD)
                    .append(callbackButton(admin, "[立即审核]",
                            () -> openAdminApplication(admin, application.id()))));
            playSound(admin, Sound.BLOCK_AMETHYST_BLOCK_CHIME);
        }
    }

    private void leave(Player player, UUID townId) {
        runtime.write(player, () -> {
            runtime.repository().leaveTown(player.getUniqueId());
            return townId;
        }, result -> {
            player.sendMessage("§e你已退出小镇。");
            syncResidence(player, townId, true);
            openMain(player);
        });
    }

    private void disband(Player mayor, String target) {
        String[] parts = target.split(":", 2);
        UUID townId = UUID.fromString(parts[0]);
        long expectedVersion = Long.parseLong(parts[1]);
        runtime.write(mayor, () -> runtime.repository().disbandTown(townId,
                mayor.getUniqueId(), expectedVersion), archived -> {
            LandProtectionService.Result result = runtime.landProtection().remove(
                    archived.residenceName(), archived.territory());
            if (!result.success()) {
                mayor.sendMessage("§c小镇已安全归档，但 Residence 移除失败："
                        + result.message() + "。名称和区块仍保持锁定，请联系管理员处理。");
                plugin.getLogger().warning("镇长解散小镇后 Residence 移除失败 "
                        + archived.profile().name() + "/" + archived.residenceName()
                        + ": " + result.message());
                return;
            }
            runtime.write(mayor, () -> {
                runtime.repository().completeTownDeletion(archived.id(), mayor.getUniqueId(),
                        mayor.getName(), "镇长通过小镇界面解散");
                return archived;
            }, completed -> {
                mayor.sendMessage("§a小镇“" + completed.profile().name()
                        + "”已解散，领地、成员、名称和区块占位均已释放。");
                playSound(mayor, Sound.ENTITY_WITHER_DEATH);
                openMain(mayor);
            });
        });
    }

    private void syncResidence(Player sender, UUID townId, boolean repair) {
        runtime.read(sender, () -> new TownMembers(runtime.repository().findTown(townId)
                        .orElseThrow(() -> new IllegalArgumentException("小镇不存在")),
                        runtime.repository().listMemberIds(townId)), state ->
                runtime.reconcile(sender, state.town(), state.members(), repair));
    }

    private void startApplicationForm(Player player, UUID targetId, long version,
                                      ApplicationText text) {
        if (maintenanceMode()) {
            player.sendMessage("§c小镇系统正在维护，申请编辑暂时停用。");
            return;
        }
        UUID formId = UUID.randomUUID();
        applicationForms.put(player.getUniqueId(),
                new ApplicationFormSession(formId, targetId, version, text));
        chatInputs.remove(player.getUniqueId());
        reviewReasonInputs.remove(player.getUniqueId());
        player.closeInventory();
        renderApplicationForm(player, formId);
    }

    private void renderApplicationForm(Player player, UUID formId) {
        ApplicationFormSession form = applicationForms.get(player.getUniqueId());
        if (form == null || !form.id().equals(formId)) {
            player.sendMessage("§c申请编辑会话已失效，请重新打开。");
            return;
        }
        player.sendMessage(Component.text("━━━━━━━━ 小镇申请资料 ━━━━━━━━",
                NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text("点击任一项目后，直接在聊天栏输入新内容。",
                NamedTextColor.GRAY));
        for (ApplicationField field : ApplicationField.values()) {
            player.sendMessage(applicationFieldLine(player, form, field));
        }
        player.sendMessage(Component.text("Residence 预览：", NamedTextColor.GRAY)
                .append(Component.text(form.text().residenceName().isBlank()
                                ? "待填写" : form.text().normalizedResidenceName(),
                        form.text().residenceName().isBlank()
                                ? NamedTextColor.RED : NamedTextColor.AQUA)));
        player.sendMessage(callbackButton(player, "[保存申请资料]",
                        () -> saveApplicationForm(player, formId))
                .append(Component.space())
                .append(callbackButton(player, "[取消编辑]",
                        () -> cancelApplicationForm(player, formId))));
    }

    private Component applicationFieldLine(Player player, ApplicationFormSession form,
                                           ApplicationField field) {
        String value = fieldValue(form.text(), field);
        List<String> errors = fieldErrors(form.text(), field);
        NamedTextColor valueColor = value.isBlank() ? NamedTextColor.RED
                : errors.isEmpty() ? NamedTextColor.GREEN : NamedTextColor.RED;
        String display = value.isBlank() ? "（未填写）" : preview(value, 100);
        Component hover = Component.text(field.requirement(), NamedTextColor.YELLOW)
                .append(Component.newline())
                .append(Component.text("建议：" + field.suggestion(), NamedTextColor.GRAY))
                .append(Component.newline())
                .append(Component.text("点击后在聊天栏输入", NamedTextColor.AQUA));
        if (!errors.isEmpty()) {
            hover = hover.append(Component.newline())
                    .append(Component.text("当前错误：" + String.join("；", errors),
                            NamedTextColor.RED));
        }
        Component line = Component.text("• " + field.label() + "：", NamedTextColor.GOLD)
                .append(Component.text(display, valueColor))
                .clickEvent(callbackEvent(player,
                        () -> beginChatInput(player, form.id(), field)))
                .hoverEvent(HoverEvent.showText(hover));
        return errors.isEmpty() ? line : line.append(Component.text(" ← " + errors.getFirst(),
                NamedTextColor.RED));
    }

    private void beginChatInput(Player player, UUID formId, ApplicationField field) {
        if (maintenanceMode()) {
            applicationForms.remove(player.getUniqueId());
            chatInputs.remove(player.getUniqueId());
            player.sendMessage("§c小镇系统正在维护，申请编辑会话已关闭。");
            return;
        }
        ApplicationFormSession form = applicationForms.get(player.getUniqueId());
        if (form == null || !form.id().equals(formId)) {
            player.sendMessage("§c申请编辑会话已失效，请重新打开。");
            return;
        }
        chatInputs.put(player.getUniqueId(), new ChatInputSession(formId, field));
        reviewReasonInputs.remove(player.getUniqueId());
        player.sendMessage(Component.text("请输入“" + field.label() + "”。",
                        NamedTextColor.YELLOW)
                .append(Component.space())
                .append(Component.text("输入“取消”放弃本次输入。", NamedTextColor.GRAY)));
        player.sendMessage(Component.text(field.requirement() + "；建议：" + field.suggestion(),
                NamedTextColor.AQUA));
    }

    private void applyChatInput(Player player, ChatInputSession input, String value) {
        if (maintenanceMode()) {
            applicationForms.remove(player.getUniqueId());
            player.sendMessage("§c小镇系统正在维护，本次输入未使用。");
            return;
        }
        ApplicationFormSession form = applicationForms.get(player.getUniqueId());
        if (form == null || !form.id().equals(input.formId())) {
            player.sendMessage("§c申请编辑会话已失效，本次输入未使用。");
            return;
        }
        if (value.equals("取消") || value.equalsIgnoreCase("cancel")) {
            player.sendMessage("§7已取消本次输入。");
            renderApplicationForm(player, form.id());
            return;
        }
        ApplicationText updated = updateField(form.text(), input.field(), value);
        List<String> errors = fieldErrors(updated, input.field());
        if (!errors.isEmpty()) {
            chatInputs.put(player.getUniqueId(), input);
            player.sendMessage("§c输入已被拒绝: " + String.join("；", errors));
            player.sendMessage("§e请直接重新输入“" + input.field().label()
                    + "”；或输入“取消”放弃本次输入。");
            renderApplicationForm(player, form.id());
            return;
        }
        applicationForms.put(player.getUniqueId(), new ApplicationFormSession(
                form.id(), form.targetId(), form.version(), updated));
        playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
        renderApplicationForm(player, form.id());
    }

    private void saveApplicationForm(Player player, UUID formId) {
        if (maintenanceMode()) {
            applicationForms.remove(player.getUniqueId());
            chatInputs.remove(player.getUniqueId());
            player.sendMessage("§c小镇系统正在维护，申请资料未保存。");
            return;
        }
        ApplicationFormSession form = applicationForms.get(player.getUniqueId());
        if (form == null || !form.id().equals(formId)) {
            player.sendMessage("§c申请编辑会话已失效，请重新打开。");
            return;
        }
        try {
            form.text().requireValid();
        } catch (IllegalArgumentException exception) {
            player.sendMessage("§c申请资料尚未完成: " + exception.getMessage());
            renderApplicationForm(player, form.id());
            return;
        }
        applicationForms.remove(player.getUniqueId(), form);
        chatInputs.remove(player.getUniqueId());
        if (form.targetId() == null) {
            Duration cooldown = Duration.ofMinutes(plugin.getConfig()
                    .getLong("phase1.application.cooldown-minutes", 5));
            runtime.write(player, () -> runtime.repository().createDraft(player.getUniqueId(),
                    form.text(), cooldown), application -> {
                player.sendMessage("§a申请草稿已保存，请继续选择领地并确认提交。");
                openApplication(player, application);
            });
        } else {
            runtime.write(player, () -> runtime.repository().updateApplicationText(form.targetId(),
                    player.getUniqueId(), form.text(), form.version()), application -> {
                player.sendMessage("§a申请资料已保存。");
                openApplication(player, application);
            });
        }
    }

    private void cancelApplicationForm(Player player, UUID formId) {
        ApplicationFormSession form = applicationForms.get(player.getUniqueId());
        if (form == null || !form.id().equals(formId)) {
            player.sendMessage("§c申请编辑会话已失效。");
            return;
        }
        applicationForms.remove(player.getUniqueId(), form);
        chatInputs.remove(player.getUniqueId());
        if (form.targetId() == null) {
            openMain(player);
        } else {
            loadApplication(player, form.targetId());
        }
    }

    private static ApplicationText updateField(ApplicationText text, ApplicationField field,
                                               String value) {
        List<String> rules = field == ApplicationField.RULES
                ? java.util.Arrays.stream(value.split("[|｜]", -1)).map(String::strip)
                .filter(rule -> !rule.isBlank()).toList() : text.rules();
        return new ApplicationText(
                field == ApplicationField.NAME ? value : text.name(),
                field == ApplicationField.SHORT_NAME ? value : text.shortName(),
                field == ApplicationField.RESIDENCE_NAME ? value : text.residenceName(),
                field == ApplicationField.DESCRIPTION ? value : text.description(),
                rules);
    }

    private static List<String> fieldErrors(ApplicationText text, ApplicationField field) {
        return text.validate().stream().filter(error -> switch (field) {
            case NAME -> error.startsWith("名称");
            case SHORT_NAME -> error.startsWith("简称");
            case RESIDENCE_NAME -> error.startsWith("领地名称");
            case DESCRIPTION -> error.startsWith("简介");
            case RULES -> error.startsWith("规则");
        }).toList();
    }

    private static String fieldValue(ApplicationText text, ApplicationField field) {
        return switch (field) {
            case NAME -> text.name();
            case SHORT_NAME -> text.shortName();
            case RESIDENCE_NAME -> text.residenceName();
            case DESCRIPTION -> text.description();
            case RULES -> String.join(" | ", text.rules());
        };
    }

    private static String preview(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum - 1) + "…";
    }

    private void issueTownForm(Player player, UUID targetId, long version,
                               ApplicationText base) {
        UUID id = UUID.randomUUID();
        formSessions.put(player.getUniqueId(), new FormSession(id, targetId, version, base));
        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setDisplayName("§6小镇资料表单（填写后签名提交，书名任意）");
        meta.setPages(formPages(base));
        meta.getPersistentDataContainer().set(sessionKey, PersistentDataType.STRING, id.toString());
        book.setItemMeta(meta);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(book);
        leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        player.closeInventory();
        player.sendMessage("§e请编辑书本第 3、4 页的简介和规则，然后签名提交；签名书名可任意填写，名称、简称与领地名称修改会被忽略。");
    }

    private ApplicationText parseForm(BookMeta meta, FormSession form) {
        List<String> pages = meta.getPages();
        if (pages.size() < 4) {
            throw new IllegalArgumentException("表单必须保留四页");
        }
        String name = afterMarker(pages.get(0), "【名称】");
        String shortName = afterMarker(pages.get(1), "【简称】");
        String description = afterMarker(pages.get(2), "【简介】");
        StringBuilder rawRules = new StringBuilder(afterMarker(pages.get(3), "【规则】"));
        for (int index = 4; index < pages.size(); index++) {
            String page = pages.get(index).replace("\r\n", "\n").replace('\r', '\n');
            String continued = page.startsWith("【规则续】")
                    ? page.substring("【规则续】".length()).stripLeading() : page.strip();
            if (!continued.isBlank()) {
                rawRules.append('\n').append(continued);
            }
        }
        List<String> rules = rawRules.toString().lines().map(String::strip)
                .filter(line -> !line.isEmpty()).toList();
        return new ApplicationText(form.base().name(), form.base().shortName(),
                form.base().residenceName(), description, rules);
    }

    private static String afterMarker(String page, String marker) {
        String normalized = page.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.startsWith(marker)) {
            throw new IllegalArgumentException("表单页首标记被删除: " + marker);
        }
        return normalized.substring(marker.length()).stripLeading();
    }

    private static List<String> formPages(ApplicationText value) {
        List<String> pages = new ArrayList<>();
        pages.add("【名称】\n" + value.name());
        pages.add("【简称】\n" + value.shortName());
        pages.add("【简介】\n" + value.description());
        String marker = "【规则】\n";
        StringBuilder current = new StringBuilder(marker);
        for (String rule : value.rules()) {
            if (current.length() > marker.length() && current.length() + rule.length() + 1 > 800) {
                pages.add(current.toString());
                marker = "【规则续】\n";
                current = new StringBuilder(marker);
            }
            if (current.length() > marker.length()) {
                current.append('\n');
            }
            current.append(rule);
        }
        pages.add(current.toString());
        return pages;
    }

    private void removeFormBook(Player player, UUID formId) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || !item.hasItemMeta()) {
                continue;
            }
            String value = item.getItemMeta().getPersistentDataContainer()
                    .get(sessionKey, PersistentDataType.STRING);
            if (formId.toString().equals(value)) {
                item.setAmount(0);
            }
        }
    }

    private void openConfirmation(Player player, String title, String confirmedAction,
                                  String target, String consequence, String returnAction,
                                  String returnTarget) {
        List<MenuItem> items = List.of(
                new MenuItem(11, button(Material.LIME_CONCRETE, "§a确认",
                        List.of("§7" + consequence), confirmedAction, target)),
                new MenuItem(15, button(Material.RED_CONCRETE, "§c取消", List.of(),
                        returnAction, returnTarget)));
        openMenu(player, 27, title, items);
    }

    private UUID openMenu(Player player, int size, String title, List<MenuItem> items) {
        UUID session = UUID.randomUUID();
        MenuHolder holder = new MenuHolder(session);
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.inventory = inventory;
        List<MenuItem> visibleItems = new ArrayList<>(items);
        if (visibleItems.stream().noneMatch(item -> "CLOSE".equals(itemAction(item.item())))) {
            int closeSlot = findBottomRowSlot(size, visibleItems);
            if (closeSlot >= 0) {
                visibleItems.add(new MenuItem(closeSlot, button(Material.BARRIER, "§c关闭",
                        List.of("§7关闭当前界面"), "CLOSE", null)));
            }
        }
        for (MenuItem item : visibleItems) {
            ItemMeta meta = item.item().getItemMeta();
            if (meta != null && meta.getPersistentDataContainer().has(actionKey,
                    PersistentDataType.STRING)) {
                meta.getPersistentDataContainer().set(sessionKey, PersistentDataType.STRING,
                        session.toString());
                item.item().setItemMeta(meta);
            }
            inventory.setItem(item.slot(), item.item());
        }
        menuSessions.put(player.getUniqueId(), session);
        player.openInventory(inventory);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (isCurrent(player, session)) {
                menuSessions.remove(player.getUniqueId());
                player.closeInventory();
                player.sendMessage("§7小镇菜单会话已超时，请重新打开。");
            }
        }, MENU_TIMEOUT_TICKS);
        return session;
    }

    private int findBottomRowSlot(int size, List<MenuItem> items) {
        boolean[] occupied = new boolean[size];
        for (MenuItem item : items) {
            if (item.slot() >= 0 && item.slot() < size) {
                occupied[item.slot()] = true;
            }
        }
        int preferred = switch (size) {
            case 9 -> 8;
            case 27 -> 26;
            case 54 -> 49;
            default -> size - 1;
        };
        if (!occupied[preferred]) {
            return preferred;
        }
        for (int slot = size - 1; slot >= Math.max(0, size - 9); slot--) {
            if (!occupied[slot]) {
                return slot;
            }
        }
        return -1;
    }

    private String itemAction(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer()
                .get(actionKey, PersistentDataType.STRING);
    }

    private Component callbackButton(Player recipient, String label, Runnable action) {
        return Component.text(label, NamedTextColor.AQUA, TextDecoration.BOLD)
                .clickEvent(callbackEvent(recipient, action))
                .hoverEvent(HoverEvent.showText(Component.text("点击打开", NamedTextColor.GRAY)));
    }

    private ClickEvent callbackEvent(Player recipient, Runnable action) {
        return ClickEvent.callback(audience -> {
            if (!(audience instanceof Player clicked)
                    || !clicked.getUniqueId().equals(recipient.getUniqueId())) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
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
        meta.setDisplayName(name);
        meta.setLore(lore);
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
        return plugin.getConfig().getBoolean("phase1.maintenance-mode", false);
    }

    private record MenuItem(int slot, ItemStack item) {
    }

    private record MainView(PhaseOneRepository.PlayerDashboard dashboard,
                            MemberGovernanceSnapshot governance,
                            List<ApplicationSnapshot> reviewQueue) {
    }

    private record MemberPage(TownSnapshot.Page page, MemberGovernanceSnapshot governance) {
    }

    private record MemberDetail(MemberGovernanceSnapshot viewer, MemberRole targetRole) {
    }

    private record ManagerNotification(TownSnapshot town, List<UUID> managerIds) {
    }

    private record FormSession(UUID id, UUID targetId, long version, ApplicationText base) {
    }

    private record ApplicationFormSession(UUID id, UUID targetId, long version,
                                          ApplicationText text) {
    }

    private record ChatInputSession(UUID formId, ApplicationField field) {
    }

    private record ReviewReasonSession(UUID applicationId, boolean requestChanges) {
    }

    private record StationRecord(String id, UUID worldId, String worldName, int x, int y, int z) {
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
        SHORT_NAME("小镇简称", "1–8 个文字、数字、空格、下划线、连字符或间隔点",
                "使用 2–4 个容易识别的字符"),
        RESIDENCE_NAME("小镇领地名称", "必须为 1–12 个英文字母，不允许空格、数字和特殊符号",
                "优先使用三个英文字母，例如 SKY"),
        DESCRIPTION("小镇简介", "最多 500 个字符，不允许格式代码或 MiniMessage 标签",
                "简要说明定位、风格和发展目标"),
        RULES("小镇规则", "至少 1 条、最多 50 条；多条规则使用竖线 | 分隔",
                "每条规则保持简短清晰");

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

    private record TownMembers(TownSnapshot town, List<UUID> members) {
    }

    private static final class MenuHolder implements InventoryHolder {
        private final UUID sessionId;
        private Inventory inventory;

        private MenuHolder(UUID sessionId) {
            this.sessionId = sessionId;
        }

        UUID sessionId() {
            return sessionId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}

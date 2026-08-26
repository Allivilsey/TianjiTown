package cn.tianji.town.paper;

import cn.tianji.town.core.application.ApplicationStatus;
import cn.tianji.town.core.application.ApplicationText;
import cn.tianji.town.core.economy.MoneyAmount;
import cn.tianji.town.core.consumption.BuffDefinition;
import cn.tianji.town.core.consumption.BuffDurationOption;
import cn.tianji.town.core.land.ExpansionDirection;
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
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
    private final NamespacedKey actionKey;
    private final NamespacedKey targetKey;
    private final Map<UUID, UUID> menuSessions = new HashMap<>();
    private final Map<UUID, ApplicationFormSession> applicationForms = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(true);

    TownUiController(TianjiTownPlugin plugin, TownRuntime runtime, TownActions actions) {
        this.plugin = plugin;
        this.runtime = runtime;
        this.actions = actions;
        this.sitePolicy = runtime.sitePolicy();
        this.stationKey = new NamespacedKey(plugin, "service_station");
        this.handbookKey = new NamespacedKey(plugin, "handbook");
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
            player.sendMessage("§c请看向 6 格内的讲台后重试。");
            return false;
        }
        List<StationRecord> stations = stationRecords();
        String existingId = lectern.getPersistentDataContainer().get(stationKey,
                PersistentDataType.STRING);
        if (existingId != null && !existingId.isBlank()) {
            if (registeredStation(block, existingId, stations) != null) {
                player.sendMessage("§e该讲台已经是小镇服务台，未重复创建。ID: " + existingId);
            } else {
                player.sendMessage("§c该讲台携带了复制或移动后的服务台标记，但 ID 与登记坐标不一致；"
                        + "请在原登记位置操作或先由管理员清理异常方块。");
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
            player.sendMessage("§a已恢复原登记坐标的小镇服务台标记，ID: "
                    + registeredLocation.id());
            return true;
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
        if (registeredStation(block, stationId, stationRecords()) == null) {
            player.sendMessage("§c该讲台的服务台 ID 与登记坐标不一致，不能移除其他位置的登记。");
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
        if (registeredStation(block, stationId, stationRecords()) == null) {
            player.sendMessage("§c当前讲台携带无效的服务台标记：ID 与登记坐标不一致。");
            return;
        }
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

    private static StationRecord registeredStation(Block block, String stationId,
                                                     List<StationRecord> stations) {
        return stations.stream().filter(station -> station.id().equals(stationId)
                        && station.sameLocation(block.getWorld().getUID(), block.getX(), block.getY(),
                        block.getZ()))
                .findFirst().orElse(null);
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
        runtime.read(player, () -> runtime.finance().findFinanceByPlayer(player.getUniqueId())
                .orElse(null), finance -> {
            if (runtime.taxEnabled() && finance != null && finance.hasUnreadTaxChange()) {
                player.sendMessage(Component.text("小镇税率已更新为 "
                                + TownRuntime.percent(finance.taxRateBps())
                                + "，同步用于 QuickShop、Jobs 与全球市场收入。 ", NamedTextColor.YELLOW)
                        .append(callbackButton(player, "[查看公共资金]",
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
                        + "”已获批准并创建完成。", NamedTextColor.GREEN));
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
            plugin.runMain(() -> openMain(player));
            return;
        }
        if (event.isCancelled()) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block != null && block.getState() instanceof Lectern lectern
                && lectern.getPersistentDataContainer().has(stationKey, PersistentDataType.STRING)) {
            event.setCancelled(true);
            String stationId = lectern.getPersistentDataContainer().get(stationKey,
                    PersistentDataType.STRING);
            if (stationId == null
                    || registeredStation(block, stationId, stationRecords()) == null) {
                event.getPlayer().sendMessage("§c该讲台是复制或移动后的无效服务台，登记坐标校验未通过。");
                return;
            }
            openMain(event.getPlayer());
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
                    List.of("§7简介、规则、领地和基础资料"), "TOWN", town.id().toString())));
            items.add(new MenuItem(12, button(Material.EMERALD_BLOCK, "§6公共资产",
                    List.of("§7资金、账本、Buff、税率和扩张"), "FINANCE", "0")));
            items.add(new MenuItem(14, button(Material.GOLDEN_HELMET, "§e成员治理",
                    List.of("§7成员、申请、投票和资料管理"), "GOVERNANCE_CENTER", null)));
            items.add(new MenuItem(16, button(pendingTotal > 0 ? Material.ENCHANTED_BOOK : Material.BOOK,
                    pendingTotal > 0 ? "§b待办中心 · " + pendingTotal : "§7待办中心",
                    List.of("§7集中处理需要你决定的事项"), "PENDING_CENTER", null)));
            items.add(new MenuItem(18, button(Material.PLAYER_HEAD, "§f个人与帮助",
                    List.of("§7领取手册、退出或解散小镇"), "PERSONAL_CENTER", null)));
        } else if (dashboard.application() != null) {
            ApplicationSnapshot application = dashboard.application();
            items.add(new MenuItem(0, button(Material.PAPER, "§6小镇申请办理中",
                    List.of("§7当前状态: §f" + application.status(),
                            application.reviewMessage() == null ? "§7请继续完成申请流程"
                                    : "§c管理员意见: " + application.reviewMessage()), null, null)));
            items.add(new MenuItem(11, button(Material.MAP, "§e继续小镇申请",
                    List.of("§7状态: " + application.status(),
                            application.reviewMessage() == null ? "§7点击查看摘要"
                                    : "§c管理员意见: " + application.reviewMessage()),
                    "APPLICATION", application.id().toString())));
        } else {
            items.add(new MenuItem(0, button(Material.BELL, "§6小镇服务",
                    List.of("§7你当前尚未加入任何小镇",
                            "§7可以申请建立小镇或加入现有小镇"), null, null)));
            if (dashboard.joinApplications().isEmpty()) {
                items.add(new MenuItem(11, button(Material.WRITABLE_BOOK, "§a申请建立小镇",
                        List.of("§7使用 Dialog 表单填写申请资料"),
                        "CREATE_APPLICATION", null)));
            }
            items.add(new MenuItem(13, button(Material.COMPASS, "§a申请加入小镇",
                    List.of("§7浏览小镇并提交 48 小时有效申请"), "JOIN_TOWNS", null)));
            if (!dashboard.joinApplications().isEmpty()) {
                items.add(new MenuItem(15, button(Material.PAPER, "§e我的入镇申请",
                        List.of("§7待处理: " + dashboard.joinApplications().size(),
                                "§7同时最多申请 3 个小镇"), "MY_JOIN_APPLICATIONS", null)));
            }
            items.add(new MenuItem(31, button(Material.WRITTEN_BOOK, "§6领取小镇手册",
                    List.of("§7手册丢失后可在服务台重新领取"), "GIVE_HANDBOOK", null)));
        }
        if (player.hasPermission("tianjitown.admin")) {
            boolean pending = !view.reviewQueue().isEmpty();
            items.add(new MenuItem(30, button(pending ? Material.ENCHANTED_BOOK : Material.BOOK,
                    pending ? "§e管理员审核 · " + view.reviewQueue().size() : "§7管理员审核",
                    pending ? List.of("§6有未完成的建镇申请") : List.of("§7查看建镇申请列表"),
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
                player.sendMessage("§c你不属于任何小镇。");
                openMain(player);
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
                    List.of("§7分页查看成员并进入治理详情"),
                    "MEMBERS", town.id() + ":0")));
            items.add(new MenuItem(12, button(Material.BOOK, "§e治理投票",
                    List.of("§7查看、参与或终止治理投票"),
                    "VOTES", town.id().toString())));
            if (governance.canReviewApplications()) {
                items.add(new MenuItem(14, button(pendingJoins > 0
                                ? Material.ENCHANTED_BOOK : Material.BOOK,
                        pendingJoins > 0 ? "§b入镇申请 · " + pendingJoins : "§7入镇申请",
                        List.of("§7审核玩家的入镇申请"),
                        "JOIN_APPLICATIONS", town.id().toString())));
            }
            if (governance.role().isLeader()) {
                items.add(new MenuItem(16, button(Material.WRITABLE_BOOK, "§e修改简介和规则",
                        List.of("§7使用 Dialog 表单编辑小镇资料"),
                        "EDIT_TOWN", town.id().toString())));
            }
            items.add(new MenuItem(22, button(Material.ARROW, "§7返回主菜单",
                    List.of(), "MAIN", null)));
            openMenu(player, 27, "成员治理", items);
        });
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
                        List.of("§7查看并审批待处理申请"),
                        "JOIN_APPLICATIONS", town.id().toString())));
            }
            if (pendingVotes > 0) {
                items.add(new MenuItem(12, button(Material.ENCHANTED_BOOK,
                        "§b治理投票 · " + pendingVotes,
                        List.of("§7参与尚未投票的治理事项"),
                        "VOTES", town.id().toString())));
            }
            if (governance.pendingTransfer() != null) {
                items.add(new MenuItem(14, button(Material.NETHER_STAR, "§e镇长转让邀请",
                        List.of("§7接受或拒绝接任本镇镇长"),
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
                    List.of("§7将手册放入背包；背包已满时掉落在脚下"),
                    "GIVE_HANDBOOK", null)));
            if (town != null && town.mayorId().equals(player.getUniqueId())) {
                items.add(new MenuItem(16, button(Material.TNT, "§4解散小镇",
                        List.of("§c仅剩镇长一人时可执行", "§c此操作不可撤销"),
                        "CONFIRM_DISBAND", town.id() + ":" + town.version())));
            } else if (town != null) {
                items.add(new MenuItem(16, button(Material.OAK_DOOR, "§c退出小镇",
                        List.of("§7退出后 24 小时内不能申请新镇"),
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
                    List.of("§7在 Dialog 中输入捐款金额",
                            "§7从个人 Vault 余额转入公共资金"),
                    "DONATION_INPUT", null)));
        }
        if (account.role().equals("MAYOR") && runtime.taxEnabled()) {
            items.add(new MenuItem(12, button(Material.GOLD_NUGGET, "§e税率设置",
                    List.of("§7进入独立表单调整统一收入税率"), "TAX_MENU", null)));
        }
        items.add(new MenuItem(14, button(Material.WRITTEN_BOOK, "§6小镇账本",
                List.of("§7在 Dialog 中分页查看公共资金流水",
                        "§7玩家税收与服务器补贴合并显示"), "LEDGER", "0")));
        items.add(new MenuItem(15, button(Material.BREWING_STAND, "§d公共 Buff",
                List.of(runtime.buffs().buffShopEnabled()
                                ? "§7查看效果、等级、时长和购买价格"
                                : "§7商店已暂停，可查看仍在生效的 Buff"),
                "BUFF_SHOP", null)));
        if (account.role().equals("MAYOR") && runtime.consumptionEnabled()) {
            items.add(new MenuItem(16, button(Material.FILLED_MAP, "§b领地扩张",
                    List.of("§7方向预览、线性价格和公共余额扣款"), "EXPANSION_MENU", null)));
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
            int[] rates = {500, 1000, 1500, 2000, 2500};
            List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>();
            for (int rate : rates) {
                if (rate <= runtime.economySettings().maximumTaxBps()) {
                    options.add(SingleOptionDialogInput.OptionEntry.create(
                            String.valueOf(rate), Component.text(TownRuntime.percent(rate)
                                    + (rate == account.taxRateBps() ? " · 当前" : "")),
                            rate == account.taxRateBps()));
                }
            }
            DialogInput input = DialogInput.singleOption("tax_rate", 360, options,
                    Component.text("选择统一收入税率", NamedTextColor.GOLD), true);
            openDialogPage(player, "设置收入税率", List.of(dialogBody(summary)), List.of(input),
                    DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE, session ->
                            DialogType.confirmation(
                                    ActionButton.create(Component.text("保存修改", NamedTextColor.GREEN),
                                            Component.text("保存后立即通知全体成员", NamedTextColor.GRAY),
                                            170, dialogAction(player, session,
                                                    response -> applyTaxDialog(player, account, response))),
                                    ActionButton.create(Component.text("取消", NamedTextColor.RED),
                                            null, 170,
                                            dialogAction(player, session, "FINANCE", "0"))));
        });
    }

    private void applyTaxDialog(Player player, EconomyRepository.TownFinance account,
                                DialogResponseView response) {
        String selected = response.getText("tax_rate");
        if (selected == null) {
            player.sendMessage("§c请选择一个税率。");
            openTaxMenu(player);
            return;
        }
        int rate;
        try {
            rate = Integer.parseInt(selected);
        } catch (NumberFormatException exception) {
            player.sendMessage("§c税率选项无效，请重新选择。");
            openTaxMenu(player);
            return;
        }
        if (rate == account.taxRateBps()) {
            player.sendMessage("§7税率没有变化。");
            openTaxMenu(player);
            return;
        }
        actions.changeTaxRate(player, account.townId(), rate, outcome ->
                handleOutcome(player, outcome, change -> {
                    notifyTaxRateChange(change);
                    openTaxMenu(player);
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
        runtime.read(player, () -> {
            Map<ExpansionDirection, TownRuntime.ExpansionPreview> previews = new LinkedHashMap<>();
            Map<ExpansionDirection, String> errors = new LinkedHashMap<>();
            for (ExpansionDirection direction : ExpansionDirection.values()) {
                try {
                    previews.put(direction, runtime.expansionPreview(player.getUniqueId(), direction));
                } catch (IllegalArgumentException exception) {
                    errors.put(direction, exception.getMessage());
                }
            }
            return new ExpansionMenu(previews, errors);
        }, menu -> {
            List<MenuItem> items = new ArrayList<>();
            int[] slots = {10, 12, 14, 16};
            int index = 0;
            for (ExpansionDirection direction : ExpansionDirection.values()) {
                TownRuntime.ExpansionPreview preview = menu.previews().get(direction);
                List<String> lore = preview == null
                        ? List.of("§c" + menu.errors().get(direction))
                        : List.of("§7目标网格: " + preview.candidate().gridX() + ","
                                + preview.candidate().gridZ(),
                                "§7价格: " + runtime.money(preview.priceMinor()),
                                "§7扩张后单元: " + preview.totalUnits(),
                                "§a点击传送并预览边界，再进入确认页");
                items.add(new MenuItem(slots[index++], button(preview == null
                        ? Material.GRAY_DYE : Material.COMPASS,
                        (preview == null ? "§7" : "§e") + "向" + direction.displayName() + "扩张",
                        lore, preview == null ? null : "PREVIEW_EXPANSION", direction.name())));
            }
            items.add(new MenuItem(22, button(Material.ARROW, "§7返回公共资金", List.of(),
                    "FINANCE", "0")));
            openMenu(player, 27, "3×3 固定网格扩张", items);
        });
    }

    private void previewExpansion(Player player, ExpansionDirection direction) {
        runtime.read(player, () -> runtime.expansionPreview(player.getUniqueId(), direction), preview -> {
            sitePolicy.teleportAndPreview(player, preview.candidate().territory());
            openConfirmation(player, "确认向" + direction.displayName() + "扩张", "EXPAND",
                    direction.name(), "将从公共资金扣除 " + runtime.money(preview.priceMinor())
                            + "，Residence 失败会自动退款", "EXPANSION_MENU", null);
        });
    }

    void openBuffShop(Player player) {
        runtime.read(player, () -> {
            Map<String, CommerceRepository.BuffQuote> quotes = new LinkedHashMap<>();
            Map<String, String> errors = new LinkedHashMap<>();
            for (BuffDefinition definition : runtime.buffs().settings().buffs().values()) {
                try {
                    quotes.put(definition.key(), runtime.buffs().repository().quoteBuff(
                            player.getUniqueId(), definition, BuffDurationOption.ONE_HOUR,
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
                                    : "§e商店已暂停新购买，现有效果仍持续到期",
                            "§7可选择一小时、一天、一周或一月，长时段按比例折扣"), null, null)));
            int slot = 9;
            for (BuffDefinition definition : runtime.buffs().settings().buffs().values()) {
                CommerceRepository.BuffQuote quote = view.quotes().get(definition.key());
                CommerceRepository.ActiveBuff current = active.get(definition.key());
                List<String> lore = new ArrayList<>();
                lore.add("§7类型: " + definition.effectKind());
                lore.add("§7效果: " + definition.effectKey());
                lore.add("§7持续: 可选一小时 / 一天 / 一周 / 一月");
                lore.add("§7叠加: " + definition.stackingRule() + "，上限 "
                        + definition.maximumLevel());
                if (current != null) {
                    lore.add("§a当前等级 " + current.level() + " / 层数 "
                            + current.stackCount());
                    lore.add("§a到期: " + current.expiresAt());
                }
                String error = view.errors().get(definition.key());
                if (quote != null) {
                    lore.add("§e一小时价格: " + runtime.money(quote.priceMinor()));
                    lore.add("§e购买后等级: " + quote.nextLevel());
                } else if (error != null) {
                    lore.add("§c" + error);
                }
                boolean purchasable = runtime.buffs().buffShopEnabled() && quote != null;
                items.add(new MenuItem(slot++, button(purchasable ? Material.POTION
                                : Material.GLASS_BOTTLE, "§d" + definition.displayName(), lore,
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
            Map<BuffDurationOption, CommerceRepository.BuffQuote> quotes = new LinkedHashMap<>();
            for (BuffDurationOption duration : BuffDurationOption.values()) {
                quotes.put(duration, runtime.buffs().repository().quoteBuff(
                        player.getUniqueId(), definition, duration,
                        runtime.settlement().scale(), Instant.now()));
            }
            return quotes;
        }, quotes -> {
            BuffDefinition definition = runtime.buffs().settings().requireBuff(buffKey);
            List<SingleOptionDialogInput.OptionEntry> options = new ArrayList<>();
            for (Map.Entry<BuffDurationOption, CommerceRepository.BuffQuote> entry
                    : quotes.entrySet()) {
                BuffDurationOption duration = entry.getKey();
                CommerceRepository.BuffQuote quote = entry.getValue();
                int discount = 100 - duration.discountBasisPoints() / 100;
                options.add(SingleOptionDialogInput.OptionEntry.create(duration.name(),
                        Component.text(duration.displayName() + " · "
                                + runtime.money(quote.priceMinor()) + " · 折扣 " + discount + "%"
                                + " · 购买后等级 " + quote.nextLevel()),
                        duration == BuffDurationOption.ONE_HOUR));
            }
            ItemStack summary = button(Material.POTION, "§d" + definition.displayName(),
                    List.of("§7类型: " + definition.effectKind(),
                            "§7效果: " + definition.effectKey(),
                            "§7请选择持续时间；最终价格已显示在选项中"), null, null);
            DialogInput duration = DialogInput.singleOption("buff_duration", 420, options,
                    Component.text("选择持续时间", NamedTextColor.GOLD), true);
            openDialogPage(player, "购买公共 Buff", List.of(dialogBody(summary)),
                    List.of(duration), DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE,
                    session -> DialogType.confirmation(
                            ActionButton.create(Component.text("继续购买", NamedTextColor.GREEN),
                                    null, 170, dialogAction(player, session,
                                            response -> applyBuffDurationDialog(
                                                    player, buffKey, response))),
                            ActionButton.create(Component.text("取消", NamedTextColor.RED),
                                    null, 170,
                                    dialogAction(player, session, "BUFF_SHOP", null))));
        });
    }

    private void applyBuffDurationDialog(Player player, String buffKey,
                                         DialogResponseView response) {
        String selected = response.getText("buff_duration");
        if (selected == null) {
            player.sendMessage("§c请选择购买时长。");
            openBuffDurations(player, buffKey);
            return;
        }
        BuffDurationOption duration;
        try {
            duration = BuffDurationOption.valueOf(selected);
        } catch (IllegalArgumentException exception) {
            player.sendMessage("§cBuff 时长选项无效，请重新选择。");
            openBuffDurations(player, buffKey);
            return;
        }
        actions.buyBuff(player, buffKey, duration, outcome ->
                handleOutcome(player, outcome, purchase -> {
                    BuffDefinition definition = runtime.buffs().settings().requireBuff(buffKey);
                    openNotice(player, "购买成功",
                            "已购买 " + definition.displayName() + "\n等级: "
                                    + purchase.buff().level() + "\n到期: "
                                    + purchase.buff().expiresAt() + "\n公共余额: "
                                    + runtime.money(purchase.balanceAfterMinor()),
                            "返回公共资产", "FINANCE", "0");
                }));
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
        Component rules = Component.text("小镇: " + governance.townName(), NamedTextColor.GRAY)
                .append(Component.newline())
                .append(Component.text("新规则版本: " + governance.townRulesRevision(),
                        NamedTextColor.GRAY));
        for (int index = 0; index < governance.rules().size(); index++) {
            rules = rules.append(Component.newline()).append(Component.newline())
                    .append(Component.text((index + 1) + ". " + governance.rules().get(index),
                            NamedTextColor.WHITE));
        }
        rules = rules.append(Component.newline()).append(Component.newline())
                .append(Component.text("确认前不能继续使用其他小镇功能", NamedTextColor.RED));
        DialogInput acknowledged = DialogInput.bool("rules_acknowledged",
                Component.text("我已阅读并理解以上规则", NamedTextColor.GOLD),
                false, "true", "false");
        String target = governance.townId() + ":" + governance.townRulesRevision();
        openDialogPage(player, "小镇规则已更新",
                List.of(DialogBody.item(new ItemStack(Material.WRITTEN_BOOK),
                                DialogBody.plainMessage(rules, 420), false, false, 48, 48)),
                List.of(acknowledged), DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE,
                session -> DialogType.confirmation(
                        ActionButton.create(Component.text("确认规则", NamedTextColor.GREEN),
                                null, 170, dialogAction(player, session,
                                        response -> acknowledgeRulesDialog(player, target, response))),
                        ActionButton.create(Component.text("稍后", NamedTextColor.RED),
                                Component.text("关闭界面；小镇功能仍保持锁定", NamedTextColor.GRAY),
                                170, dialogAction(player, session, "CLOSE", null))));
    }

    private void acknowledgeRulesDialog(Player player, String target,
                                        DialogResponseView response) {
        if (!Boolean.TRUE.equals(response.getBoolean("rules_acknowledged"))) {
            player.sendMessage("§c请先勾选“我已阅读并理解以上规则”。");
            openMain(player);
            return;
        }
        acknowledgeRules(player, target);
    }

    private void openApplication(Player player, ApplicationSnapshot application) {
        List<String> summary = new ArrayList<>(List.of("§7名称: " + application.text().name(),
                "§7简称: " + application.text().shortName(),
                "§7领地名称: " + application.text().residenceName(),
                "§7状态: " + application.status(),
                "§7建镇申请费: §f2000（批准后转为初始公共资金）"));
        for (InitialMemberConfirmation member : application.initialMembers()) {
            summary.add("§7初始成员: " + displayName(member.playerId()) + " · "
                    + member.status());
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
                    List.of("§7使用分步 Dialog 表单修改资料"), "EDIT_APPLICATION",
                    application.id().toString())));
            items.add(new MenuItem(12, button(Material.COMPASS, "§e选择当前区块",
                    List.of("§7当前区块将成为 3×3 初始领地中心"), "SELECT_SITE",
                    application.id().toString())));
            if (application.territory() != null) {
                items.add(new MenuItem(14, button(Material.ENDER_EYE, "§b预览已选领地",
                        List.of("§7传送至领地中心并显示火焰边界"), "PREVIEW_SITE",
                        application.id().toString())));
                boolean confirmed = application.initialMembersConfirmed();
                items.add(new MenuItem(16, button(confirmed ? Material.LIME_CONCRETE
                                : Material.GRAY_CONCRETE,
                        confirmed ? "§a提交申请" : "§7等待初始成员确认",
                        confirmed ? List.of("§7进入确认页面", "§7批准时申请人需支付 2000")
                                : List.of("§c两名初始成员均确认后才可提交"),
                        confirmed ? "CONFIRM_SUBMIT" : null,
                        confirmed ? application.id().toString() : null)));
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
            items.add(new MenuItem(10, button(Material.WRITTEN_BOOK, "§e阅读小镇规则",
                    List.of("§7完整查看当前规则"), "TOWN_RULES",
                    town.id().toString())));
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

    private void openTownRules(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException("小镇不存在")), town -> {
            Component content = Component.text(town.profile().name() + " · 当前规则",
                    NamedTextColor.GOLD);
            if (town.profile().rules().isEmpty()) {
                content = content.append(Component.newline()).append(Component.newline())
                        .append(Component.text("暂未设置规则", NamedTextColor.GRAY));
            } else {
                for (int index = 0; index < town.profile().rules().size(); index++) {
                    content = content.append(Component.newline()).append(Component.newline())
                            .append(Component.text((index + 1) + ". "
                                    + town.profile().rules().get(index), NamedTextColor.WHITE));
                }
            }
            openDialogPage(player, "小镇规则", List.of(
                            DialogBody.item(new ItemStack(Material.WRITTEN_BOOK),
                                    DialogBody.plainMessage(content, 420), false, false, 48, 48)),
                    List.of(), DialogBase.DialogAfterAction.NONE,
                    session -> DialogType.notice(ActionButton.create(
                            Component.text("返回小镇资料", NamedTextColor.GREEN), null, 220,
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
                MemberRole nextRole = view.targetRole() == MemberRole.DEPUTY_MAYOR
                        ? MemberRole.MEMBER : MemberRole.DEPUTY_MAYOR;
                items.add(new MenuItem(10, button(Material.GOLDEN_HELMET,
                        nextRole == MemberRole.DEPUTY_MAYOR
                                ? "§a任命为副镇长" : "§e降为普通镇员",
                        List.of("§7每个小镇最多 3 名副镇长"), "CONFIRM_ROLE",
                        townId + ":" + targetId + ":" + nextRole)));
            }
            boolean viewerCanRemove = view.viewer().role().isLeader() && !targetIsMayor
                    && (viewerIsMayor || view.targetRole() == MemberRole.MEMBER);
            if (viewerCanRemove) {
                items.add(new MenuItem(12, button(Material.RED_CONCRETE, "§c移除成员",
                        List.of("§c立即移出小镇并同步 Residence", "§7需要再次确认"),
                        "CONFIRM_KICK_MEMBER", townId + ":" + targetId)));
            }
            if (viewerIsMayor && !targetIsMayor) {
                items.add(new MenuItem(14, button(Material.NETHER_STAR, "§e发起镇长转让",
                        List.of("§7候选成员必须在 24 小时内接受"), "CONFIRM_TRANSFER_MAYOR",
                        townId + ":" + targetId)));
            }
            if (!targetIsMayor && !targetId.equals(player.getUniqueId())) {
                items.add(new MenuItem(16, button(Material.PAPER, "§e发起投票移除",
                        List.of("§7赞成票必须严格超过有效选民的 50%"),
                        "CONFIRM_CREATE_VOTE", townId + ":KICK_MEMBER:" + targetId)));
            }
            if (!targetIsMayor && view.viewer().role().isLeader()) {
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
                        List.of("§7赞成: " + vote.yesVotes() + "/" + vote.requiredYes(),
                                "§7反对: " + vote.noVotes(), "§7到期: " + vote.endsAt()),
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
            if (vote.createdBy().equals(player.getUniqueId())) {
                items.add(new MenuItem(18, button(Material.BARRIER, "§c终止本次投票",
                        List.of("§7仅发起人可操作", "§c终止后不可恢复"),
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
                        List.of("§7简称: " + town.profile().shortName(),
                                "§7简介: " + preview(town.profile().description(), 80),
                                "§7点击查看规则并申请加入"),
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
                        List.of("§7申请时间: " + application.createdAt(),
                                "§7到期: " + application.expiresAt(), "§7点击审核"),
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
        openAdminApplications(admin, 0);
    }

    private void openAdminApplications(Player admin, int requestedPage) {
        if (!admin.hasPermission("tianjitown.admin")) {
            admin.sendMessage("§c没有管理员权限。");
            return;
        }
        int page = Math.max(0, requestedPage);
        runtime.read(admin, () -> runtime.repository().listReviewQueue(45), applications -> {
            List<MenuItem> items = new ArrayList<>();
            List<ApplicationSnapshot> visible = page(applications, page, 8);
            for (int index = 0; index < visible.size(); index++) {
                ApplicationSnapshot application = visible.get(index);
                Material material = application.status() == ApplicationStatus.PROVISION_FAILED
                        ? Material.REDSTONE_BLOCK : Material.WRITABLE_BOOK;
                items.add(new MenuItem(index, button(material, "§e" + application.text().name(),
                        List.of("§7简称: " + application.text().shortName(),
                                "§7状态: " + application.status(), "§7点击查看并处理"),
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
                    "§7状态: " + application.status(),
                    "§7申请费: 2000（批准后成为初始公共资金）"));
            for (InitialMemberConfirmation member : application.initialMembers()) {
                summary.add("§7初始成员: " + displayName(member.playerId()) + " · "
                        + member.status());
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
                        List.of("§7创建小镇并投影 3×3 Residence"), "CONFIRM_ADMIN_APPROVE",
                        application.id().toString())));
                items.add(new MenuItem(12, button(Material.RED_CONCRETE, "§c拒绝",
                        List.of("§7在 Dialog 中填写拒绝原因"), "CONFIRM_ADMIN_REJECT",
                        application.id().toString())));
                items.add(new MenuItem(14, button(Material.ORANGE_CONCRETE, "§e要求补件",
                        List.of("§7在 Dialog 中填写修改要求"), "CONFIRM_ADMIN_CHANGE",
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
            closeUi(player);
            player.sendMessage("§c小镇系统已进入维护模式，本次操作未执行。");
            return;
        }
        try {
            switch (action) {
                case "MAIN" -> openMain(player);
                case "CLOSE" -> closeUi(player);
                case "GIVE_HANDBOOK" -> {
                    giveHandbook(player);
                    openMain(player);
                }
                case "CREATE_APPLICATION" -> startApplicationForm(player, null, 0,
                        new ApplicationText("", "", "", "", List.of()), List.of());
                case "APPLICATION" -> loadApplication(player, UUID.fromString(target));
                case "EDIT_APPLICATION" -> loadApplicationForForm(player, UUID.fromString(target));
                case "SELECT_SITE" -> selectSite(player, UUID.fromString(target));
                case "PREVIEW_SITE" -> previewApplication(player, UUID.fromString(target));
                case "CONFIRM_SUBMIT" -> openConfirmation(player, "确认提交申请", "SUBMIT", target,
                        "提交后需等待管理员审核", "APPLICATION", target);
                case "SUBMIT" -> submit(player, UUID.fromString(target));
                case "CONFIRM_CANCEL" -> openConfirmation(player, "确认撤回申请", "CANCEL", target,
                        "撤回会释放选址并进入冷却", "APPLICATION", target);
                case "CANCEL" -> cancel(player, UUID.fromString(target));
                case "TOWN" -> openTown(player, UUID.fromString(target));
                case "TOWN_RULES" -> openTownRules(player, UUID.fromString(target));
                case "FINANCE" -> openFinance(player, Integer.parseInt(target));
                case "GOVERNANCE_CENTER" -> openGovernanceCenter(player);
                case "PENDING_CENTER" -> openPendingCenter(player);
                case "PERSONAL_CENTER" -> openPersonalCenter(player);
                case "TAX_MENU" -> openTaxMenu(player);
                case "LEDGER" -> openLedger(player, Integer.parseInt(target));
                case "BUFF_SHOP" -> openBuffShop(player);
                case "BUFF_DURATIONS" -> openBuffDurations(player, target);
                case "DONATION_INPUT" -> startDonationInput(player);
                case "EXPANSION_MENU" -> openExpansionMenu(player);
                case "PREVIEW_EXPANSION" -> previewExpansion(player,
                        ExpansionDirection.valueOf(target));
                case "EXPAND" -> actions.expandTown(player, ExpansionDirection.valueOf(target),
                        outcome -> handleOutcome(player, outcome, operation -> {
                            player.sendMessage("§a领地扩张完成，公共资金已扣款。");
                            openExpansionMenu(player);
                        }));
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
                case "VOTES" -> openVotes(player, UUID.fromString(target));
                case "VOTES_PAGE" -> {
                    String[] parts = target.split(":");
                    openVotes(player, UUID.fromString(parts[0]), Integer.parseInt(parts[1]));
                }
                case "VOTE_DETAIL" -> openVote(player, UUID.fromString(target));
                case "CONFIRM_CREATE_VOTE" -> openConfirmation(player, "确认发起治理投票",
                        "CREATE_VOTE", target, "选民快照和通过门槛将在创建时冻结",
                        "MEMBER_DETAIL", memberTarget(target));
                case "CREATE_VOTE" -> createVote(player, target);
                case "CAST_VOTE" -> castVote(player, target);
                case "CONFIRM_CANCEL_VOTE" -> openConfirmation(player, "确认终止治理投票",
                        "CANCEL_VOTE", target, "投票将立即结束且不可恢复",
                        "VOTE_DETAIL", target);
                case "CANCEL_VOTE" -> cancelVote(player, UUID.fromString(target));
                case "PREVIEW_TOWN" -> previewTown(player, UUID.fromString(target));
                case "JOIN_TOWNS" -> openJoinTowns(player);
                case "JOIN_TOWNS_PAGE" -> openJoinTowns(player, Integer.parseInt(target));
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
                case "JOIN_APPLICATIONS_PAGE" -> {
                    String[] parts = target.split(":");
                    openTownJoinApplications(player, UUID.fromString(parts[0]),
                            Integer.parseInt(parts[1]));
                }
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
                case "ADMIN_APPLICATIONS_PAGE" -> openAdminApplications(player,
                        Integer.parseInt(target));
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
        actions.changeMemberRole(mayor, townId, playerId, role, outcome ->
                handleOutcome(mayor, outcome, changed -> {
            mayor.sendMessage("§a成员角色已调整为 " + changed + "，正在复核 Residence 权限。");
            openMemberDetail(mayor, townId, playerId);
        }));
    }

    private void kickMember(Player mayor, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        UUID playerId = UUID.fromString(parts[1]);
        actions.kickMember(mayor, townId, playerId, outcome ->
                handleOutcome(mayor, outcome, changedTown -> {
            mayor.sendMessage("§a成员已移出小镇，正在同步 Residence 权限。");
            Player removed = Bukkit.getPlayer(playerId);
            if (removed != null) {
                removed.sendMessage("§c你已被小镇管理组移出小镇。");
            }
            openMembers(mayor, changedTown, 0);
        }));
    }

    private void requestMayorTransfer(Player mayor, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        UUID candidateId = UUID.fromString(parts[1]);
        actions.requestMayorTransfer(mayor, townId, candidateId, outcome ->
                handleOutcome(mayor, outcome, transfer -> {
            mayor.sendMessage("§a镇长转让请求已发出，有效期至 " + transfer.expiresAt() + "。");
            Player candidate = Bukkit.getPlayer(candidateId);
            if (candidate != null) {
                candidate.sendMessage(Component.text("你收到了一项镇长转让请求。 ",
                                NamedTextColor.GOLD)
                        .append(callbackButton(candidate, "[处理]",
                                () -> openTransferRequest(candidate, transfer.id()))));
            }
            openMain(mayor);
        }));
    }

    private void decideMayorTransfer(Player candidate, String target) {
        String[] parts = target.split(":");
        UUID transferId = UUID.fromString(parts[0]);
        boolean accept = Boolean.parseBoolean(parts[1]);
        actions.decideMayorTransfer(candidate, transferId, accept, outcome ->
                handleOutcome(candidate, outcome, transfer -> {
            candidate.sendMessage(accept ? "§a你已接任镇长。" : "§e你已拒绝本次镇长转让。");
            Player oldMayor = Bukkit.getPlayer(transfer.requestedBy());
            if (oldMayor != null) {
                oldMayor.sendMessage(accept ? "§e镇长转让已被接受，你现在是普通成员。"
                        : "§e候选成员拒绝了镇长转让。");
            }
            openMain(candidate);
        }));
    }

    private void acknowledgeRules(Player player, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        long revision = Long.parseLong(parts[1]);
        actions.acknowledgeRules(player, townId, revision, outcome ->
                handleOutcome(player, outcome, confirmed -> {
            player.sendMessage("§a已记录你对规则版本 " + confirmed + " 的确认。");
            openMain(player);
        }));
    }

    private void createVote(Player player, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        VoteType type = VoteType.valueOf(parts[1]);
        UUID targetId = UUID.fromString(parts[2]);
        actions.createVote(player, townId, type, targetId, outcome ->
                handleOutcome(player, outcome, vote -> {
            player.sendMessage("§a治理投票已创建：有效选民 " + vote.eligibleVoters()
                    + " 人，通过需 " + vote.requiredYes() + " 票。");
            openVote(player, vote.id());
        }));
    }

    private void castVote(Player player, String target) {
        String[] parts = target.split(":");
        UUID voteId = UUID.fromString(parts[0]);
        boolean approve = Boolean.parseBoolean(parts[1]);
        actions.castVote(player, voteId, approve, outcome ->
                handleOutcome(player, outcome, vote -> {
            player.sendMessage("§a投票已记录。当前赞成/门槛：" + vote.yesVotes()
                    + "/" + vote.requiredYes() + "，状态：" + vote.status());
            openVotes(player, vote.townId());
        }));
    }

    private void cancelVote(Player player, UUID voteId) {
        actions.cancelOwnVote(player, voteId, outcome ->
                handleOutcome(player, outcome, vote -> {
                    player.sendMessage("§e你已终止本次治理投票。");
                    openVotes(player, vote.townId());
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
                member.sendMessage(Component.text("小镇税率已更新为 "
                                + TownRuntime.percent(change.basisPoints())
                                + "，同步用于 QuickShop、Jobs 与全球市场收入。 ",
                                NamedTextColor.YELLOW)
                        .append(callbackButton(member, "[查看税率]",
                                () -> openTaxMenu(member)))
                        .append(Component.space())
                        .append(callbackButton(member, "[查看公共资金]",
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
            sitePolicy.preview(player, application.territory());
            openApplication(player, application);
        }));
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
        actions.submitApplication(player, applicationId, outcome ->
                handleOutcome(player, outcome, application -> {
                    player.sendMessage("§a申请已提交，等待管理员审核。");
                    playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
                    notifyApplicationSubmitted(application);
                    openApplication(player, application);
                }));
    }

    private void cancel(Player player, UUID applicationId) {
        actions.cancelApplication(player, applicationId, outcome ->
                handleOutcome(player, outcome, application -> {
            player.sendMessage("§e申请已撤回，选址预留已释放。");
            openMain(player);
        }));
    }

    private void applyJoin(Player player, UUID townId) {
        actions.applyToTown(player, townId, outcome ->
                handleOutcome(player, outcome, application -> {
                    player.sendMessage("§a入镇申请已提交，有效期至 " + application.expiresAt() + "。");
                    playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING);
                    notifyMayorJoinApplication(application);
                    openMyJoinApplications(player);
                }));
    }

    private void cancelJoin(Player player, UUID applicationId) {
        actions.cancelJoinApplication(player, applicationId, outcome ->
                handleOutcome(player, outcome, ignored -> {
            player.sendMessage("§e入镇申请已撤回。");
            playSound(player, Sound.UI_BUTTON_CLICK);
            openMyJoinApplications(player);
        }));
    }

    private void approveJoin(Player mayor, UUID applicationId) {
        actions.approveJoinApplication(mayor, applicationId, outcome ->
                handleOutcome(mayor, outcome, application -> {
            mayor.sendMessage("§a已批准入镇申请，正在同步 Residence 成员权限。");
            playSound(mayor, Sound.ENTITY_PLAYER_LEVELUP);
            notifyJoinDecision(application, true);
            openTownJoinApplications(mayor, application.townId());
        }));
    }

    private void rejectJoin(Player mayor, UUID applicationId) {
        actions.rejectJoinApplication(mayor, applicationId, outcome ->
                handleOutcome(mayor, outcome, application -> {
            mayor.sendMessage("§e已拒绝该入镇申请。");
            playSound(mayor, Sound.UI_BUTTON_CLICK);
            notifyJoinDecision(application, false);
            openTownJoinApplications(mayor, application.townId());
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
            admin.sendMessage("§c没有管理员权限。");
            return;
        }
        runtime.read(admin, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("申请不存在")), application -> {
            Component explanation = Component.text(requestChanges ? "要求补充资料" : "拒绝申请",
                            requestChanges ? NamedTextColor.YELLOW : NamedTextColor.RED)
                    .append(Component.newline())
                    .append(Component.text("小镇: " + application.text().name(), NamedTextColor.GRAY))
                    .append(Component.newline())
                    .append(Component.text(requestChanges
                            ? "请写明需要申请人补充或修改的内容。"
                            : "请写明拒绝原因，便于申请人理解和改进。",
                            NamedTextColor.WHITE));
            if (error != null) {
                explanation = explanation.append(Component.newline()).append(Component.newline())
                        .append(Component.text(error, NamedTextColor.RED));
            }
            DialogInput reasonInput = DialogInput.text("review_reason", 400,
                    Component.text(requestChanges ? "修改要求" : "拒绝原因",
                            NamedTextColor.GOLD), true, initialReason, 500,
                    TextDialogInput.MultilineOptions.create(6, 110));
            openDialogPage(admin, requestChanges ? "要求修改申请" : "拒绝小镇申请",
                    List.of(DialogBody.item(new ItemStack(requestChanges
                                            ? Material.WRITABLE_BOOK : Material.BARRIER),
                                    DialogBody.plainMessage(explanation, 420),
                                    false, false, 48, 48)),
                    List.of(reasonInput), DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE,
                    session -> DialogType.confirmation(
                            ActionButton.create(Component.text(requestChanges
                                            ? "发送修改要求" : "确认拒绝",
                                    requestChanges ? NamedTextColor.YELLOW : NamedTextColor.RED),
                                    Component.text("操作将立即记录并通知申请人",
                                            NamedTextColor.GRAY), 190,
                                    dialogAction(admin, session, response -> applyReviewReason(
                                            admin, applicationId, requestChanges, response))),
                            ActionButton.create(Component.text("取消", NamedTextColor.GRAY),
                                    null, 150, dialogAction(admin, session,
                                            "ADMIN_APPLICATION", applicationId.toString()))));
        });
    }

    private void applyReviewReason(Player admin, UUID applicationId, boolean requestChanges,
                                   DialogResponseView response) {
        if (!admin.hasPermission("tianjitown.admin")) {
            admin.sendMessage("§c没有管理员权限，本次审批未执行。");
            return;
        }
        String reason = responseText(response, "review_reason");
        if (reason.isBlank() || reason.length() > 500) {
            openAdminDecisionDialog(admin, applicationId, requestChanges, reason,
                    reason.isBlank() ? "原因不能为空。" : "原因不能超过 500 个字符。");
            return;
        }
        adminDecision(admin, applicationId, requestChanges, reason);
    }

    private void adminDecision(Player admin, UUID applicationId, boolean requestChanges,
                               String reason) {
        actions.reviewApplication(admin, applicationId, requestChanges, reason, outcome ->
                handleOutcome(admin, outcome, application -> {
            admin.sendMessage(requestChanges ? "§a已要求申请人补充资料。" : "§a申请已拒绝。");
            playSound(admin, Sound.UI_BUTTON_CLICK);
            notifyApplicationDecision(application);
            openAdminApplications(admin);
        }));
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
            closeUi(admin);
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
            playSound(admin, Sound.BLOCK_BELL_USE);
        }
    }

    private void leave(Player player, UUID townId) {
        actions.leaveTown(player, townId, outcome -> handleOutcome(player, outcome, result -> {
            player.sendMessage("§e你已退出小镇。");
            openMain(player);
        }));
    }

    private void disband(Player mayor, String target) {
        String[] parts = target.split(":", 2);
        UUID townId = UUID.fromString(parts[0]);
        long expectedVersion = Long.parseLong(parts[1]);
        actions.disbandTown(mayor, townId, expectedVersion, outcome ->
                handleOutcome(mayor, outcome, completed -> {
                mayor.sendMessage("§a小镇“" + completed.profile().name()
                        + "”已解散，领地、成员、名称和区块占位均已释放。");
                playSound(mayor, Sound.BLOCK_ANVIL_LAND);
                openMain(mayor);
            }));
    }

    private void startApplicationForm(Player player, UUID targetId, long version,
                                      ApplicationText text, List<String> initialMemberNames) {
        if (maintenanceMode()) {
            player.sendMessage("§c小镇系统正在维护，申请编辑暂时停用。");
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
            player.sendMessage("§c小镇系统正在维护，资料编辑暂时停用。");
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
            player.sendMessage("§c申请编辑会话已失效，请重新打开。");
            return;
        }
        if (form.purpose() == FormPurpose.TOWN_PROFILE) {
            renderTownProfileDialog(player, form);
        } else {
            renderApplicationBasicsDialog(player, form);
        }
    }

    private void renderApplicationBasicsDialog(Player player, ApplicationFormSession form) {
        ApplicationText text = form.text();
        List<DialogInput> inputs = List.of(
                DialogInput.text("town_name", 380,
                        Component.text("小镇名称", NamedTextColor.GOLD), true,
                        text.name(), 24, null),
                DialogInput.text("short_name", 380,
                        Component.text("小镇简称", NamedTextColor.GOLD), true,
                        text.shortName(), 8, null),
                DialogInput.text("residence_name", 380,
                        Component.text("Residence 领地名称", NamedTextColor.GOLD), true,
                        text.residenceName(), 12, null));
        Component guidance = Component.text("第 1 / 3 步 · 基础资料", NamedTextColor.GOLD)
                .append(Component.newline())
                .append(Component.text("名称和简称用于玩家界面；领地名称只能包含 1～12 个英文字母。",
                        NamedTextColor.GRAY));
        openDialogPage(player, "申请建立小镇", List.of(
                        DialogBody.item(new ItemStack(Material.WRITABLE_BOOK),
                                DialogBody.plainMessage(guidance, 400), false, false, 48, 48)),
                inputs, DialogBase.DialogAfterAction.NONE, session ->
                        DialogType.confirmation(
                                ActionButton.create(Component.text("下一步", NamedTextColor.GREEN),
                                        null, 170, dialogAction(player, session,
                                                response -> applyApplicationBasics(
                                                        player, form.id(), response))),
                                ActionButton.create(Component.text("取消编辑", NamedTextColor.RED),
                                        null, 170, dialogAction(player, session,
                                                response -> cancelApplicationForm(player, form.id())))));
    }

    private void applyApplicationBasics(Player player, UUID formId,
                                        DialogResponseView response) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        ApplicationText updated = new ApplicationText(
                responseText(response, "town_name"),
                responseText(response, "short_name"),
                responseText(response, "residence_name"),
                form.text().description(), form.text().rules());
        ApplicationFormSession candidate = new ApplicationFormSession(form.id(), form.purpose(),
                form.targetId(), form.version(), updated, form.initialMemberNames());
        applicationForms.put(player.getUniqueId(), candidate);
        List<String> errors = new ArrayList<>();
        errors.addAll(fieldErrors(player, candidate, ApplicationField.NAME));
        errors.addAll(fieldErrors(player, candidate, ApplicationField.SHORT_NAME));
        errors.addAll(fieldErrors(player, candidate, ApplicationField.RESIDENCE_NAME));
        if (!errors.isEmpty()) {
            player.sendMessage("§c基础资料有误: " + String.join("；", errors));
            renderApplicationBasicsDialog(player, candidate);
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
                        Component.text("小镇简介", NamedTextColor.GOLD), true,
                        form.text().description(), 500, descriptionLines),
                DialogInput.text("rules", 400,
                        Component.text("小镇规则（每行一条）", NamedTextColor.GOLD), true,
                        String.join("\n", form.text().rules()), 5000, ruleLines));
        Component guidance = Component.text("第 2 / 3 步 · 简介和规则", NamedTextColor.GOLD)
                .append(Component.newline())
                .append(Component.text("规则至少填写一条、最多 50 条；每行视为一条规则。",
                        NamedTextColor.GRAY));
        openDialogPage(player, "申请建立小镇", List.of(
                        DialogBody.item(new ItemStack(Material.BOOK),
                                DialogBody.plainMessage(guidance, 420), false, false, 48, 48)),
                inputs, DialogBase.DialogAfterAction.NONE, session -> {
                    List<ActionButton> actions = List.of(
                            ActionButton.create(Component.text("上一步", NamedTextColor.GRAY),
                                    null, 150, dialogAction(player, session,
                                            response -> renderApplicationBasicsDialog(player, form))),
                            ActionButton.create(Component.text("下一步", NamedTextColor.GREEN),
                                    null, 150, dialogAction(player, session,
                                            response -> applyApplicationContent(
                                                    player, form.id(), response))));
                    return DialogType.multiAction(actions)
                            .exitAction(ActionButton.create(
                                    Component.text("取消编辑", NamedTextColor.RED), null, 140,
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
            player.sendMessage("§c简介或规则有误: " + String.join("；", errors));
            renderApplicationContentDialog(player, candidate);
            return;
        }
        renderApplicationMembersDialog(player, candidate);
    }

    private void renderApplicationMembersDialog(Player player, ApplicationFormSession form) {
        List<SingleOptionDialogInput.OptionEntry> options = Bukkit.getOnlinePlayers().stream()
                .filter(candidate -> !candidate.getUniqueId().equals(player.getUniqueId()))
                .sorted(java.util.Comparator.comparing(Player::getName,
                        String.CASE_INSENSITIVE_ORDER))
                .map(candidate -> SingleOptionDialogInput.OptionEntry.create(candidate.getName(),
                        Component.text(candidate.getName()), false))
                .toList();
        if (options.size() < 2) {
            player.sendMessage("§c至少需要两名其他在线玩家才能保存建镇申请。");
            renderApplicationContentDialog(player, form);
            return;
        }
        List<DialogInput> inputs = List.of(
                DialogInput.singleOption("member_one", 380,
                        selectedOptions(options, form.initialMemberNames().get(0)),
                        Component.text("初始成员一", NamedTextColor.GOLD), true),
                DialogInput.singleOption("member_two", 380,
                        selectedOptions(options, form.initialMemberNames().get(1)),
                        Component.text("初始成员二", NamedTextColor.GOLD), true));
        Component guidance = Component.text("第 3 / 3 步 · 初始成员", NamedTextColor.GOLD)
                .append(Component.newline())
                .append(Component.text("请选择两名不同的在线玩家。保存后系统会请求他们确认。",
                        NamedTextColor.GRAY));
        openDialogPage(player, "申请建立小镇", List.of(
                        DialogBody.item(new ItemStack(Material.PLAYER_HEAD),
                                DialogBody.plainMessage(guidance, 400), false, false, 48, 48)),
                inputs, DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE, session -> {
                    List<ActionButton> actions = List.of(
                            ActionButton.create(Component.text("上一步", NamedTextColor.GRAY),
                                    null, 150, dialogAction(player, session,
                                            response -> renderApplicationContentDialog(player, form))),
                            ActionButton.create(Component.text("保存申请资料", NamedTextColor.GREEN),
                                    null, 180, dialogAction(player, session,
                                            response -> applyApplicationMembers(
                                                    player, form.id(), response))));
                    return DialogType.multiAction(actions)
                            .exitAction(ActionButton.create(
                                    Component.text("取消编辑", NamedTextColor.RED), null, 140,
                                    dialogAction(player, session,
                                            response -> cancelApplicationForm(player, form.id()))))
                            .columns(2).build();
                });
    }

    private void applyApplicationMembers(Player player, UUID formId,
                                         DialogResponseView response) {
        ApplicationFormSession form = requireApplicationForm(player, formId);
        if (form == null) {
            return;
        }
        List<String> members = List.of(responseText(response, "member_one"),
                responseText(response, "member_two"));
        ApplicationFormSession candidate = new ApplicationFormSession(form.id(), form.purpose(),
                form.targetId(), form.version(), form.text(), members);
        applicationForms.put(player.getUniqueId(), candidate);
        try {
            requireInitialMemberIds(player, members);
        } catch (IllegalArgumentException exception) {
            player.sendMessage("§c" + exception.getMessage());
            renderApplicationMembersDialog(player, candidate);
            return;
        }
        saveApplicationForm(player, formId);
    }

    private void renderTownProfileDialog(Player player, ApplicationFormSession form) {
        List<DialogInput> inputs = List.of(
                DialogInput.text("description", 400,
                        Component.text("小镇简介", NamedTextColor.GOLD), true,
                        form.text().description(), 500,
                        TextDialogInput.MultilineOptions.create(6, 100)),
                DialogInput.text("rules", 400,
                        Component.text("小镇规则（每行一条）", NamedTextColor.GOLD), true,
                        String.join("\n", form.text().rules()), 5000,
                        TextDialogInput.MultilineOptions.create(20, 170)));
        Component guidance = Component.text(form.text().name(), NamedTextColor.GOLD)
                .append(Component.newline())
                .append(Component.text("名称和简称需要管理员代办；此处只编辑简介和规则。",
                        NamedTextColor.GRAY));
        openDialogPage(player, "编辑小镇简介和规则", List.of(
                        DialogBody.item(new ItemStack(Material.WRITABLE_BOOK),
                                DialogBody.plainMessage(guidance, 420), false, false, 48, 48)),
                inputs, DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE,
                session -> DialogType.confirmation(
                        ActionButton.create(Component.text("保存修改", NamedTextColor.GREEN),
                                null, 170, dialogAction(player, session,
                                        response -> applyTownProfileDialog(
                                                player, form.id(), response))),
                        ActionButton.create(Component.text("取消", NamedTextColor.RED),
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
            player.sendMessage("§c申请编辑会话已失效，请重新打开。");
            openMain(player);
            return null;
        }
        return form;
    }

    private static String responseText(DialogResponseView response, String key) {
        return Objects.requireNonNullElse(response.getText(key), "").strip();
    }

    private static List<SingleOptionDialogInput.OptionEntry> selectedOptions(
            List<SingleOptionDialogInput.OptionEntry> options, String selected) {
        return options.stream().map(option -> SingleOptionDialogInput.OptionEntry.create(
                option.id(), option.display(), option.id().equalsIgnoreCase(selected))).toList();
    }

    private void startDonationInput(Player player) {
        if (!runtime.consumptionEnabled()) {
            player.sendMessage("§c新资金写入当前已暂停。");
            return;
        }
        openDonationDialog(player, null, "");
    }

    private void openDonationDialog(Player player, String error, String initial) {
        runtime.read(player, () -> runtime.finance().findFinanceByPlayer(player.getUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("你不属于任何小镇")), account -> {
            List<String> description = new ArrayList<>(List.of(
                    "§7小镇: §f" + account.townName(),
                    "§7当前公共余额: §f" + runtime.money(account.balanceMinor()),
                    "§7金额必须大于 0，最多保留 " + runtime.settlement().scale() + " 位小数"));
            if (error != null && !error.isBlank()) {
                description.add("§c" + error);
            }
            ItemStack summary = button(Material.SUNFLOWER, "§6向小镇捐款",
                    description, null, null);
            DialogInput amount = DialogInput.text("donation_amount", 360,
                    Component.text("捐款金额", NamedTextColor.GOLD), true,
                    initial, 64, null);
            openDialogPage(player, "向小镇捐款", List.of(dialogBody(summary)), List.of(amount),
                    DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE, session ->
                            DialogType.confirmation(
                                    ActionButton.create(Component.text("确认捐款", NamedTextColor.GREEN),
                                            Component.text("从个人余额转入公共资金", NamedTextColor.GRAY),
                                            170, dialogAction(player, session,
                                                    response -> applyDonationDialog(player, response))),
                                    ActionButton.create(Component.text("取消", NamedTextColor.RED),
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
                            openNotice(player, "捐款成功",
                                    "已向小镇捐款 " + runtime.money(amount.minorUnits())
                                            + "\n当前公共余额: "
                                            + runtime.money(mutation.balanceAfterMinor()),
                                    "返回公共资产", "FINANCE", "0")));
        } catch (ArithmeticException | NumberFormatException exception) {
            openDonationDialog(player, "金额格式无效，请输入有效正数。", value);
        } catch (IllegalArgumentException exception) {
            openDonationDialog(player, exception.getMessage(), value);
        }
    }

    private void saveApplicationForm(Player player, UUID formId) {
        if (maintenanceMode()) {
            applicationForms.remove(player.getUniqueId());
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
            if (form.purpose() == FormPurpose.APPLICATION) {
                requireInitialMemberIds(player, form.initialMemberNames());
            }
        } catch (IllegalArgumentException exception) {
            player.sendMessage("§c资料尚未完成: " + exception.getMessage());
            renderApplicationForm(player, form.id());
            return;
        }
        applicationForms.remove(player.getUniqueId(), form);
        if (form.purpose() == FormPurpose.TOWN_PROFILE) {
            actions.updateTownProfile(player, form.targetId(), form.text(), form.version(), outcome ->
                    handleOutcome(player, outcome, town -> {
                player.sendMessage("§a小镇简介和规则已保存。");
                openTown(player, town.id());
            }));
            return;
        }
        List<UUID> initialMemberIds = requireInitialMemberIds(player,
                form.initialMemberNames());
        if (form.targetId() == null) {
            actions.createApplication(player, form.text(), initialMemberIds, outcome ->
                    handleOutcome(player, outcome, application -> {
                player.sendMessage("§a申请草稿已保存，请继续选择领地并确认提交。");
                notifyInitialMembers(application);
                openApplication(player, application);
            }));
        } else {
            actions.updateApplication(player, form.targetId(), form.text(), initialMemberIds,
                    form.version(), outcome ->
                    handleOutcome(player, outcome, application -> {
                player.sendMessage("§a申请资料已保存。");
                notifyInitialMembers(application);
                openApplication(player, application);
            }));
        }
    }

    private void cancelApplicationForm(Player player, UUID formId) {
        ApplicationFormSession form = applicationForms.get(player.getUniqueId());
        if (form == null || !form.id().equals(formId)) {
            player.sendMessage("§c申请编辑会话已失效。");
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
                field == ApplicationField.SHORT_NAME ? value : text.shortName(),
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

    private void sendInitialMemberReminder(Player member, ApplicationSnapshot application) {
        member.sendMessage(Component.text(application.text().name()
                        + " 邀请你作为建镇初始成员。 ", NamedTextColor.GOLD)
                .append(callbackButton(member, "[确认]", () -> respondInitialMember(
                        member, application.id(), true)))
                .append(Component.space())
                .append(callbackButton(member, "[拒绝]", () -> respondInitialMember(
                        member, application.id(), false))));
    }

    private void respondInitialMember(Player member, UUID applicationId, boolean confirm) {
        actions.respondInitialMember(member, applicationId, confirm, outcome ->
                handleOutcome(member, outcome, application -> {
                    member.sendMessage(confirm ? "§a你已确认成为该小镇的初始成员。"
                            : "§e你已拒绝成为该小镇的初始成员。");
                    Player applicant = Bukkit.getPlayer(application.applicantId());
                    if (applicant != null) {
                        applicant.sendMessage((confirm ? "§a" : "§e") + member.getName()
                                + (confirm ? " 已确认" : " 已拒绝") + "成为建镇初始成员。");
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
            case SHORT_NAME -> error.startsWith("简称");
            case RESIDENCE_NAME -> error.startsWith("领地名称");
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

    private static <T> void handleOutcome(Player player, TownActionOutcome<T> outcome,
                                          Consumer<T> success) {
        if (outcome.result().success()) {
            success.accept(outcome.value());
            return;
        }
        player.closeDialog();
        String detail = outcome.result().data().get("detail");
        player.sendMessage("§c操作失败 [" + outcome.result().reason() + "]"
                + (detail == null ? "" : ": " + detail));
    }

    private void openConfirmation(Player player, String title, String confirmedAction,
                                  String target, String consequence, String returnAction,
                                  String returnTarget) {
        Material material = title.contains("解散") ? Material.TNT
                : title.contains("扩张") ? Material.FILLED_MAP
                : title.contains("Buff") || title.contains("购买") ? Material.POTION
                : title.contains("移除") || title.contains("终止") ? Material.BARRIER
                : Material.PAPER;
        ItemStack summary = button(material, "§6" + title,
                List.of("§7" + consequence,
                        title.contains("解散") || title.contains("终止")
                                ? "§c此操作不可撤销" : "§7请确认信息无误"), null, null);
        openDialogPage(player, title, List.of(dialogBody(summary)), List.of(),
                DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE, session ->
                        DialogType.confirmation(
                                ActionButton.create(Component.text("确认", NamedTextColor.GREEN),
                                        Component.text(consequence, NamedTextColor.GRAY), 170,
                                        dialogAction(player, session, confirmedAction, target)),
                                ActionButton.create(Component.text("取消", NamedTextColor.RED),
                                        null, 170,
                                        dialogAction(player, session, returnAction, returnTarget))));
    }

    private UUID openMenu(Player player, int size, String title, List<MenuItem> items) {
        List<MenuItem> ordered = items.stream()
                .sorted(java.util.Comparator.comparingInt(MenuItem::slot))
                .toList();
        List<DialogBody> bodies = ordered.stream()
                .filter(item -> itemAction(item.item()) == null)
                .map(item -> dialogBody(item.item()))
                .toList();
        List<MenuItem> actions = ordered.stream()
                .filter(item -> itemAction(item.item()) != null)
                .toList();
        return openDialogPage(player, title, bodies, List.of(),
                DialogBase.DialogAfterAction.NONE, session -> {
                    ActionButton exit = exitButton(player, session, "关闭", "关闭当前界面");
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
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(title, NamedTextColor.GOLD))
                        .externalTitle(Component.text(title))
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
                List.of(DialogBody.plainMessage(Component.text(message), 380)), List.of(),
                DialogBase.DialogAfterAction.NONE, session -> DialogType.notice(
                        ActionButton.create(Component.text(actionLabel, NamedTextColor.GREEN),
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

    private ActionButton exitButton(Player player, UUID session, String label, String tooltip) {
        return ActionButton.create(Component.text(label, NamedTextColor.GRAY),
                Component.text(tooltip, NamedTextColor.GRAY), 140,
                dialogAction(player, session, "CLOSE", null));
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
                playSound(clicked, Sound.UI_BUTTON_CLICK);
                handler.accept(response);
            });
        }, ClickCallback.Options.builder().uses(1)
                .lifetime(Duration.ofMinutes(1)).build());
    }

    private void closeUi(Player player) {
        player.closeDialog();
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

    private record ExpansionMenu(Map<ExpansionDirection, TownRuntime.ExpansionPreview> previews,
                                 Map<ExpansionDirection, String> errors) {
    }

    private record BuffShopView(List<CommerceRepository.ActiveBuff> active,
                                Map<String, CommerceRepository.BuffQuote> quotes,
                                Map<String, String> errors) {
    }

    private record MemberPage(TownSnapshot.Page page, MemberGovernanceSnapshot governance) {
    }

    private record MemberDetail(MemberGovernanceSnapshot viewer, MemberRole targetRole) {
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

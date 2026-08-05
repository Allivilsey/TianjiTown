package cn.tianji.town.paper;

import cn.tianji.town.core.application.ApplicationStatus;
import cn.tianji.town.core.application.ApplicationText;
import cn.tianji.town.core.town.MemberRole;
import cn.tianji.town.storage.phase1.ApplicationSnapshot;
import cn.tianji.town.storage.phase1.InvitationSnapshot;
import cn.tianji.town.storage.phase1.PhaseOneRepository;
import cn.tianji.town.storage.phase1.TownSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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
        String stationId = UUID.randomUUID().toString();
        lectern.getPersistentDataContainer().set(stationKey, PersistentDataType.STRING, stationId);
        lectern.update(true);
        player.sendMessage("§a已创建小镇服务台，ID: " + stationId);
        return true;
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
        runtime.read(player, () -> runtime.repository().dashboard(player.getUniqueId()), dashboard -> {
            if (isCurrent(player, request)) {
                renderMain(player, dashboard);
            }
        });
    }

    void previewTownForAdmin(Player player, TownSnapshot town) {
        sitePolicy.preview(player, town.territory());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(handbookKey,
                PersistentDataType.BYTE)) {
            event.setCancelled(true);
            openMain(event.getPlayer());
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
        if (form.type() == FormType.APPLICATION_CREATE) {
            Duration cooldown = Duration.ofHours(plugin.getConfig()
                    .getLong("phase1.application.cooldown-hours", 24));
            runtime.write(player, () -> runtime.repository().createDraft(player.getUniqueId(),
                    parsed, cooldown), application -> {
                removeFormBook(player, form.id());
                openApplication(player, application);
            });
        } else if (form.type() == FormType.APPLICATION_EDIT) {
            runtime.write(player, () -> runtime.repository().updateApplicationText(form.targetId(),
                    player.getUniqueId(), parsed, form.version()), application -> {
                removeFormBook(player, form.id());
                openApplication(player, application);
            });
        } else {
            ApplicationText lockedNames = new ApplicationText(form.base().name(),
                    form.base().shortName(), parsed.description(), parsed.rules());
            runtime.write(player, () -> runtime.repository().updateTownProfile(form.targetId(),
                    lockedNames, form.version(), player.getUniqueId(), player.getName(),
                    "镇长通过书本界面修改简介和规则"), town -> {
                removeFormBook(player, form.id());
                runtime.write(player, () -> {
                    try {
                        runtime.exportProfile(town.id());
                        return town;
                    } catch (IOException exception) {
                        runtime.repository().markProfileFailed(town.id(), exception.getMessage());
                        throw new IllegalStateException("资料已保存到 MySQL，但 YAML 导出失败: "
                                + exception.getMessage(), exception);
                    }
                }, ignored -> openTown(player, town.id()));
            });
        }
    }

    private void renderMain(Player player, PhaseOneRepository.PlayerDashboard dashboard) {
        List<MenuItem> items = new ArrayList<>();
        if (dashboard.town() != null) {
            TownSnapshot town = dashboard.town();
            items.add(new MenuItem(11, button(Material.BELL, "§a" + town.profile().name(),
                    List.of("§7查看小镇资料与成员"), "TOWN", town.id().toString())));
            if (town.mayorId().equals(player.getUniqueId())) {
                items.add(new MenuItem(13, button(Material.PLAYER_HEAD, "§e邀请成员",
                        List.of("§7从当前在线玩家中选择"), "INVITE_MENU", town.id().toString())));
                items.add(new MenuItem(15, button(Material.WRITABLE_BOOK, "§e修改简介和规则",
                        List.of("§7名称和简称需要管理员代办"), "EDIT_TOWN", town.id().toString())));
            } else {
                items.add(new MenuItem(15, button(Material.OAK_DOOR, "§c退出小镇",
                        List.of("§7需要再次确认"), "CONFIRM_LEAVE", town.id().toString())));
            }
        } else if (dashboard.application() != null) {
            ApplicationSnapshot application = dashboard.application();
            items.add(new MenuItem(11, button(Material.MAP, "§e继续小镇申请",
                    List.of("§7状态: " + application.status(),
                            application.reviewMessage() == null ? "§7点击查看摘要"
                                    : "§c管理员意见: " + application.reviewMessage()),
                    "APPLICATION", application.id().toString())));
        } else {
            items.add(new MenuItem(11, button(Material.WRITABLE_BOOK, "§a申请建立小镇",
                    List.of("§7使用书本填写名称、简称、简介和规则"),
                    "CREATE_APPLICATION", null)));
        }
        if (dashboard.town() == null && !dashboard.invitations().isEmpty()) {
            items.add(new MenuItem(15, button(Material.CHEST, "§6查看邀请",
                    List.of("§7待处理邀请: " + dashboard.invitations().size()),
                    "INVITATIONS", null)));
        }
        items.add(new MenuItem(22, button(Material.WRITTEN_BOOK, "§6领取小镇手册",
                List.of("§7手册丢失后可在服务台重新领取"), "GIVE_HANDBOOK", null)));
        openMenu(player, 27, "小镇服务", items);
    }

    private void openApplication(Player player, ApplicationSnapshot application) {
        List<String> summary = new ArrayList<>(List.of("§7名称: " + application.text().name(),
                "§7简称: " + application.text().shortName(),
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
                    List.of("§7提交书本后返回摘要"), "EDIT_APPLICATION",
                    application.id().toString())));
            items.add(new MenuItem(12, button(Material.COMPASS, "§e选择当前区块",
                    List.of("§7当前区块将成为 3×3 初始领地中心"), "SELECT_SITE",
                    application.id().toString())));
            if (application.territory() != null) {
                items.add(new MenuItem(14, button(Material.ENDER_EYE, "§b预览已选领地",
                        List.of("§7显示临时粒子边界"), "PREVIEW_SITE",
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
                                + town.territory().center().z()), "PREVIEW_TOWN",
                        town.id().toString())));
            }
            items.add(new MenuItem(26, button(Material.ARROW, "§7返回", List.of(), "MAIN", null)));
            openMenu(player, 27, "小镇详情", items);
        });
    }

    private void openMembers(Player player, UUID townId, int page) {
        runtime.read(player, () -> runtime.repository().listMembers(townId, page, 45), result -> {
            List<MenuItem> items = new ArrayList<>();
            int slot = 0;
            for (TownSnapshot.Member member : result.members()) {
                String name = Objects.requireNonNullElse(Bukkit.getOfflinePlayer(member.playerId()).getName(),
                        member.playerId().toString());
                items.add(new MenuItem(slot++, button(Material.PLAYER_HEAD,
                        (member.role() == MemberRole.MAYOR ? "§6" : "§f") + name,
                        List.of("§7身份: " + member.role(), "§7加入: " + member.joinedAt()),
                        null, null)));
            }
            if (page > 0) {
                items.add(new MenuItem(45, button(Material.ARROW, "§e上一页", List.of(),
                        "MEMBERS", townId + ":" + (page - 1))));
            }
            if (result.hasNext()) {
                items.add(new MenuItem(53, button(Material.ARROW, "§e下一页", List.of(),
                        "MEMBERS", townId + ":" + (page + 1))));
            }
            openMenu(player, 54, "小镇成员 · 第 " + (page + 1) + " 页", items);
        });
    }

    private void openInvitations(Player player) {
        runtime.read(player, () -> runtime.repository().listInvitations(player.getUniqueId()), invitations -> {
            List<MenuItem> items = new ArrayList<>();
            for (int index = 0; index < Math.min(invitations.size(), 45); index++) {
                InvitationSnapshot invitation = invitations.get(index);
                items.add(new MenuItem(index, button(Material.PAPER, "§6" + invitation.townName(),
                        List.of("§7到期: " + invitation.expiresAt(), "§7点击查看完整资料"),
                        "INVITATION", invitation.id().toString())));
            }
            openMenu(player, 54, "小镇邀请", items);
        });
    }

    private void openInvitation(Player player, UUID invitationId) {
        runtime.read(player, () -> runtime.repository().listInvitations(player.getUniqueId()).stream()
                .filter(invitation -> invitation.id().equals(invitationId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("邀请不存在或已过期")), invitation ->
                runtime.read(player, () -> runtime.repository().findTown(invitation.townId())
                        .orElseThrow(() -> new IllegalArgumentException("小镇不存在")), town -> {
                    List<MenuItem> items = List.of(
                            new MenuItem(4, button(Material.BELL, "§6" + town.profile().name(),
                                    List.of("§7简称: " + town.profile().shortName(),
                                            "§7简介: " + town.profile().description(),
                                            "§7规则:", "§f" + String.join(" | ", town.profile().rules())),
                                    null, null)),
                            new MenuItem(13, button(Material.LIME_CONCRETE, "§a确认加入",
                                    List.of("§7加入后将接受上述规则"), "ACCEPT_INVITATION",
                                    invitation.id().toString())),
                            new MenuItem(22, button(Material.ARROW, "§7返回", List.of(),
                                    "INVITATIONS", null)));
                    openMenu(player, 27, "确认加入小镇", items);
                }));
    }

    private void openInviteMenu(Player mayor, UUID townId) {
        List<MenuItem> items = new ArrayList<>();
        int slot = 0;
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (candidate.getUniqueId().equals(mayor.getUniqueId()) || slot >= 45) {
                continue;
            }
            items.add(new MenuItem(slot++, button(Material.PLAYER_HEAD, "§f" + candidate.getName(),
                    List.of("§7点击发送 7 天有效邀请"), "INVITE_PLAYER",
                    townId + ":" + candidate.getUniqueId())));
        }
        openMenu(mayor, 54, "邀请在线玩家", items);
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
                case "GIVE_HANDBOOK" -> {
                    giveHandbook(player);
                    openMain(player);
                }
                case "CREATE_APPLICATION" -> issueForm(player, FormType.APPLICATION_CREATE,
                        null, 0, null);
                case "APPLICATION" -> loadApplication(player, UUID.fromString(target));
                case "EDIT_APPLICATION" -> loadApplicationForForm(player, UUID.fromString(target));
                case "SELECT_SITE" -> selectSite(player, UUID.fromString(target));
                case "PREVIEW_SITE" -> previewApplication(player, UUID.fromString(target));
                case "CONFIRM_SUBMIT" -> openConfirmation(player, "确认提交申请", "SUBMIT", target,
                        "提交后需等待管理员审核");
                case "SUBMIT" -> submit(player, UUID.fromString(target));
                case "CONFIRM_CANCEL" -> openConfirmation(player, "确认撤回申请", "CANCEL", target,
                        "撤回会释放选址并进入冷却");
                case "CANCEL" -> cancel(player, UUID.fromString(target));
                case "TOWN" -> openTown(player, UUID.fromString(target));
                case "MEMBERS" -> {
                    String[] parts = target.split(":");
                    openMembers(player, UUID.fromString(parts[0]), Integer.parseInt(parts[1]));
                }
                case "PREVIEW_TOWN" -> previewTown(player, UUID.fromString(target));
                case "INVITATIONS" -> openInvitations(player);
                case "INVITATION" -> openInvitation(player, UUID.fromString(target));
                case "ACCEPT_INVITATION" -> acceptInvitation(player, UUID.fromString(target));
                case "INVITE_MENU" -> openInviteMenu(player, UUID.fromString(target));
                case "INVITE_PLAYER" -> invitePlayer(player, target);
                case "EDIT_TOWN" -> loadTownForForm(player, UUID.fromString(target));
                case "CONFIRM_LEAVE" -> openConfirmation(player, "确认退出小镇", "LEAVE", target,
                        "退出后需要新邀请才能再次加入");
                case "LEAVE" -> leave(player, UUID.fromString(target));
                default -> player.sendMessage("§c菜单操作已失效，请重新打开。");
            }
        } catch (IllegalArgumentException exception) {
            player.sendMessage("§c菜单数据无效，请重新打开。");
        }
    }

    private void loadApplication(Player player, UUID applicationId) {
        runtime.read(player, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("申请不存在")),
                application -> openApplication(player, application));
    }

    private void loadApplicationForForm(Player player, UUID applicationId) {
        runtime.read(player, () -> runtime.repository().findApplication(applicationId)
                .orElseThrow(() -> new IllegalArgumentException("申请不存在")), application ->
                issueForm(player, FormType.APPLICATION_EDIT, application.id(), application.version(),
                        application.text()));
    }

    private void loadTownForForm(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException("小镇不存在")), town -> {
            if (!town.mayorId().equals(player.getUniqueId())) {
                player.sendMessage("§c只有镇长可以修改简介和规则。");
                return;
            }
            issueForm(player, FormType.TOWN_PROFILE, town.id(), town.version(), town.profile());
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
                sitePolicy.preview(player, application.territory());
            }
        });
    }

    private void previewTown(Player player, UUID townId) {
        runtime.read(player, () -> runtime.repository().findTown(townId)
                .orElseThrow(() -> new IllegalArgumentException("小镇不存在")), town ->
                sitePolicy.preview(player, town.territory()));
    }

    private void submit(Player player, UUID applicationId) {
        runtime.write(player, () -> runtime.repository().submit(applicationId, player.getUniqueId()),
                application -> {
                    player.sendMessage("§a申请已提交，等待管理员审核。");
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

    private void acceptInvitation(Player player, UUID invitationId) {
        runtime.write(player, () -> runtime.repository().acceptInvitation(invitationId,
                player.getUniqueId()), townId -> {
            player.sendMessage("§a已加入小镇，正在同步领地权限。");
            syncResidence(player, townId, true);
            openMain(player);
        });
    }

    private void invitePlayer(Player mayor, String target) {
        String[] parts = target.split(":");
        UUID townId = UUID.fromString(parts[0]);
        UUID playerId = UUID.fromString(parts[1]);
        runtime.write(mayor, () -> runtime.repository().invite(townId, mayor.getUniqueId(),
                playerId, Duration.ofDays(7)), invitation -> {
            mayor.sendMessage("§a已发送邀请给 "
                    + Objects.requireNonNullElse(Bukkit.getOfflinePlayer(playerId).getName(), playerId.toString()));
            openMain(mayor);
        });
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

    private void syncResidence(Player sender, UUID townId, boolean repair) {
        runtime.read(sender, () -> new TownMembers(runtime.repository().findTown(townId)
                        .orElseThrow(() -> new IllegalArgumentException("小镇不存在")),
                        runtime.repository().listMemberIds(townId)), state ->
                runtime.reconcile(sender, state.town(), state.members(), repair));
    }

    private void issueForm(Player player, FormType type, UUID targetId, long version,
                           ApplicationText base) {
        UUID id = UUID.randomUUID();
        ApplicationText value = base == null ? new ApplicationText("", "", "", List.of()) : base;
        formSessions.put(player.getUniqueId(), new FormSession(id, type, targetId, version, value));
        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setDisplayName("§6小镇资料表单（填写后签名提交）");
        meta.setPages(formPages(value));
        meta.getPersistentDataContainer().set(sessionKey, PersistentDataType.STRING, id.toString());
        book.setItemMeta(meta);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(book);
        leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        player.closeInventory();
        if (type == FormType.TOWN_PROFILE) {
            player.sendMessage("§e请编辑书本第 3、4 页的简介和规则，然后签名提交；名称与简称修改会被忽略。");
        } else {
            player.sendMessage("§e请编辑四页内容（规则每行一条），然后签名提交。不要删除每页首行标记。");
        }
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
        if (form.type() == FormType.TOWN_PROFILE) {
            name = form.base().name();
            shortName = form.base().shortName();
        }
        return new ApplicationText(name, shortName, description, rules);
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
                                  String target, String consequence) {
        List<MenuItem> items = List.of(
                new MenuItem(11, button(Material.LIME_CONCRETE, "§a确认",
                        List.of("§7" + consequence), confirmedAction, target)),
                new MenuItem(15, button(Material.RED_CONCRETE, "§c取消", List.of(), "MAIN", null)));
        openMenu(player, 27, title, items);
    }

    private UUID openMenu(Player player, int size, String title, List<MenuItem> items) {
        UUID session = UUID.randomUUID();
        MenuHolder holder = new MenuHolder(session);
        Inventory inventory = Bukkit.createInventory(holder, size, title);
        holder.inventory = inventory;
        for (MenuItem item : items) {
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

    private record FormSession(UUID id, FormType type, UUID targetId, long version,
                               ApplicationText base) {
    }

    private enum FormType {
        APPLICATION_CREATE,
        APPLICATION_EDIT,
        TOWN_PROFILE
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

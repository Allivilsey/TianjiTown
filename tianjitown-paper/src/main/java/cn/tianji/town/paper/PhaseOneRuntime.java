package cn.tianji.town.paper;

import cn.tianji.town.core.ports.LandProtectionService;
import cn.tianji.town.core.ports.RegionBoundaryService;
import cn.tianji.town.core.land.ExpansionDirection;
import cn.tianji.town.core.land.ExpansionPricing;
import cn.tianji.town.core.land.TerritoryRules;
import cn.tianji.town.core.land.TerritoryUnit;
import cn.tianji.town.core.town.TownStatus;
import cn.tianji.town.integrations.quickshop.QuickShopTaxAdapter;
import cn.tianji.town.integrations.vault.VaultSettlementService;
import cn.tianji.town.storage.database.DatabaseGate;
import cn.tianji.town.storage.phase1.ApplicationSnapshot;
import cn.tianji.town.storage.phase1.PhaseOneRepository;
import cn.tianji.town.storage.phase1.TownSnapshot;
import cn.tianji.town.storage.phase2.GovernanceRepository;
import cn.tianji.town.storage.phase2.VoteSnapshot;
import cn.tianji.town.storage.phase3.PhaseThreeRepository;
import cn.tianji.town.storage.phase4.PhaseFourRepository;
import cn.tianji.town.storage.phase5.PhaseFiveRepository;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class PhaseOneRuntime {
    private final TianjiTownPlugin plugin;
    private final DatabaseGate database;
    private final PhaseOneRepository repository;
    private final GovernanceRepository governance;
    private final PhaseThreeRepository finance;
    private final LandProtectionService landProtection;
    private final SitePolicy sitePolicy;
    private final PhaseThreeSettings phaseThreeSettings;
    private final VaultSettlementService settlement;
    private final PhaseFourRuntime phaseFour;
    private final PhaseFiveRuntime phaseFive;
    private final Map<UUID, QuickShopTaxAdapter.TaxPolicy> taxPolicies = new ConcurrentHashMap<>();
    private final RetryingWorkQueue<QuickShopTaxAdapter.SuccessfulTax> pendingTaxes;
    private final AtomicBoolean databaseAvailable = new AtomicBoolean(true);
    private final AtomicBoolean quickShopTaxAvailable = new AtomicBoolean(false);
    private final ProvisionCoordinator provisions = new ProvisionCoordinator();

    PhaseOneRuntime(TianjiTownPlugin plugin, DatabaseGate database,
                    LandProtectionService landProtection,
                    RegionBoundaryService regionBoundaries) {
        this.plugin = plugin;
        this.database = database;
        this.landProtection = landProtection;
        this.sitePolicy = new SitePolicy(plugin, landProtection, regionBoundaries);
        this.repository = new PhaseOneRepository(database.dataSource(),
                plugin.getServer()::isPrimaryThread);
        this.governance = new GovernanceRepository(database.dataSource(),
                plugin.getServer()::isPrimaryThread);
        this.finance = new PhaseThreeRepository(database.dataSource(),
                plugin.getServer()::isPrimaryThread);
        this.phaseThreeSettings = PhaseThreeSettings.load(plugin.getConfig());
        this.settlement = new VaultSettlementService(plugin.getServer(),
                phaseThreeSettings.settlementAccount(), phaseThreeSettings.fallbackScale());
        this.phaseFour = new PhaseFourRuntime(plugin, this,
                new PhaseFourRepository(database.dataSource(),
                        plugin.getServer()::isPrimaryThread),
                PhaseFourSettings.load(plugin.getConfig()));
        this.phaseFive = new PhaseFiveRuntime(plugin, this,
                new PhaseFiveRepository(database.dataSource(),
                        plugin.getServer()::isPrimaryThread),
                PhaseFiveSettings.load(plugin.getConfig()), java.util.Objects.requireNonNull(
                plugin.getServer().getPluginManager().getPlugin("QuickShop-Hikari"),
                "QuickShop-Hikari"));
        this.pendingTaxes = new RetryingWorkQueue<>(new RetryingWorkQueue.Scheduler() {
            @Override
            public void executeAsync(Runnable task) {
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
            }

            @Override
            public void schedule(Runnable task, long delayTicks) {
                plugin.getServer().getScheduler().runTaskLater(plugin, task, delayTicks);
            }
        }, 20L * 5, 20L * 30, this::recordQuickShopTax, this::handleQuickShopTaxFailure);
    }

    PhaseOneRepository repository() {
        return repository;
    }

    GovernanceRepository governance() {
        return governance;
    }

    PhaseThreeRepository finance() {
        return finance;
    }

    PhaseThreeSettings phaseThreeSettings() {
        return phaseThreeSettings;
    }

    VaultSettlementService settlement() {
        return settlement;
    }

    PhaseFourRuntime phaseFour() {
        return phaseFour;
    }

    PhaseFiveRuntime phaseFive() {
        return phaseFive;
    }

    DatabaseGate database() {
        return database;
    }

    QuickShopTaxAdapter.TaxPolicy taxPolicy(UUID receiverId) {
        return taxEnabled() ? taxPolicies.get(receiverId) : null;
    }

    boolean taxEnabled() {
        return quickShopTaxAvailable.get() && plugin.getConfig().getBoolean(
                "phase3.tax.enabled", phaseThreeSettings.taxEnabled());
    }

    void setQuickShopTaxAvailable(boolean available) {
        quickShopTaxAvailable.set(available);
    }

    boolean consumptionEnabled() {
        return plugin.getConfig().getBoolean("phase3.consumption.enabled",
                phaseThreeSettings.consumptionEnabled());
    }

    LandProtectionService landProtection() {
        return landProtection;
    }

    SitePolicy sitePolicy() {
        return sitePolicy;
    }

    boolean databaseAvailable() {
        return databaseAvailable.get();
    }

    void checkRecovery() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean healthy = database.ping();
            if (healthy) {
                try {
                    refreshTaxPolicies();
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("刷新 QuickShop 税率内存映射失败: "
                            + safeMessage(exception));
                }
            }
            boolean previous = databaseAvailable.getAndSet(healthy);
            if (healthy && !previous) {
                plugin.getLogger().info("SQLite 连接已恢复，写操作重新开放。");
                flushPendingTaxes();
            } else if (!healthy && previous) {
                plugin.getLogger().severe("SQLite 连接中断，写操作已锁定；Residence 保护保持不变。");
            }
        });
    }

    void recoverStartupState() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                finance.initializeAccounts();
                refreshTaxPolicies();
                int recovered = repository.recoverInterruptedProvisions(
                        "服务器在 Residence 投影完成前停止，启动时已转为可重试失败状态");
                databaseAvailable.set(true);
                if (recovered > 0) {
                    plugin.getLogger().warning("已恢复 " + recovered
                            + " 个中断的建镇流程；申请进入 PROVISION_FAILED，等待管理员重试。");
                }
                List<PhaseThreeRepository.ExpansionOperation> expansions =
                        finance.pendingExpansions();
                List<cn.tianji.town.storage.phase4.PhaseFourRepository.ResourceOrder> orders =
                        phaseFour.repository().openOrders(1_000);
                if (!orders.isEmpty()) {
                    long claiming = orders.stream().filter(order -> order.status()
                            .equals("CLAIMING")).count();
                    long refunds = orders.stream().filter(order -> order.status()
                            .equals("REFUND_REQUIRED")).count();
                    plugin.getLogger().warning("启动检查发现 " + orders.size()
                            + " 个未完成资源订单，其中领取确认中 " + claiming
                            + " 个、待退款 " + refunds + " 个；玩家登录会恢复领取标记。"
                            + "可使用 /townadmin order list 检查。");
                }
                if (!expansions.isEmpty()) {
                    plugin.getServer().getScheduler().runTask(plugin,
                            () -> recoverExpansions(expansions));
                }
            } catch (RuntimeException exception) {
                if (exception instanceof PhaseOneRepository.StorageUnavailableException) {
                    databaseAvailable.set(false);
                }
                plugin.getLogger().severe("恢复中断建镇流程失败: " + safeMessage(exception));
            }
        });
    }

    void reconcileAll() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<TownMembers> states = repository.listTowns(false).stream()
                        .filter(town -> town.status() == TownStatus.ACTIVE)
                        .map(town -> new TownMembers(town, repository.listMemberIds(town.id()),
                                finance.territoryUnits(town.id())))
                        .toList();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    for (TownMembers state : states) {
                        List<LandProtectionService.Area> areas = state.units().stream()
                                .filter(unit -> unit.projectionStatus().equals("ACTIVE"))
                                .map(unit -> new LandProtectionService.Area(unit.residenceAreaName(),
                                        unit.unit().territory())).toList();
                        LandProtectionService.Inspection inspection;
                        try {
                            inspection = landProtection.inspect(state.town().residenceName(), areas,
                                    state.members());
                        } catch (RuntimeException | LinkageError exception) {
                            inspection = LandProtectionService.Inspection.invalid(
                                    "Residence API 不可用: " + safeMessage(exception));
                        }
                        if (inspection.state() == LandProtectionService.ProjectionState.MISSING) {
                            archiveMissingProjection(state.town(), inspection.message());
                            continue;
                        }
                        boolean healthy = inspection.state()
                                == LandProtectionService.ProjectionState.HEALTHY;
                        LandProtectionService.Result result = healthy
                                ? LandProtectionService.Result.ok(inspection.message())
                                : LandProtectionService.Result.failure(inspection.message());
                        if (!healthy) {
                            plugin.getLogger().severe("Residence 对账发现异常 " + state.town().id()
                                    + ": " + inspection.message()
                                    + "；自动任务不会删除或重建投影，请由管理员检查。");
                        }
                        recordLandAudit(null, "SYSTEM", state.town().id(), false, result);
                    }
                });
            } catch (RuntimeException exception) {
                databaseAvailable.set(false);
                plugin.getLogger().severe("Residence 对账读取 SQLite 失败: " + safeMessage(exception));
            }
        });
    }

    void settleDueVotes() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<VoteSnapshot> settled = governance.settleDueVotes();
                List<TownMembers> changedMemberships = settled.stream()
                        .filter(vote -> vote.passed()
                                && vote.type() == cn.tianji.town.core.governance.VoteType.KICK_MEMBER)
                        .map(VoteSnapshot::townId).distinct()
                        .map(townId -> repository.findTown(townId)
                                .map(town -> new TownMembers(town, repository.listMemberIds(townId),
                                        finance.territoryUnits(townId)))
                                .orElse(null))
                        .filter(java.util.Objects::nonNull)
                        .toList();
                databaseAvailable.set(true);
                if (!changedMemberships.isEmpty()) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        for (TownMembers state : changedMemberships) {
                            reconcile(org.bukkit.Bukkit.getConsoleSender(), state.town(),
                                    state.members(), true);
                        }
                        phaseFour.refreshAllPlayers();
                    });
                }
            } catch (RuntimeException exception) {
                if (exception instanceof GovernanceRepository.StorageUnavailableException) {
                    databaseAvailable.set(false);
                }
                plugin.getLogger().severe("治理投票定时结算失败: " + safeMessage(exception));
            }
        });
    }

    private void archiveMissingProjection(TownSnapshot town, String detail) {
        plugin.getLogger().severe("ACTIVE 小镇缺少 Residence 投影 " + town.id() + "/"
                + town.residenceName() + ": " + detail + "；正在执行安全归档并保留复用锁。");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                boolean archived = repository.archiveTownForMissingProjection(town.id(), detail);
                databaseAvailable.set(true);
                if (archived) {
                    plugin.getLogger().severe("小镇已安全归档 " + town.profile().name()
                            + "；名称、领地名称和原区块保持锁定，未自动创建或删除 Residence。");
                }
            } catch (RuntimeException exception) {
                if (exception instanceof PhaseOneRepository.StorageUnavailableException) {
                    databaseAvailable.set(false);
                }
                plugin.getLogger().severe("小镇安全归档失败 " + town.id() + ": "
                        + safeMessage(exception));
            }
        });
    }

    <T> void read(CommandSender sender, Supplier<T> operation, Consumer<T> success) {
        execute(sender, false, operation, success);
    }

    <T> void write(CommandSender sender, Supplier<T> operation, Consumer<T> success) {
        execute(sender, true, operation, success);
    }

    void provision(CommandSender sender, UUID applicationId, UUID reviewerId,
                   String reviewerName, String reason, String idempotencyKey,
                   Consumer<ApplicationSnapshot> completion) {
        if (!databaseAvailable.get()) {
            sender.sendMessage("§cSQLite 当前不可用，写操作已锁定；现有 Residence 保护不受影响。");
            return;
        }
        if (!provisions.tryBegin(applicationId)) {
            sender.sendMessage("§e该申请正在执行建镇流程，本次重复请求已合并。");
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PhaseOneRepository.Provisioning provisioning = repository.beginProvision(applicationId,
                        reviewerId, reviewerName, reason, idempotencyKey);
                databaseAvailable.set(true);
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> projectProvision(sender, applicationId, provisioning, completion));
            } catch (RuntimeException exception) {
                provisions.finish(applicationId);
                handleFailure(sender, exception);
            }
        });
    }

    private void projectProvision(CommandSender sender, UUID applicationId,
                                  PhaseOneRepository.Provisioning provisioning,
                                  Consumer<ApplicationSnapshot> completion) {
        if (provisioning.town().status() == TownStatus.ACTIVE) {
            provisions.finish(applicationId);
            sender.sendMessage("§a该申请已完成建镇，无需重复批准。");
            return;
        }
        try {
            // 碰撞由创建服务检查，使重试能够识别并复用本镇已经创建的系统投影。
            SitePolicy.Validation validation = sitePolicy.validateEnvironment(
                    provisioning.town().territory());
            LandProtectionService.Result land = validation.valid()
                    ? landProtection.create(provisioning.town().residenceName(),
                    provisioning.town().territory(),
                    provisioning.members())
                    : LandProtectionService.Result.failure("批准时选址复核失败: " + validation.error());
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                    () -> finishProvision(sender, applicationId, land, completion));
        } catch (RuntimeException exception) {
            provisions.finish(applicationId);
            handleFailure(sender, exception);
        }
    }

    private void finishProvision(CommandSender sender, UUID applicationId,
                                 LandProtectionService.Result land,
                                 Consumer<ApplicationSnapshot> completion) {
        try {
            boolean completed = land.success();
            String completedDetail = land.message();
            ApplicationSnapshot application = repository.finishProvision(
                    applicationId, completed, completedDetail);
            if (completed) {
                refreshTaxPolicies();
            }
            databaseAvailable.set(true);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage(completed ? "§a小镇已批准并完成 3×3 领地投影。"
                        : "§c自动创建失败，申请已进入 PROVISION_FAILED: " + completedDetail);
                completion.accept(application);
            });
        } catch (RuntimeException exception) {
            handleFailure(sender, exception);
        } finally {
            provisions.finish(applicationId);
        }
    }

    void reconcile(CommandSender sender, TownSnapshot town, List<UUID> members, boolean repair) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<LandProtectionService.Area> areas = finance.territoryUnits(town.id()).stream()
                        .filter(unit -> unit.projectionStatus().equals("ACTIVE"))
                        .map(unit -> new LandProtectionService.Area(unit.residenceAreaName(),
                                unit.unit().territory())).toList();
                refreshTaxPolicies();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    LandProtectionService.Result result;
                    try {
                        result = landProtection.reconcile(town.residenceName(), areas, members,
                                repair);
                    } catch (RuntimeException | LinkageError exception) {
                        result = LandProtectionService.Result.failure(
                                "Residence API 不可用: " + safeMessage(exception));
                    }
                    sender.sendMessage((result.success() ? "§a" : "§c")
                            + town.profile().name() + ": " + result.message());
                    recordLandAudit(actorId(sender), sender.getName(), town.id(), repair, result);
                });
            } catch (RuntimeException exception) {
                handleFailure(sender, exception);
            }
        });
    }

    private void recordLandAudit(UUID actorId, String actorName, UUID townId, boolean repair,
                                 LandProtectionService.Result result) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                repository.recordAudit(actorId, actorName,
                        repair ? "LAND_RECONCILE_REPAIR" : "LAND_RECONCILE_CHECK", "TOWN",
                        townId.toString(), result.success() ? "对账完成" : "对账失败",
                        result.message());
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("写入领地对账审计失败: " + safeMessage(exception));
            }
        });
    }

    void acceptQuickShopTax(QuickShopTaxAdapter.SuccessfulTax tax) {
        pendingTaxes.submit(tax);
    }

    void flushPendingTaxes() {
        pendingTaxes.flush();
    }

    private void recordQuickShopTax(QuickShopTaxAdapter.SuccessfulTax tax) {
        finance.recordQuickShopTax(new PhaseThreeRepository.QuickShopTax(
                tax.townId(), tax.businessKey(), tax.shopId(), tax.shopType(),
                tax.receiverId(), tax.interactingId(), tax.grossMinor(),
                tax.basisPoints(), tax.taxMinor(), tax.worldName()));
        databaseAvailable.set(true);
    }

    private void handleQuickShopTaxFailure(QuickShopTaxAdapter.SuccessfulTax tax,
                                           RuntimeException exception) {
        if (exception instanceof PhaseThreeRepository.StorageUnavailableException) {
            databaseAvailable.set(false);
        }
        plugin.getLogger().severe("QuickShop 税款 " + tax.businessKey()
                + " 已进入清算账户但账本暂未写入，将自动重试: "
                + safeMessage(exception));
    }

    void reconcileSettlement() {
        long external;
        try {
            external = settlement.balanceMinor();
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("读取 Vault 清算账户失败: " + safeMessage(exception));
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PhaseThreeRepository.Reconciliation result = finance.reconcileSettlement(external);
                if (!result.healthy()) {
                    plugin.getLogger().severe("清算账户少于小镇分账负债，所有小镇消费已锁定: 外部="
                            + money(result.externalBalanceMinor()) + "，应有="
                            + money(result.requiredMinor()));
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().severe("清算账户对账失败: " + safeMessage(exception));
            }
        });
    }

    void donate(Player player, long amountMinor) {
        if (!consumptionEnabled()) {
            player.sendMessage("§c阶段 3 新消费入口已由功能开关暂停。");
            return;
        }
        executeExternalOperation(player, () -> {
            PhaseThreeRepository.TownFinance account = finance.findFinanceByPlayer(
                    player.getUniqueId()).orElseThrow(() ->
                    new IllegalArgumentException("你不属于任何小镇"));
            String key = "donation:" + UUID.randomUUID();
            return finance.prepareOperation(account.townId(), "DONATION", amountMinor,
                    player.getUniqueId(), player.getName(), key, "成员捐款");
        }, operation -> settlement.transferFromPlayer(player, operation.amountMinor()),
                mutation -> {
                    player.sendMessage("§a捐款成功，当前小镇公共余额: §f"
                            + money(mutation.balanceAfterMinor()));
                    TownUiController ui = plugin.townUi();
                    if (ui != null) {
                        ui.openFinance(player, 0);
                    }
                });
    }

    void adjustFunds(CommandSender sender, UUID townId, long amountMinor, String reason) {
        executeExternalOperation(sender, () -> finance.prepareOperation(townId,
                        "ADMIN_ADJUSTMENT", amountMinor, actorId(sender), sender.getName(),
                        "admin-adjustment:" + UUID.randomUUID(), reason),
                operation -> settlement.adjustSettlement(operation.amountMinor()),
                mutation -> sender.sendMessage("§a资金调整已完成，小镇余额: "
                        + money(mutation.balanceAfterMinor())));
    }

    void changeTaxRate(Player mayor, UUID townId, int basisPoints) {
        if (!taxEnabled()) {
            mayor.sendMessage("§c新税收入口已由功能开关暂停。");
            return;
        }
        if (!phaseThreeSettings.allowsTaxRate(basisPoints)) {
            sendTaxRangeError(mayor);
            return;
        }
        write(mayor, () -> {
            PhaseThreeRepository.TaxChange change = finance.changeTaxRate(townId,
                    mayor.getUniqueId(), basisPoints, mayor.getName(), "镇长通过公共资金界面修改");
            refreshTaxPolicies();
            return change;
        }, change -> {
            mayor.sendMessage("§a小镇税率已更新为 " + percent(change.basisPoints())
                    + "；成员将在登录和资金界面收到版本告知。");
            TownUiController ui = plugin.townUi();
            if (ui != null) {
                ui.openFinance(mayor, 0);
            }
        });
    }

    void forceTaxRate(CommandSender sender, UUID townId, int basisPoints, String reason) {
        if (!phaseThreeSettings.allowsTaxRate(basisPoints)) {
            sendTaxRangeError(sender);
            return;
        }
        write(sender, () -> {
            PhaseThreeRepository.TaxChange change = finance.forceTaxRate(townId,
                    actorId(sender), basisPoints, sender.getName(), reason);
            refreshTaxPolicies();
            return change;
        }, change -> sender.sendMessage("§a税率已强制调整为 "
                + percent(change.basisPoints())));
    }

    ExpansionPreview expansionPreview(UUID playerId, ExpansionDirection direction) {
        PhaseThreeRepository.TownFinance account = finance.findFinanceByPlayer(playerId)
                .orElseThrow(() -> new IllegalArgumentException("你不属于任何小镇"));
        if (!account.role().equals("MAYOR")) {
            throw new IllegalArgumentException("只有镇长可以使用公共资金扩张");
        }
        List<PhaseThreeRepository.TerritoryUnitSnapshot> snapshots =
                finance.territoryUnits(account.townId()).stream()
                        .filter(unit -> !unit.projectionStatus().equals("FAILED")).toList();
        if (snapshots.size() >= phaseThreeSettings.maximumUnits()) {
            throw new IllegalArgumentException("领地单元已达到配置上限");
        }
        TerritoryUnit candidate = TerritoryRules.next(
                snapshots.stream().map(PhaseThreeRepository.TerritoryUnitSnapshot::unit).toList(),
                direction);
        long price = ExpansionPricing.price(phaseThreeSettings.expansionBaseCost(),
                phaseThreeSettings.expansionGrowthFactor(), snapshots.size(), settlement.scale())
                .minorUnits();
        PhaseThreeRepository.TerritoryUnitSnapshot origin = snapshots.stream()
                .filter(unit -> unit.unit().gridX() == 0 && unit.unit().gridZ() == 0)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("初始领地单元缺失"));
        String areaName = "unit_" + coordinate(candidate.gridX()) + "_"
                + coordinate(candidate.gridZ());
        return new ExpansionPreview(account, candidate, origin.residenceName(), areaName, price,
                snapshots.size() + 1);
    }

    void expand(Player mayor, ExpansionDirection direction) {
        if (!consumptionEnabled()) {
            mayor.sendMessage("§c阶段 3 新消费入口已由功能开关暂停。");
            return;
        }
        read(mayor, () -> expansionPreview(mayor.getUniqueId(), direction), preview -> {
            SitePolicy.Validation validation = sitePolicy.validateExpansion(
                    preview.candidate().territory(), preview.residenceName());
            if (!validation.valid()) {
                mayor.sendMessage("§c扩张环境复核失败: " + validation.error());
                return;
            }
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    PhaseThreeRepository.ExpansionOperation operation = finance.prepareExpansion(
                            new PhaseThreeRepository.ExpansionRequest(preview.account().townId(),
                                    preview.candidate(), preview.residenceName(), preview.areaName(),
                                    preview.priceMinor(), mayor.getUniqueId(), mayor.getName(),
                                    "expansion:" + UUID.randomUUID()));
                    plugin.getServer().getScheduler().runTask(plugin,
                            () -> projectExpansion(mayor, operation));
                } catch (RuntimeException exception) {
                    handleFailure(mayor, exception);
                }
            });
        });
    }

    String money(long minorUnits) {
        return java.math.BigDecimal.valueOf(minorUnits, settlement.scale()).toPlainString();
    }

    static String percent(int basisPoints) {
        return java.math.BigDecimal.valueOf(basisPoints, 2).stripTrailingZeros().toPlainString()
                + "%";
    }

    private void projectExpansion(CommandSender sender,
                                  PhaseThreeRepository.ExpansionOperation operation) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<UUID> loaded = repository.listMemberIds(operation.townId());
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> addExpansionArea(sender, operation, loaded));
            } catch (RuntimeException exception) {
                handleFailure(sender, exception);
            }
        });
    }

    private void addExpansionArea(CommandSender sender,
                                  PhaseThreeRepository.ExpansionOperation operation,
                                  List<UUID> members) {
        LandProtectionService.Result attempted;
        try {
            attempted = landProtection.addArea(operation.residenceName(),
                    new LandProtectionService.Area(operation.residenceAreaName(),
                            operation.unit().territory()), members);
        } catch (RuntimeException | LinkageError exception) {
            attempted = LandProtectionService.Result.failure(
                    "Residence API 不可用: " + safeMessage(exception));
        }
        LandProtectionService.Result result = attempted;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (result.success()) {
                    finance.completeExpansion(operation.expansionId());
                } else {
                    finance.refundExpansion(operation.expansionId(), result.message());
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(
                        result.success() ? "§a领地扩张完成，公共资金已扣款。"
                                : "§cResidence 扩张失败，公共资金已自动退款: "
                                + result.message()));
            } catch (RuntimeException exception) {
                handleFailure(sender, exception);
            }
        });
    }

    private void recoverExpansions(List<PhaseThreeRepository.ExpansionOperation> expansions) {
        for (PhaseThreeRepository.ExpansionOperation expansion : expansions) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    List<UUID> members = repository.listMemberIds(expansion.townId());
                    plugin.getServer().getScheduler().runTask(plugin,
                            () -> addExpansionArea(org.bukkit.Bukkit.getConsoleSender(),
                                    expansion, members));
                } catch (RuntimeException exception) {
                    plugin.getLogger().severe("恢复领地扩张失败 " + expansion.expansionId()
                            + ": " + safeMessage(exception));
                }
            });
        }
    }

    private void executeExternalOperation(CommandSender sender,
                                          Supplier<PhaseThreeRepository.EconomyOperation> prepare,
                                          java.util.function.Function<PhaseThreeRepository.EconomyOperation,
                                                  VaultSettlementService.Result> external,
                                          Consumer<PhaseThreeRepository.LedgerMutation> success) {
        if (!databaseAvailable.get()) {
            sender.sendMessage("§cSQLite 当前不可用，资金操作已锁定。");
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PhaseThreeRepository.EconomyOperation operation = prepare.get();
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> preflightExternalOperation(sender, operation, external, success));
            } catch (RuntimeException exception) {
                handleFailure(sender, exception);
            }
        });
    }

    private void preflightExternalOperation(CommandSender sender,
                                            PhaseThreeRepository.EconomyOperation operation,
                                            java.util.function.Function<
                                                    PhaseThreeRepository.EconomyOperation,
                                                    VaultSettlementService.Result> external,
                                            Consumer<PhaseThreeRepository.LedgerMutation> success) {
        VaultSettlementService.Result availability;
        try {
            availability = settlement.checkAvailability();
        } catch (RuntimeException exception) {
            availability = VaultSettlementService.Result.failure(
                    "Vault 清算账户预检异常: " + safeMessage(exception), false, false);
        }
        if (!availability.success()) {
            finishFailedExternalOperation(sender, operation, availability);
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                finance.markOperationExternalApplied(operation.operationId());
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> applyExternalOperation(sender, operation, external, success));
            } catch (RuntimeException exception) {
                handleFailure(sender, exception);
            }
        });
    }

    private void applyExternalOperation(CommandSender sender,
                                        PhaseThreeRepository.EconomyOperation operation,
                                        java.util.function.Function<
                                                PhaseThreeRepository.EconomyOperation,
                                                VaultSettlementService.Result> external,
                                        Consumer<PhaseThreeRepository.LedgerMutation> success) {
        VaultSettlementService.Result result;
        try {
            result = external.apply(operation);
        } catch (RuntimeException exception) {
            result = VaultSettlementService.Result.failure(
                    "Vault 外部调用异常，资金结果需要人工复核: " + safeMessage(exception),
                    false, true);
        }
        if (!result.success()) {
            finishFailedExternalOperation(sender, operation, result);
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PhaseThreeRepository.LedgerMutation mutation =
                        finance.completeOperation(operation.operationId());
                plugin.getServer().getScheduler().runTask(plugin, () -> success.accept(mutation));
            } catch (RuntimeException exception) {
                handleFailure(sender, exception);
            }
        });
    }

    private void finishFailedExternalOperation(CommandSender sender,
                                               PhaseThreeRepository.EconomyOperation operation,
                                               VaultSettlementService.Result result) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (result.compensationRequired()) {
                    finance.requireCompensation(operation.operationId(), result.message());
                    plugin.getLogger().severe("资金操作需要人工补偿，已锁定小镇消费: operation="
                            + operation.operationId() + ", town=" + operation.townId()
                            + ", error=" + result.message());
                } else {
                    finance.cancelOperation(operation.operationId(), result.message());
                }
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> sender.sendMessage("§c资金操作失败"
                                + (result.compensated() ? "，已自动退回玩家资金: " : ": ")
                                + result.message() + (result.compensationRequired()
                                ? "；该镇消费已锁定，需人工补偿。" : "")));
            } catch (RuntimeException exception) {
                handleFailure(sender, exception);
            }
        });
    }

    private void refreshTaxPolicies() {
        Map<UUID, QuickShopTaxAdapter.TaxPolicy> loaded = new java.util.HashMap<>();
        for (PhaseThreeRepository.MemberTaxPolicy policy : finance.loadMemberTaxPolicies()) {
            loaded.put(policy.playerId(), new QuickShopTaxAdapter.TaxPolicy(policy.townId(),
                    policy.taxRateBps()));
        }
        taxPolicies.clear();
        taxPolicies.putAll(loaded);
    }

    private static UUID actorId(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    private void sendTaxRangeError(CommandSender sender) {
        sender.sendMessage("§c税率必须在 0% 到配置上限 "
                + percent(phaseThreeSettings.maximumTaxBps()) + "（含）之间。");
    }

    private static String coordinate(int value) {
        return value < 0 ? "m" + Math.abs(value) : "p" + value;
    }

    private <T> void execute(CommandSender sender, boolean write, Supplier<T> operation,
                             Consumer<T> success) {
        if (write && !databaseAvailable.get()) {
            sender.sendMessage("§cSQLite 当前不可用，写操作已锁定；现有 Residence 保护不受影响。");
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                T result = operation.get();
                databaseAvailable.set(true);
                plugin.getServer().getScheduler().runTask(plugin, () -> success.accept(result));
            } catch (RuntimeException exception) {
                handleFailure(sender, exception);
            }
        });
    }

    private void handleFailure(CommandSender sender, RuntimeException exception) {
        if (exception instanceof PhaseOneRepository.StorageUnavailableException
                || exception instanceof GovernanceRepository.StorageUnavailableException
                || exception instanceof PhaseThreeRepository.StorageUnavailableException
                || exception instanceof PhaseFourRepository.StorageUnavailableException
                || exception instanceof PhaseFiveRepository.StorageUnavailableException) {
            databaseAvailable.set(false);
            plugin.getLogger().severe(exception.getMessage());
        }
        plugin.getServer().getScheduler().runTask(plugin,
                () -> sender.sendMessage("§c操作失败: " + safeMessage(exception)));
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    record ExpansionPreview(PhaseThreeRepository.TownFinance account, TerritoryUnit candidate,
                            String residenceName, String areaName, long priceMinor,
                            int totalUnits) {
    }

    private record TownMembers(TownSnapshot town, List<UUID> members,
                               List<PhaseThreeRepository.TerritoryUnitSnapshot> units) {
    }
}

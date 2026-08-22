package cn.tianji.town.paper;

import cn.tianji.town.core.ports.LandProtectionService;
import cn.tianji.town.core.ports.WorldBoundaryService;
import cn.tianji.town.integrations.globalmarketplus.GlobalMarketPlusIncomeTaxAdapter;
import cn.tianji.town.integrations.jobs.JobsIncomeTaxAdapter;
import cn.tianji.town.core.land.ExpansionDirection;
import cn.tianji.town.core.land.ExpansionPricing;
import cn.tianji.town.core.land.TerritoryRules;
import cn.tianji.town.core.land.TerritoryUnit;
import cn.tianji.town.core.town.TownStatus;
import cn.tianji.town.integrations.quickshop.QuickShopTaxAdapter;
import cn.tianji.town.integrations.vault.VaultSettlementService;
import cn.tianji.town.storage.database.DatabaseGate;
import cn.tianji.town.storage.town.ApplicationSnapshot;
import cn.tianji.town.storage.town.TownRepository;
import cn.tianji.town.storage.town.TownSnapshot;
import cn.tianji.town.storage.governance.GovernanceRepository;
import cn.tianji.town.storage.governance.VoteSnapshot;
import cn.tianji.town.storage.economy.EconomyRepository;
import cn.tianji.town.storage.commerce.CommerceRepository;
import cn.tianji.town.storage.bonus.TownBonusRepository;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class TownRuntime {
    private static final BigDecimal APPLICATION_FEE = new BigDecimal("2000.00");
    private final TianjiTownPlugin plugin;
    private final DatabaseGate database;
    private final TownRepository repository;
    private final GovernanceRepository governance;
    private final EconomyRepository finance;
    private final LandProtectionService landProtection;
    private final SitePolicy sitePolicy;
    private final EconomySettings economySettings;
    private final VaultSettlementService settlement;
    private final BuffRuntime buffs;
    private final TownBonusRuntime bonuses;
    private final Map<UUID, QuickShopTaxAdapter.TaxPolicy> taxPolicies = new ConcurrentHashMap<>();
    private final RetryingWorkQueue<QuickShopTaxAdapter.SuccessfulTax> pendingTaxes;
    private final RetryingWorkQueue<EconomyRepository.ExternalIncomeTax> pendingIncomeTaxes;
    private final DonationCompensationCoordinator donationCompensations;
    private final AtomicBoolean databaseAvailable = new AtomicBoolean(true);
    private final AtomicBoolean quickShopTaxAvailable = new AtomicBoolean(false);
    private final ProvisionCoordinator provisions = new ProvisionCoordinator();

    TownRuntime(TianjiTownPlugin plugin, DatabaseGate database,
                    LandProtectionService landProtection,
                    WorldBoundaryService worldBoundaries) {
        this.plugin = plugin;
        this.database = database;
        this.landProtection = landProtection;
        this.sitePolicy = new SitePolicy(plugin, landProtection, worldBoundaries);
        this.repository = new TownRepository(database.dataSource(),
                plugin.getServer()::isPrimaryThread);
        this.governance = new GovernanceRepository(database.dataSource(),
                plugin.getServer()::isPrimaryThread);
        this.finance = new EconomyRepository(database.dataSource(),
                plugin.getServer()::isPrimaryThread);
        this.economySettings = EconomySettings.load(plugin.getConfig());
        this.settlement = new VaultSettlementService(plugin.getServer(),
                economySettings.settlementAccount(), economySettings.fallbackScale());
        this.buffs = new BuffRuntime(plugin, this,
                new CommerceRepository(database.dataSource(),
                        plugin.getServer()::isPrimaryThread),
                BuffSettings.load(plugin.getConfig(), settlement.scale()));
        this.bonuses = new TownBonusRuntime(plugin, this,
                new TownBonusRepository(database.dataSource(),
                        plugin.getServer()::isPrimaryThread),
                TownBonusSettings.load(plugin.getConfig()), java.util.Objects.requireNonNull(
                plugin.getServer().getPluginManager().getPlugin("QuickShop-Hikari"),
                "QuickShop-Hikari"));
        this.pendingTaxes = new RetryingWorkQueue<>(new RetryingWorkQueue.Scheduler() {
            @Override
            public void executeAsync(Runnable task) {
                if (!plugin.runAsync(task)) {
                    throw new java.util.concurrent.RejectedExecutionException(
                            "插件生命周期已停止");
                }
            }

            @Override
            public void schedule(Runnable task, long delayTicks) {
                if (!plugin.runMainLater(task, delayTicks)) {
                    throw new java.util.concurrent.RejectedExecutionException(
                            "插件生命周期已停止");
                }
            }
        }, 20L * 5, 20L * 30, this::recordQuickShopTax, this::handleQuickShopTaxFailure);
        this.pendingIncomeTaxes = new RetryingWorkQueue<>(new RetryingWorkQueue.Scheduler() {
            @Override
            public void executeAsync(Runnable task) {
                if (!plugin.runAsync(task)) {
                    throw new java.util.concurrent.RejectedExecutionException(
                            "插件生命周期已停止");
                }
            }

            @Override
            public void schedule(Runnable task, long delayTicks) {
                if (!plugin.runMainLater(task, delayTicks)) {
                    throw new java.util.concurrent.RejectedExecutionException(
                            "插件生命周期已停止");
                }
            }
        }, 20L * 5, 20L * 30, this::recordExternalIncomeTax,
                this::handleExternalIncomeTaxFailure);
        this.donationCompensations = createDonationCompensationCoordinator();
    }

    private DonationCompensationCoordinator createDonationCompensationCoordinator() {
        return new DonationCompensationCoordinator(new DonationCompensationCoordinator.Scheduler() {
            @Override
            public void runMainLater(Runnable task, long delayTicks) {
                plugin.runMainLater(task, delayTicks);
            }

            @Override
            public void runAsync(Runnable task) {
                plugin.runAsync(task);
            }
        }, (playerId, amountMinor) -> settlement.refundDebitedPlayer(
                plugin.getServer().getOfflinePlayer(playerId), amountMinor),
                (operationId, detail) -> finance.resolveCompensation(operationId, detail),
                new DonationCompensationCoordinator.Listener() {
                    @Override
                    public void retryFailed(EconomyRepository.EconomyOperation operation,
                                            int attempt, String detail) {
                        plugin.getLogger().warning("捐款自动补偿第 " + attempt
                                + " 次尝试失败: operation=" + operation.operationId()
                                + ", error=" + detail);
                    }

                    @Override
                    public void finalizationFailed(EconomyRepository.EconomyOperation operation,
                                                   int attempt, String detail) {
                        plugin.getLogger().warning("捐款外部余额已恢复，但第 " + attempt
                                + " 次 SQLite 收尾失败: operation=" + operation.operationId()
                                + ", error=" + detail);
                    }

                    @Override
                    public void recovered(EconomyRepository.EconomyOperation operation,
                                          int attempts) {
                        plugin.getLogger().info("捐款自动补偿已恢复玩家余额，正在复核消费锁: operation="
                                + operation.operationId() + ", attempts=" + attempts);
                        reconcileSettlementAfterCompensation(operation);
                    }

                    @Override
                    public void exhausted(EconomyRepository.EconomyOperation operation,
                                          String detail) {
                        plugin.getLogger().severe("捐款自动补偿达到重试上限，消费锁保持不变: operation="
                                + operation.operationId() + ", error=" + detail);
                    }
                });
    }

    private void reconcileSettlementAfterCompensation(
            EconomyRepository.EconomyOperation operation) {
        plugin.runMain(() -> {
            long externalBalance;
            try {
                externalBalance = settlement.balanceMinor();
            } catch (RuntimeException exception) {
                plugin.getLogger().severe("自动补偿后的清算余额读取失败，消费锁保持不变: operation="
                        + operation.operationId() + ", error=" + safeMessage(exception));
                return;
            }
            plugin.runAsync(() -> {
                try {
                    EconomyRepository.Reconciliation reconciliation =
                            finance.reconcileSettlement(externalBalance);
                    if (!reconciliation.healthy()) {
                        plugin.getLogger().severe("自动补偿完成但清算仍有短款，消费锁保持不变: operation="
                                + operation.operationId() + ", external="
                                + reconciliation.externalBalanceMinor() + ", required="
                                + reconciliation.requiredMinor());
                    }
                } catch (RuntimeException exception) {
                    plugin.getLogger().severe("自动补偿后的清算复核失败，消费锁保持不变: operation="
                            + operation.operationId() + ", error=" + safeMessage(exception));
                }
            });
        });
    }

    TownRepository repository() {
        return repository;
    }

    GovernanceRepository governance() {
        return governance;
    }

    EconomyRepository finance() {
        return finance;
    }

    EconomySettings economySettings() {
        return economySettings;
    }

    VaultSettlementService settlement() {
        return settlement;
    }

    BuffRuntime buffs() {
        return buffs;
    }

    TownBonusRuntime bonuses() {
        return bonuses;
    }

    DatabaseGate database() {
        return database;
    }

    QuickShopTaxAdapter.TaxPolicy taxPolicy(UUID receiverId) {
        return taxEnabled() ? taxPolicies.get(receiverId) : null;
    }

    boolean taxEnabled() {
        return plugin.getConfig().getBoolean("phase3.tax.enabled",
                economySettings.taxEnabled());
    }

    boolean quickShopTaxEnabled() {
        return taxEnabled() && quickShopTaxAvailable.get();
    }

    void setQuickShopTaxAvailable(boolean available) {
        quickShopTaxAvailable.set(available);
    }

    boolean consumptionEnabled() {
        return plugin.getConfig().getBoolean("phase3.consumption.enabled",
                economySettings.consumptionEnabled());
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
        plugin.runAsync(() -> {
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
        plugin.runAsync(() -> {
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
                List<EconomyRepository.ExpansionOperation> expansions =
                        finance.pendingExpansions();
                if (!expansions.isEmpty()) {
                    plugin.runMain(
                            () -> recoverExpansions(expansions));
                }
            } catch (RuntimeException exception) {
                if (exception instanceof TownRepository.StorageUnavailableException) {
                    databaseAvailable.set(false);
                }
                plugin.getLogger().severe("恢复中断建镇流程失败: " + safeMessage(exception));
            }
        });
    }

    void reconcileAll() {
        plugin.runAsync(() -> {
            try {
                List<TownMembers> states = repository.listTowns(false).stream()
                        .filter(town -> town.status() == TownStatus.ACTIVE)
                        .map(town -> new TownMembers(town, repository.listMemberIds(town.id()),
                                finance.territoryUnits(town.id())))
                        .toList();
                plugin.runMain(() -> {
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
        plugin.runAsync(() -> {
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
                    plugin.runMain(() -> {
                        for (TownMembers state : changedMemberships) {
                            reconcile(org.bukkit.Bukkit.getConsoleSender(), state.town(),
                                    state.members(), true);
                        }
                        buffs.refreshAllPlayers();
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
        plugin.runAsync(() -> {
            try {
                boolean archived = repository.archiveTownForMissingProjection(town.id(), detail);
                databaseAvailable.set(true);
                if (archived) {
                    plugin.getLogger().severe("小镇已安全归档 " + town.profile().name()
                            + "；名称、领地名称和原区块保持锁定，未自动创建或删除 Residence。");
                }
            } catch (RuntimeException exception) {
                if (exception instanceof TownRepository.StorageUnavailableException) {
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
        plugin.runAsync(() -> {
            try {
                ApplicationSnapshot application = repository.findApplication(applicationId)
                        .orElseThrow(() -> new IllegalArgumentException("申请不存在"));
                long feeMinor = application.applicationFeeMinor() > 0
                        ? application.applicationFeeMinor()
                        : APPLICATION_FEE.movePointRight(settlement.scale())
                        .longValueExact();
                plugin.runMain(() -> chargeAndBeginProvision(
                        sender, application, reviewerId, reviewerName, reason, idempotencyKey,
                        feeMinor, completion));
            } catch (RuntimeException exception) {
                provisions.finish(applicationId);
                handleFailure(sender, exception);
            }
        });
    }

    private void chargeAndBeginProvision(CommandSender sender, ApplicationSnapshot application,
                                         UUID reviewerId, String reviewerName, String reason,
                                         String idempotencyKey, long feeMinor,
                                         Consumer<ApplicationSnapshot> completion) {
        boolean needsCharge = application.applicationFeeMinor() == 0;
        if (needsCharge) {
            VaultSettlementService.Result payment = settlement.transferFromPlayer(
                    plugin.getServer().getOfflinePlayer(application.applicantId()), feeMinor);
            if (!payment.success()) {
                provisions.finish(application.id());
                sender.sendMessage("§c申请人无法支付建镇申请费 " + money(feeMinor)
                        + ": " + payment.message());
                return;
            }
        }
        plugin.runAsync(() -> {
            try {
                TownRepository.Provisioning provisioning = repository.beginProvision(
                        application.id(), reviewerId, reviewerName, reason, idempotencyKey,
                        feeMinor);
                databaseAvailable.set(true);
                plugin.runMain(
                        () -> projectProvision(sender, application.id(), provisioning, completion));
            } catch (RuntimeException exception) {
                plugin.runMain(() -> {
                    if (needsCharge) {
                        VaultSettlementService.Result refund = settlement.transferToPlayer(
                                plugin.getServer().getOfflinePlayer(application.applicantId()),
                                feeMinor);
                        if (!refund.success()) {
                            plugin.getLogger().severe("建镇申请数据库写入失败且申请费自动返还失败: "
                                    + refund.message());
                        }
                    }
                    provisions.finish(application.id());
                    handleFailure(sender, exception);
                });
            }
        });
    }

    private void projectProvision(CommandSender sender, UUID applicationId,
                                  TownRepository.Provisioning provisioning,
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
            plugin.runAsync(() -> finishProvision(sender, applicationId, land, completion));
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
            plugin.runMain(() -> {
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
        reconcileAction(sender, town, members, repair, result -> sender.sendMessage(
                        (result.success() ? "§a" : "§c") + town.profile().name() + ": "
                                + result.message()),
                exception -> handleFailure(sender, exception));
    }

    void reconcileAction(CommandSender sender, TownSnapshot town, List<UUID> members,
                         boolean repair, Consumer<LandProtectionService.Result> success,
                         Consumer<RuntimeException> failure) {
        plugin.runAsync(() -> {
            try {
                List<LandProtectionService.Area> areas = finance.territoryUnits(town.id()).stream()
                        .filter(unit -> unit.projectionStatus().equals("ACTIVE"))
                        .map(unit -> new LandProtectionService.Area(unit.residenceAreaName(),
                                unit.unit().territory())).toList();
                refreshTaxPolicies();
                plugin.runMain(() -> {
                    LandProtectionService.Result result;
                    try {
                        result = landProtection.reconcile(town.residenceName(), areas, members,
                                repair);
                    } catch (RuntimeException | LinkageError exception) {
                        result = LandProtectionService.Result.failure(
                                "Residence API 不可用: " + safeMessage(exception));
                    }
                    recordLandAudit(actorId(sender), sender.getName(), town.id(), repair, result);
                    success.accept(result);
                });
            } catch (RuntimeException exception) {
                reportActionFailure(exception, failure);
            }
        });
    }

    private void recordLandAudit(UUID actorId, String actorName, UUID townId, boolean repair,
                                 LandProtectionService.Result result) {
        plugin.runAsync(() -> {
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
        VaultSettlementService.Result subsidy = settlement.adjustSettlement(tax.taxMinor());
        if (!subsidy.success()) {
            plugin.getLogger().severe("QuickShop 税收服务器补贴入账失败，暂不写入双倍账本: "
                    + subsidy.message());
            return;
        }
        pendingTaxes.submit(tax);
    }

    void flushPendingTaxes() {
        pendingTaxes.flush();
        pendingIncomeTaxes.flush();
    }

    private void recordQuickShopTax(QuickShopTaxAdapter.SuccessfulTax tax) {
        finance.recordQuickShopTax(new EconomyRepository.QuickShopTax(
                tax.townId(), tax.businessKey(), tax.shopId(), tax.shopType(),
                tax.receiverId(), tax.interactingId(), tax.grossMinor(),
                tax.basisPoints(), tax.taxMinor(), tax.worldName()));
        databaseAvailable.set(true);
    }

    private void handleQuickShopTaxFailure(QuickShopTaxAdapter.SuccessfulTax tax,
                                           RuntimeException exception) {
        if (exception instanceof EconomyRepository.StorageUnavailableException) {
            databaseAvailable.set(false);
        }
        plugin.getLogger().severe("QuickShop 税款 " + tax.businessKey()
                + " 已进入清算账户但账本暂未写入，将自动重试: "
                + safeMessage(exception));
    }

    JobsIncomeTaxAdapter.TaxResult acceptJobsIncomeTax(
            JobsIncomeTaxAdapter.Earning earning) {
        EconomyRepository.ExternalIncomeTax tax = externalIncomeTax("JOBS",
                earning.player(), earning.player().getName(), earning.grossAmount(),
                "jobs:" + UUID.randomUUID());
        if (tax == null) {
            return JobsIncomeTaxAdapter.TaxResult.unchanged(earning.grossAmount());
        }
        VaultSettlementService.Result transferred = settlement.adjustSettlement(
                Math.multiplyExact(tax.taxMinor(), 2));
        if (!transferred.success()) {
            plugin.getLogger().severe("Jobs 收入税转入清算账户失败，已保留玩家原始收入: "
                    + transferred.message());
            return JobsIncomeTaxAdapter.TaxResult.unchanged(earning.grossAmount());
        }
        pendingIncomeTaxes.submit(tax);
        double net = BigDecimal.valueOf(tax.grossMinor() - tax.taxMinor(),
                settlement.scale()).doubleValue();
        return JobsIncomeTaxAdapter.TaxResult.taxed(net);
    }

    void acceptGlobalMarketPlusIncomeTax(GlobalMarketPlusIncomeTaxAdapter.Earning earning) {
        EconomyRepository.ExternalIncomeTax tax = externalIncomeTax("GLOBALMARKETPLUS",
                earning.player(), earning.receiverName(), earning.grossAmount(),
                earning.businessKey());
        if (tax == null) {
            return;
        }
        VaultSettlementService.Result transferred = settlement.transferFromPlayer(
                earning.player(), tax.taxMinor());
        if (!transferred.success()) {
            plugin.getLogger().severe("GlobalMarketPlus 收入税扣取失败，未写入小镇账本: "
                    + transferred.message());
            return;
        }
        VaultSettlementService.Result subsidy = settlement.adjustSettlement(tax.taxMinor());
        if (!subsidy.success()) {
            VaultSettlementService.Result refunded = settlement.transferToPlayer(
                    earning.player(), tax.taxMinor());
            plugin.getLogger().severe("GlobalMarketPlus 税收服务器补贴入账失败，税款"
                    + (refunded.success() ? "已返还玩家: " : "返还玩家也失败: ")
                    + subsidy.message());
            return;
        }
        pendingIncomeTaxes.submit(tax);
    }

    private EconomyRepository.ExternalIncomeTax externalIncomeTax(
            String source, org.bukkit.OfflinePlayer receiver, String receiverName, double gross,
            String businessKey) {
        if (!taxEnabled() || !Double.isFinite(gross) || gross <= 0) {
            return null;
        }
        QuickShopTaxAdapter.TaxPolicy policy = taxPolicies.get(receiver.getUniqueId());
        if (policy == null || policy.basisPoints() <= 0) {
            return null;
        }
        long grossMinor = BigDecimal.valueOf(gross).movePointRight(settlement.scale())
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        long taxMinor = BigDecimal.valueOf(grossMinor)
                .multiply(BigDecimal.valueOf(policy.basisPoints()))
                .movePointLeft(4).setScale(0, RoundingMode.HALF_UP).longValueExact();
        if (grossMinor <= 0 || taxMinor <= 0 || taxMinor >= grossMinor) {
            return null;
        }
        String safeName = receiverName == null || receiverName.isBlank()
                ? receiver.getUniqueId().toString() : receiverName;
        return new EconomyRepository.ExternalIncomeTax(policy.townId(), businessKey, source,
                receiver.getUniqueId(), safeName, grossMinor, policy.basisPoints(), taxMinor);
    }

    private void recordExternalIncomeTax(EconomyRepository.ExternalIncomeTax tax) {
        finance.recordExternalIncomeTax(tax);
        databaseAvailable.set(true);
    }

    private void handleExternalIncomeTaxFailure(EconomyRepository.ExternalIncomeTax tax,
                                                RuntimeException exception) {
        if (exception instanceof EconomyRepository.StorageUnavailableException) {
            databaseAvailable.set(false);
        }
        plugin.getLogger().severe(tax.source() + " 税款 " + tax.businessKey()
                + " 已进入清算账户但账本暂未写入，将自动重试: " + safeMessage(exception));
    }

    void reconcileSettlement() {
        long external;
        try {
            external = settlement.balanceMinor();
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("读取 Vault 清算账户失败: " + safeMessage(exception));
            return;
        }
        plugin.runAsync(() -> {
            try {
                EconomyRepository.Reconciliation result = finance.reconcileSettlement(external);
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

    void donateAction(Player player, long amountMinor,
                      Consumer<EconomyRepository.LedgerMutation> success,
                      Consumer<RuntimeException> failure) {
        if (!consumptionEnabled()) {
            failure.accept(new IllegalStateException("公共资金新消费入口已由功能开关暂停"));
            return;
        }
        executeExternalOperation(player, () -> {
            EconomyRepository.TownFinance account = finance.findFinanceByPlayer(
                    player.getUniqueId()).orElseThrow(() ->
                    new IllegalArgumentException("你不属于任何小镇"));
            String key = "donation:" + UUID.randomUUID();
            return finance.prepareOperation(account.townId(), "DONATION", amountMinor,
                    player.getUniqueId(), player.getName(), key, "成员捐款");
        }, operation -> settlement.transferFromPlayer(player, operation.amountMinor()), success,
                failure);
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
        if (!economySettings.allowsTaxRate(basisPoints)) {
            sendTaxRangeError(mayor);
            return;
        }
        write(mayor, () -> {
            EconomyRepository.TaxChange change = finance.changeTaxRate(townId,
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
        if (!economySettings.allowsTaxRate(basisPoints)) {
            sendTaxRangeError(sender);
            return;
        }
        write(sender, () -> {
            EconomyRepository.TaxChange change = finance.forceTaxRate(townId,
                    actorId(sender), basisPoints, sender.getName(), reason);
            refreshTaxPolicies();
            return change;
        }, change -> sender.sendMessage("§a税率已强制调整为 "
                + percent(change.basisPoints())));
    }

    ExpansionPreview expansionPreview(UUID playerId, ExpansionDirection direction) {
        EconomyRepository.TownFinance account = finance.findFinanceByPlayer(playerId)
                .orElseThrow(() -> new IllegalArgumentException("你不属于任何小镇"));
        if (!account.role().equals("MAYOR")) {
            throw new IllegalArgumentException("只有镇长可以使用公共资金扩张");
        }
        List<EconomyRepository.TerritoryUnitSnapshot> snapshots =
                finance.territoryUnits(account.townId()).stream()
                        .filter(unit -> !unit.projectionStatus().equals("FAILED")).toList();
        if (snapshots.size() >= economySettings.maximumUnits()) {
            throw new IllegalArgumentException("领地单元已达到配置上限");
        }
        TerritoryUnit candidate = TerritoryRules.next(
                snapshots.stream().map(EconomyRepository.TerritoryUnitSnapshot::unit).toList(),
                direction);
        long price = ExpansionPricing.price(economySettings.expansionBaseCost(),
                economySettings.expansionPerUnitIncrease(), snapshots.size(),
                settlement.scale())
                .minorUnits();
        EconomyRepository.TerritoryUnitSnapshot origin = snapshots.stream()
                .filter(unit -> unit.unit().gridX() == 0 && unit.unit().gridZ() == 0)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("初始领地单元缺失"));
        String areaName = "unit_" + coordinate(candidate.gridX()) + "_"
                + coordinate(candidate.gridZ());
        return new ExpansionPreview(account, candidate, origin.residenceName(), areaName, price,
                snapshots.size() + 1);
    }

    void expandAction(Player mayor, ExpansionDirection direction,
                      Consumer<EconomyRepository.ExpansionOperation> success,
                      Consumer<RuntimeException> failure) {
        if (!consumptionEnabled()) {
            failure.accept(new IllegalStateException("公共资金新消费入口已由功能开关暂停"));
            return;
        }
        readAction(mayor, () -> expansionPreview(mayor.getUniqueId(), direction), preview -> {
            SitePolicy.Validation validation = sitePolicy.validateExpansion(
                    preview.candidate().territory(), preview.residenceName());
            if (!validation.valid()) {
                failure.accept(new IllegalArgumentException(
                        "扩张环境复核失败: " + validation.error()));
                return;
            }
            plugin.runAsync(() -> {
                try {
                    EconomyRepository.ExpansionOperation operation = finance.prepareExpansion(
                            new EconomyRepository.ExpansionRequest(preview.account().townId(),
                                    preview.candidate(), preview.residenceName(), preview.areaName(),
                                    preview.priceMinor(), mayor.getUniqueId(), mayor.getName(),
                                    "expansion:" + UUID.randomUUID()));
                    plugin.runMain(
                            () -> projectExpansion(mayor, operation, success, failure));
                } catch (RuntimeException exception) {
                    reportActionFailure(exception, failure);
                }
            });
        }, failure);
    }

    <T> void writeAction(CommandSender sender, Supplier<T> operation, Consumer<T> success,
                         Consumer<RuntimeException> failure) {
        executeAction(sender, true, operation, success, failure);
    }

    <T> void readAction(CommandSender sender, Supplier<T> operation, Consumer<T> success,
                        Consumer<RuntimeException> failure) {
        executeAction(sender, false, operation, success, failure);
    }

    String money(long minorUnits) {
        return java.math.BigDecimal.valueOf(minorUnits, settlement.scale()).toPlainString();
    }

    static String percent(int basisPoints) {
        return java.math.BigDecimal.valueOf(basisPoints, 2).stripTrailingZeros().toPlainString()
                + "%";
    }

    private void projectExpansion(CommandSender sender,
                                  EconomyRepository.ExpansionOperation operation,
                                  Consumer<EconomyRepository.ExpansionOperation> success,
                                  Consumer<RuntimeException> failure) {
        plugin.runAsync(() -> {
            try {
                List<UUID> loaded = repository.listMemberIds(operation.townId());
                plugin.runMain(
                        () -> addExpansionArea(sender, operation, loaded, success, failure));
            } catch (RuntimeException exception) {
                reportActionFailure(exception, failure);
            }
        });
    }

    private void addExpansionArea(CommandSender sender,
                                  EconomyRepository.ExpansionOperation operation,
                                  List<UUID> members,
                                  Consumer<EconomyRepository.ExpansionOperation> success,
                                  Consumer<RuntimeException> failure) {
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
        plugin.runAsync(() -> {
            try {
                if (result.success()) {
                    finance.completeExpansion(operation.expansionId());
                } else {
                    finance.refundExpansion(operation.expansionId(), result.message());
                }
                plugin.runMain(() -> {
                    if (result.success()) {
                        success.accept(operation);
                    } else {
                        failure.accept(new IllegalStateException(
                                "Residence 扩张失败，公共资金已自动退款: " + result.message()));
                    }
                });
            } catch (RuntimeException exception) {
                reportActionFailure(exception, failure);
            }
        });
    }

    private void recoverExpansions(List<EconomyRepository.ExpansionOperation> expansions) {
        for (EconomyRepository.ExpansionOperation expansion : expansions) {
            plugin.runAsync(() -> {
                try {
                    List<UUID> members = repository.listMemberIds(expansion.townId());
                    plugin.runMain(
                            () -> addExpansionArea(org.bukkit.Bukkit.getConsoleSender(),
                                    expansion, members,
                                    ignored -> plugin.getLogger().info(
                                            "已恢复领地扩张 " + expansion.expansionId()),
                                    exception -> plugin.getLogger().severe(
                                            "恢复领地扩张失败 " + expansion.expansionId()
                                                    + ": " + safeMessage(exception))));
                } catch (RuntimeException exception) {
                    plugin.getLogger().severe("恢复领地扩张失败 " + expansion.expansionId()
                            + ": " + safeMessage(exception));
                }
            });
        }
    }

    private void executeExternalOperation(CommandSender sender,
                                          Supplier<EconomyRepository.EconomyOperation> prepare,
                                          java.util.function.Function<EconomyRepository.EconomyOperation,
                                                  VaultSettlementService.Result> external,
                                          Consumer<EconomyRepository.LedgerMutation> success) {
        executeExternalOperation(sender, prepare, external, success,
                exception -> handleFailure(sender, exception));
    }

    private void executeExternalOperation(CommandSender sender,
                                          Supplier<EconomyRepository.EconomyOperation> prepare,
                                          java.util.function.Function<EconomyRepository.EconomyOperation,
                                                  VaultSettlementService.Result> external,
                                          Consumer<EconomyRepository.LedgerMutation> success,
                                          Consumer<RuntimeException> failure) {
        if (!databaseAvailable.get()) {
            failure.accept(new EconomyRepository.StorageUnavailableException(
                    "SQLite 当前不可用，资金操作已锁定", null));
            return;
        }
        plugin.runAsync(() -> {
            try {
                EconomyRepository.EconomyOperation operation = prepare.get();
                plugin.runMain(
                        () -> preflightExternalOperation(sender, operation, external, success,
                                failure));
            } catch (RuntimeException exception) {
                reportActionFailure(exception, failure);
            }
        });
    }

    private void preflightExternalOperation(CommandSender sender,
                                            EconomyRepository.EconomyOperation operation,
                                            java.util.function.Function<
                                                    EconomyRepository.EconomyOperation,
                                                    VaultSettlementService.Result> external,
                                            Consumer<EconomyRepository.LedgerMutation> success,
                                            Consumer<RuntimeException> failure) {
        VaultSettlementService.Result availability;
        try {
            availability = settlement.checkAvailability();
        } catch (RuntimeException exception) {
            availability = VaultSettlementService.Result.failure(
                    "Vault 清算账户预检异常: " + safeMessage(exception), false, false);
        }
        if (!availability.success()) {
            finishFailedExternalOperation(sender, operation, availability, failure);
            return;
        }
        plugin.runAsync(() -> {
            try {
                finance.markOperationExternalApplied(operation.operationId());
                plugin.runMain(
                        () -> applyExternalOperation(sender, operation, external, success,
                                failure));
            } catch (RuntimeException exception) {
                reportActionFailure(exception, failure);
            }
        });
    }

    private void applyExternalOperation(CommandSender sender,
                                        EconomyRepository.EconomyOperation operation,
                                        java.util.function.Function<
                                                EconomyRepository.EconomyOperation,
                                                VaultSettlementService.Result> external,
                                        Consumer<EconomyRepository.LedgerMutation> success,
                                        Consumer<RuntimeException> failure) {
        VaultSettlementService.Result result;
        try {
            result = external.apply(operation);
        } catch (RuntimeException exception) {
            result = VaultSettlementService.Result.failure(
                    "Vault 外部调用异常，资金结果需要人工复核: " + safeMessage(exception),
                    false, true);
        }
        if (!result.success()) {
            finishFailedExternalOperation(sender, operation, result, failure);
            return;
        }
        plugin.runAsync(() -> {
            try {
                EconomyRepository.LedgerMutation mutation =
                        finance.completeOperation(operation.operationId());
                plugin.runMain(() -> success.accept(mutation));
            } catch (RuntimeException exception) {
                reportActionFailure(exception, failure);
            }
        });
    }

    private void finishFailedExternalOperation(CommandSender sender,
                                               EconomyRepository.EconomyOperation operation,
                                               VaultSettlementService.Result result,
                                               Consumer<RuntimeException> failure) {
        plugin.runAsync(() -> {
            try {
                if (result.compensationRequired()) {
                    finance.requireCompensation(operation.operationId(), result.message());
                    boolean automaticRefund = result.playerRefundRequired()
                            && operation.operationType().equals("DONATION")
                            && operation.actorId() != null;
                    if (automaticRefund) {
                        donationCompensations.submit(operation);
                        plugin.getLogger().warning("捐款退款暂时失败，已锁定小镇消费并启动自动补偿: "
                                + "operation=" + operation.operationId() + ", town="
                                + operation.townId() + ", error=" + result.message());
                    } else {
                        plugin.getLogger().severe("资金操作需要人工补偿，已锁定小镇消费: operation="
                                + operation.operationId() + ", town=" + operation.townId()
                                + ", error=" + result.message());
                    }
                } else {
                    finance.cancelOperation(operation.operationId(), result.message());
                }
                plugin.runMain(() -> failure.accept(
                        new IllegalStateException(result.message()
                                + compensationHint(operation, result))));
            } catch (RuntimeException exception) {
                reportActionFailure(exception, failure);
            }
        });
    }

    private static String compensationHint(EconomyRepository.EconomyOperation operation,
                                           VaultSettlementService.Result result) {
        if (!result.compensationRequired()) {
            return "";
        }
        return result.playerRefundRequired() && operation.operationType().equals("DONATION")
                && operation.actorId() != null
                ? "；该镇消费已临时锁定，系统正在自动补偿"
                : "；该镇消费已锁定，需人工补偿";
    }

    void refreshTaxPolicies() {
        Map<UUID, QuickShopTaxAdapter.TaxPolicy> loaded = new java.util.HashMap<>();
        for (EconomyRepository.MemberTaxPolicy policy : finance.loadMemberTaxPolicies()) {
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
        sender.sendMessage("§c税率必须在 5%~25% 之间，并按 5% 递增。");
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
        plugin.runAsync(() -> {
            try {
                T result = operation.get();
                databaseAvailable.set(true);
                plugin.runMain(() -> success.accept(result));
            } catch (RuntimeException exception) {
                handleFailure(sender, exception);
            }
        });
    }

    private <T> void executeAction(CommandSender sender, boolean write, Supplier<T> operation,
                                   Consumer<T> success, Consumer<RuntimeException> failure) {
        if (write && !databaseAvailable.get()) {
            failure.accept(new TownRepository.StorageUnavailableException(
                    "SQLite 当前不可用，写操作已锁定", null));
            return;
        }
        plugin.runAsync(() -> {
            try {
                T result = operation.get();
                databaseAvailable.set(true);
                plugin.runMain(() -> success.accept(result));
            } catch (RuntimeException exception) {
                markStorageFailure(exception);
                plugin.runMain(() -> failure.accept(exception));
            }
        });
    }

    private void handleFailure(CommandSender sender, RuntimeException exception) {
        markStorageFailure(exception);
        plugin.runMain(
                () -> sender.sendMessage("§c操作失败: " + safeMessage(exception)));
    }

    private void reportActionFailure(RuntimeException exception,
                                     Consumer<RuntimeException> failure) {
        markStorageFailure(exception);
        if (plugin.getServer().isPrimaryThread()) {
            failure.accept(exception);
        } else {
            plugin.runMain(() -> failure.accept(exception));
        }
    }

    private void markStorageFailure(RuntimeException exception) {
        if (exception instanceof TownRepository.StorageUnavailableException
                || exception instanceof GovernanceRepository.StorageUnavailableException
                || exception instanceof EconomyRepository.StorageUnavailableException
                || exception instanceof CommerceRepository.StorageUnavailableException
                || exception instanceof TownBonusRepository.StorageUnavailableException) {
            databaseAvailable.set(false);
            plugin.getLogger().severe(exception.getMessage());
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    record ExpansionPreview(EconomyRepository.TownFinance account, TerritoryUnit candidate,
                            String residenceName, String areaName, long priceMinor,
                            int totalUnits) {
    }

    private record TownMembers(TownSnapshot town, List<UUID> members,
                               List<EconomyRepository.TerritoryUnitSnapshot> units) {
    }
}

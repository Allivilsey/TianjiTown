package org.allivlisey.tianjitown.paper.runtime;
import org.allivlisey.tianjitown.paper.station.StationDirectory;
import org.allivlisey.tianjitown.storage.station.StationRepository;
import org.allivlisey.tianjitown.storage.diagnostics.TownDiagnosticRepository;
import org.allivlisey.tianjitown.paper.land.TerritoryPreviewService;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.allivlisey.tianjitown.core.land.ExpansionDirection;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.core.ports.WorldBoundaryService;
import org.allivlisey.tianjitown.integrations.globalmarketplus.GlobalMarketPlusIncomeTaxAdapter;
import org.allivlisey.tianjitown.integrations.jobs.JobsIncomeTaxAdapter;
import org.allivlisey.tianjitown.integrations.quickshop.QuickShopTaxAdapter;
import org.allivlisey.tianjitown.integrations.vault.VaultSettlementService;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.bonus.TownBonusRuntime;
import org.allivlisey.tianjitown.paper.buff.BuffRuntime;
import org.allivlisey.tianjitown.paper.config.BuffSettings;
import org.allivlisey.tianjitown.paper.config.ApplicationSettings;
import org.allivlisey.tianjitown.paper.config.EconomySettings;
import org.allivlisey.tianjitown.paper.config.TownBonusSettings;
import org.allivlisey.tianjitown.paper.land.ProvisionResult;
import org.allivlisey.tianjitown.paper.land.SitePolicy;
import org.allivlisey.tianjitown.paper.land.TerritoryService;
import org.allivlisey.tianjitown.storage.bonus.TownBonusRepository;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository;
import org.allivlisey.tianjitown.storage.database.DatabaseGate;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.allivlisey.tianjitown.storage.governance.GovernanceRepository;
import org.allivlisey.tianjitown.storage.governance.VoteSnapshot;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.safeMessage;
import static org.allivlisey.tianjitown.paper.runtime.RuntimeText.safeText;

public final class TownRuntime {

    private static final String QUICK_SHOP_TAX_REFRESH_FAILURE =
            "log.scheduler.quick-shop-tax-refresh-failure";


    private static final String SQLITE_RECOVERED = "log.lifecycle.sqlite-recovered";
    private static final String SQLITE_INTERRUPTED = "log.lifecycle.sqlite-interrupted";
    private static final String INTERRUPTED_PROVISION_REASON =
            "log.lifecycle.interrupted-provision-reason";
    private static final String INTERRUPTED_PROVISIONS_RECOVERED =
            "log.lifecycle.interrupted-provisions-recovered";
    private static final String INTERRUPTED_PROVISION_RECOVERY_FAILURE =
            "log.lifecycle.interrupted-provision-recovery-failure";

    private static final String PERIODIC_VOTE_SETTLEMENT_FAILURE =
            "log.scheduler.periodic.vote-settlement-failure";

    private final TownEconomyRuntime economy;
    private final TownLandRuntime land;
    private final TownTaxRuntime taxes;
    private final TownProvisionRuntime provisioning;
    private final TownProvisionRecovery provisionRecovery;
    private final TownExpansionRuntime expansions;
    private final TownRuntimeTasks tasks;
    private final TianjiTownPlugin plugin;
    private final DatabaseGate database;
    private final StationDirectory stations;
    private final TownRepository repository;
    private final GovernanceRepository governance;
    private final EconomyRepository finance;
    private final LandProtectionService landProtection;
    private final SitePolicy sitePolicy;
    private final TerritoryPreviewService territoryPreviews;
    private final EconomySettings economySettings;
    private final VaultSettlementService settlement;
    private final BuffRuntime buffs;
    private final TownBonusRuntime bonuses;
    private final AtomicBoolean databaseAvailable = new AtomicBoolean(true);
    private final Set<String> activeResidenceNames;

    public TownRuntime(TianjiTownPlugin plugin, DatabaseGate database,
                    LandProtectionService landProtection,
                    WorldBoundaryService worldBoundaries,
                    Set<String> activeResidenceNames) {
        this.plugin = plugin;
        this.database = database;
        this.landProtection = landProtection;
        this.activeResidenceNames = java.util.Objects.requireNonNull(activeResidenceNames,
                "activeResidenceNames");
        this.sitePolicy = new SitePolicy(plugin, landProtection, worldBoundaries);
        this.territoryPreviews = new TerritoryPreviewService(plugin);
        this.stations = new StationDirectory(
                new StationRepository(database.dataSource(),
                        plugin.getServer()::isPrimaryThread));
        this.repository = new TownRepository(database.dataSource(),
                plugin.getServer()::isPrimaryThread);
        this.governance = new GovernanceRepository(database.dataSource(),
                plugin.getServer()::isPrimaryThread);
        this.finance = new EconomyRepository(database.dataSource(),
                plugin.getServer()::isPrimaryThread);
        this.economySettings = EconomySettings.load(plugin.getConfig(),
                plugin.messages()::plainText);
        this.settlement = new VaultSettlementService(plugin.getServer(),
                economySettings.settlementAccount(), economySettings.fallbackScale(),
                plugin.messages()::plainText);
        applicationFeeMinor();
        TerritoryService territories = new TerritoryService(finance, sitePolicy, plugin.messages(),
                economySettings, settlement.scale());
        this.tasks = new TownRuntimeTasks(plugin, databaseAvailable);
        this.taxes = new TownTaxRuntime(plugin, finance, economySettings, settlement,
                databaseAvailable);
        this.provisioning = new TownProvisionRuntime(plugin, repository, landProtection,
                sitePolicy, settlement, databaseAvailable, activeResidenceNames, tasks,
                taxes::refreshTaxPolicies, this::applicationFeeMinor);
        this.provisionRecovery = new TownProvisionRecovery(plugin, repository,
                landProtection, settlement);
        this.expansions = new TownExpansionRuntime(plugin, repository, finance,
                landProtection, territories, tasks, this::consumptionEnabled);
        this.buffs = new BuffRuntime(plugin, this,
                new CommerceRepository(database.dataSource(),
                        plugin.getServer()::isPrimaryThread),
                BuffSettings.load(plugin.getConfig(), plugin.messages()));
        this.bonuses = new TownBonusRuntime(plugin, this,
                new TownBonusRepository(database.dataSource(),
                        plugin.getServer()::isPrimaryThread),
                new TownDiagnosticRepository(database.dataSource(),
                        plugin.getServer()::isPrimaryThread),
                TownBonusSettings.load(plugin.getConfig(), plugin.messages()::plainText),
                java.util.Objects.requireNonNull(
                plugin.getServer().getPluginManager().getPlugin("QuickShop-Hikari"),
                "QuickShop-Hikari"));
        this.economy = new TownEconomyRuntime(plugin, finance, economySettings, settlement,
                databaseAvailable, tasks, taxes, this::consumptionEnabled);
        this.land = new TownLandRuntime(plugin, repository, finance, landProtection,
                databaseAvailable, tasks, taxes::refreshTaxPolicies);
    }

    public StationDirectory stations() {
        return stations;
    }

    public TownRepository repository() {
        return repository;
    }

    public GovernanceRepository governance() {
        return governance;
    }

    public EconomyRepository finance() {
        return finance;
    }

    public EconomySettings economySettings() {
        return economySettings;
    }

    public VaultSettlementService settlement() {
        return settlement;
    }

    /**
     * Returns the configured application fee in the settlement provider's minor units. The approval
     * flow and the submission confirmation use this same conversion.
     */
    public long applicationFeeMinor() {
        return ApplicationSettings.feeMinor(plugin.getConfig(), settlement.scale(),
                plugin.messages()::plainText);
    }

    public BuffRuntime buffs() {
        return buffs;
    }

    public TownBonusRuntime bonuses() {
        return bonuses;
    }

    public DatabaseGate database() {
        return database;
    }

    public QuickShopTaxAdapter.TaxPolicy taxPolicy(UUID receiverId) {
        return taxes.taxPolicy(receiverId);
    }

    public boolean taxEnabled() {
        return taxes.taxEnabled();
    }

    public boolean quickShopTaxEnabled() {
        return taxes.quickShopTaxEnabled();
    }

    public void setQuickShopTaxAvailable(boolean available) {
        taxes.setQuickShopTaxAvailable(available);
    }

    public boolean consumptionEnabled() {
        return plugin.getConfig().getBoolean("economy.consumption.enabled",
                economySettings.consumptionEnabled());
    }

    public LandProtectionService landProtection() {
        return landProtection;
    }

    public TerritoryPreviewService territoryPreviews() {
        return territoryPreviews;
    }

    public SitePolicy sitePolicy() {
        return sitePolicy;
    }

    public boolean databaseAvailable() {
        return databaseAvailable.get();
    }

    /**
     * Installs the UI callback used after a successful mayor tax-rate change. Keeping this
     * callback at the composition boundary prevents the runtime package from depending on UI.
     */
    public void setTaxChangeNotifier(Consumer<Player> notifier) {
        economy.setTaxChangeNotifier(notifier);

    }

    public void checkRecovery() {
        plugin.runAsync(() -> {
            boolean healthy = database.ping();
            if (healthy) {
                try {
                    refreshTaxPolicies();
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning(plugin.messages().plainText(
                            QUICK_SHOP_TAX_REFRESH_FAILURE,
                            Map.of("detail", safeText(safeMessage(exception)))));
                }
            }
            boolean previous = databaseAvailable.getAndSet(healthy);
            if (healthy && !previous) {
                plugin.getLogger().info(plugin.messages().plainText(SQLITE_RECOVERED));
                flushPendingTaxes();
            } else if (!healthy && previous) {
                plugin.getLogger().severe(plugin.messages().plainText(SQLITE_INTERRUPTED));
            }
        });
    }

    public void recoverStartupState() {
        plugin.runAsync(() -> {
            try {
                finance.initializeAccounts();
                refreshTaxPolicies();
                int recovered = repository.recoverInterruptedProvisions(
                        plugin.messages().plainText(INTERRUPTED_PROVISION_REASON));
                databaseAvailable.set(true);
                if (recovered > 0) {
                    plugin.getLogger().warning(plugin.messages().plainText(
                            INTERRUPTED_PROVISIONS_RECOVERED,
                            Map.of("count", recovered)));
                }
                List<EconomyRepository.ExpansionOperation> expansions =
                        finance.pendingExpansions();
                if (!expansions.isEmpty()) {
                    plugin.runMain(
                            () -> this.expansions.recoverExpansions(expansions));
                }
                List<EconomyRepository.ExpansionBatchOperation> batches =
                        finance.pendingExpansionBatches();
                if (!batches.isEmpty()) {
                    plugin.runMain(() -> this.expansions.recoverExpansionBatches(batches));
                }
            } catch (RuntimeException exception) {
                if (exception instanceof TownRepository.StorageUnavailableException) {
                    databaseAvailable.set(false);
                }
                plugin.getLogger().severe(plugin.messages().plainText(
                        INTERRUPTED_PROVISION_RECOVERY_FAILURE,
                        Map.of("detail", safeText(safeMessage(exception)))));
            }
        });
    }

    public void reconcileAll() {
        land.reconcileAll();
    }

    public void settleDueVotes() {
        plugin.runAsync(() -> {
            try {
                List<VoteSnapshot> settled = governance.settleDueVotes();
                List<TownLandState> changedMemberships = settled.stream()
                        .filter(vote -> vote.passed()
                                && vote.type() == org.allivlisey.tianjitown.core.governance.VoteType.KICK_MEMBER)
                        .map(VoteSnapshot::townId).distinct()
                        .map(townId -> repository.findTown(townId)
                                .map(town -> new TownLandState(town,
                                        repository.listLandAccessIds(townId),
                                        finance.territoryUnits(townId)))
                                .orElse(null))
                        .filter(java.util.Objects::nonNull)
                        .toList();
                databaseAvailable.set(true);
                if (!changedMemberships.isEmpty()) {
                    plugin.runMain(() -> {
                        for (TownLandState state : changedMemberships) {
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
                plugin.getLogger().severe(plugin.messages().plainText(
                        PERIODIC_VOTE_SETTLEMENT_FAILURE, Map.of(
                                "detail", safeText(safeMessage(exception)))));
            }
        });
    }

    public <T> void read(CommandSender sender, Supplier<T> operation, Consumer<T> success) {
        tasks.read(sender, operation, success);
    }

    public <T> void write(CommandSender sender, Supplier<T> operation, Consumer<T> success) {
        tasks.write(sender, operation, success);
    }

    public void provision(CommandSender sender, UUID applicationId, UUID reviewerId,
                   String reviewerName, String reason, String idempotencyKey,
        Consumer<ProvisionResult> completion) {
        provisioning.provision(sender, applicationId, reviewerId, reviewerName, reason, idempotencyKey,
                completion);
    }

    public void recoverFailedApplication(Player administrator, UUID applicationId,
                                  TownRepository.RecoveryMode mode,
                                  Consumer<ProvisionResult> completion) {
        provisionRecovery.recoverFailedApplication(administrator, applicationId, mode, completion);
    }

    public void deactivateResidence(String residenceName) {
        activeResidenceNames.remove(residenceName.toLowerCase(java.util.Locale.ROOT));
    }

    public void setTownTeleportPoint(Player actor, TownSnapshot town, Location location,
                              Consumer<LandProtectionService.Result> completion) {
        land.setTownTeleportPoint(actor, town, location, completion);
    }

    public void reconcile(CommandSender sender, TownSnapshot town, List<UUID> members, boolean repair) {
        land.reconcile(sender, town, members, repair);
    }

    public void reconcileAction(CommandSender sender, TownSnapshot town, List<UUID> members,
                         boolean repair, Consumer<LandProtectionService.Result> success,
                         Consumer<RuntimeException> failure) {
        land.reconcileAction(sender, town, members, repair, success, failure);
    }

    public void acceptQuickShopTax(QuickShopTaxAdapter.SuccessfulTax tax) {
        taxes.acceptQuickShopTax(tax);
    }

    public void flushPendingTaxes() {
        taxes.flushPendingTaxes();
    }

    public EconomyRepository.SubsidyQuota taxSubsidyQuota(UUID townId) {
        return taxes.taxSubsidyQuota(townId);
    }

    public JobsIncomeTaxAdapter.TaxResult acceptJobsIncomeTax(
            JobsIncomeTaxAdapter.Earning earning) {
        return taxes.acceptJobsIncomeTax(earning);
    }

    public void acceptGlobalMarketPlusIncomeTax(GlobalMarketPlusIncomeTaxAdapter.Earning earning) {
        taxes.acceptGlobalMarketPlusIncomeTax(earning);
    }

    public void reconcileSettlement() {
        economy.reconcileSettlement();
    }

    public void donateAction(Player player, long amountMinor,
                      Consumer<EconomyRepository.LedgerMutation> success,
                      Consumer<RuntimeException> failure) {
        economy.donateAction(player, amountMinor, success, failure);
    }

    public void adjustFunds(CommandSender sender, UUID townId, long amountMinor, String reason) {
        economy.adjustFunds(sender, townId, amountMinor, reason);
    }

    public void changeTaxRate(Player mayor, UUID townId, int basisPoints) {
        economy.changeTaxRate(mayor, townId, basisPoints);
    }

    public void forceTaxRate(CommandSender sender, UUID townId, int basisPoints, String reason) {
        economy.forceTaxRate(sender, townId, basisPoints, reason);
    }

    public TerritoryService.ExpansionPreview expansionPreview(UUID playerId,
                                                        ExpansionDirection direction) {
        return expansions.expansionPreview(playerId, direction);
    }

    public TerritoryService.ExpansionPreview expansionPreview(UUID playerId, int gridX, int gridZ) {
        return expansions.expansionPreview(playerId, gridX, gridZ);
    }

    public TerritoryService.ExpansionBatchPreview expansionBatchPreview(UUID playerId,
                                                                  Set<TerritoryService.GridSelection> selections) {
        return expansions.expansionBatchPreview(playerId, selections);
    }

    public void loadTerritoryMap(Player player, Consumer<TerritoryService.TerritoryMap> success) {
        expansions.loadTerritoryMap(player, success);
    }

    public SitePolicy.Validation validateExpansionPreview(
            TerritoryService.ExpansionPreview preview) {
        return expansions.validateExpansionPreview(preview);
    }

    public void expandAction(Player mayor, ExpansionDirection direction,
                      Consumer<EconomyRepository.ExpansionOperation> success,
                      Consumer<RuntimeException> failure) {
        expansions.expandAction(mayor, direction, success, failure);
    }

    public void expandAction(Player mayor, int gridX, int gridZ,
                      Consumer<EconomyRepository.ExpansionOperation> success,
                      Consumer<RuntimeException> failure) {
        expansions.expandAction(mayor, gridX, gridZ, success, failure);
    }

    public void expandBatchAction(Player mayor, Set<TerritoryService.GridSelection> selections,
                           Consumer<EconomyRepository.ExpansionBatchOperation> success,
                           Consumer<RuntimeException> failure) {
        expansions.expandBatchAction(mayor, selections, success, failure);
    }

    public void expandBatchAction(Player mayor, Set<TerritoryService.GridSelection> selections,
                                 String requestId, long expectedPriceMinor,
                                 Consumer<EconomyRepository.ExpansionBatchOperation> success,
                                 Consumer<RuntimeException> failure) {
        expansions.expandBatchAction(mayor, selections, requestId, expectedPriceMinor, success, failure);
    }

    public void expandBatchAction(Player mayor, Set<TerritoryService.GridSelection> selections,
                                 String requestId,
                           Consumer<EconomyRepository.ExpansionBatchOperation> success,
                           Consumer<RuntimeException> failure) {
        expansions.expandBatchAction(mayor, selections, requestId, success, failure);
    }

    public <T> void writeAction(CommandSender sender, Supplier<T> operation, Consumer<T> success,
                         Consumer<RuntimeException> failure) {
        tasks.writeAction(sender, operation, success, failure);
    }

    public <T> void readAction(CommandSender sender, Supplier<T> operation, Consumer<T> success,
                        Consumer<RuntimeException> failure) {
        tasks.readAction(sender, operation, success, failure);
    }

    public String money(long minorUnits) {
        return settlement.formatMinor(minorUnits);
    }

    public static String percent(int basisPoints) {
        return RuntimeText.percent(basisPoints);
    }

    public void refreshTaxPolicies() {
        taxes.refreshTaxPolicies();
    }


}

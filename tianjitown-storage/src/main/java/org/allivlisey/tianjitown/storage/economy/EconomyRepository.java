package org.allivlisey.tianjitown.storage.economy;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.allivlisey.tianjitown.core.land.TerritoryUnit;

public final class EconomyRepository {
    private final EconomyDatabase database;
    private final TerritoryExpansionStore territoryExpansionStore;
    private final EconomyOperationStore economyOperationStore;
    private final EconomyTaxStore economyTaxStore;
    private final EconomyLedgerStore economyLedgerStore;

    public EconomyRepository(DataSource dataSource, BooleanSupplier forbiddenThread) {
        this.database = new EconomyDatabase(dataSource, forbiddenThread);
        this.territoryExpansionStore = new TerritoryExpansionStore(database);
        this.economyOperationStore = new EconomyOperationStore(database);
        this.economyTaxStore = new EconomyTaxStore(database);
        this.economyLedgerStore = new EconomyLedgerStore(database);
    }

    public void initializeAccounts() {
        database.requireWorkerThread();
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO town_accounts (town_id)
                    SELECT town_id FROM towns WHERE status = 'ACTIVE'
                    ON CONFLICT (town_id) DO NOTHING
                    """)) {
                statement.executeUpdate();
            }
            return null;
        });
    }

    public List<MemberTaxPolicy> loadMemberTaxPolicies() {
        return economyTaxStore.loadMemberTaxPolicies();
    }

    public Optional<TownFinance> findFinanceByPlayer(UUID playerId) {
        database.requireWorkerThread();
        Objects.requireNonNull(playerId, "playerId");
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT t.town_id, t.name, m.role, t.tax_rate_bps, t.tax_revision,
                           m.accepted_tax_revision, a.balance_minor, a.locked, a.lock_reason,
                           (SELECT COUNT(*) FROM territory_units u
                             WHERE u.town_id = t.town_id AND u.projection_status IN ('ACTIVE', 'PENDING'))
                               AS unit_count
                      FROM town_members m
                      JOIN towns t ON t.town_id = m.town_id
                      JOIN town_accounts a ON a.town_id = t.town_id
                     WHERE m.player_uuid = ? AND t.status = 'ACTIVE'
                    """)) {
                statement.setBytes(1, EconomyPersistence.uuid(playerId));
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(readFinance(rows)) : Optional.empty();
                }
            }
        });
    }

    public Optional<TownFinance> findFinanceByTown(UUID townId) {
        database.requireWorkerThread();
        Objects.requireNonNull(townId, "townId");
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT t.town_id, t.name, 'ADMIN' AS role, t.tax_rate_bps, t.tax_revision,
                           t.tax_revision AS accepted_tax_revision, a.balance_minor, a.locked,
                           a.lock_reason,
                           (SELECT COUNT(*) FROM territory_units u
                             WHERE u.town_id = t.town_id AND u.projection_status IN ('ACTIVE', 'PENDING'))
                               AS unit_count
                      FROM towns t JOIN town_accounts a ON a.town_id = t.town_id
                     WHERE t.town_id = ?
                    """)) {
                statement.setBytes(1, EconomyPersistence.uuid(townId));
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(readFinance(rows)) : Optional.empty();
                }
            }
        });
    }

    public TaxChange changeTaxRate(UUID townId, UUID mayorId, int basisPoints,
                                   String actorName, String reason) {
        return economyTaxStore.changeTaxRate(townId, mayorId, basisPoints, actorName, reason);
    }

    public TaxChange forceTaxRate(UUID townId, UUID actorId, int basisPoints,
                                  String actorName, String reason) {
        return economyTaxStore.forceTaxRate(townId, actorId, basisPoints, actorName, reason);
    }

    public void acknowledgeTaxRevision(UUID playerId, int revision) {
        economyTaxStore.acknowledgeTaxRevision(playerId, revision);
    }

    public SubsidyReservation reserveQuickShopSubsidy(UUID townId, String businessKey,
                                                       long requestedMinor,
                                                       long weeklyLimitMinor,
                                                       long twelveHourLimitMinor,
                                                       Instant now, ZoneId zoneId) {
        return economyTaxStore.reserveQuickShopSubsidy(townId, businessKey, requestedMinor, weeklyLimitMinor,
                twelveHourLimitMinor, now, zoneId);
    }

    public void cancelQuickShopSubsidy(String businessKey, String error) {
        economyTaxStore.cancelQuickShopSubsidy(businessKey, error);
    }

    public SubsidyQuota quickShopSubsidyQuota(UUID townId, long weeklyLimitMinor,
                                               long twelveHourLimitMinor, Instant now,
                                               ZoneId zoneId) {
        return economyTaxStore.quickShopSubsidyQuota(townId, weeklyLimitMinor, twelveHourLimitMinor, now, zoneId);
    }

    public LedgerMutation recordQuickShopTax(QuickShopTax tax) {
        return economyTaxStore.recordQuickShopTax(tax);
    }

    public LedgerMutation recordExternalIncomeTax(ExternalIncomeTax tax) {
        return economyTaxStore.recordExternalIncomeTax(tax);
    }

    public EconomyOperation prepareOperation(UUID townId, String operationType, long amountMinor,
                                             UUID actorId, String actorName, String businessKey,
                                             String note) {
        return economyOperationStore.prepareOperation(townId, operationType, amountMinor, actorId, actorName,
                businessKey, note);
    }

    public EconomyOperation markOperationExternalApplied(UUID operationId) {
        return economyOperationStore.markOperationExternalApplied(operationId);
    }

    public LedgerMutation completeOperation(UUID operationId) {
        return economyOperationStore.completeOperation(operationId);
    }

    public EconomyOperation requireCompensation(UUID operationId, String error) {
        return economyOperationStore.requireCompensation(operationId, error);
    }

    public EconomyOperation cancelOperation(UUID operationId, String error) {
        return economyOperationStore.cancelOperation(operationId, error);
    }

    public EconomyOperation resolveCompensation(UUID operationId, String detail) {
        return economyOperationStore.resolveCompensation(operationId, detail);
    }

    public List<EconomyOperation> pendingOperations() {
        return economyOperationStore.pendingOperations();
    }

    public Reconciliation reconcileSettlement(long externalBalanceMinor) {
        return economyOperationStore.reconcileSettlement(externalBalanceMinor);
    }

    public Reconciliation inspectSettlement(long externalBalanceMinor) {
        return economyOperationStore.inspectSettlement(externalBalanceMinor);
    }

    public List<LedgerEntry> ledger(UUID townId, int page, int pageSize) {
        return economyLedgerStore.ledger(townId, page, pageSize);
    }

    public List<LedgerEntry> displayLedger(UUID townId, int page, int pageSize) {
        return economyLedgerStore.displayLedger(townId, page, pageSize);
    }

    public List<UUID> unresolvedLedgerActorIds() {
        return economyLedgerStore.unresolvedLedgerActorIds();
    }

    public int backfillLedgerActorName(UUID actorId, String confirmedName) {
        return economyLedgerStore.backfillLedgerActorName(actorId, confirmedName);
    }

    public List<TerritoryUnitSnapshot> territoryUnits(UUID townId) {
        return territoryExpansionStore.territoryUnits(townId);
    }

    public List<OccupiedTerritoryChunk> occupiedChunksOutsideTown(
            UUID townId, UUID worldId, int minimumX, int maximumX,
            int minimumZ, int maximumZ) {
        return territoryExpansionStore.occupiedChunksOutsideTown(townId, worldId, minimumX, maximumX,
                minimumZ, maximumZ);
    }

    public ExpansionOperation prepareExpansion(ExpansionRequest request) {
        return territoryExpansionStore.prepareExpansion(request);
    }

    /**
     * 在同一个 SQLite 事务中预留一批领地、写入所有单元并只扣除一次总价。
     * Residence 投影在事务提交后由 Paper 主线程执行，失败时调用 refundExpansionBatch
     * 整体回滚，不能通过重复调用单格接口模拟批量操作。
     */
    public ExpansionBatchOperation prepareExpansionBatch(ExpansionBatchRequest request) {
        return territoryExpansionStore.prepareExpansionBatch(request);
    }

    public ExpansionBatchOperation completeExpansionBatch(UUID batchId) {
        return territoryExpansionStore.completeExpansionBatch(batchId);
    }

    public void refundExpansionBatch(UUID batchId, String error) {
        territoryExpansionStore.refundExpansionBatch(batchId, error);
    }

    public List<ExpansionBatchOperation> pendingExpansionBatches() {
        return territoryExpansionStore.pendingExpansionBatches();
    }

    public ExpansionOperation completeExpansion(UUID expansionId) {
        return territoryExpansionStore.completeExpansion(expansionId);
    }

    public void refundExpansion(UUID expansionId, String error) {
        territoryExpansionStore.refundExpansion(expansionId, error);
    }

    public List<ExpansionOperation> pendingExpansions() {
        return territoryExpansionStore.pendingExpansions();
    }

    private static TownFinance readFinance(ResultSet row) throws SQLException {
        return new TownFinance(EconomyPersistence.readUuid(row, "town_id"), row.getString("name"),
                row.getString("role"), row.getLong("balance_minor"),
                row.getInt("tax_rate_bps"), row.getInt("tax_revision"),
                row.getInt("accepted_tax_revision"), row.getBoolean("locked"),
                row.getString("lock_reason"), row.getInt("unit_count"));
    }

    public record MemberTaxPolicy(UUID townId, UUID playerId, int taxRateBps) {
    }

    public record TownFinance(UUID townId, String townName, String role, long balanceMinor,
                              int taxRateBps, int taxRevision, int acceptedTaxRevision,
                              boolean locked, String lockReason, int unitCount) {
        public boolean hasUnreadTaxChange() {
            return acceptedTaxRevision < taxRevision;
        }
    }

    public record TaxChange(UUID townId, int basisPoints, int revision) {
    }

    public record QuickShopTax(UUID townId, String businessKey, long shopId, String shopType,
                               UUID receiverId, String receiverName, UUID interactingId, long grossMinor,
                               int taxRateBps, long taxMinor, String worldName) {
        public QuickShopTax {
            Objects.requireNonNull(townId, "townId");
            Objects.requireNonNull(businessKey, "businessKey");
            Objects.requireNonNull(receiverId, "receiverId");
            receiverName = receiverName == null || receiverName.isBlank()
                    ? receiverId.toString() : receiverName;
            Objects.requireNonNull(interactingId, "interactingId");
            Objects.requireNonNull(worldName, "worldName");
            if (!shopType.equals("SELLING") && !shopType.equals("BUYING")) {
                throw new IllegalArgumentException("商店类型必须为 SELLING 或 BUYING");
            }
        }

        public QuickShopTax(UUID townId, String businessKey, long shopId, String shopType,
                            UUID receiverId, UUID interactingId, long grossMinor,
                            int taxRateBps, long taxMinor, String worldName) {
            this(townId, businessKey, shopId, shopType, receiverId,
                    receiverId.toString(), interactingId, grossMinor, taxRateBps, taxMinor,
                    worldName);
        }
    }

    public record SubsidyReservation(UUID reservationId, UUID townId, String businessKey,
                                     long requestedMinor, long grantedMinor,
                                     Instant twelveHourStart, Instant weekStart,
                                     String status) {
    }

    public record SubsidyQuota(long weeklyRemainingMinor, long twelveHourRemainingMinor,
                               Instant weeklyRefreshAt, Instant twelveHourRefreshAt) {
    }

    public record ExternalIncomeTax(UUID townId, String businessKey, String source,
                                    UUID receiverId, String receiverName, long grossMinor,
                                    int taxRateBps, long taxMinor) {
    }

    public record LedgerMutation(UUID entryId, UUID townId, long amountMinor,
                                 long balanceAfterMinor, String businessKey) {
    }

    public record LedgerEntry(UUID entryId, UUID townId, String entryType, long amountMinor,
                              long balanceAfterMinor, UUID actorId, String actorName,
                              String businessKey, String note, Instant createdAt) {
    }

    public record EconomyOperation(UUID operationId, UUID townId, String operationType,
                                   String businessKey, long amountMinor, UUID actorId,
                                   String actorName, String note, String status,
                                   String lastError, Instant createdAt) {
    }

    public record Reconciliation(long externalBalanceMinor, long internalBalanceMinor,
                                 long pendingMinor, long requiredMinor, boolean healthy) {
        public long surplusMinor() {
            return externalBalanceMinor - requiredMinor;
        }
    }

    public record TerritoryUnitSnapshot(UUID unitId, UUID townId, TerritoryUnit unit,
                                        String residenceName, String residenceAreaName,
                                        String projectionStatus, String projectionError) {
    }

    public record OccupiedTerritoryChunk(UUID townId, String townName, int chunkX, int chunkZ) {
    }

    public record ExpansionRequest(UUID townId, TerritoryUnit unit, String residenceName,
                                   String residenceAreaName, long priceMinor, UUID actorId,
                                   String actorName, String businessKey) {
    }

    public record ExpansionBatchItem(TerritoryUnit unit, String residenceName,
                                     String residenceAreaName, long priceMinor) {
    }

    public record ExpansionBatchRequest(UUID townId, List<ExpansionBatchItem> items,
                                        long totalPriceMinor, UUID actorId, String actorName,
                                        String businessKey) {
        public ExpansionBatchRequest {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record ExpansionBatchOperation(UUID batchId, UUID townId, String businessKey,
                                          UUID actorId, String actorName, long totalPriceMinor,
                                          String status, String lastError,
                                          List<ExpansionOperation> expansions) {
        public ExpansionBatchOperation {
            expansions = expansions == null ? List.of() : List.copyOf(expansions);
        }
    }

    public record ExpansionOperation(UUID expansionId, UUID townId, UUID unitId,
                                     String businessKey, UUID actorId, long priceMinor,
                                     String status, String lastError, TerritoryUnit unit,
                                     String residenceName, String residenceAreaName) {
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }

        public ConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class StorageUnavailableException extends RuntimeException {
        public StorageUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}

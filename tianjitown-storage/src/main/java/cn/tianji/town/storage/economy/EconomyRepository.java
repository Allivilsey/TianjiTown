package cn.tianji.town.storage.economy;

import cn.tianji.town.core.land.ChunkPosition;
import cn.tianji.town.core.land.InitialTerritory;
import cn.tianji.town.core.land.TerritoryUnit;
import cn.tianji.town.core.land.TerritoryRules;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class EconomyRepository {
    private static final String RECONCILIATION_LOCK = "SETTLEMENT_RECONCILIATION:";
    private static final String COMPENSATION_LOCK = "ECONOMY_COMPENSATION:";
    private final DataSource dataSource;
    private final BooleanSupplier forbiddenThread;

    public EconomyRepository(DataSource dataSource, BooleanSupplier forbiddenThread) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.forbiddenThread = Objects.requireNonNull(forbiddenThread, "forbiddenThread");
    }

    public void initializeAccounts() {
        requireWorkerThread();
        transaction(connection -> {
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
        requireWorkerThread();
        return query(connection -> {
            List<MemberTaxPolicy> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT t.town_id, m.player_uuid, t.tax_rate_bps
                      FROM towns t JOIN town_members m ON m.town_id = t.town_id
                     WHERE t.status = 'ACTIVE'
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new MemberTaxPolicy(readUuid(rows, "town_id"),
                            readUuid(rows, "player_uuid"), rows.getInt("tax_rate_bps")));
                }
            }
            return List.copyOf(result);
        });
    }

    public Optional<TownFinance> findFinanceByPlayer(UUID playerId) {
        requireWorkerThread();
        Objects.requireNonNull(playerId, "playerId");
        return query(connection -> {
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
                statement.setBytes(1, uuid(playerId));
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(readFinance(rows)) : Optional.empty();
                }
            }
        });
    }

    public Optional<TownFinance> findFinanceByTown(UUID townId) {
        requireWorkerThread();
        Objects.requireNonNull(townId, "townId");
        return query(connection -> {
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
                statement.setBytes(1, uuid(townId));
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(readFinance(rows)) : Optional.empty();
                }
            }
        });
    }

    public TaxChange changeTaxRate(UUID townId, UUID mayorId, int basisPoints,
                                   String actorName, String reason) {
        requireWorkerThread();
        requireTaxRate(basisPoints);
        requireReason(reason);
        return transaction(connection -> {
            TownTax current = requireTownTax(connection, townId);
            requireMayor(connection, townId, mayorId);
            if (current.basisPoints() == basisPoints) {
                return new TaxChange(townId, basisPoints, current.revision());
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE towns
                       SET tax_rate_bps = ?, tax_revision = tax_revision + 1,
                           version = version + 1
                     WHERE town_id = ? AND status = 'ACTIVE'
                    """)) {
                statement.setInt(1, basisPoints);
                statement.setBytes(2, uuid(townId));
                requireUpdated(statement, "小镇不存在或已归档");
            }
            audit(connection, mayorId, actorName, "TAX_RATE_CHANGE", townId,
                    reason, current.basisPoints() + " -> " + basisPoints);
            TownTax changed = requireTownTax(connection, townId);
            return new TaxChange(townId, changed.basisPoints(), changed.revision());
        });
    }

    public TaxChange forceTaxRate(UUID townId, UUID actorId, int basisPoints,
                                  String actorName, String reason) {
        requireWorkerThread();
        requireTaxRate(basisPoints);
        requireReason(reason);
        return transaction(connection -> {
            TownTax current = requireTownTax(connection, townId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE towns
                       SET tax_rate_bps = ?, tax_revision = tax_revision + 1,
                           version = version + 1
                     WHERE town_id = ?
                    """)) {
                statement.setInt(1, basisPoints);
                statement.setBytes(2, uuid(townId));
                requireUpdated(statement, "小镇不存在");
            }
            audit(connection, actorId, actorName, "TAX_RATE_FORCE", townId,
                    reason, current.basisPoints() + " -> " + basisPoints);
            TownTax changed = requireTownTax(connection, townId);
            return new TaxChange(townId, changed.basisPoints(), changed.revision());
        });
    }

    public void acknowledgeTaxRevision(UUID playerId, int revision) {
        requireWorkerThread();
        transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE town_members
                       SET accepted_tax_revision = ?
                     WHERE player_uuid = ?
                       AND ? <= (SELECT tax_revision FROM towns WHERE town_id = town_members.town_id)
                    """)) {
                statement.setInt(1, revision);
                statement.setBytes(2, uuid(playerId));
                statement.setInt(3, revision);
                requireUpdated(statement, "税率版本已变化，请重新打开");
            }
            return null;
        });
    }

    public LedgerMutation recordQuickShopTax(QuickShopTax tax) {
        requireWorkerThread();
        Objects.requireNonNull(tax, "tax");
        if (tax.taxMinor() <= 0 || tax.grossMinor() <= 0) {
            throw new IllegalArgumentException("交易额和税额必须大于 0");
        }
        requireTaxRate(tax.taxRateBps());
        return transaction(connection -> {
            String subsidyKey = tax.businessKey() + ":subsidy";
            Optional<LedgerMutation> existing = findLedgerByBusinessKey(connection, subsidyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
            UUID taxId = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO quickshop_tax_records
                        (tax_id, town_id, business_key, shop_id, shop_type, receiver_uuid,
                         interacting_uuid, gross_minor, tax_rate_bps, tax_minor, world_name)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setBytes(1, uuid(taxId));
                statement.setBytes(2, uuid(tax.townId()));
                statement.setString(3, tax.businessKey());
                statement.setLong(4, tax.shopId());
                statement.setString(5, tax.shopType());
                statement.setBytes(6, uuid(tax.receiverId()));
                statement.setBytes(7, uuid(tax.interactingId()));
                statement.setLong(8, tax.grossMinor());
                statement.setInt(9, tax.taxRateBps());
                statement.setLong(10, tax.taxMinor());
                statement.setString(11, tax.worldName());
                statement.executeUpdate();
            }
            postLedger(connection, tax.townId(), "QUICKSHOP_TAX", tax.taxMinor(),
                    tax.receiverId(), tax.receiverId().toString(), tax.businessKey(),
                    tax.shopType() + " 商店税，shop=" + tax.shopId(), false);
            return postLedger(connection, tax.townId(), "SERVER_TAX_SUBSIDY", tax.taxMinor(),
                    null, "SERVER", subsidyKey, "QuickShop 税收等额服务器补贴", false);
        });
    }

    public LedgerMutation recordExternalIncomeTax(ExternalIncomeTax tax) {
        requireWorkerThread();
        Objects.requireNonNull(tax, "tax");
        if (tax.grossMinor() <= 0 || tax.taxMinor() <= 0) {
            throw new IllegalArgumentException("外部收入与税额必须大于 0");
        }
        if (!Set.of("JOBS", "GLOBALMARKETPLUS").contains(tax.source())) {
            throw new IllegalArgumentException("不支持的外部收入来源: " + tax.source());
        }
        requireTaxRate(tax.taxRateBps());
        return transaction(connection -> {
            String subsidyKey = tax.businessKey() + ":subsidy";
            Optional<LedgerMutation> existing = findLedgerByBusinessKey(connection, subsidyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
            UUID taxId = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO external_income_tax_records
                        (tax_id, town_id, business_key, source, receiver_uuid,
                         gross_minor, tax_rate_bps, tax_minor)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setBytes(1, uuid(taxId));
                statement.setBytes(2, uuid(tax.townId()));
                statement.setString(3, tax.businessKey());
                statement.setString(4, tax.source());
                statement.setBytes(5, uuid(tax.receiverId()));
                statement.setLong(6, tax.grossMinor());
                statement.setInt(7, tax.taxRateBps());
                statement.setLong(8, tax.taxMinor());
                statement.executeUpdate();
            }
            postLedger(connection, tax.townId(), tax.source() + "_TAX",
                    tax.taxMinor(), tax.receiverId(), tax.receiverName(), tax.businessKey(),
                    tax.source() + " 收入税", false);
            return postLedger(connection, tax.townId(), "SERVER_TAX_SUBSIDY", tax.taxMinor(),
                    null, "SERVER", subsidyKey, tax.source() + " 税收等额服务器补贴", false);
        });
    }

    public EconomyOperation prepareOperation(UUID townId, String operationType, long amountMinor,
                                             UUID actorId, String actorName, String businessKey,
                                             String note) {
        requireWorkerThread();
        if (amountMinor == 0) {
            throw new IllegalArgumentException("操作金额不能为 0");
        }
        if (!operationType.equals("DONATION") && !operationType.equals("ADMIN_ADJUSTMENT")) {
            throw new IllegalArgumentException("不支持的经济操作类型");
        }
        return transaction(connection -> {
            Optional<EconomyOperation> existing = findOperation(connection, businessKey);
            if (existing.isPresent()) {
                return existing.get();
            }
            AccountState account = requireAccount(connection, townId);
            if (amountMinor < 0) {
                requireUnlocked(account);
                if (Math.addExact(account.balanceMinor(), amountMinor) < 0) {
                    throw new ConflictException("小镇余额不足");
                }
            }
            UUID operationId = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO economy_operations
                        (operation_id, town_id, operation_type, business_key, amount_minor,
                         actor_uuid, actor_name, note, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED')
                    """)) {
                statement.setBytes(1, uuid(operationId));
                statement.setBytes(2, uuid(townId));
                statement.setString(3, operationType);
                statement.setString(4, businessKey);
                statement.setLong(5, amountMinor);
                statement.setBytes(6, actorId == null ? null : uuid(actorId));
                statement.setString(7, actorName);
                statement.setString(8, note == null ? "" : note);
                statement.executeUpdate();
            }
            return requireOperation(connection, operationId);
        });
    }

    public EconomyOperation markOperationExternalApplied(UUID operationId) {
        requireWorkerThread();
        return updateOperation(operationId, "PREPARED", "EXTERNAL_APPLIED", null);
    }

    public LedgerMutation completeOperation(UUID operationId) {
        requireWorkerThread();
        return transaction(connection -> {
            EconomyOperation operation = requireOperation(connection, operationId);
            Optional<LedgerMutation> existing = findLedgerByBusinessKey(connection,
                    operation.businessKey());
            if (existing.isPresent()) {
                setOperationStatus(connection, operationId, "COMPLETED", null);
                return existing.get();
            }
            if (!operation.status().equals("EXTERNAL_APPLIED")) {
                throw new ConflictException("外部资金尚未完成，不能写入账本");
            }
            LedgerMutation mutation = postLedger(connection, operation.townId(),
                    operation.operationType(), operation.amountMinor(), operation.actorId(),
                    operation.actorName(), operation.businessKey(), operation.note(),
                    operation.operationType().equals("ADMIN_ADJUSTMENT"));
            setOperationStatus(connection, operationId, "COMPLETED", null);
            return mutation;
        });
    }

    public EconomyOperation requireCompensation(UUID operationId, String error) {
        requireWorkerThread();
        return transaction(connection -> {
            EconomyOperation current = requireOperation(connection, operationId);
            if (current.status().equals("COMPLETED")) {
                throw new ConflictException("已完成操作不能标记为待补偿");
            }
            setOperationStatus(connection, operationId, "COMPENSATION_REQUIRED", safe(error));
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE town_accounts
                       SET locked = 1, lock_reason = ?, version = version + 1
                     WHERE town_id = ?
                    """)) {
                statement.setString(1, COMPENSATION_LOCK + " " + operationId + " " + safe(error));
                statement.setBytes(2, uuid(current.townId()));
                requireUpdated(statement, "小镇账户不存在");
            }
            return requireOperation(connection, operationId);
        });
    }

    public EconomyOperation cancelOperation(UUID operationId, String error) {
        requireWorkerThread();
        return transaction(connection -> {
            EconomyOperation current = requireOperation(connection, operationId);
            if (current.status().equals("COMPLETED")) {
                throw new ConflictException("已完成操作不能取消");
            }
            setOperationStatus(connection, operationId, "CANCELLED", safe(error));
            return requireOperation(connection, operationId);
        });
    }

    public List<EconomyOperation> pendingOperations() {
        requireWorkerThread();
        return query(connection -> {
            List<EconomyOperation> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM economy_operations
                     WHERE status NOT IN ('COMPLETED', 'CANCELLED')
                     ORDER BY created_at, operation_id
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(readOperation(rows));
                }
            }
            return List.copyOf(result);
        });
    }

    public Reconciliation reconcileSettlement(long externalBalanceMinor) {
        requireWorkerThread();
        if (externalBalanceMinor < 0) {
            throw new IllegalArgumentException("结算账户余额不能小于 0");
        }
        return transaction(connection -> {
            long internal;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COALESCE(SUM(balance_minor), 0) AS total FROM town_accounts");
                 ResultSet row = statement.executeQuery()) {
                row.next();
                internal = row.getLong("total");
            }
            long pending;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COALESCE(SUM(amount_minor), 0) AS total
                      FROM economy_operations WHERE status = 'EXTERNAL_APPLIED'
                    """); ResultSet row = statement.executeQuery()) {
                row.next();
                pending = row.getLong("total");
            }
            long required = Math.max(0, Math.addExact(internal, pending));
            boolean healthy = externalBalanceMinor >= required;
            String reason = healthy ? null : RECONCILIATION_LOCK
                    + " 外部=" + externalBalanceMinor + ", 应有=" + required;
            try (PreparedStatement statement = connection.prepareStatement(healthy ? """
                    UPDATE town_accounts SET locked = 0, lock_reason = NULL, version = version + 1
                     WHERE locked = 1 AND lock_reason LIKE 'SETTLEMENT_RECONCILIATION:%'
                    """ : """
                    UPDATE town_accounts SET locked = 1, lock_reason = ?, version = version + 1
                    """)) {
                if (!healthy) {
                    statement.setString(1, reason);
                }
                statement.executeUpdate();
            }
            return new Reconciliation(externalBalanceMinor, internal, pending, required, healthy);
        });
    }

    public Reconciliation inspectSettlement(long externalBalanceMinor) {
        requireWorkerThread();
        if (externalBalanceMinor < 0) {
            throw new IllegalArgumentException("结算账户余额不能小于 0");
        }
        return query(connection -> settlementSnapshot(connection, externalBalanceMinor));
    }

    private static Reconciliation settlementSnapshot(Connection connection,
                                                      long externalBalanceMinor)
            throws SQLException {
        long internal;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(SUM(balance_minor), 0) AS total FROM town_accounts");
             ResultSet row = statement.executeQuery()) {
            row.next();
            internal = row.getLong("total");
        }
        long pending;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(amount_minor), 0) AS total
                  FROM economy_operations WHERE status = 'EXTERNAL_APPLIED'
                """); ResultSet row = statement.executeQuery()) {
            row.next();
            pending = row.getLong("total");
        }
        long required = Math.max(0, Math.addExact(internal, pending));
        return new Reconciliation(externalBalanceMinor, internal, pending, required,
                externalBalanceMinor >= required);
    }

    public List<LedgerEntry> ledger(UUID townId, int page, int pageSize) {
        requireWorkerThread();
        if (page < 0 || pageSize < 1 || pageSize > 45) {
            throw new IllegalArgumentException("账本分页参数无效");
        }
        return query(connection -> {
            List<LedgerEntry> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM ledger_entries
                     WHERE town_id = ?
                     ORDER BY created_at DESC, entry_id LIMIT ? OFFSET ?
                    """)) {
                statement.setBytes(1, uuid(townId));
                statement.setInt(2, pageSize);
                statement.setInt(3, Math.multiplyExact(page, pageSize));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(readLedger(rows));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    public List<TerritoryUnitSnapshot> territoryUnits(UUID townId) {
        requireWorkerThread();
        return query(connection -> listTerritoryUnits(connection, townId));
    }

    public ExpansionOperation prepareExpansion(ExpansionRequest request) {
        requireWorkerThread();
        Objects.requireNonNull(request, "request");
        if (request.priceMinor() <= 0) {
            throw new IllegalArgumentException("扩张价格必须大于 0");
        }
        return transaction(connection -> {
            Optional<ExpansionOperation> existing = findExpansion(connection, request.businessKey());
            if (existing.isPresent()) {
                return existing.get();
            }
            AccountState account = requireAccount(connection, request.townId());
            requireUnlocked(account);
            if (account.balanceMinor() < request.priceMinor()) {
                throw new ConflictException("小镇余额不足");
            }
            List<TerritoryUnitSnapshot> snapshots = listTerritoryUnits(connection, request.townId());
            List<TerritoryUnit> units = snapshots.stream().map(TerritoryUnitSnapshot::unit).toList();
            TerritoryUnit origin = units.stream()
                    .filter(unit -> unit.gridX() == 0 && unit.gridZ() == 0)
                    .findFirst().orElseThrow(() -> new ConflictException("初始领地单元缺失"));
            ChunkPosition expectedCenter = new ChunkPosition(
                    origin.territory().center().worldId(), origin.territory().center().worldName(),
                    Math.addExact(origin.territory().center().x(),
                            Math.multiplyExact(request.unit().gridX(), 3)),
                    Math.addExact(origin.territory().center().z(),
                            Math.multiplyExact(request.unit().gridZ(), 3)));
            if (!request.unit().territory().center().equals(expectedCenter)) {
                throw new ConflictException("目标领地不在固定 3×3 单元网格上");
            }
            List<TerritoryUnit> withCandidate = new ArrayList<>(units);
            withCandidate.add(request.unit());
            TerritoryRules.requireConnected(withCandidate);
            if (Math.abs((long) request.unit().gridX()) > TerritoryRules.GRID_RADIUS
                    || Math.abs((long) request.unit().gridZ()) > TerritoryRules.GRID_RADIUS) {
                throw new ConflictException("目标超出 5×5 扩张网格");
            }
            UUID unitId = UUID.randomUUID();
            UUID expansionId = UUID.randomUUID();
            InitialTerritory territory = request.unit().territory();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO territory_units
                        (unit_id, town_id, world_uuid, world_name, grid_x, grid_z,
                         center_chunk_x, center_chunk_z, residence_name, residence_area_name)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setBytes(1, uuid(unitId));
                statement.setBytes(2, uuid(request.townId()));
                statement.setBytes(3, uuid(territory.center().worldId()));
                statement.setString(4, territory.center().worldName());
                statement.setInt(5, request.unit().gridX());
                statement.setInt(6, request.unit().gridZ());
                statement.setInt(7, territory.center().x());
                statement.setInt(8, territory.center().z());
                statement.setString(9, request.residenceName());
                statement.setString(10, request.residenceAreaName());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO territory_chunks (unit_id, world_uuid, chunk_x, chunk_z)
                    VALUES (?, ?, ?, ?)
                    """)) {
                for (ChunkPosition chunk : territory.chunks()) {
                    statement.setBytes(1, uuid(unitId));
                    statement.setBytes(2, uuid(chunk.worldId()));
                    statement.setInt(3, chunk.x());
                    statement.setInt(4, chunk.z());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO territory_expansions
                        (expansion_id, town_id, unit_id, business_key, actor_uuid, price_minor, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'PREPARED')
                    """)) {
                statement.setBytes(1, uuid(expansionId));
                statement.setBytes(2, uuid(request.townId()));
                statement.setBytes(3, uuid(unitId));
                statement.setString(4, request.businessKey());
                statement.setBytes(5, uuid(request.actorId()));
                statement.setLong(6, request.priceMinor());
                statement.executeUpdate();
            }
            postLedger(connection, request.townId(), "EXPANSION", -request.priceMinor(),
                    request.actorId(), request.actorName(), request.businessKey(),
                    "扩张至网格 " + request.unit().gridX() + "," + request.unit().gridZ(), false);
            return requireExpansion(connection, expansionId);
        });
    }

    public ExpansionOperation completeExpansion(UUID expansionId) {
        requireWorkerThread();
        return transaction(connection -> {
            ExpansionOperation expansion = requireExpansion(connection, expansionId);
            if (expansion.status().equals("COMPLETED")) {
                return expansion;
            }
            if (!expansion.status().equals("PREPARED")) {
                throw new ConflictException("当前扩张状态不能完成");
            }
            try (PreparedStatement unit = connection.prepareStatement("""
                    UPDATE territory_units SET projection_status = 'ACTIVE', projection_error = NULL
                     WHERE unit_id = ?
                    """)) {
                unit.setBytes(1, uuid(expansion.unitId()));
                requireUpdated(unit, "领地单元不存在");
            }
            setExpansionStatus(connection, expansionId, "COMPLETED", null);
            return requireExpansion(connection, expansionId);
        });
    }

    public void refundExpansion(UUID expansionId, String error) {
        requireWorkerThread();
        transaction(connection -> {
            ExpansionOperation expansion = requireExpansion(connection, expansionId);
            if (expansion.status().equals("COMPLETED")) {
                throw new ConflictException("已完成扩张不能退款");
            }
            String refundKey = expansion.businessKey() + ":refund";
            if (findLedgerByBusinessKey(connection, refundKey).isEmpty()) {
                postLedger(connection, expansion.townId(), "EXPANSION_REFUND", expansion.priceMinor(),
                        expansion.actorId(), expansion.actorId().toString(), refundKey,
                        "Residence 投影失败退款: " + safe(error), false);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM territory_expansions WHERE expansion_id = ?")) {
                statement.setBytes(1, uuid(expansionId));
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM territory_units WHERE unit_id = ?")) {
                statement.setBytes(1, uuid(expansion.unitId()));
                statement.executeUpdate();
            }
            return null;
        });
    }

    public List<ExpansionOperation> pendingExpansions() {
        requireWorkerThread();
        return query(connection -> {
            List<ExpansionOperation> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT e.*, u.world_uuid, u.world_name, u.grid_x, u.grid_z,
                           u.center_chunk_x, u.center_chunk_z, u.residence_name, u.residence_area_name
                      FROM territory_expansions e JOIN territory_units u ON u.unit_id = e.unit_id
                     WHERE e.status IN ('PREPARED', 'COMPENSATION_REQUIRED')
                     ORDER BY e.created_at
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(readExpansion(rows));
                }
            }
            return List.copyOf(result);
        });
    }

    private EconomyOperation updateOperation(UUID operationId, String expected, String status,
                                             String error) {
        return transaction(connection -> {
            EconomyOperation current = requireOperation(connection, operationId);
            if (current.status().equals(status)) {
                return current;
            }
            if (!current.status().equals(expected)) {
                throw new ConflictException("经济操作状态已变化");
            }
            setOperationStatus(connection, operationId, status, error);
            return requireOperation(connection, operationId);
        });
    }

    private LedgerMutation postLedger(Connection connection, UUID townId, String type,
                                      long amountMinor, UUID actorId, String actorName,
                                      String businessKey, String note, boolean bypassLock)
            throws SQLException {
        AccountState account = requireAccount(connection, townId);
        if (amountMinor < 0 && !bypassLock) {
            requireUnlocked(account);
        }
        long after = Math.addExact(account.balanceMinor(), amountMinor);
        if (after < 0) {
            throw new ConflictException("小镇余额不足");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE town_accounts SET balance_minor = ?, version = version + 1
                 WHERE town_id = ? AND version = ?
                """)) {
            statement.setLong(1, after);
            statement.setBytes(2, uuid(townId));
            statement.setLong(3, account.version());
            requireUpdated(statement, "小镇账户已被其他操作修改，请重试");
        }
        UUID entryId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger_entries
                    (entry_id, town_id, entry_type, amount_minor, balance_after_minor,
                     actor_uuid, actor_name, business_key, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setBytes(1, uuid(entryId));
            statement.setBytes(2, uuid(townId));
            statement.setString(3, type);
            statement.setLong(4, amountMinor);
            statement.setLong(5, after);
            statement.setBytes(6, actorId == null ? null : uuid(actorId));
            statement.setString(7, actorName == null ? "SYSTEM" : actorName);
            statement.setString(8, businessKey);
            statement.setString(9, safe(note));
            statement.executeUpdate();
        }
        return new LedgerMutation(entryId, townId, amountMinor, after, businessKey);
    }

    private static Optional<LedgerMutation> findLedgerByBusinessKey(Connection connection,
                                                                    String businessKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT entry_id, town_id, amount_minor, balance_after_minor, business_key
                  FROM ledger_entries WHERE business_key = ?
                """)) {
            statement.setString(1, businessKey);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(new LedgerMutation(readUuid(row, "entry_id"),
                        readUuid(row, "town_id"), row.getLong("amount_minor"),
                        row.getLong("balance_after_minor"), row.getString("business_key")))
                        : Optional.empty();
            }
        }
    }

    private static Optional<EconomyOperation> findOperation(Connection connection,
                                                            String businessKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM economy_operations WHERE business_key = ?")) {
            statement.setString(1, businessKey);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readOperation(row)) : Optional.empty();
            }
        }
    }

    private static EconomyOperation requireOperation(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM economy_operations WHERE operation_id = ?")) {
            statement.setBytes(1, uuid(operationId));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ConflictException("经济操作不存在");
                }
                return readOperation(row);
            }
        }
    }

    private static void setOperationStatus(Connection connection, UUID operationId, String status,
                                           String error) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE economy_operations SET status = ?, last_error = ? WHERE operation_id = ?
                """)) {
            statement.setString(1, status);
            statement.setString(2, error);
            statement.setBytes(3, uuid(operationId));
            requireUpdated(statement, "经济操作不存在");
        }
    }

    private static EconomyOperation readOperation(ResultSet row) throws SQLException {
        byte[] actor = row.getBytes("actor_uuid");
        return new EconomyOperation(readUuid(row, "operation_id"), readUuid(row, "town_id"),
                row.getString("operation_type"), row.getString("business_key"),
                row.getLong("amount_minor"), actor == null ? null : uuid(actor),
                row.getString("actor_name"), row.getString("note"), row.getString("status"),
                row.getString("last_error"), instant(row, "created_at"));
    }

    private static Optional<ExpansionOperation> findExpansion(Connection connection,
                                                              String businessKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.*, u.world_uuid, u.world_name, u.grid_x, u.grid_z,
                       u.center_chunk_x, u.center_chunk_z, u.residence_name, u.residence_area_name
                  FROM territory_expansions e JOIN territory_units u ON u.unit_id = e.unit_id
                 WHERE e.business_key = ?
                """)) {
            statement.setString(1, businessKey);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readExpansion(row)) : Optional.empty();
            }
        }
    }

    private static ExpansionOperation requireExpansion(Connection connection, UUID expansionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.*, u.world_uuid, u.world_name, u.grid_x, u.grid_z,
                       u.center_chunk_x, u.center_chunk_z, u.residence_name, u.residence_area_name
                  FROM territory_expansions e JOIN territory_units u ON u.unit_id = e.unit_id
                 WHERE e.expansion_id = ?
                """)) {
            statement.setBytes(1, uuid(expansionId));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ConflictException("扩张操作不存在");
                }
                return readExpansion(row);
            }
        }
    }

    private static void setExpansionStatus(Connection connection, UUID expansionId, String status,
                                           String error) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE territory_expansions SET status = ?, last_error = ? WHERE expansion_id = ?
                """)) {
            statement.setString(1, status);
            statement.setString(2, error);
            statement.setBytes(3, uuid(expansionId));
            requireUpdated(statement, "扩张操作不存在");
        }
    }

    private static ExpansionOperation readExpansion(ResultSet row) throws SQLException {
        InitialTerritory territory = new InitialTerritory(new ChunkPosition(
                readUuid(row, "world_uuid"), row.getString("world_name"),
                row.getInt("center_chunk_x"), row.getInt("center_chunk_z")));
        TerritoryUnit unit = new TerritoryUnit(row.getInt("grid_x"), row.getInt("grid_z"), territory);
        return new ExpansionOperation(readUuid(row, "expansion_id"), readUuid(row, "town_id"),
                readUuid(row, "unit_id"), row.getString("business_key"),
                readUuid(row, "actor_uuid"), row.getLong("price_minor"), row.getString("status"),
                row.getString("last_error"), unit, row.getString("residence_name"),
                row.getString("residence_area_name"));
    }

    private static List<TerritoryUnitSnapshot> listTerritoryUnits(Connection connection, UUID townId)
            throws SQLException {
        List<TerritoryUnitSnapshot> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM territory_units WHERE town_id = ?
                 ORDER BY grid_z, grid_x
                """)) {
            statement.setBytes(1, uuid(townId));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    InitialTerritory territory = new InitialTerritory(new ChunkPosition(
                            readUuid(rows, "world_uuid"), rows.getString("world_name"),
                            rows.getInt("center_chunk_x"), rows.getInt("center_chunk_z")));
                    result.add(new TerritoryUnitSnapshot(readUuid(rows, "unit_id"), townId,
                            new TerritoryUnit(rows.getInt("grid_x"), rows.getInt("grid_z"), territory),
                            rows.getString("residence_name"), rows.getString("residence_area_name"),
                            rows.getString("projection_status"), rows.getString("projection_error")));
                }
            }
        }
        return List.copyOf(result);
    }

    private static AccountState requireAccount(Connection connection, UUID townId) throws SQLException {
        try (PreparedStatement ensure = connection.prepareStatement("""
                INSERT INTO town_accounts (town_id) VALUES (?) ON CONFLICT (town_id) DO NOTHING
                """)) {
            ensure.setBytes(1, uuid(townId));
            ensure.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM town_accounts WHERE town_id = ?")) {
            statement.setBytes(1, uuid(townId));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ConflictException("小镇账户不存在");
                }
                return new AccountState(townId, row.getLong("balance_minor"),
                        row.getBoolean("locked"), row.getString("lock_reason"),
                        row.getLong("version"));
            }
        }
    }

    private static TownTax requireTownTax(Connection connection, UUID townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tax_rate_bps, tax_revision FROM towns WHERE town_id = ?
                """)) {
            statement.setBytes(1, uuid(townId));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ConflictException("小镇不存在");
                }
                return new TownTax(row.getInt("tax_rate_bps"), row.getInt("tax_revision"));
            }
        }
    }

    private static void requireMayor(Connection connection, UUID townId, UUID playerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM town_members
                 WHERE town_id = ? AND player_uuid = ?
                   AND role = 'MAYOR'
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.setBytes(2, uuid(playerId));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ConflictException("只有镇长可以修改税率");
                }
            }
        }
    }

    private static void audit(Connection connection, UUID actorId, String actorName, String action,
                              UUID townId, String reason, String detail) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit_logs
                    (actor_uuid, actor_name, action, target_type, target_id, reason, detail)
                VALUES (?, ?, ?, 'TOWN', ?, ?, ?)
                """)) {
            statement.setBytes(1, actorId == null ? null : uuid(actorId));
            statement.setString(2, actorName == null ? "SYSTEM" : actorName);
            statement.setString(3, action);
            statement.setString(4, townId.toString());
            statement.setString(5, reason);
            statement.setString(6, detail);
            statement.executeUpdate();
        }
    }

    private static TownFinance readFinance(ResultSet row) throws SQLException {
        return new TownFinance(readUuid(row, "town_id"), row.getString("name"),
                row.getString("role"), row.getLong("balance_minor"),
                row.getInt("tax_rate_bps"), row.getInt("tax_revision"),
                row.getInt("accepted_tax_revision"), row.getBoolean("locked"),
                row.getString("lock_reason"), row.getInt("unit_count"));
    }

    private static LedgerEntry readLedger(ResultSet row) throws SQLException {
        byte[] actor = row.getBytes("actor_uuid");
        return new LedgerEntry(readUuid(row, "entry_id"), readUuid(row, "town_id"),
                row.getString("entry_type"), row.getLong("amount_minor"),
                row.getLong("balance_after_minor"), actor == null ? null : uuid(actor),
                row.getString("actor_name"), row.getString("business_key"),
                row.getString("note"), instant(row, "created_at"));
    }

    private static void requireUnlocked(AccountState account) {
        if (account.locked()) {
            throw new ConflictException("小镇资金已锁定: " + account.lockReason());
        }
    }

    private static void requireTaxRate(int basisPoints) {
        if (basisPoints < 500 || basisPoints > 2_500 || basisPoints % 500 != 0) {
            throw new IllegalArgumentException("税率必须为 5%~25%，且以 5% 为步进");
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("原因必须为 1~500 个字符");
        }
    }

    private void requireWorkerThread() {
        if (forbiddenThread.getAsBoolean()) {
            throw new IllegalStateException("禁止在 Paper 主线程执行数据库 I/O");
        }
    }

    private <T> T query(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            return work.run(connection);
        } catch (SQLException exception) {
            throw translate(exception);
        }
    }

    private <T> T transaction(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean begun = false;
            try {
                executeTransactionCommand(connection, "BEGIN IMMEDIATE");
                begun = true;
                T result = work.run(connection);
                executeTransactionCommand(connection, "COMMIT");
                begun = false;
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, begun, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw translate(exception);
        }
    }

    private static void rollback(Connection connection, boolean begun, Throwable failure) {
        if (!begun) {
            return;
        }
        try {
            executeTransactionCommand(connection, "ROLLBACK");
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void executeTransactionCommand(Connection connection, String command)
            throws SQLException {
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute(command);
        }
    }

    private static RuntimeException translate(SQLException exception) {
        if (exception instanceof SQLIntegrityConstraintViolationException
                || "23000".equals(exception.getSQLState()) || exception.getErrorCode() == 19) {
            return new ConflictException("数据已被其他操作占用，请刷新后重试", exception);
        }
        return new StorageUnavailableException("SQLite 操作失败: " + exception.getMessage(), exception);
    }

    private static void requireUpdated(PreparedStatement statement, String message)
            throws SQLException {
        if (statement.executeUpdate() != 1) {
            throw new ConflictException(message);
        }
    }

    private static byte[] uuid(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static UUID readUuid(ResultSet row, String column) throws SQLException {
        return uuid(row.getBytes(column));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return Instant.ofEpochMilli(row.getLong(column));
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 2_000 ? value : value.substring(0, 2_000);
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private record TownTax(int basisPoints, int revision) {
    }

    private record AccountState(UUID townId, long balanceMinor, boolean locked,
                                String lockReason, long version) {
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
                               UUID receiverId, UUID interactingId, long grossMinor,
                               int taxRateBps, long taxMinor, String worldName) {
        public QuickShopTax {
            Objects.requireNonNull(townId, "townId");
            Objects.requireNonNull(businessKey, "businessKey");
            Objects.requireNonNull(receiverId, "receiverId");
            Objects.requireNonNull(interactingId, "interactingId");
            Objects.requireNonNull(worldName, "worldName");
            if (!shopType.equals("SELLING") && !shopType.equals("BUYING")) {
                throw new IllegalArgumentException("商店类型必须为 SELLING 或 BUYING");
            }
        }
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

    public record ExpansionRequest(UUID townId, TerritoryUnit unit, String residenceName,
                                   String residenceAreaName, long priceMinor, UUID actorId,
                                   String actorName, String businessKey) {
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

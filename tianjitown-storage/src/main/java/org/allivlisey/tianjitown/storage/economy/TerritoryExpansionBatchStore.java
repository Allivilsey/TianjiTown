package org.allivlisey.tianjitown.storage.economy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.land.TerritoryRules;
import org.allivlisey.tianjitown.core.land.TerritoryUnit;
import org.allivlisey.tianjitown.storage.economy.EconomyPersistence.AccountState;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.ConflictException;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.ExpansionBatchItem;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.ExpansionBatchOperation;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.ExpansionBatchRequest;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.ExpansionOperation;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.TerritoryUnitSnapshot;

import static org.allivlisey.tianjitown.storage.economy.TerritoryExpansionSql.*;

final class TerritoryExpansionBatchStore {
    private final EconomyDatabase database;

    TerritoryExpansionBatchStore(EconomyDatabase database) {
        this.database = database;
    }

    ExpansionBatchOperation prepareExpansionBatch(ExpansionBatchRequest request) {
        database.requireWorkerThread();
        Objects.requireNonNull(request, "request");
        validateBatchRequest(request);
        return database.transaction(connection -> {
            Optional<ExpansionBatchOperation> existing = findExpansionBatch(connection,
                    request.businessKey());
            if (existing.isPresent()) {
                return existing.get();
            }
            AccountState account = EconomyPersistence.requireAccount(connection, request.townId());
            EconomyPersistence.requireUnlocked(account);
            if (account.balanceMinor() < request.totalPriceMinor()) {
                throw new ConflictException("小镇余额不足");
            }
            List<TerritoryUnitSnapshot> snapshots = listTerritoryUnits(connection,
                    request.townId());
            if (request.expectedUnitCount() >= 0
                    && request.expectedUnitCount() != snapshots.size()) {
                throw new ConflictException("领地数量已变化，请刷新扩张报价后重试");
            }
            List<TerritoryUnit> units = new ArrayList<>(snapshots.stream()
                    .map(TerritoryUnitSnapshot::unit).toList());
            if (units.size() + request.items().size() > TerritoryRules.MAXIMUM_UNITS) {
                throw new ConflictException("批量扩张后超过领地单元上限");
            }
            TerritoryUnit origin = units.stream()
                    .filter(unit -> unit.gridX() == 0 && unit.gridZ() == 0)
                    .findFirst().orElseThrow(() -> new ConflictException("初始领地单元缺失"));
            Set<Grid> occupied = new HashSet<>();
            units.forEach(unit -> occupied.add(new Grid(unit.gridX(), unit.gridZ())));
            List<TerritoryUnit> candidates = new ArrayList<>();
            for (ExpansionBatchItem item : request.items()) {
                TerritoryUnit candidate = item.unit();
                if (!occupied.add(new Grid(candidate.gridX(), candidate.gridZ()))) {
                    throw new ConflictException("批量扩张包含已占用或重复的领地单元");
                }
                if (Math.abs((long) candidate.gridX()) > TerritoryRules.GRID_RADIUS
                        || Math.abs((long) candidate.gridZ()) > TerritoryRules.GRID_RADIUS) {
                    throw new ConflictException("目标超出 5×5 扩张网格");
                }
                ChunkPosition expectedCenter = new ChunkPosition(
                        origin.territory().center().worldId(),
                        origin.territory().center().worldName(),
                        Math.addExact(origin.territory().center().x(), Math.multiplyExact(
                                candidate.gridX(), InitialTerritory.CHUNKS_PER_SIDE)),
                        Math.addExact(origin.territory().center().z(), Math.multiplyExact(
                                candidate.gridZ(), InitialTerritory.CHUNKS_PER_SIDE)));
                if (!candidate.territory().center().equals(expectedCenter)) {
                    throw new ConflictException("目标领地不在固定 5×5 区块网格上");
                }
                candidates.add(candidate);
            }
            List<TerritoryUnit> all = new ArrayList<>(units);
            all.addAll(candidates);
            try {
                TerritoryRules.requireConnected(all);
            } catch (IllegalArgumentException exception) {
                throw new ConflictException("批量选区必须与现有领地保持连通: "
                        + exception.getMessage(), exception);
            }
            UUID batchId = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO territory_expansion_batches
                        (batch_id, town_id, business_key, actor_uuid, actor_name,
                         total_price_minor, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'PREPARED')
                    """)) {
                statement.setBytes(1, EconomyPersistence.uuid(batchId));
                statement.setBytes(2, EconomyPersistence.uuid(request.townId()));
                statement.setString(3, request.businessKey());
                statement.setBytes(4, EconomyPersistence.uuid(request.actorId()));
                statement.setString(5, request.actorName());
                statement.setLong(6, request.totalPriceMinor());
                statement.executeUpdate();
            }
            List<UUID> unitIds = new ArrayList<>();
            for (ExpansionBatchItem item : request.items()) {
                unitIds.add(insertTerritoryUnit(connection, request.townId(), item.unit(),
                        item.residenceName(), item.residenceAreaName()));
            }
            try (PreparedStatement expansion = connection.prepareStatement("""
                    INSERT INTO territory_expansions
                        (expansion_id, town_id, unit_id, business_key, actor_uuid,
                         price_minor, status, batch_id)
                    VALUES (?, ?, ?, ?, ?, ?, 'PREPARED', ?)
                    """)) {
                for (int index = 0; index < request.items().size(); index++) {
                    ExpansionBatchItem item = request.items().get(index);
                    TerritoryUnit unit = item.unit();
                    expansion.setBytes(1, EconomyPersistence.uuid(UUID.randomUUID()));
                    expansion.setBytes(2, EconomyPersistence.uuid(request.townId()));
                    expansion.setBytes(3, EconomyPersistence.uuid(unitIds.get(index)));
                    expansion.setString(4, request.businessKey() + ":" + unit.gridX()
                            + ":" + unit.gridZ());
                    expansion.setBytes(5, EconomyPersistence.uuid(request.actorId()));
                    expansion.setLong(6, item.priceMinor());
                    expansion.setBytes(7, EconomyPersistence.uuid(batchId));
                    expansion.addBatch();
                }
                expansion.executeBatch();
            }
            EconomyPersistence.postLedger(connection, request.townId(), "EXPANSION", -request.totalPriceMinor(),
                    request.actorId(), request.actorName(), request.businessKey(),
                    "批量扩张 " + request.items().size() + " 个领地单元", false);
            EconomyPersistence.audit(connection, request.actorId(), request.actorName(), "EXPANSION_BATCH_PREPARE",
                    request.townId(), request.businessKey(),
                    "批量扩张 " + request.items().size() + " 个领地单元，总价 "
                            + request.totalPriceMinor());
            return requireExpansionBatch(connection, batchId);
        });
    }

    ExpansionBatchOperation completeExpansionBatch(UUID batchId) {
        database.requireWorkerThread();
        Objects.requireNonNull(batchId, "batchId");
        return database.transaction(connection -> {
            ExpansionBatchOperation batch = requireExpansionBatch(connection, batchId);
            if (batch.status().equals("COMPLETED")) {
                return batch;
            }
            if (!batch.status().equals("PREPARED")) {
                throw new ConflictException("当前批量扩张状态不能完成");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE territory_units SET projection_status = 'ACTIVE',
                           projection_error = NULL
                     WHERE unit_id IN (SELECT unit_id FROM territory_expansions WHERE batch_id = ?)
                    """)) {
                statement.setBytes(1, EconomyPersistence.uuid(batchId));
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE territory_expansions SET status = 'COMPLETED', last_error = NULL
                     WHERE batch_id = ? AND status = 'PREPARED'
                    """)) {
                statement.setBytes(1, EconomyPersistence.uuid(batchId));
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE territory_expansion_batches SET status = 'COMPLETED', last_error = NULL
                     WHERE batch_id = ? AND status = 'PREPARED'
                    """)) {
                statement.setBytes(1, EconomyPersistence.uuid(batchId));
                EconomyPersistence.requireUpdated(statement, "批量扩张状态已被其他操作修改");
            }
            EconomyPersistence.audit(connection, batch.actorId(), batch.actorName(), "EXPANSION_BATCH_COMPLETE",
                    batch.townId(), batch.businessKey(), "批量扩张 Residence 投影完成");
            return requireExpansionBatch(connection, batchId);
        });
    }

    void refundExpansionBatch(UUID batchId, String error) {
        database.requireWorkerThread();
        Objects.requireNonNull(batchId, "batchId");
        database.transaction(connection -> {
            ExpansionBatchOperation batch = requireExpansionBatch(connection, batchId);
            if (batch.status().equals("REFUNDED")) {
                return null;
            }
            if (batch.status().equals("COMPLETED")) {
                throw new ConflictException("已完成批量扩张不能退款");
            }
            String refundKey = batch.businessKey() + ":refund";
            if (EconomyPersistence.findLedgerByBusinessKey(connection, refundKey).isEmpty()) {
                EconomyPersistence.postLedger(connection, batch.townId(), "EXPANSION_REFUND",
                        batch.totalPriceMinor(), batch.actorId(), batch.actorName(), refundKey,
                        "批量 Residence 投影失败退款: " + EconomyPersistence.safe(error), false);
            }
            List<UUID> unitIds = batch.expansions().stream()
                    .map(ExpansionOperation::unitId)
                    .toList();
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM territory_expansions WHERE batch_id = ?")) {
                statement.setBytes(1, EconomyPersistence.uuid(batchId));
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM territory_units WHERE unit_id = ?")) {
                for (UUID unitId : unitIds) {
                    statement.setBytes(1, EconomyPersistence.uuid(unitId));
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE territory_expansion_batches
                       SET status = 'REFUNDED', last_error = ?
                     WHERE batch_id = ? AND status IN ('PREPARED', 'COMPENSATION_REQUIRED')
                    """)) {
                statement.setString(1, EconomyPersistence.safe(error));
                statement.setBytes(2, EconomyPersistence.uuid(batchId));
                EconomyPersistence.requireUpdated(statement, "批量扩张状态已被其他操作修改");
            }
            EconomyPersistence.audit(connection, batch.actorId(), batch.actorName(), "EXPANSION_BATCH_REFUND",
                    batch.townId(), batch.businessKey(), "批量扩张已退款: " + EconomyPersistence.safe(error));
            return null;
        });
    }

    List<ExpansionBatchOperation> pendingExpansionBatches() {
        database.requireWorkerThread();
        return database.query(connection -> {
            List<ExpansionBatchOperation> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM territory_expansion_batches
                     WHERE status IN ('PREPARED', 'COMPENSATION_REQUIRED')
                     ORDER BY created_at, batch_id
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(readExpansionBatch(connection, rows));
                }
            }
            return List.copyOf(result);
        });
    }

    private static Optional<ExpansionBatchOperation> findExpansionBatch(Connection connection,
                                                                          String businessKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM territory_expansion_batches WHERE business_key = ?
                """)) {
            statement.setString(1, businessKey);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readExpansionBatch(connection, row))
                        : Optional.empty();
            }
        }
    }

    private static ExpansionBatchOperation requireExpansionBatch(Connection connection,
                                                                  UUID batchId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM territory_expansion_batches WHERE batch_id = ?
                """)) {
            statement.setBytes(1, EconomyPersistence.uuid(batchId));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ConflictException("批量扩张操作不存在");
                }
                return readExpansionBatch(connection, row);
            }
        }
    }

    private static ExpansionBatchOperation readExpansionBatch(Connection connection,
                                                               ResultSet row) throws SQLException {
        UUID batchId = EconomyPersistence.readUuid(row, "batch_id");
        return new ExpansionBatchOperation(batchId, EconomyPersistence.readUuid(row, "town_id"),
                row.getString("business_key"), EconomyPersistence.readUuid(row, "actor_uuid"),
                row.getString("actor_name"), row.getLong("total_price_minor"),
                row.getString("status"), row.getString("last_error"),
                listBatchExpansions(connection, batchId));
    }

    private static List<ExpansionOperation> listBatchExpansions(Connection connection,
                                                                  UUID batchId)
            throws SQLException {
        List<ExpansionOperation> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.*, u.world_uuid, u.world_name, u.grid_x, u.grid_z,
                       u.center_chunk_x, u.center_chunk_z, u.residence_name,
                       u.residence_area_name
                  FROM territory_expansions e JOIN territory_units u ON u.unit_id = e.unit_id
                 WHERE e.batch_id = ? ORDER BY u.grid_z, u.grid_x
                """)) {
            statement.setBytes(1, EconomyPersistence.uuid(batchId));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(readExpansion(rows));
                }
            }
        }
        return List.copyOf(result);
    }

    private static void validateBatchRequest(ExpansionBatchRequest request) {
        Objects.requireNonNull(request.townId(), "townId");
        Objects.requireNonNull(request.actorId(), "actorId");
        if (request.actorName() == null || request.actorName().isBlank()) {
            throw new IllegalArgumentException("批量扩张操作人名称不能为空");
        }
        if (request.businessKey() == null || request.businessKey().isBlank()) {
            throw new IllegalArgumentException("批量扩张幂等键不能为空");
        }
        if (request.items() == null || request.items().isEmpty()
                || request.items().size() > TerritoryRules.MAXIMUM_UNITS) {
            throw new IllegalArgumentException("批量扩张至少需要一个且不能超过 25 个领地单元");
        }
        long total = 0;
        Set<Grid> grids = new HashSet<>();
        for (ExpansionBatchItem item : request.items()) {
            Objects.requireNonNull(item, "batch item");
            Objects.requireNonNull(item.unit(), "batch item unit");
            if (item.priceMinor() <= 0) {
                throw new IllegalArgumentException("批量扩张单元价格必须大于 0");
            }
            if (item.residenceName() == null || item.residenceName().isBlank()
                    || item.residenceAreaName() == null || item.residenceAreaName().isBlank()) {
                throw new IllegalArgumentException("批量扩张 Residence 名称不能为空");
            }
            if (!grids.add(new Grid(item.unit().gridX(), item.unit().gridZ()))) {
                throw new IllegalArgumentException("批量扩张包含重复网格");
            }
            total = Math.addExact(total, item.priceMinor());
        }
        if (request.totalPriceMinor() <= 0 || total != request.totalPriceMinor()) {
            throw new IllegalArgumentException("批量扩张总价与单元价格不一致");
        }
    }

    private record Grid(int x, int z) {}
}

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
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.ExpansionRequest;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.OccupiedTerritoryChunk;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.TerritoryUnitSnapshot;

/** Territory reservation, expansion settlement and refund transactions. */
final class TerritoryExpansionStore {
    private final EconomyDatabase database;

    TerritoryExpansionStore(EconomyDatabase database) {
        this.database = database;
    }

    List<TerritoryUnitSnapshot> territoryUnits(UUID townId) {
        database.requireWorkerThread();
        return database.query(connection -> listTerritoryUnits(connection, townId));
    }

    List<OccupiedTerritoryChunk> occupiedChunksOutsideTown(
            UUID townId, UUID worldId, int minimumX, int maximumX,
            int minimumZ, int maximumZ) {
        database.requireWorkerThread();
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(worldId, "worldId");
        if (minimumX > maximumX || minimumZ > maximumZ) {
            throw new IllegalArgumentException("领地区块查询范围无效");
        }
        return database.query(connection -> {
            List<OccupiedTerritoryChunk> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT DISTINCT u.town_id, t.name AS town_name,
                           c.chunk_x, c.chunk_z
                      FROM territory_chunks c
                      JOIN territory_units u ON u.unit_id = c.unit_id
                      JOIN towns t ON t.town_id = u.town_id
                     WHERE u.town_id <> ? AND c.world_uuid = ?
                       AND t.status = 'ACTIVE' AND u.reuse_blocked = 1
                       AND c.chunk_x BETWEEN ? AND ?
                       AND c.chunk_z BETWEEN ? AND ?
                     ORDER BY c.chunk_z, c.chunk_x
                    """)) {
                statement.setBytes(1, EconomyPersistence.uuid(townId));
                statement.setBytes(2, EconomyPersistence.uuid(worldId));
                statement.setInt(3, minimumX);
                statement.setInt(4, maximumX);
                statement.setInt(5, minimumZ);
                statement.setInt(6, maximumZ);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        result.add(new OccupiedTerritoryChunk(EconomyPersistence.readUuid(rows, "town_id"),
                                rows.getString("town_name"), rows.getInt("chunk_x"),
                                rows.getInt("chunk_z")));
                    }
                }
            }
            return List.copyOf(result);
        });
    }

    ExpansionOperation prepareExpansion(ExpansionRequest request) {
        database.requireWorkerThread();
        Objects.requireNonNull(request, "request");
        if (request.priceMinor() <= 0) {
            throw new IllegalArgumentException("扩张价格必须大于 0");
        }
        return database.transaction(connection -> {
            Optional<ExpansionOperation> existing = findExpansion(connection, request.businessKey());
            if (existing.isPresent()) {
                return existing.get();
            }
            AccountState account = EconomyPersistence.requireAccount(connection, request.townId());
            EconomyPersistence.requireUnlocked(account);
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
                            Math.multiplyExact(request.unit().gridX(),
                                    InitialTerritory.CHUNKS_PER_SIDE)),
                    Math.addExact(origin.territory().center().z(),
                            Math.multiplyExact(request.unit().gridZ(),
                                    InitialTerritory.CHUNKS_PER_SIDE)));
            if (!request.unit().territory().center().equals(expectedCenter)) {
                throw new ConflictException("目标领地不在固定 5×5 单元网格上");
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
                statement.setBytes(1, EconomyPersistence.uuid(unitId));
                statement.setBytes(2, EconomyPersistence.uuid(request.townId()));
                statement.setBytes(3, EconomyPersistence.uuid(territory.center().worldId()));
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
                    statement.setBytes(1, EconomyPersistence.uuid(unitId));
                    statement.setBytes(2, EconomyPersistence.uuid(chunk.worldId()));
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
                statement.setBytes(1, EconomyPersistence.uuid(expansionId));
                statement.setBytes(2, EconomyPersistence.uuid(request.townId()));
                statement.setBytes(3, EconomyPersistence.uuid(unitId));
                statement.setString(4, request.businessKey());
                statement.setBytes(5, EconomyPersistence.uuid(request.actorId()));
                statement.setLong(6, request.priceMinor());
                statement.executeUpdate();
            }
            EconomyPersistence.postLedger(connection, request.townId(), "EXPANSION", -request.priceMinor(),
                    request.actorId(), request.actorName(), request.businessKey(),
                    "扩张至网格 " + request.unit().gridX() + "," + request.unit().gridZ(), false);
            EconomyPersistence.audit(connection, request.actorId(), request.actorName(), "EXPANSION_PREPARE",
                    request.townId(), request.businessKey(),
                    "扩张至网格 " + request.unit().gridX() + "," + request.unit().gridZ());
            return requireExpansion(connection, expansionId);
        });
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
            try (PreparedStatement unit = connection.prepareStatement("""
                    INSERT INTO territory_units
                        (unit_id, town_id, world_uuid, world_name, grid_x, grid_z,
                         center_chunk_x, center_chunk_z, residence_name, residence_area_name)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
                 PreparedStatement chunk = connection.prepareStatement("""
                    INSERT INTO territory_chunks (unit_id, world_uuid, chunk_x, chunk_z)
                    VALUES (?, ?, ?, ?)
                    """)) {
                for (int index = 0; index < request.items().size(); index++) {
                    ExpansionBatchItem item = request.items().get(index);
                    TerritoryUnit territoryUnit = item.unit();
                    InitialTerritory territory = territoryUnit.territory();
                    UUID unitId = UUID.randomUUID();
                    unitIds.add(unitId);
                    unit.setBytes(1, EconomyPersistence.uuid(unitId));
                    unit.setBytes(2, EconomyPersistence.uuid(request.townId()));
                    unit.setBytes(3, EconomyPersistence.uuid(territory.center().worldId()));
                    unit.setString(4, territory.center().worldName());
                    unit.setInt(5, territoryUnit.gridX());
                    unit.setInt(6, territoryUnit.gridZ());
                    unit.setInt(7, territory.center().x());
                    unit.setInt(8, territory.center().z());
                    unit.setString(9, item.residenceName());
                    unit.setString(10, item.residenceAreaName());
                    unit.executeUpdate();
                    for (ChunkPosition chunkPosition : territory.chunks()) {
                        chunk.setBytes(1, EconomyPersistence.uuid(unitId));
                        chunk.setBytes(2, EconomyPersistence.uuid(chunkPosition.worldId()));
                        chunk.setInt(3, chunkPosition.x());
                        chunk.setInt(4, chunkPosition.z());
                        chunk.addBatch();
                    }
                }
                chunk.executeBatch();
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

    ExpansionOperation completeExpansion(UUID expansionId) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
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
                unit.setBytes(1, EconomyPersistence.uuid(expansion.unitId()));
                EconomyPersistence.requireUpdated(unit, "领地单元不存在");
            }
            setExpansionStatus(connection, expansionId, "COMPLETED", null);
            EconomyPersistence.audit(connection, expansion.actorId(), expansion.actorId().toString(),
                    "EXPANSION_COMPLETE", expansion.townId(), expansion.businessKey(),
                    "Residence 扩张投影完成");
            return requireExpansion(connection, expansionId);
        });
    }

    void refundExpansion(UUID expansionId, String error) {
        database.requireWorkerThread();
        database.transaction(connection -> {
            ExpansionOperation expansion = requireExpansion(connection, expansionId);
            if (expansion.status().equals("COMPLETED")) {
                throw new ConflictException("已完成扩张不能退款");
            }
            String refundKey = expansion.businessKey() + ":refund";
            if (EconomyPersistence.findLedgerByBusinessKey(connection, refundKey).isEmpty()) {
                EconomyPersistence.postLedger(connection, expansion.townId(), "EXPANSION_REFUND", expansion.priceMinor(),
                        expansion.actorId(), expansion.actorId().toString(), refundKey,
                        "Residence 投影失败退款: " + EconomyPersistence.safe(error), false);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM territory_expansions WHERE expansion_id = ?")) {
                statement.setBytes(1, EconomyPersistence.uuid(expansionId));
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM territory_units WHERE unit_id = ?")) {
                statement.setBytes(1, EconomyPersistence.uuid(expansion.unitId()));
                statement.executeUpdate();
            }
            EconomyPersistence.audit(connection, expansion.actorId(), expansion.actorId().toString(),
                    "EXPANSION_REFUND", expansion.townId(), expansion.businessKey(),
                    "Residence 扩张投影失败，已退款: " + EconomyPersistence.safe(error));
            return null;
        });
    }

    List<ExpansionOperation> pendingExpansions() {
        database.requireWorkerThread();
        return database.query(connection -> {
            List<ExpansionOperation> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT e.*, u.world_uuid, u.world_name, u.grid_x, u.grid_z,
                           u.center_chunk_x, u.center_chunk_z, u.residence_name, u.residence_area_name
                      FROM territory_expansions e JOIN territory_units u ON u.unit_id = e.unit_id
                     WHERE e.status IN ('PREPARED', 'COMPENSATION_REQUIRED')
                     ORDER BY e.created_at, e.expansion_id
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(readExpansion(rows));
                }
            }
            return List.copyOf(result);
        });
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

    private static ExpansionOperation requireExpansion(Connection connection, UUID expansionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.*, u.world_uuid, u.world_name, u.grid_x, u.grid_z,
                       u.center_chunk_x, u.center_chunk_z, u.residence_name, u.residence_area_name
                  FROM territory_expansions e JOIN territory_units u ON u.unit_id = e.unit_id
                 WHERE e.expansion_id = ?
                """)) {
            statement.setBytes(1, EconomyPersistence.uuid(expansionId));
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
            statement.setBytes(3, EconomyPersistence.uuid(expansionId));
            EconomyPersistence.requireUpdated(statement, "扩张操作不存在");
        }
    }

    private static ExpansionOperation readExpansion(ResultSet row) throws SQLException {
        InitialTerritory territory = new InitialTerritory(new ChunkPosition(
                EconomyPersistence.readUuid(row, "world_uuid"), row.getString("world_name"),
                row.getInt("center_chunk_x"), row.getInt("center_chunk_z")));
        TerritoryUnit unit = new TerritoryUnit(row.getInt("grid_x"), row.getInt("grid_z"), territory);
        return new ExpansionOperation(EconomyPersistence.readUuid(row, "expansion_id"), EconomyPersistence.readUuid(row, "town_id"),
                EconomyPersistence.readUuid(row, "unit_id"), row.getString("business_key"),
                EconomyPersistence.readUuid(row, "actor_uuid"), row.getLong("price_minor"), row.getString("status"),
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
            statement.setBytes(1, EconomyPersistence.uuid(townId));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    InitialTerritory territory = new InitialTerritory(new ChunkPosition(
                            EconomyPersistence.readUuid(rows, "world_uuid"), rows.getString("world_name"),
                            rows.getInt("center_chunk_x"), rows.getInt("center_chunk_z")));
                    result.add(new TerritoryUnitSnapshot(EconomyPersistence.readUuid(rows, "unit_id"), townId,
                            new TerritoryUnit(rows.getInt("grid_x"), rows.getInt("grid_z"), territory),
                            rows.getString("residence_name"), rows.getString("residence_area_name"),
                            rows.getString("projection_status"), rows.getString("projection_error")));
                }
            }
        }
        return List.copyOf(result);
    }

    private record Grid(int x, int z) {
    }
}

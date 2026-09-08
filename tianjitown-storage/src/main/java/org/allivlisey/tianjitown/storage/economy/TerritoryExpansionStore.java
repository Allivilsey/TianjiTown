package org.allivlisey.tianjitown.storage.economy;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.land.TerritoryRules;
import org.allivlisey.tianjitown.core.land.TerritoryUnit;
import org.allivlisey.tianjitown.storage.economy.EconomyPersistence.AccountState;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.ConflictException;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.ExpansionOperation;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.ExpansionRequest;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.OccupiedTerritoryChunk;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.TerritoryUnitSnapshot;

import static org.allivlisey.tianjitown.storage.economy.TerritoryExpansionSql.*;

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
            requireNoPendingExpansion(connection, request.townId());
            AccountState account = EconomyPersistence.requireAccount(connection, request.townId());
            EconomyPersistence.requireUnlocked(account);
            if (account.balanceMinor() < request.priceMinor()) {
                throw new ConflictException("小镇余额不足");
            }
            List<TerritoryUnitSnapshot> snapshots = listTerritoryUnits(connection, request.townId());
            if (request.expectedUnitCount() >= 0
                    && request.expectedUnitCount() != snapshots.size()) {
                throw new ConflictException("领地数量已变化，请刷新扩张报价后重试");
            }
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
            UUID unitId = insertTerritoryUnit(connection, request.townId(), request.unit(),
                    request.residenceName(), request.residenceAreaName());
            UUID expansionId = UUID.randomUUID();
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

    ExpansionOperation completeExpansion(UUID expansionId) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            requireStandaloneExpansion(connection, expansionId);
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
            requireStandaloneExpansion(connection, expansionId);
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
                     WHERE e.batch_id IS NULL AND e.status IN ('PREPARED', 'COMPENSATION_REQUIRED')
                     ORDER BY e.created_at, e.expansion_id
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(readExpansion(rows));
                }
            }
            return List.copyOf(result);
        });
    }

    private record Grid(int x, int z) {
    }
}

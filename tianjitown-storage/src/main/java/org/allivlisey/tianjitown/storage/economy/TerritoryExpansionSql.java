package org.allivlisey.tianjitown.storage.economy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.allivlisey.tianjitown.core.land.ChunkPosition;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.land.TerritoryUnit;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.ConflictException;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.ExpansionOperation;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.TerritoryUnitSnapshot;

final class TerritoryExpansionSql {
    private TerritoryExpansionSql() {}

    static UUID insertTerritoryUnit(Connection connection, UUID townId, TerritoryUnit unit,
                                    String residenceName, String residenceAreaName) throws SQLException {
        UUID unitId = UUID.randomUUID();
        InitialTerritory territory = unit.territory();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO territory_units
                    (unit_id, town_id, world_uuid, world_name, grid_x, grid_z,
                     center_chunk_x, center_chunk_z, residence_name, residence_area_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setBytes(1, EconomyPersistence.uuid(unitId));
            statement.setBytes(2, EconomyPersistence.uuid(townId));
            statement.setBytes(3, EconomyPersistence.uuid(territory.center().worldId()));
            statement.setString(4, territory.center().worldName());
            statement.setInt(5, unit.gridX());
            statement.setInt(6, unit.gridZ());
            statement.setInt(7, territory.center().x());
            statement.setInt(8, territory.center().z());
            statement.setString(9, residenceName);
            statement.setString(10, residenceAreaName);
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
        return unitId;
    }

    static Optional<ExpansionOperation> findExpansion(Connection connection,
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

    static ExpansionOperation requireExpansion(Connection connection, UUID expansionId)
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

    static void setExpansionStatus(Connection connection, UUID expansionId, String status,
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

    static ExpansionOperation readExpansion(ResultSet row) throws SQLException {
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

    static List<TerritoryUnitSnapshot> listTerritoryUnits(Connection connection, UUID townId)
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

}

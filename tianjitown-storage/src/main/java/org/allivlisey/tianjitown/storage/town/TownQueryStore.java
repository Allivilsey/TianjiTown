package org.allivlisey.tianjitown.storage.town;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.town.TownStatus;

import static org.allivlisey.tianjitown.storage.town.TownPersistence.RULE_SEPARATOR;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.readUuid;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.uuid;

import org.allivlisey.tianjitown.storage.town.TownRepository.*;

final class TownQueryStore {
    private final TownDatabase database;

    TownQueryStore(TownDatabase database) {
        this.database = database;
    }

    public Optional<TownSnapshot> findTown(UUID townId) {
        database.requireWorkerThread();
        return database.query(connection -> TownPersistence.findTown(connection, townId));
    }

    public Optional<TownSnapshot> findTownByName(String townName) {
        database.requireWorkerThread();
        String normalizedName = ApplicationText.normalizeNameKey(townName);
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT town_id FROM towns WHERE normalized_name = ?
                     ORDER BY (status = 'ARCHIVED'), reuse_blocked DESC, created_at DESC LIMIT 1
                    """)) {
                statement.setString(1, normalizedName);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                            ? TownPersistence.findTown(connection, readUuid(result, "town_id")) : Optional.empty();
                }
            }
        });
    }

    public Optional<TownSnapshot> findTownByCode(String townCode) {
        database.requireWorkerThread();
        String normalizedName = townCode.strip().toLowerCase(java.util.Locale.ROOT);
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT t.town_id FROM towns t
                      JOIN territory_units u ON u.town_id = t.town_id AND u.grid_x = 0 AND u.grid_z = 0
                     WHERE lower(u.residence_name) = ?
                     ORDER BY (t.status = 'ARCHIVED'), t.reuse_blocked DESC, t.created_at DESC LIMIT 1
                    """)) {
                statement.setString(1, normalizedName);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                            ? TownPersistence.findTown(connection, readUuid(result, "town_id")) : Optional.empty();
                }
            }
        });
    }

    public Optional<TownSnapshot> findTownByMember(UUID playerId) {
        database.requireWorkerThread();
        return database.query(connection -> {
            Optional<UUID> townId = TownPersistence.memberTownId(connection, playerId);
            return townId.isPresent() ? TownPersistence.findTown(connection, townId.get()) : Optional.empty();
        });
    }

    public PlayerDashboard dashboard(UUID playerId) {
        database.requireWorkerThread();
        return database.query(connection -> {
            Optional<UUID> townId = TownPersistence.memberTownId(connection, playerId);
            Optional<TownSnapshot> town = townId.isPresent()
                    ? TownPersistence.findTown(connection, townId.get()) : Optional.empty();
            if (town.isPresent() && town.get().status() != TownStatus.ACTIVE) {
                town = Optional.empty();
            }
            Optional<ApplicationSnapshot> application;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT application_id FROM town_applications WHERE active_applicant = ? LIMIT 1
                    """)) {
                statement.setBytes(1, uuid(playerId));
                try (ResultSet result = statement.executeQuery()) {
                    application = result.next()
                            ? TownPersistence.findApplication(connection, readUuid(result, "application_id"))
                            : Optional.empty();
                }
            }
            List<JoinApplicationSnapshot> joinApplications = TownPersistence.listJoinApplicationsForPlayer(
                    connection, playerId);
            List<JoinApplicationSnapshot> incomingApplications = town.isPresent()
                    && TownPersistence.canReviewJoinApplications(connection, town.get().id(), playerId)
                    ? TownPersistence.listJoinApplicationsForTown(connection, town.get().id()) : List.of();
            return new PlayerDashboard(town.orElse(null), application.orElse(null),
                    joinApplications, incomingApplications);
        });
    }

    public List<TownSnapshot> listReservedTowns() {
        database.requireWorkerThread();
        return database.query(connection -> {
            List<TownSnapshot> towns = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT town_id FROM towns WHERE reuse_blocked = TRUE");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) TownPersistence.findTown(connection,
                        readUuid(result, "town_id")).ifPresent(towns::add);
            }
            return List.copyOf(towns);
        });
    }

    public List<TownSnapshot> listTowns(boolean includeArchived) {
        database.requireWorkerThread();
        return database.query(connection -> {
            List<TownSnapshot> towns = new ArrayList<>();
            String sql = includeArchived ? """
                    SELECT town_id FROM towns
                     ORDER BY (status = 'ARCHIVED'), reuse_blocked DESC, created_at DESC, town_id
                    """
                    : "SELECT town_id FROM towns WHERE status <> 'ARCHIVED' "
                    + "ORDER BY created_at, town_id";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    TownPersistence.findTown(connection, readUuid(result, "town_id")).ifPresent(towns::add);
                }
            }
            return List.copyOf(towns);
        });
    }

}

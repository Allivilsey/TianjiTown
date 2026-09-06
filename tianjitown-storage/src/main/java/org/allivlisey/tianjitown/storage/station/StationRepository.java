package org.allivlisey.tianjitown.storage.station;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class StationRepository {
    private final DataSource dataSource;
    private final BooleanSupplier forbiddenThread;

    public StationRepository(DataSource dataSource, BooleanSupplier forbiddenThread) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.forbiddenThread = Objects.requireNonNull(forbiddenThread);
    }

    public List<StationRecord> load() {
        requireWorkerThread();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT * FROM service_stations ORDER BY rowid");
             var rows = statement.executeQuery()) {
            List<StationRecord> records = new ArrayList<>();
            while (rows.next()) {
                records.add(new StationRecord(rows.getString("station_id"),
                        uuid(rows.getBytes("world_uuid")), rows.getString("world_name"),
                        rows.getInt("x"), rows.getInt("y"), rows.getInt("z"),
                        uuid(rows.getBytes("town_id")), rows.getString("town_name")));
            }
            return List.copyOf(records);
        } catch (SQLException exception) {
            throw new StorageUnavailableException(exception);
        }
    }

    public boolean insert(StationRecord station) {
        requireWorkerThread();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO service_stations
                         (station_id, world_uuid, world_name, x, y, z, town_id, town_name)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                     """)) {
            statement.setString(1, station.id());
            statement.setBytes(2, bytes(station.worldId()));
            statement.setString(3, station.worldName());
            statement.setInt(4, station.x());
            statement.setInt(5, station.y());
            statement.setInt(6, station.z());
            statement.setBytes(7, bytes(station.townId()));
            statement.setString(8, station.townName());
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new StorageUnavailableException(exception);
        }
    }

    public void deleteAt(UUID worldId, int x, int y, int z) {
        requireWorkerThread();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     DELETE FROM service_stations WHERE world_uuid = ? AND x = ? AND y = ? AND z = ?
                     """)) {
            statement.setBytes(1, bytes(worldId));
            statement.setInt(2, x);
            statement.setInt(3, y);
            statement.setInt(4, z);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new StorageUnavailableException(exception);
        }
    }

    private void requireWorkerThread() {
        if (forbiddenThread.getAsBoolean()) {
            throw new IllegalStateException("服务台数据库操作不能在服务器主线程执行");
        }
    }

    private static byte[] bytes(UUID value) {
        return value == null ? null : ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        if (value == null) return null;
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public static final class StorageUnavailableException extends RuntimeException {
        public StorageUnavailableException(SQLException cause) {
            super("服务台数据库操作失败: " + cause.getMessage(), cause);
        }
    }
}

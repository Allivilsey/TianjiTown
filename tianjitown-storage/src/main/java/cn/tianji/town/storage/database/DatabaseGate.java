package cn.tianji.town.storage.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseGate implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final Flyway flyway;

    public DatabaseGate(DatabaseConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("TianjiTown-MySQL");
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.maximumPoolSize());
        hikari.setMinimumIdle(config.minimumIdle());
        hikari.setConnectionTimeout(config.connectionTimeout().toMillis());
        hikari.setAutoCommit(false);
        hikari.setReadOnly(false);
        hikari.setLeakDetectionThreshold(30_000);
        dataSource = new HikariDataSource(hikari);
        flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .load();
    }

    public HealthResult verifyAndMigrate() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            if (!result.next() || result.getInt(1) != 1) {
                return HealthResult.failure("SELECT 1 返回异常");
            }
            connection.rollback();
        } catch (SQLException exception) {
            return HealthResult.failure(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        try {
            flyway.validate();
            flyway.migrate();
            MigrationInfo current = flyway.info().current();
            return HealthResult.success(current == null ? "empty" : current.getVersion().toString());
        } catch (RuntimeException exception) {
            return HealthResult.failure(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    public HikariDataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        dataSource.close();
    }

    public record HealthResult(boolean healthy, String detail) {
        public static HealthResult success(String schemaVersion) {
            return new HealthResult(true, "MySQL/Flyway 正常，schema=" + schemaVersion);
        }

        public static HealthResult failure(String detail) {
            return new HealthResult(false, detail);
        }
    }
}

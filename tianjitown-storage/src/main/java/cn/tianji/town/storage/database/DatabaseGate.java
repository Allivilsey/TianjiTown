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
        hikari.setPoolName("TianjiTown-SQLite");
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setDriverClassName("org.sqlite.JDBC");
        hikari.setMaximumPoolSize(1);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(config.connectionTimeout().toMillis());
        hikari.setAutoCommit(true);
        hikari.setReadOnly(false);
        hikari.setLeakDetectionThreshold(30_000);
        hikari.addDataSourceProperty("busy_timeout", config.busyTimeout().toMillis());
        hikari.addDataSourceProperty("foreign_keys", true);
        hikari.addDataSourceProperty("journal_mode", "WAL");
        hikari.addDataSourceProperty("synchronous", "NORMAL");
        hikari.addDataSourceProperty("date_class", "INTEGER");
        hikari.addDataSourceProperty("date_precision", "MILLISECONDS");
        hikari.addDataSourceProperty("recursive_triggers", false);
        dataSource = new HikariDataSource(hikari);
        flyway = Flyway.configure(DatabaseGate.class.getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .failOnMissingLocations(true)
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
        } catch (SQLException exception) {
            return HealthResult.failure(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        try {
            flyway.migrate();
            flyway.validate();
            MigrationInfo current = flyway.info().current();
            if (current == null) {
                return HealthResult.failure("未发现或执行任何 Flyway 迁移");
            }
            return HealthResult.success(current.getVersion().toString());
        } catch (RuntimeException exception) {
            return HealthResult.failure(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    public HikariDataSource dataSource() {
        return dataSource;
    }

    public boolean ping() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            boolean healthy = result.next() && result.getInt(1) == 1;
            return healthy;
        } catch (SQLException exception) {
            return false;
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    public record HealthResult(boolean healthy, String detail) {
        public static HealthResult success(String schemaVersion) {
            return new HealthResult(true, "SQLite/Flyway 正常，schema=" + schemaVersion);
        }

        public static HealthResult failure(String detail) {
            return new HealthResult(false, detail);
        }
    }
}

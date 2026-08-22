package cn.tianji.town.storage.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseGate implements AutoCloseable {
    private static final MigrationVersion SUPPORTED_SCHEMA_VERSION =
            MigrationVersion.fromVersion("7.0");
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
        hikari.addDataSourceProperty("transaction_mode", "IMMEDIATE");
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
            verifyMigrationHistory();
            flyway.migrate();
            flyway.validate();
            MigrationInfo current = flyway.info().current();
            if (current == null) {
                return HealthResult.failure("未发现或执行任何 Flyway 迁移");
            }
            if (!SUPPORTED_SCHEMA_VERSION.equals(current.getVersion())) {
                return HealthResult.failure("Flyway schema 版本不受支持: 当前="
                        + current.getVersion() + "，支持=" + SUPPORTED_SCHEMA_VERSION);
            }
            verifyWritable();
            return HealthResult.success(current.getVersion().toString());
        } catch (RuntimeException exception) {
            return HealthResult.failure(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private void verifyMigrationHistory() {
        for (MigrationInfo migration : flyway.info().all()) {
            if (migration.getState().isFailed()) {
                throw new IllegalStateException("检测到失败的 Flyway 迁移: version="
                        + migration.getVersion() + ", state=" + migration.getState());
            }
            MigrationVersion version = migration.getVersion();
            if (version != null && version.isNewerThan(SUPPORTED_SCHEMA_VERSION)) {
                throw new IllegalStateException("检测到高于当前插件支持范围的 Flyway 迁移: version="
                        + version + "，支持=" + SUPPORTED_SCHEMA_VERSION);
            }
        }
    }

    private void verifyWritable() {
        String probeTable = "__tianjitown_write_probe_"
                + java.util.UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.executeUpdate("CREATE TABLE " + probeTable
                        + " (probe_value INTEGER NOT NULL)");
                statement.executeUpdate("DROP TABLE " + probeTable);
            } finally {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("SQLite 文件不可写: " + exception.getMessage(),
                    exception);
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

    public String schemaVersion() {
        MigrationInfo current = flyway.info().current();
        return current == null ? "none" : current.getVersion().toString();
    }

    public void onlineBackup(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        try {
            Path parent = normalized.getParent();
            if (parent == null) {
                throw new IllegalArgumentException("备份目标必须包含目录");
            }
            Files.createDirectories(parent);
            if (Files.exists(normalized)) {
                throw new IllegalArgumentException("拒绝覆盖已有备份: " + normalized);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法准备 SQLite 备份目录: " + normalized, exception);
        }
        String escaped = normalized.toString().replace("'", "''");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("VACUUM main INTO '" + escaped + "'");
        } catch (SQLException exception) {
            try {
                Files.deleteIfExists(normalized);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw new IllegalStateException("SQLite 在线备份失败: " + exception.getMessage(),
                    exception);
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

package cn.tianji.town.storage.database;

import java.time.Duration;
import java.util.Objects;

public record DatabaseConfig(
        String jdbcUrl,
        String username,
        String password,
        int maximumPoolSize,
        int minimumIdle,
        Duration connectionTimeout
) {
    public DatabaseConfig {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(connectionTimeout, "connectionTimeout");
        if (!jdbcUrl.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("TianjiTown 仅允许 MySQL JDBC URL");
        }
        if (maximumPoolSize < 1 || maximumPoolSize > 20) {
            throw new IllegalArgumentException("maximumPoolSize 必须在 1..20");
        }
        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new IllegalArgumentException("minimumIdle 必须在 0..maximumPoolSize");
        }
    }
}


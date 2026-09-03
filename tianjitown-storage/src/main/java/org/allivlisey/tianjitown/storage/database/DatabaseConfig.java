package org.allivlisey.tianjitown.storage.database;

import java.time.Duration;
import java.util.Objects;

public record DatabaseConfig(
        String jdbcUrl,
        Duration connectionTimeout,
        Duration busyTimeout
) {
    public DatabaseConfig {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(connectionTimeout, "connectionTimeout");
        Objects.requireNonNull(busyTimeout, "busyTimeout");
        if (!jdbcUrl.startsWith("jdbc:sqlite:")) {
            throw new IllegalArgumentException("TianjiTown 仅允许 SQLite JDBC URL");
        }
        if (connectionTimeout.isNegative() || connectionTimeout.isZero()) {
            throw new IllegalArgumentException("connectionTimeout 必须大于 0");
        }
        if (busyTimeout.isNegative() || busyTimeout.isZero()) {
            throw new IllegalArgumentException("busyTimeout 必须大于 0");
        }
    }
}

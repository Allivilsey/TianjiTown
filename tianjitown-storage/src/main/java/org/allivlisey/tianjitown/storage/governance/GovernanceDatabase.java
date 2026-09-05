package org.allivlisey.tianjitown.storage.governance;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.allivlisey.tianjitown.storage.governance.GovernanceRepository.ConflictException;
import org.allivlisey.tianjitown.storage.governance.GovernanceRepository.StorageUnavailableException;

/** Owns connections, thread checks and SQLite transaction boundaries. */
final class GovernanceDatabase {
    private final DataSource dataSource;
    private final BooleanSupplier forbiddenThread;

    GovernanceDatabase(DataSource dataSource, BooleanSupplier forbiddenThread) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.forbiddenThread = Objects.requireNonNull(forbiddenThread, "forbiddenThread");
    }

    void requireWorkerThread() {
        if (forbiddenThread.getAsBoolean()) {
            throw new IllegalStateException("SQLite 业务访问不得在 Paper 主线程执行");
        }
    }

    <T> T query(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            return work.run(connection);
        } catch (SQLException exception) {
            throw translate(exception);
        }
    }

    <T> T transaction(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean begun = false;
            try {
                executeTransactionCommand(connection, "BEGIN IMMEDIATE");
                begun = true;
                T result = work.run(connection);
                executeTransactionCommand(connection, "COMMIT");
                begun = false;
                return result;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, begun, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw translate(exception);
        }
    }

    private static void rollback(Connection connection, boolean begun, Throwable failure) {
        if (!begun) {
            return;
        }
        try {
            executeTransactionCommand(connection, "ROLLBACK");
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void executeTransactionCommand(Connection connection, String command)
            throws SQLException {
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute(command);
        }
    }

    private static RuntimeException translate(SQLException exception) {
        String message = exception.getMessage() == null ? "SQLite 操作失败" : exception.getMessage();
        if (message.contains("UNIQUE constraint failed")) {
            return new ConflictException("操作与现有治理状态冲突，请重新读取后再试", exception);
        }
        return new StorageUnavailableException("SQLite 治理操作失败: " + message, exception);
    }

    @FunctionalInterface
    interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}

package org.allivlisey.tianjitown.storage.commerce;

import org.allivlisey.tianjitown.storage.commerce.CommerceRepository.ConflictException;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository.StorageUnavailableException;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Owns connections, worker-thread checks and SQLite transaction boundaries. */
final class CommerceDatabase {
    private final DataSource dataSource;
    private final BooleanSupplier forbiddenThread;

    CommerceDatabase(DataSource dataSource, BooleanSupplier forbiddenThread) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.forbiddenThread = Objects.requireNonNull(forbiddenThread, "forbiddenThread");
    }

    void requireWorkerThread() {
        if (forbiddenThread.getAsBoolean()) {
            throw new IllegalStateException("禁止在 Paper 主线程执行数据库 I/O");
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
        if (exception instanceof SQLIntegrityConstraintViolationException
                || "23000".equals(exception.getSQLState()) || exception.getErrorCode() == 19) {
            return new ConflictException("数据已被其他操作占用，请刷新后重试", exception);
        }
        return new StorageUnavailableException("SQLite 操作失败: " + exception.getMessage(),
                exception);
    }

    @FunctionalInterface
    interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}

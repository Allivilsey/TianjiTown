package org.allivlisey.tianjitown.storage.economy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.allivlisey.tianjitown.storage.economy.EconomyPersistence.AccountState;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.ConflictException;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.EconomyOperation;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.LedgerMutation;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.Reconciliation;

/** External economy operation lifecycle and settlement reconciliation. */
final class EconomyOperationStore {
    private final EconomyDatabase database;

    EconomyOperationStore(EconomyDatabase database) {
        this.database = database;
    }

    private static final String COMPENSATION_LOCK = "ECONOMY_COMPENSATION:";

    private static final String RECONCILIATION_LOCK = "SETTLEMENT_RECONCILIATION:";

    EconomyOperation prepareOperation(UUID townId, String operationType, long amountMinor,
                                             UUID actorId, String actorName, String businessKey,
                                             String note) {
        database.requireWorkerThread();
        Objects.requireNonNull(operationType, "operationType");
        if (amountMinor == 0) {
            throw new IllegalArgumentException("操作金额不能为 0");
        }
        if (!"DONATION".equals(operationType) && !"ADMIN_ADJUSTMENT".equals(operationType)) {
            throw new IllegalArgumentException("不支持的经济操作类型");
        }
        if ("DONATION".equals(operationType) && actorId == null) {
            throw new IllegalArgumentException("捐款操作必须带玩家身份");
        }
        return database.transaction(connection -> {
            Optional<EconomyOperation> existing = findOperation(connection, businessKey);
            if (existing.isPresent()) {
                return existing.get();
            }
            AccountState account = EconomyPersistence.requireAccount(connection, townId);
            if (amountMinor < 0) {
                EconomyPersistence.requireUnlocked(account);
                if (Math.addExact(account.balanceMinor(), amountMinor) < 0) {
                    throw new ConflictException("小镇余额不足");
                }
            }
            UUID operationId = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO economy_operations
                        (operation_id, town_id, operation_type, business_key, amount_minor,
                         actor_uuid, actor_name, note, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED')
                    """)) {
                statement.setBytes(1, EconomyPersistence.uuid(operationId));
                statement.setBytes(2, EconomyPersistence.uuid(townId));
                statement.setString(3, operationType);
                statement.setString(4, businessKey);
                statement.setLong(5, amountMinor);
                statement.setBytes(6, actorId == null ? null : EconomyPersistence.uuid(actorId));
                statement.setString(7, actorName);
                statement.setString(8, note == null ? "" : note);
                statement.executeUpdate();
            }
            return requireOperation(connection, operationId);
        });
    }

    EconomyOperation markOperationExternalApplied(UUID operationId) {
        database.requireWorkerThread();
        return updateOperation(operationId, "PREPARED", "EXTERNAL_APPLIED", null);
    }

    LedgerMutation completeOperation(UUID operationId) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            EconomyOperation operation = requireOperation(connection, operationId);
            Optional<LedgerMutation> existing = EconomyPersistence.findLedgerByBusinessKey(connection,
                    operation.businessKey());
            if (existing.isPresent()) {
                setOperationStatus(connection, operationId, "COMPLETED", null);
                return existing.get();
            }
            if (!operation.status().equals("EXTERNAL_APPLIED")) {
                throw new ConflictException("外部资金尚未完成，不能写入账本");
            }
            LedgerMutation mutation = EconomyPersistence.postLedger(connection, operation.townId(),
                    operation.operationType(), operation.amountMinor(), operation.actorId(),
                    operation.actorName(), operation.businessKey(), operation.note(),
                    operation.operationType().equals("ADMIN_ADJUSTMENT"));
            setOperationStatus(connection, operationId, "COMPLETED", null);
            return mutation;
        });
    }

    EconomyOperation requireCompensation(UUID operationId, String error) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            EconomyOperation current = requireOperation(connection, operationId);
            if (current.status().equals("COMPLETED")) {
                throw new ConflictException("已完成操作不能标记为待补偿");
            }
            setOperationStatus(connection, operationId, "COMPENSATION_REQUIRED", EconomyPersistence.safe(error));
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE town_accounts
                       SET locked = 1, lock_reason = ?, version = version + 1
                     WHERE town_id = ?
                    """)) {
                statement.setString(1, COMPENSATION_LOCK + " " + operationId + " " + EconomyPersistence.safe(error));
                statement.setBytes(2, EconomyPersistence.uuid(current.townId()));
                EconomyPersistence.requireUpdated(statement, "小镇账户不存在");
            }
            return requireOperation(connection, operationId);
        });
    }

    EconomyOperation cancelOperation(UUID operationId, String error) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            EconomyOperation current = requireOperation(connection, operationId);
            if (current.status().equals("COMPLETED")) {
                throw new ConflictException("已完成操作不能取消");
            }
            setOperationStatus(connection, operationId, "CANCELLED", EconomyPersistence.safe(error));
            return requireOperation(connection, operationId);
        });
    }

    EconomyOperation resolveCompensation(UUID operationId, String detail) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            EconomyOperation current = requireOperation(connection, operationId);
            if (current.status().equals("CANCELLED")) {
                return current;
            }
            if (!current.status().equals("COMPENSATION_REQUIRED")) {
                throw new ConflictException("只有待补偿操作可以完成自动补偿");
            }
            setOperationStatus(connection, operationId, "CANCELLED", EconomyPersistence.safe(detail));
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE town_accounts
                       SET locked = 1, lock_reason = ?, version = version + 1
                     WHERE town_id = ? AND lock_reason LIKE ?
                       AND NOT EXISTS (
                           SELECT 1 FROM economy_operations
                            WHERE town_id = ? AND status = 'COMPENSATION_REQUIRED'
                       )
                    """)) {
                statement.setString(1, RECONCILIATION_LOCK
                        + " 自动补偿完成，等待清算余额复核");
                statement.setBytes(2, EconomyPersistence.uuid(current.townId()));
                statement.setString(3, COMPENSATION_LOCK + "%");
                statement.setBytes(4, EconomyPersistence.uuid(current.townId()));
                statement.executeUpdate();
            }
            return requireOperation(connection, operationId);
        });
    }

    List<EconomyOperation> pendingOperations() {
        database.requireWorkerThread();
        return database.query(connection -> {
            List<EconomyOperation> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM economy_operations
                     WHERE status NOT IN ('COMPLETED', 'CANCELLED')
                     ORDER BY created_at, operation_id
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(readOperation(rows));
                }
            }
            return List.copyOf(result);
        });
    }

    Reconciliation reconcileSettlement(long externalBalanceMinor) {
        database.requireWorkerThread();
        if (externalBalanceMinor < 0) {
            throw new IllegalArgumentException("结算账户余额不能小于 0");
        }
        return database.transaction(connection -> {
            long internal;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COALESCE(SUM(balance_minor), 0) AS total FROM town_accounts");
                 ResultSet row = statement.executeQuery()) {
                row.next();
                internal = row.getLong("total");
            }
            long pending;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COALESCE(SUM(amount_minor), 0) AS total
                      FROM economy_operations WHERE status = 'EXTERNAL_APPLIED'
                    """); ResultSet row = statement.executeQuery()) {
                row.next();
                pending = row.getLong("total");
            }
            long required = Math.max(0, Math.addExact(internal, pending));
            boolean healthy = externalBalanceMinor >= required;
            String reason = healthy ? null : RECONCILIATION_LOCK
                    + " 外部=" + externalBalanceMinor + ", 应有=" + required;
            try (PreparedStatement statement = connection.prepareStatement(healthy ? """
                    UPDATE town_accounts SET locked = 0, lock_reason = NULL, version = version + 1
                     WHERE locked = 1 AND lock_reason LIKE 'SETTLEMENT_RECONCILIATION:%'
                    """ : """
                    UPDATE town_accounts SET locked = 1, lock_reason = ?, version = version + 1
                    """)) {
                if (!healthy) {
                    statement.setString(1, reason);
                }
                statement.executeUpdate();
            }
            return new Reconciliation(externalBalanceMinor, internal, pending, required, healthy);
        });
    }

    Reconciliation inspectSettlement(long externalBalanceMinor) {
        database.requireWorkerThread();
        if (externalBalanceMinor < 0) {
            throw new IllegalArgumentException("结算账户余额不能小于 0");
        }
        return database.query(connection -> settlementSnapshot(connection, externalBalanceMinor));
    }

    private static Reconciliation settlementSnapshot(Connection connection,
                                                      long externalBalanceMinor)
            throws SQLException {
        long internal;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(SUM(balance_minor), 0) AS total FROM town_accounts");
             ResultSet row = statement.executeQuery()) {
            row.next();
            internal = row.getLong("total");
        }
        long pending;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(amount_minor), 0) AS total
                  FROM economy_operations WHERE status = 'EXTERNAL_APPLIED'
                """); ResultSet row = statement.executeQuery()) {
            row.next();
            pending = row.getLong("total");
        }
        long required = Math.max(0, Math.addExact(internal, pending));
        return new Reconciliation(externalBalanceMinor, internal, pending, required,
                externalBalanceMinor >= required);
    }

    private EconomyOperation updateOperation(UUID operationId, String expected, String status,
                                             String error) {
        return database.transaction(connection -> {
            EconomyOperation current = requireOperation(connection, operationId);
            if (current.status().equals(status)) {
                return current;
            }
            if (!current.status().equals(expected)) {
                throw new ConflictException("经济操作状态已变化");
            }
            setOperationStatus(connection, operationId, status, error);
            return requireOperation(connection, operationId);
        });
    }

    private static Optional<EconomyOperation> findOperation(Connection connection,
                                                            String businessKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM economy_operations WHERE business_key = ?")) {
            statement.setString(1, businessKey);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(readOperation(row)) : Optional.empty();
            }
        }
    }

    private static EconomyOperation requireOperation(Connection connection, UUID operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM economy_operations WHERE operation_id = ?")) {
            statement.setBytes(1, EconomyPersistence.uuid(operationId));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ConflictException("经济操作不存在");
                }
                return readOperation(row);
            }
        }
    }

    private static void setOperationStatus(Connection connection, UUID operationId, String status,
                                           String error) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE economy_operations SET status = ?, last_error = ? WHERE operation_id = ?
                """)) {
            statement.setString(1, status);
            statement.setString(2, error);
            statement.setBytes(3, EconomyPersistence.uuid(operationId));
            EconomyPersistence.requireUpdated(statement, "经济操作不存在");
        }
    }

    private static EconomyOperation readOperation(ResultSet row) throws SQLException {
        byte[] actor = row.getBytes("actor_uuid");
        return new EconomyOperation(EconomyPersistence.readUuid(row, "operation_id"), EconomyPersistence.readUuid(row, "town_id"),
                row.getString("operation_type"), row.getString("business_key"),
                row.getLong("amount_minor"), actor == null ? null : EconomyPersistence.uuid(actor),
                row.getString("actor_name"), row.getString("note"), row.getString("status"),
                row.getString("last_error"), EconomyPersistence.instant(row, "created_at"));
    }
}

package org.allivlisey.tianjitown.storage.governance;

import org.allivlisey.tianjitown.core.town.MemberRole;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.allivlisey.tianjitown.storage.governance.GovernanceRepository.ConflictException;
import static org.allivlisey.tianjitown.storage.governance.GovernanceSqlValues.requireUpdated;
import static org.allivlisey.tianjitown.storage.governance.GovernanceSqlValues.uuid;
import static org.allivlisey.tianjitown.storage.governance.GovernanceSqlValues.readUuid;
import static org.allivlisey.tianjitown.storage.governance.GovernanceSqlValues.instant;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.performMayorTransfer;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.requireActiveTown;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.requireRole;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.memberExists;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.audit;
import static org.allivlisey.tianjitown.storage.governance.GovernancePersistence.shiftedInstant;

/** Mayor transfer requests and decisions. */
final class MayorTransferStore {
    private final GovernanceDatabase database;

    MayorTransferStore(GovernanceDatabase database) {
        this.database = database;
    }

    TransferSnapshot requestMayorTransfer(UUID townId, UUID candidateId, UUID mayorId,
                                                 Duration lifetime) {
        database.requireWorkerThread();
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("镇长转让确认有效期必须大于 0");
        }
        Instant expiresAt = shiftedInstant(Instant.now(), lifetime, true,
                "镇长转让确认时间配置无效");
        return database.transaction(connection -> {
            expireTransfers(connection);
            requireRole(connection, townId, mayorId, MemberRole.MAYOR);
            if (mayorId.equals(candidateId) || !memberExists(connection, townId, candidateId)) {
                throw new ConflictException("候选人必须是本镇其他成员");
            }
            UUID transferId = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO mayor_transfer_requests
                        (transfer_id, town_id, requested_by, candidate_uuid, status, expires_at)
                    VALUES (?, ?, ?, ?, 'PENDING', ?)
                    """)) {
                statement.setBytes(1, uuid(transferId));
                statement.setBytes(2, uuid(townId));
                statement.setBytes(3, uuid(mayorId));
                statement.setBytes(4, uuid(candidateId));
                statement.setLong(5, expiresAt.toEpochMilli());
                statement.executeUpdate();
            }
            audit(connection, mayorId, mayorId.toString(), "MAYOR_TRANSFER_REQUEST", townId,
                    "镇长发起双方确认转让", candidateId.toString());
            return requireTransfer(connection, transferId);
        });
    }

    TransferSnapshot decideMayorTransfer(UUID transferId, UUID candidateId, boolean accept) {
        database.requireWorkerThread();
        database.transaction(connection -> {
            expireTransfers(connection);
            return null;
        });
        return database.transaction(connection -> {
            TransferSnapshot transfer = requireTransfer(connection, transferId);
            if (!"PENDING".equals(transfer.status()) || !candidateId.equals(transfer.candidateId())) {
                throw new ConflictException("转让请求已失效或不属于你");
            }
            if (accept) {
                requireActiveTown(connection, transfer.townId());
                requireRole(connection, transfer.townId(), transfer.requestedBy(), MemberRole.MAYOR);
                if (!memberExists(connection, transfer.townId(), candidateId)) {
                    throw new ConflictException("候选人已不属于该小镇");
                }
            }
            String status = accept ? "ACCEPTED" : "REJECTED";
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE mayor_transfer_requests SET status = ?, decided_at = ?
                     WHERE transfer_id = ? AND status = 'PENDING'
                    """)) {
                statement.setString(1, status);
                statement.setLong(2, Instant.now().toEpochMilli());
                statement.setBytes(3, uuid(transferId));
                requireUpdated(statement, "转让请求已被处理");
            }
            if (accept) performMayorTransfer(connection, transfer.townId(), transfer.requestedBy(), candidateId);
            audit(connection, candidateId, candidateId.toString(),
                    accept ? "MAYOR_TRANSFER_ACCEPT" : "MAYOR_TRANSFER_REJECT",
                    transfer.townId(), accept ? "候选成员接受镇长转让" : "候选成员拒绝镇长转让",
                    transferId.toString());
            return requireTransfer(connection, transferId);
        });
    }

    static Optional<TransferSnapshot> findPendingTransfer(Connection connection, UUID candidateId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM mayor_transfer_requests
                 WHERE candidate_uuid = ? AND status = 'PENDING' AND expires_at > ?
                   AND EXISTS (SELECT 1 FROM town_members m JOIN towns t ON t.town_id = m.town_id
                       WHERE m.town_id = mayor_transfer_requests.town_id
                         AND m.player_uuid = requested_by AND m.role = 'MAYOR' AND t.status = 'ACTIVE')
                   AND EXISTS (SELECT 1 FROM town_members m
                       WHERE m.town_id = mayor_transfer_requests.town_id
                         AND m.player_uuid = candidate_uuid AND m.role <> 'MAYOR')
                 ORDER BY created_at DESC LIMIT 1
                """)) {
            statement.setBytes(1, uuid(candidateId));
            statement.setLong(2, Instant.now().toEpochMilli());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readTransfer(result)) : Optional.empty();
            }
        }
    }

    private TransferSnapshot requireTransfer(Connection connection, UUID transferId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM mayor_transfer_requests WHERE transfer_id = ?")) {
            statement.setBytes(1, uuid(transferId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ConflictException("镇长转让请求不存在");
                }
                return readTransfer(result);
            }
        }
    }

    private static TransferSnapshot readTransfer(ResultSet result) throws SQLException {
        return new TransferSnapshot(readUuid(result, "transfer_id"),
                readUuid(result, "town_id"), readUuid(result, "requested_by"),
                readUuid(result, "candidate_uuid"), result.getString("status"),
                instant(result, "expires_at"), instant(result, "created_at"));
    }

    static void expireTransfers(Connection connection) throws SQLException {
        MayorTransferValidity.cancelInvalid(connection);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE mayor_transfer_requests SET status = 'EXPIRED', decided_at = ?
                 WHERE status = 'PENDING' AND expires_at <= ?
                """)) {
            long now = Instant.now().toEpochMilli();
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.executeUpdate();
        }
    }
}

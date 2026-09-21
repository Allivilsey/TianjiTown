package org.allivlisey.tianjitown.storage.town;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.allivlisey.tianjitown.core.application.ApplicationActor;
import org.allivlisey.tianjitown.core.application.ApplicationStatus;
import org.allivlisey.tianjitown.core.application.ApplicationWorkflow;
import org.allivlisey.tianjitown.storage.town.ApplicationFeeOperation.*;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.*;

final class TownApplicationFeeStore {
    private final TownDatabase database;

    TownApplicationFeeStore(TownDatabase database) { this.database = database; }

    ApplicationFeeOperation find(UUID id) {
        database.requireWorkerThread();
        return database.query(connection -> read(connection, TownPersistence.requireApplication(connection, id)));
    }

    List<ApplicationFeeOperation> pending(int limit) {
        database.requireWorkerThread();
        return database.query(connection -> {
            List<ApplicationFeeOperation> result = new ArrayList<>();
            try (var statement = connection.prepareStatement("""
                    SELECT a.application_id FROM town_applications a
                    LEFT JOIN application_fee_operations f ON f.application_id = a.application_id
                    WHERE f.state IN ('COLLECTING', 'COLLECTION_UNKNOWN', 'PLAYER_REFUND_PENDING',
                          'PLAYER_REFUNDING', 'PLAYER_REFUND_UNKNOWN', 'REFUND_PENDING', 'REFUNDING', 'REFUND_UNKNOWN')
                       OR a.application_fee_status = 'REFUND_PENDING'
                       OR (a.town_id IS NULL AND a.application_fee_status = 'ESCROWED')
                    ORDER BY COALESCE(f.updated_at, a.updated_at) LIMIT ?
                    """)) {
                statement.setInt(1, Math.max(1, Math.min(limit, 1000)));
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) result.add(read(connection,
                            TownPersistence.requireApplication(connection, readUuid(rows, "application_id"))));
                }
            }
            return List.copyOf(result);
        });
    }

    ApplicationFeeOperation claimCollection(UUID id, long expectedApplicationVersion, long amount,
                                            UUID actor, String actorName) {
        database.requireWorkerThread();
        if (amount <= 0) throw new IllegalArgumentException("申请费必须大于 0");
        return database.transaction(connection -> {
            var application = TownPersistence.requireApplication(connection, id);
            if (application.version() != expectedApplicationVersion) throw conflict("申请已变更，请刷新后重试");
            ApplicationWorkflow.requireAllowed(application.status(), ApplicationStatus.APPROVED_PROVISIONING,
                    ApplicationActor.ADMINISTRATOR);
            var current = read(connection, application);
            if (current.state() != State.UNPAID && current.state() != State.REFUNDED) {
                throw conflict("该申请已有资金操作：" + current.state() + "；请查询申请费记录并处理，禁止重复扣款");
            }
            return save(connection, application, current, amount, State.COLLECTING, actor, actorName,
                    "收取申请费前持久化认领");
        });
    }

    ApplicationFeeOperation claimRefund(UUID id, UUID actor, String actorName, long expectedVersion) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            var application = TownPersistence.requireApplication(connection, id);
            var current = read(connection, application);
            if (expectedVersion != Long.MIN_VALUE && current.version() != expectedVersion)
                throw conflict("资金记录已变更，请重新查询后确认退款");
            if (current.state() == State.PLAYER_REFUND_PENDING) {
                return save(connection, application, current, current.amountMinor(), State.PLAYER_REFUNDING,
                        actor, actorName, "退款给已扣款但清算未入账的申请人");
            }
            if (application.townId() != null || (current.state() != State.REFUND_PENDING
                    && current.state() != State.ESCROWED)) {
                throw conflict("申请费当前不可安全退款：" + current.state() + "；结果不明需先核实，已有小镇需先完成建镇恢复清理");
            }
            return save(connection, application, current, current.amountMinor(), State.REFUNDING,
                    actor, actorName, "从清算账户退还托管申请费前持久化认领");
        });
    }

    ApplicationFeeOperation complete(ApplicationFeeOperation claim, Outcome outcome, String detail,
                                     UUID actor, String actorName) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            var application = TownPersistence.requireApplication(connection, claim.applicationId());
            var current = read(connection, application);
            if (current.version() != claim.version() || current.state() != claim.state()) {
                throw conflict("申请费操作已被其他处理更新，拒绝覆盖");
            }
            State target = switch (claim.state()) {
                case COLLECTING -> switch (outcome) {
                    case SUCCESS -> State.ESCROWED;
                    case FAILED -> State.UNPAID;
                    case PLAYER_REFUND_REQUIRED -> State.PLAYER_REFUND_PENDING;
                    case UNKNOWN -> State.COLLECTION_UNKNOWN;
                };
                case PLAYER_REFUNDING -> switch (outcome) {
                    case SUCCESS -> State.UNPAID;
                    case FAILED -> State.PLAYER_REFUND_PENDING;
                    default -> State.PLAYER_REFUND_UNKNOWN;
                };
                case REFUNDING -> switch (outcome) {
                    case SUCCESS -> State.REFUNDED;
                    case FAILED -> State.REFUND_PENDING;
                    default -> State.REFUND_UNKNOWN;
                };
                default -> throw conflict("该记录不是待完成的外部付款操作");
            };
            return save(connection, application, current, current.amountMinor(), target, actor, actorName, detail);
        });
    }

    ApplicationFeeOperation resolve(UUID id, long expectedVersion, Resolution resolution,
                                    UUID actor, String actorName, String reason) {
        database.requireWorkerThread();
        TownPersistence.requireReason(reason);
        return database.transaction(connection -> {
            var application = TownPersistence.requireApplication(connection, id);
            var current = read(connection, application);
            if (current.version() != expectedVersion) throw conflict("资金记录已变更，请重新查询后核实");
            State target;
            switch (current.state()) {
                case COLLECTING, COLLECTION_UNKNOWN -> target = switch (resolution) {
                    case COLLECTED -> State.ESCROWED;
                    case NO_PAYMENT -> State.UNPAID;
                    case PLAYER_DEBIT_ONLY -> State.PLAYER_REFUND_PENDING;
                    default -> throw conflict("收款待核实仅接受 COLLECTED、NO_PAYMENT 或 PLAYER_DEBIT_ONLY");
                };
                case PLAYER_REFUNDING, PLAYER_REFUND_UNKNOWN -> target = switch (resolution) {
                    case REFUNDED -> State.UNPAID;
                    case REFUND_NOT_PAID -> State.PLAYER_REFUND_PENDING;
                    default -> throw conflict("玩家退款待核实仅接受 REFUNDED 或 REFUND_NOT_PAID");
                };
                case REFUNDING, REFUND_UNKNOWN -> target = switch (resolution) {
                    case REFUNDED -> State.REFUNDED;
                    case REFUND_NOT_PAID -> State.REFUND_PENDING;
                    default -> throw conflict("托管退款待核实仅接受 REFUNDED 或 REFUND_NOT_PAID");
                };
                default -> throw conflict("该操作结果已明确，无需人工确认");
            }
            return save(connection, application, current, current.amountMinor(), target, actor, actorName,
                    "管理员核实 " + resolution + "：" + reason);
        });
    }

    static ApplicationFeeOperation read(Connection connection, ApplicationSnapshot application) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM application_fee_operations WHERE application_id = ?")) {
            statement.setBytes(1, uuid(application.id()));
            try (var row = statement.executeQuery()) {
                if (row.next()) {
                    State state = State.valueOf(row.getString("state"));
                    // Recovery transitions application + town in one transaction. Preserve that authoritative intent.
                    if (state == State.ESCROWED && application.applicationFeeStatus() == ApplicationSnapshot.FeeStatus.REFUND_PENDING)
                        state = State.REFUND_PENDING;
                    if (state == State.ESCROWED && application.applicationFeeStatus() == ApplicationSnapshot.FeeStatus.REFUNDED)
                        state = State.REFUNDED;
                    return new ApplicationFeeOperation(application.id(), application.applicantId(),
                            row.getLong("amount_minor"), state, row.getString("detail"), row.getLong("version"));
                }
            }
        }
        State state = switch (application.applicationFeeStatus()) {
            case UNPAID -> State.UNPAID;
            case ESCROWED, CONSUMED -> State.ESCROWED;
            case REFUND_PENDING -> State.REFUND_PENDING;
            case REFUNDED -> State.REFUNDED;
        };
        return new ApplicationFeeOperation(application.id(), application.applicantId(),
                application.applicationFeeMinor(), state, "现有申请费用状态", -1);
    }

    private ApplicationFeeOperation save(Connection connection, ApplicationSnapshot application,
            ApplicationFeeOperation old, long amount, State target, UUID actor, String actorName,
            String detail) throws SQLException {
        String safeDetail = detail == null ? "" : detail.substring(0, Math.min(2000, detail.length()));
        try (var statement = connection.prepareStatement("""
                INSERT INTO application_fee_operations(application_id, amount_minor, state, detail, version)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(application_id) DO UPDATE SET amount_minor=excluded.amount_minor,
                    state=excluded.state, detail=excluded.detail, version=excluded.version,
                    updated_at=CAST(unixepoch('subsec') * 1000 AS INTEGER)
                """)) {
            statement.setBytes(1, uuid(application.id()));
            statement.setLong(2, amount);
            statement.setString(3, target.name());
            statement.setString(4, safeDetail);
            statement.setLong(5, old.version() + 1);
            statement.executeUpdate();
        }
        ApplicationSnapshot.FeeStatus feeStatus = switch (target) {
            case ESCROWED -> ApplicationSnapshot.FeeStatus.ESCROWED;
            case REFUND_PENDING, REFUNDING, REFUND_UNKNOWN -> ApplicationSnapshot.FeeStatus.REFUND_PENDING;
            case REFUNDED -> ApplicationSnapshot.FeeStatus.REFUNDED;
            default -> ApplicationSnapshot.FeeStatus.UNPAID;
        };
        try (var statement = connection.prepareStatement("""
                UPDATE town_applications SET application_fee_minor=?, application_fee_status=?, version=version+1
                WHERE application_id=?
                """)) {
            statement.setLong(1, feeStatus == ApplicationSnapshot.FeeStatus.UNPAID ? 0 : amount);
            statement.setString(2, feeStatus.name());
            statement.setBytes(3, uuid(application.id()));
            TownPersistence.requireUpdated(statement, "申请费状态写入失败");
        }
        TownPersistence.audit(connection, null, actor, actorName, "APPLICATION_FEE_" + target.name(),
                "APPLICATION", application.id().toString(), safeDetail,
                old.state() + " -> " + target + "; amount_minor=" + amount + "; version=" + (old.version() + 1));
        return new ApplicationFeeOperation(application.id(), application.applicantId(), amount,
                target, safeDetail, old.version() + 1);
    }

    private static TownRepository.ConflictException conflict(String detail) {
        return new TownRepository.ConflictException(detail);
    }
}

package org.allivlisey.tianjitown.storage.economy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.ConflictException;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.ExternalIncomeTax;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.IncomeTaxCollection;

/** Durable claims for player-to-settlement income tax payments. No method executes external money. */
final class IncomeTaxCollectionStore {
    private final EconomyDatabase database;

    IncomeTaxCollectionStore(EconomyDatabase database) { this.database = database; }

    IncomeTaxCollection prepareIncomeTaxCollection(ExternalIncomeTax rawTax) {
        database.requireWorkerThread();
        ExternalIncomeTax tax = validate(rawTax);
        return database.transaction(connection -> {
            Optional<IncomeTaxCollection> existing = findByBusinessKey(connection, tax.businessKey());
            if (existing.isPresent()) {
                if (!existing.get().tax().equals(tax)) throw conflict("税款业务键已用于另一笔收入，拒绝复用");
                return existing.get();
            }
            if (!eligible(connection, tax)) throw conflict("收入所属小镇已停用或玩家已离开，取消收税");
            UUID operationId = UUID.randomUUID();
            try (var statement = connection.prepareStatement("""
                    INSERT INTO income_tax_collections
                        (operation_id, town_id, business_key, source, receiver_uuid, receiver_name,
                         gross_minor, tax_rate_bps, tax_minor, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED')
                    """)) {
                statement.setBytes(1, EconomyPersistence.uuid(operationId));
                statement.setBytes(2, EconomyPersistence.uuid(tax.townId()));
                statement.setString(3, tax.businessKey());
                statement.setString(4, tax.source());
                statement.setBytes(5, EconomyPersistence.uuid(tax.receiverId()));
                statement.setString(6, tax.receiverName());
                statement.setLong(7, tax.grossMinor());
                statement.setInt(8, tax.taxRateBps());
                statement.setLong(9, tax.taxMinor());
                statement.executeUpdate();
            }
            return require(connection, operationId);
        });
    }

    IncomeTaxCollection claimIncomeTaxCollection(IncomeTaxCollection expected) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            var current = requireExpected(connection, expected);
            if (!current.status().equals("PREPARED")) throw conflict("该税款已认领或处理，禁止重复收款");
            if (!eligible(connection, current.tax())) {
                return transition(connection, current, "FAILED", "付款前复核失败：小镇已停用或玩家已离开；未执行收款");
            }
            return transition(connection, current, "ATTEMPTED", "已持久化收款认领；外部结果尚待确认");
        });
    }

    IncomeTaxCollection finishIncomeTaxCollection(IncomeTaxCollection attempted, String status, String detail) {
        database.requireWorkerThread();
        if (!Set.of("SUCCEEDED", "FAILED", "REFUND_REQUIRED", "AMBIGUOUS").contains(status))
            throw new IllegalArgumentException("未知税款收取结果");
        return finish(attempted, "ATTEMPTED", status, detail);
    }

    IncomeTaxCollection claimIncomeTaxRefund(IncomeTaxCollection expected) {
        return claimIncomeTaxRefund(expected, null, "SYSTEM");
    }

    IncomeTaxCollection claimIncomeTaxRefund(IncomeTaxCollection expected, UUID actor, String actorName) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            var current = requireExpected(connection, expected);
            if (!current.status().equals("REFUND_REQUIRED"))
                throw conflict("该税款不在明确欠退玩家状态，结果不明须先核实");
            var attempted = transition(connection, current, "REFUND_ATTEMPTED", "已认领直接返还玩家扣款；不重复扣减清算账户");
            try (var statement = connection.prepareStatement("""
                    INSERT INTO audit_logs (actor_uuid, actor_name, action, target_type, target_id, reason, detail)
                    VALUES (?, ?, 'INCOME_TAX_REFUND', 'INCOME_TAX_COLLECTION', ?, ?, ?)
                    """)) {
                statement.setBytes(1, actor == null ? null : EconomyPersistence.uuid(actor));
                statement.setString(2, actorName == null ? "SYSTEM" : actorName);
                statement.setString(3, current.operationId().toString());
                statement.setString(4, "重试返还明确欠退玩家的收入税扣款");
                statement.setString(5, "REFUND_REQUIRED -> REFUND_ATTEMPTED; receiver="
                        + current.tax().receiverId() + "; tax_minor=" + current.tax().taxMinor()
                        + "; version=" + attempted.version());
                statement.executeUpdate();
            }
            return attempted;
        });
    }

    IncomeTaxCollection finishIncomeTaxRefund(IncomeTaxCollection attempted, String status, String detail) {
        database.requireWorkerThread();
        if (!Set.of("REFUNDED", "REFUND_REQUIRED", "AMBIGUOUS").contains(status))
            throw new IllegalArgumentException("未知税款退款结果");
        return finish(attempted, "REFUND_ATTEMPTED", status, detail);
    }

    private IncomeTaxCollection finish(IncomeTaxCollection attempted, String expectedStatus, String status, String detail) {
        Objects.requireNonNull(attempted, "attempted");
        if (!attempted.status().equals(expectedStatus)) throw conflict("付款结果缺少有效的持久化认领");
        return database.transaction(connection -> {
            var current = require(connection, attempted.operationId());
            // Retry only the same persisted result, never another external payment or a stale outcome.
            if (current.version() == attempted.version() + 1 && current.status().equals(status)
                    && current.tax().equals(attempted.tax())) return current;
            requireSameSnapshot(current, attempted);
            return transition(connection, current, status, detail);
        });
    }

    IncomeTaxCollection markIncomeTaxRecorded(UUID operationId) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            var current = require(connection, operationId);
            if (current.status().equals("RECORDED")) return current;
            if (!current.status().equals("SUCCEEDED")) throw conflict("税款尚未确认收取，不能标记已入账");
            try (var statement = connection.prepareStatement("""
                    SELECT 1 FROM ledger_entries WHERE business_key = ? AND town_id = ?
                       AND entry_type = ? AND amount_minor = ? AND actor_uuid = ?
                    """)) {
                statement.setString(1, current.tax().businessKey());
                statement.setBytes(2, EconomyPersistence.uuid(current.tax().townId()));
                statement.setString(3, current.tax().source() + "_TAX");
                statement.setLong(4, current.tax().taxMinor());
                statement.setBytes(5, EconomyPersistence.uuid(current.tax().receiverId()));
                try (var row = statement.executeQuery()) {
                    if (!row.next()) throw conflict("未找到匹配的税款流水，保留待入账状态");
                }
            }
            return transition(connection, current, "RECORDED", "已核对税款流水并完成入账");
        });
    }

    List<IncomeTaxCollection> pendingIncomeTaxCollections() {
        database.requireWorkerThread();
        return database.query(connection -> {
            List<IncomeTaxCollection> pending = new ArrayList<>();
            try (var statement = connection.prepareStatement("""
                    SELECT * FROM income_tax_collections WHERE status NOT IN ('FAILED', 'REFUNDED', 'RECORDED')
                    ORDER BY created_at, operation_id
                    """); var rows = statement.executeQuery()) {
                while (rows.next()) pending.add(read(rows));
            }
            return List.copyOf(pending);
        });
    }

    void recoverInterruptedIncomeTaxCollections() {
        database.requireWorkerThread();
        database.transaction(connection -> {
            try (var prepared = connection.prepareStatement("""
                    UPDATE income_tax_collections SET status = 'FAILED',
                        last_error = '启动恢复：未认领外部付款，已取消未执行收款', version = version + 1,
                        updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                    WHERE status = 'PREPARED'
                    """); var attempted = connection.prepareStatement("""
                    UPDATE income_tax_collections SET last_error = '启动恢复：' || status || ' 外部结果不明，需核实双方资金',
                        status = 'AMBIGUOUS', version = version + 1,
                        updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                    WHERE status IN ('ATTEMPTED', 'REFUND_ATTEMPTED')
                    """)) {
                prepared.executeUpdate();
                attempted.executeUpdate();
            }
            return null;
        });
    }

    /** Caller must exclude live external calls; the runtime owns that guard, while SQLite validates the durable snapshot. */
    IncomeTaxCollection resolveIncomeTaxCollection(IncomeTaxCollection expected, boolean paid,
            UUID actor, String actorName, String reason) {
        database.requireWorkerThread();
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new IllegalArgumentException("核实依据必须为 1~500 个字符");
        return database.transaction(connection -> {
            var current = requireExpected(connection, expected);
            if (!Set.of("AMBIGUOUS", "REFUND_REQUIRED", "ATTEMPTED", "REFUND_ATTEMPTED").contains(current.status())
                    && !(paid && current.status().equals("SUCCEEDED")))
                throw conflict("该税款当前不需要人工核实，不能覆盖已终结的付款结果");
            String target = paid ? "SUCCEEDED" : "FAILED";
            var resolved = transition(connection, current, target, "管理员核实：" + reason);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO audit_logs (actor_uuid, actor_name, action, target_type, target_id, reason, detail)
                    VALUES (?, ?, 'INCOME_TAX_COLLECTION_RESOLVE', 'INCOME_TAX_COLLECTION', ?, ?, ?)
                    """)) {
                statement.setBytes(1, actor == null ? null : EconomyPersistence.uuid(actor));
                statement.setString(2, actorName == null ? "CONSOLE" : actorName);
                statement.setString(3, current.operationId().toString());
                statement.setString(4, reason);
                statement.setString(5, current.status() + " -> " + target + "; business_key="
                        + current.tax().businessKey() + "; tax_minor=" + current.tax().taxMinor()
                        + "; version=" + resolved.version());
                statement.executeUpdate();
            }
            return resolved;
        });
    }

    private static boolean eligible(Connection connection, ExternalIncomeTax tax) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT 1 FROM towns t JOIN town_members m ON m.town_id = t.town_id
                WHERE t.town_id = ? AND t.status = 'ACTIVE' AND m.player_uuid = ?
                """)) {
            statement.setBytes(1, EconomyPersistence.uuid(tax.townId()));
            statement.setBytes(2, EconomyPersistence.uuid(tax.receiverId()));
            try (var row = statement.executeQuery()) { return row.next(); }
        }
    }

    private static IncomeTaxCollection transition(Connection connection, IncomeTaxCollection expected,
            String status, String detail) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE income_tax_collections SET status = ?, last_error = ?, version = version + 1,
                    updated_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                WHERE operation_id = ? AND status = ? AND version = ?
                """)) {
            statement.setString(1, status);
            statement.setString(2, EconomyPersistence.safe(detail));
            statement.setBytes(3, EconomyPersistence.uuid(expected.operationId()));
            statement.setString(4, expected.status());
            statement.setLong(5, expected.version());
            EconomyPersistence.requireUpdated(statement, "税款记录已更新，请刷新后重新处理");
        }
        return require(connection, expected.operationId());
    }

    private static IncomeTaxCollection requireExpected(Connection connection, IncomeTaxCollection expected) throws SQLException {
        Objects.requireNonNull(expected, "expected");
        var current = require(connection, expected.operationId());
        requireSameSnapshot(current, expected);
        return current;
    }

    private static void requireSameSnapshot(IncomeTaxCollection current, IncomeTaxCollection expected) {
        if (current.version() != expected.version() || !current.status().equals(expected.status())
                || !current.tax().equals(expected.tax()))
            throw conflict("税款记录已更新，请刷新后重新处理");
    }

    private static IncomeTaxCollection require(Connection connection, UUID id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM income_tax_collections WHERE operation_id = ?")) {
            statement.setBytes(1, EconomyPersistence.uuid(id));
            try (var row = statement.executeQuery()) {
                if (!row.next()) throw conflict("税款收取记录不存在");
                return read(row);
            }
        }
    }

    private static Optional<IncomeTaxCollection> findByBusinessKey(Connection connection, String key) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM income_tax_collections WHERE business_key = ?")) {
            statement.setString(1, key);
            try (var row = statement.executeQuery()) { return row.next() ? Optional.of(read(row)) : Optional.empty(); }
        }
    }

    private static IncomeTaxCollection read(ResultSet row) throws SQLException {
        var tax = new ExternalIncomeTax(EconomyPersistence.readUuid(row, "town_id"), row.getString("business_key"),
                row.getString("source"), EconomyPersistence.readUuid(row, "receiver_uuid"), row.getString("receiver_name"),
                row.getLong("gross_minor"), row.getInt("tax_rate_bps"), row.getLong("tax_minor"));
        return new IncomeTaxCollection(EconomyPersistence.readUuid(row, "operation_id"), tax,
                row.getString("status"), row.getString("last_error"), row.getLong("version"));
    }

    private static ExternalIncomeTax validate(ExternalIncomeTax tax) {
        Objects.requireNonNull(tax, "tax");
        Objects.requireNonNull(tax.townId(), "townId");
        Objects.requireNonNull(tax.receiverId(), "receiverId");
        if (tax.businessKey() == null || tax.businessKey().isBlank()) throw new IllegalArgumentException("税款必须有业务键");
        if (tax.source() == null || !Set.of("JOBS", "GLOBALMARKETPLUS").contains(tax.source()))
            throw new IllegalArgumentException("不支持的税款来源");
        if (tax.taxMinor() <= 0 || tax.grossMinor() <= tax.taxMinor())
            throw new IllegalArgumentException("税额必须大于零且小于收入");
        if (tax.taxRateBps() < 500 || tax.taxRateBps() > 2500 || tax.taxRateBps() % 100 != 0)
            throw new IllegalArgumentException("税率必须为 5%~25%，且以 1% 为步进");
        String name = tax.receiverName() == null || tax.receiverName().isBlank()
                ? tax.receiverId().toString() : tax.receiverName();
        return new ExternalIncomeTax(tax.townId(), tax.businessKey(), tax.source(), tax.receiverId(), name,
                tax.grossMinor(), tax.taxRateBps(), tax.taxMinor());
    }

    private static ConflictException conflict(String message) { return new ConflictException(message); }
}

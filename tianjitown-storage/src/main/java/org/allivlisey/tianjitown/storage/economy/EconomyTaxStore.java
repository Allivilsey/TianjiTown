package org.allivlisey.tianjitown.storage.economy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.ConflictException;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.ExternalIncomeTax;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.LedgerMutation;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.MemberTaxPolicy;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.QuickShopTax;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.SubsidyQuota;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.SubsidyReservation;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository.TaxChange;

/** Tax rates, subsidy quotas and tax ledger workflows. */
final class EconomyTaxStore {
    private final EconomyDatabase database;

    EconomyTaxStore(EconomyDatabase database) {
        this.database = database;
    }

    List<MemberTaxPolicy> loadMemberTaxPolicies() {
        database.requireWorkerThread();
        return database.query(connection -> {
            List<MemberTaxPolicy> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT t.town_id, m.player_uuid, t.tax_rate_bps
                      FROM towns t JOIN town_members m ON m.town_id = t.town_id
                     WHERE t.status = 'ACTIVE'
                    """); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new MemberTaxPolicy(EconomyPersistence.readUuid(rows, "town_id"),
                            EconomyPersistence.readUuid(rows, "player_uuid"), rows.getInt("tax_rate_bps")));
                }
            }
            return List.copyOf(result);
        });
    }

    TaxChange changeTaxRate(UUID townId, UUID mayorId, int basisPoints,
                                   String actorName, String reason) {
        database.requireWorkerThread();
        requireTaxRate(basisPoints);
        requireReason(reason);
        return database.transaction(connection -> {
            TownTax current = requireTownTax(connection, townId);
            requireMayor(connection, townId, mayorId);
            if (current.basisPoints() == basisPoints) {
                return new TaxChange(townId, basisPoints, current.revision());
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE towns
                       SET tax_rate_bps = ?, tax_revision = tax_revision + 1,
                           version = version + 1
                     WHERE town_id = ? AND status = 'ACTIVE'
                    """)) {
                statement.setInt(1, basisPoints);
                statement.setBytes(2, EconomyPersistence.uuid(townId));
                EconomyPersistence.requireUpdated(statement, "小镇不存在或已归档");
            }
            EconomyPersistence.audit(connection, mayorId, actorName, "TAX_RATE_CHANGE", townId,
                    reason, current.basisPoints() + " -> " + basisPoints);
            TownTax changed = requireTownTax(connection, townId);
            return new TaxChange(townId, changed.basisPoints(), changed.revision());
        });
    }

    TaxChange forceTaxRate(UUID townId, UUID actorId, int basisPoints,
                                  String actorName, String reason) {
        database.requireWorkerThread();
        requireTaxRate(basisPoints);
        requireReason(reason);
        return database.transaction(connection -> {
            TownTax current = requireTownTax(connection, townId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE towns
                       SET tax_rate_bps = ?, tax_revision = tax_revision + 1,
                           version = version + 1
                     WHERE town_id = ?
                    """)) {
                statement.setInt(1, basisPoints);
                statement.setBytes(2, EconomyPersistence.uuid(townId));
                EconomyPersistence.requireUpdated(statement, "小镇不存在");
            }
            EconomyPersistence.audit(connection, actorId, actorName, "TAX_RATE_FORCE", townId,
                    reason, current.basisPoints() + " -> " + basisPoints);
            TownTax changed = requireTownTax(connection, townId);
            return new TaxChange(townId, changed.basisPoints(), changed.revision());
        });
    }

    void acknowledgeTaxRevision(UUID playerId, int revision) {
        database.requireWorkerThread();
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE town_members
                       SET accepted_tax_revision = ?
                     WHERE player_uuid = ?
                       AND ? <= (SELECT tax_revision FROM towns WHERE town_id = town_members.town_id)
                    """)) {
                statement.setInt(1, revision);
                statement.setBytes(2, EconomyPersistence.uuid(playerId));
                statement.setInt(3, revision);
                EconomyPersistence.requireUpdated(statement, "税率版本已变化，请重新打开");
            }
            return null;
        });
    }

    SubsidyReservation reserveTaxSubsidy(UUID townId, String businessKey,
                                                       long requestedMinor,
                                                       long weeklyLimitMinor,
                                                       long twelveHourLimitMinor,
                                                       Instant now, ZoneId zoneId) {
        database.requireWorkerThread();
        Objects.requireNonNull(townId, "townId");
        Objects.requireNonNull(businessKey, "businessKey");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(zoneId, "zoneId");
        if (requestedMinor <= 0 || weeklyLimitMinor < 0 || twelveHourLimitMinor < 0) {
            throw new IllegalArgumentException("补贴请求和限额无效");
        }
        Periods periods = periods(now, zoneId);
        return database.transaction(connection -> {
            Optional<SubsidyReservation> existing = findSubsidyReservation(connection,
                    businessKey);
            if (existing.isPresent()) {
                SubsidyReservation reservation = existing.get();
                if (!reservation.townId().equals(townId)
                        || reservation.requestedMinor() != requestedMinor
                        || reservation.status().equals("CANCELLED")) {
                    throw new ConflictException("税收补贴预留与请求不一致");
                }
                return reservation;
            }
            long usedWeekly = subsidyUsed(connection, townId, "week_start",
                    periods.weekStart().toEpochMilli());
            long usedTwelveHours = subsidyUsed(connection, townId, "period_12h_start",
                    periods.twelveHourStart().toEpochMilli());
            long weeklyRemaining = Math.max(0L, weeklyLimitMinor - usedWeekly);
            long twelveHourRemaining = Math.max(0L,
                    twelveHourLimitMinor - usedTwelveHours);
            long granted = Math.min(requestedMinor,
                    Math.min(weeklyRemaining, twelveHourRemaining));
            UUID reservationId = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO quickshop_subsidy_reservations
                        (reservation_id, town_id, business_key, requested_minor, granted_minor,
                         period_12h_start, week_start, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'RESERVED', ?)
                    """)) {
                statement.setBytes(1, EconomyPersistence.uuid(reservationId));
                statement.setBytes(2, EconomyPersistence.uuid(townId));
                statement.setString(3, businessKey);
                statement.setLong(4, requestedMinor);
                statement.setLong(5, granted);
                statement.setLong(6, periods.twelveHourStart().toEpochMilli());
                statement.setLong(7, periods.weekStart().toEpochMilli());
                statement.setLong(8, now.toEpochMilli());
                statement.executeUpdate();
            }
            return requireSubsidyReservation(connection, businessKey);
        });
    }

    void cancelTaxSubsidy(String businessKey, String error) {
        database.requireWorkerThread();
        Objects.requireNonNull(businessKey, "businessKey");
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE quickshop_subsidy_reservations
                       SET status = 'CANCELLED', last_error = ?
                     WHERE business_key = ? AND status = 'RESERVED'
                    """)) {
                statement.setString(1, EconomyPersistence.safe(error));
                statement.setString(2, businessKey);
                statement.executeUpdate();
            }
            return null;
        });
    }

    SubsidyQuota taxSubsidyQuota(UUID townId, long weeklyLimitMinor,
                                               long twelveHourLimitMinor, Instant now,
                                               ZoneId zoneId) {
        database.requireWorkerThread();
        Periods periods = periods(now, zoneId);
        return database.query(connection -> {
            long weeklyUsed = subsidyUsed(connection, townId, "week_start",
                    periods.weekStart().toEpochMilli());
            long twelveHourUsed = subsidyUsed(connection, townId, "period_12h_start",
                    periods.twelveHourStart().toEpochMilli());
            return new SubsidyQuota(Math.max(0, weeklyLimitMinor - weeklyUsed),
                    Math.max(0, twelveHourLimitMinor - twelveHourUsed),
                    periods.nextWeek(), periods.nextTwelveHour());
        });
    }

    LedgerMutation recordQuickShopTax(QuickShopTax tax) {
        database.requireWorkerThread();
        Objects.requireNonNull(tax, "tax");
        if (tax.taxMinor() <= 0 || tax.grossMinor() <= 0) {
            throw new IllegalArgumentException("交易额和税额必须大于 0");
        }
        requireTaxRate(tax.taxRateBps());
        return database.transaction(connection -> {
            Optional<LedgerMutation> existing = EconomyPersistence.findLedgerByBusinessKey(connection,
                    tax.businessKey());
            if (existing.isPresent()) {
                // 返回整笔交易最后一条已落账的变更，保证重试与首次处理得到相同的余额快照。
                return EconomyPersistence.findLedgerByBusinessKey(connection, tax.businessKey() + ":subsidy")
                        .orElse(existing.get());
            }
            SubsidyReservation subsidy = requireSubsidyReservation(connection, tax.businessKey());
            if (!subsidy.townId().equals(tax.townId())
                    || subsidy.requestedMinor() != tax.taxMinor()
                    || subsidy.status().equals("CANCELLED")) {
                throw new ConflictException("税收补贴预留与税款不一致");
            }
            UUID taxId = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO quickshop_tax_records
                        (tax_id, town_id, business_key, shop_id, shop_type, receiver_uuid,
                         interacting_uuid, gross_minor, tax_rate_bps, tax_minor, world_name,
                         receiver_name)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setBytes(1, EconomyPersistence.uuid(taxId));
                statement.setBytes(2, EconomyPersistence.uuid(tax.townId()));
                statement.setString(3, tax.businessKey());
                statement.setLong(4, tax.shopId());
                statement.setString(5, tax.shopType());
                statement.setBytes(6, EconomyPersistence.uuid(tax.receiverId()));
                statement.setBytes(7, EconomyPersistence.uuid(tax.interactingId()));
                statement.setLong(8, tax.grossMinor());
                statement.setInt(9, tax.taxRateBps());
                statement.setLong(10, tax.taxMinor());
                statement.setString(11, tax.worldName());
                statement.setString(12, tax.receiverName());
                statement.executeUpdate();
            }
            LedgerMutation taxMutation = EconomyPersistence.postLedger(connection, tax.townId(), "QUICKSHOP_TAX",
                    tax.taxMinor(), tax.receiverId(), tax.receiverName(), tax.businessKey(),
                    tax.shopType() + " 商店税，shop=" + tax.shopId(), false);
            LedgerMutation result = taxMutation;
            if (subsidy.grantedMinor() > 0) {
                result = EconomyPersistence.postLedger(connection, tax.townId(), "SERVER_TAX_SUBSIDY",
                        subsidy.grantedMinor(), null, "SERVER",
                        tax.businessKey() + ":subsidy",
                        subsidy.grantedMinor() == tax.taxMinor()
                                ? "QuickShop 税收等额服务器补贴"
                                : "QuickShop 税收限额内部分补贴", false);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE quickshop_subsidy_reservations
                       SET status = 'APPLIED', last_error = NULL
                     WHERE business_key = ? AND status IN ('RESERVED', 'APPLIED')
                    """)) {
                statement.setString(1, tax.businessKey());
                EconomyPersistence.requireUpdated(statement, "税收补贴预留已失效");
            }
            return result;
        });
    }

    LedgerMutation recordExternalIncomeTax(ExternalIncomeTax tax) {
        database.requireWorkerThread();
        Objects.requireNonNull(tax, "tax");
        if (tax.grossMinor() <= 0 || tax.taxMinor() <= 0) {
            throw new IllegalArgumentException("外部收入与税额必须大于 0");
        }
        if (!Set.of("JOBS", "GLOBALMARKETPLUS").contains(tax.source())) {
            throw new IllegalArgumentException("不支持的外部收入来源: " + tax.source());
        }
        requireTaxRate(tax.taxRateBps());
        return database.transaction(connection -> {
            String subsidyKey = tax.businessKey() + ":subsidy";
            Optional<LedgerMutation> existing = EconomyPersistence.findLedgerByBusinessKey(
                    connection, tax.businessKey());
            if (existing.isPresent()) {
                return EconomyPersistence.findLedgerByBusinessKey(connection, subsidyKey)
                        .orElse(existing.get());
            }
            SubsidyReservation subsidy = requireSubsidyReservation(connection, tax.businessKey());
            if (!subsidy.townId().equals(tax.townId())
                    || subsidy.requestedMinor() != tax.taxMinor()
                    || !subsidy.status().equals("RESERVED")) {
                throw new ConflictException("税收补贴预留与税款不一致");
            }
            UUID taxId = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO external_income_tax_records
                        (tax_id, town_id, business_key, source, receiver_uuid,
                         gross_minor, tax_rate_bps, tax_minor)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setBytes(1, EconomyPersistence.uuid(taxId));
                statement.setBytes(2, EconomyPersistence.uuid(tax.townId()));
                statement.setString(3, tax.businessKey());
                statement.setString(4, tax.source());
                statement.setBytes(5, EconomyPersistence.uuid(tax.receiverId()));
                statement.setLong(6, tax.grossMinor());
                statement.setInt(7, tax.taxRateBps());
                statement.setLong(8, tax.taxMinor());
                statement.executeUpdate();
            }
            LedgerMutation result = EconomyPersistence.postLedger(connection, tax.townId(), tax.source() + "_TAX",
                    tax.taxMinor(), tax.receiverId(), tax.receiverName(), tax.businessKey(),
                    tax.source() + " 收入税", false);
            if (subsidy.grantedMinor() > 0) {
                result = EconomyPersistence.postLedger(connection, tax.townId(), "SERVER_TAX_SUBSIDY",
                        subsidy.grantedMinor(), null, "SERVER", subsidyKey,
                        tax.source() + " 税收限额内服务器补贴", false);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE quickshop_subsidy_reservations
                       SET status = 'APPLIED', last_error = NULL
                     WHERE business_key = ? AND status = 'RESERVED'
                    """)) {
                statement.setString(1, tax.businessKey());
                EconomyPersistence.requireUpdated(statement, "税收补贴预留已失效");
            }
            return result;
        });
    }

    private static long subsidyUsed(Connection connection, UUID townId, String periodColumn,
                                    long periodStart) throws SQLException {
        if (!periodColumn.equals("week_start")
                && !periodColumn.equals("period_12h_start")) {
            throw new IllegalArgumentException("未知补贴周期列");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(SUM(granted_minor), 0) AS used "
                        + "FROM quickshop_subsidy_reservations WHERE town_id = ? AND "
                        + periodColumn + " = ? AND status IN ('RESERVED', 'APPLIED')")) {
            statement.setBytes(1, EconomyPersistence.uuid(townId));
            statement.setLong(2, periodStart);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong("used") : 0L;
            }
        }
    }

    private static Optional<SubsidyReservation> findSubsidyReservation(Connection connection,
                                                                        String businessKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM quickshop_subsidy_reservations WHERE business_key = ?
                """)) {
            statement.setString(1, businessKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readSubsidyReservation(result))
                        : Optional.empty();
            }
        }
    }

    private static SubsidyReservation requireSubsidyReservation(Connection connection,
                                                                  String businessKey)
            throws SQLException {
        return findSubsidyReservation(connection, businessKey)
                .orElseThrow(() -> new ConflictException("税款缺少补贴额度预留"));
    }

    private static SubsidyReservation readSubsidyReservation(ResultSet result)
            throws SQLException {
        return new SubsidyReservation(EconomyPersistence.readUuid(result, "reservation_id"),
                EconomyPersistence.readUuid(result, "town_id"), result.getString("business_key"),
                result.getLong("requested_minor"), result.getLong("granted_minor"),
                Instant.ofEpochMilli(result.getLong("period_12h_start")),
                Instant.ofEpochMilli(result.getLong("week_start")),
                result.getString("status"));
    }

    private static Periods periods(Instant now, ZoneId zoneId) {
        ZonedDateTime local = now.atZone(org.allivlisey.tianjitown.core.time.TownTime.ZONE).minusHours(4);
        ZonedDateTime twelveHourStart = local.withMinute(0).withSecond(0).withNano(0)
                .withHour(local.getHour() < 12 ? 0 : 12).plusHours(4);
        ZonedDateTime weekStart = local.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .withHour(0).withMinute(0).withSecond(0).withNano(0).plusHours(4);
        return new Periods(twelveHourStart.toInstant(), weekStart.toInstant(),
                weekStart.plusWeeks(1).toInstant(), twelveHourStart.plusHours(12).toInstant());
    }

    private static TownTax requireTownTax(Connection connection, UUID townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tax_rate_bps, tax_revision FROM towns WHERE town_id = ?
                """)) {
            statement.setBytes(1, EconomyPersistence.uuid(townId));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ConflictException("小镇不存在");
                }
                return new TownTax(row.getInt("tax_rate_bps"), row.getInt("tax_revision"));
            }
        }
    }

    private static void requireMayor(Connection connection, UUID townId, UUID playerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM town_members
                 WHERE town_id = ? AND player_uuid = ?
                   AND role = 'MAYOR'
                """)) {
            statement.setBytes(1, EconomyPersistence.uuid(townId));
            statement.setBytes(2, EconomyPersistence.uuid(playerId));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new ConflictException("只有镇长可以修改税率");
                }
            }
        }
    }

    private static void requireTaxRate(int basisPoints) {
        if (basisPoints < 500 || basisPoints > 2_500 || basisPoints % 100 != 0) {
            throw new IllegalArgumentException("税率必须为 5%~25%，且以 1% 为步进");
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("原因必须为 1~500 个字符");
        }
    }

    private record TownTax(int basisPoints, int revision) {
    }

    private record Periods(Instant twelveHourStart, Instant weekStart, Instant nextWeek,
                           Instant nextTwelveHour) {
    }
}

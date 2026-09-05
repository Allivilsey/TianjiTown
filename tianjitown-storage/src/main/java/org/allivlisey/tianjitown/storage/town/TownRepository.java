package org.allivlisey.tianjitown.storage.town;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.land.InitialTerritory;
import org.allivlisey.tianjitown.core.town.TownStatus;

import static org.allivlisey.tianjitown.storage.town.TownPersistence.RULE_SEPARATOR;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.readUuid;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.uuid;

public final class TownRepository {
    private final TownJoinApplicationStore joinApplications;
    private final TownInvitationStore invitations;
    private final TownDatabase database;
    private final TownApplicationStore applicationStore;
    private final TownProvisioningStore provisioningStore;
    private final TownMembershipStore membershipStore;

    public TownRepository(DataSource dataSource, BooleanSupplier forbiddenThread) {
        this.database = new TownDatabase(dataSource, forbiddenThread);
        this.invitations = new TownInvitationStore(database);
        this.joinApplications = new TownJoinApplicationStore(database);
        this.applicationStore = new TownApplicationStore(database);
        this.provisioningStore = new TownProvisioningStore(database);
        this.membershipStore = new TownMembershipStore(database);
    }

    public ApplicationSnapshot createDraft(UUID applicantId, ApplicationText text,
                                           List<UUID> initialMemberIds, Duration cooldown) {
        return applicationStore.createDraft(applicantId, text, initialMemberIds, cooldown);
    }

    public ApplicationSnapshot updateApplicationText(UUID applicationId, UUID applicantId,
                                                     ApplicationText text,
                                                     List<UUID> initialMemberIds,
                                                     long expectedVersion) {
        return applicationStore.updateApplicationText(applicationId, applicantId, text, initialMemberIds,
                expectedVersion);
    }

    public ApplicationSnapshot respondInitialMember(UUID applicationId, UUID playerId,
                                                     boolean confirm) {
        return applicationStore.respondInitialMember(applicationId, playerId, confirm);
    }

    public ApplicationSnapshot selectSite(UUID applicationId, UUID applicantId,
                                          InitialTerritory territory, Instant expiresAt,
                                          int bufferChunks) {
        return applicationStore.selectSite(applicationId, applicantId, territory, expiresAt, bufferChunks);
    }

    public ApplicationSnapshot submit(UUID applicationId, UUID applicantId) {
        return applicationStore.submit(applicationId, applicantId);
    }

    public ApplicationSnapshot cancel(UUID applicationId, UUID applicantId, String reason) {
        return applicationStore.cancel(applicationId, applicantId, reason);
    }

    public ApplicationSnapshot requestChanges(UUID applicationId, UUID reviewerId,
                                              String reviewerName, String reason) {
        return applicationStore.requestChanges(applicationId, reviewerId, reviewerName, reason);
    }

    public ApplicationSnapshot reject(UUID applicationId, UUID reviewerId,
                                      String reviewerName, String reason) {
        return applicationStore.reject(applicationId, reviewerId, reviewerName, reason);
    }

    public Provisioning beginProvision(UUID applicationId, UUID reviewerId, String reviewerName,
                                       String reason, String idempotencyKey,
                                       long applicationFeeMinor) {
        return provisioningStore.beginProvision(applicationId, reviewerId, reviewerName, reason,
                idempotencyKey, applicationFeeMinor);
    }

    public Provisioning beginProvision(UUID applicationId, UUID reviewerId, String reviewerName,
                                       String reason, String idempotencyKey,
                                       long applicationFeeMinor, String applicantName) {
        return provisioningStore.beginProvision(applicationId, reviewerId, reviewerName, reason,
                idempotencyKey, applicationFeeMinor, applicantName);
    }

    public ApplicationSnapshot finishProvision(UUID applicationId, boolean success, String detail) {
        return provisioningStore.finishProvision(applicationId, success, detail);
    }

    public Optional<ApplicationSnapshot> findApplication(UUID applicationId) {
        return applicationStore.findApplication(applicationId);
    }

    public List<ApplicationSnapshot> listPendingInitialMemberApplications(UUID playerId) {
        return applicationStore.listPendingInitialMemberApplications(playerId);
    }

    public int recoverInterruptedProvisions(String reason) {
        return provisioningStore.recoverInterruptedProvisions(reason);
    }

    public Provisioning failedProvision(UUID applicationId) {
        return provisioningStore.failedProvision(applicationId);
    }

    public ApplicationSnapshot recoverFailedProvision(UUID applicationId, UUID reviewerId,
                                                       String reviewerName, String reason,
                                                       RecoveryMode mode) {
        return provisioningStore.recoverFailedProvision(applicationId, reviewerId, reviewerName, reason, mode);
    }

    public ApplicationSnapshot completeApplicationFeeRefund(UUID applicationId, UUID reviewerId,
                                                              String reviewerName,
                                                              String detail) {
        return provisioningStore.completeApplicationFeeRefund(applicationId, reviewerId, reviewerName, detail);
    }

    public Optional<ApplicationSnapshot> findReviewApplicationByName(String townName) {
        return applicationStore.findReviewApplicationByName(townName);
    }

    public Optional<ApplicationSnapshot> findOpenApplication(UUID applicantId) {
        return applicationStore.findOpenApplication(applicantId);
    }

    public Optional<ApplicationFormDraft> findFormDraft(UUID applicantId) {
        return applicationStore.findFormDraft(applicantId);
    }

    public ApplicationFormDraft saveFormDraft(ApplicationFormDraft draft) {
        return applicationStore.saveFormDraft(draft);
    }

    public void deleteFormDraft(UUID applicantId) {
        applicationStore.deleteFormDraft(applicantId);
    }

    public List<ApplicationSnapshot> listReviewQueue(int limit) {
        return applicationStore.listReviewQueue(limit);
    }

    public List<ApplicationSnapshot> listApplicationsForCompletion(int limit) {
        return applicationStore.listApplicationsForCompletion(limit);
    }

    public Optional<TownSnapshot> findTown(UUID townId) {
        database.requireWorkerThread();
        return database.query(connection -> TownPersistence.findTown(connection, townId));
    }

    public Optional<TownSnapshot> findTownByName(String townName) {
        database.requireWorkerThread();
        String normalizedName = ApplicationText.normalizeNameKey(townName);
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT town_id FROM towns WHERE normalized_name = ?
                     ORDER BY (status = 'ARCHIVED'), reuse_blocked DESC, created_at DESC LIMIT 1
                    """)) {
                statement.setString(1, normalizedName);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                            ? TownPersistence.findTown(connection, readUuid(result, "town_id")) : Optional.empty();
                }
            }
        });
    }

    public Optional<TownSnapshot> findTownByMember(UUID playerId) {
        database.requireWorkerThread();
        return database.query(connection -> {
            Optional<UUID> townId = TownPersistence.memberTownId(connection, playerId);
            return townId.isPresent() ? TownPersistence.findTown(connection, townId.get()) : Optional.empty();
        });
    }

    public PlayerDashboard dashboard(UUID playerId) {
        database.requireWorkerThread();
        return database.query(connection -> {
            Optional<UUID> townId = TownPersistence.memberTownId(connection, playerId);
            Optional<TownSnapshot> town = townId.isPresent()
                    ? TownPersistence.findTown(connection, townId.get()) : Optional.empty();
            if (town.isPresent() && town.get().status() != TownStatus.ACTIVE) {
                town = Optional.empty();
            }
            Optional<ApplicationSnapshot> application;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT application_id FROM town_applications WHERE active_applicant = ? LIMIT 1
                    """)) {
                statement.setBytes(1, uuid(playerId));
                try (ResultSet result = statement.executeQuery()) {
                    application = result.next()
                            ? TownPersistence.findApplication(connection, readUuid(result, "application_id"))
                            : Optional.empty();
                }
            }
            List<JoinApplicationSnapshot> joinApplications = TownPersistence.listJoinApplicationsForPlayer(
                    connection, playerId);
            List<JoinApplicationSnapshot> incomingApplications = town.isPresent()
                    && TownPersistence.canReviewJoinApplications(connection, town.get().id(), playerId)
                    ? TownPersistence.listJoinApplicationsForTown(connection, town.get().id()) : List.of();
            return new PlayerDashboard(town.orElse(null), application.orElse(null),
                    joinApplications, incomingApplications);
        });
    }

    public List<TownSnapshot> listTowns(boolean includeArchived) {
        database.requireWorkerThread();
        return database.query(connection -> {
            List<TownSnapshot> towns = new ArrayList<>();
            String sql = includeArchived ? """
                    SELECT town_id FROM towns
                     ORDER BY (status = 'ARCHIVED'), reuse_blocked DESC, created_at DESC, town_id
                    """
                    : "SELECT town_id FROM towns WHERE status <> 'ARCHIVED' "
                    + "ORDER BY created_at, town_id";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    TownPersistence.findTown(connection, readUuid(result, "town_id")).ifPresent(towns::add);
                }
            }
            return List.copyOf(towns);
        });
    }

    public TownSnapshot.Page listMembers(UUID townId, int page, int pageSize) {
        return membershipStore.listMembers(townId, page, pageSize);
    }

    /**
     * Returns the complete membership set for presentation-layer ordering.  Player names are not
     * persisted, so the Paper layer applies its name-aware order before it paginates.
     */
    public List<TownSnapshot.Member> listAllMembers(UUID townId) {
        return membershipStore.listAllMembers(townId);
    }

    public List<UUID> listMemberIds(UUID townId) {
        return membershipStore.listMemberIds(townId);
    }

    public List<MemberConflict> initialMemberConflicts(List<UUID> playerIds) {
        return membershipStore.initialMemberConflicts(playerIds);
    }

    public TownSnapshot.VisitorPage listVisitors(UUID townId, int page, int pageSize) {
        return membershipStore.listVisitors(townId, page, pageSize);
    }

    public List<UUID> listVisitorIds(UUID townId) {
        return membershipStore.listVisitorIds(townId);
    }

    public List<UUID> listLandAccessIds(UUID townId) {
        return membershipStore.listLandAccessIds(townId);
    }

    public TownSnapshot.Visitor addVisitor(UUID townId, UUID playerId, UUID actorId,
                                           String actorName) {
        return membershipStore.addVisitor(townId, playerId, actorId, actorName);
    }

    /**
     * Adds a visitor and returns the town name read in the same transaction as the mutation.
     */
    public TownPlayerChange addVisitorWithTownName(UUID townId, UUID playerId, UUID actorId,
                                                    String actorName) {
        return membershipStore.addVisitorWithTownName(townId, playerId, actorId, actorName);
    }

    public UUID removeVisitor(UUID townId, UUID playerId, UUID actorId, String actorName) {
        return membershipStore.removeVisitor(townId, playerId, actorId, actorName);
    }

    /**
     * Removes a visitor and returns the town name read in the same transaction as the mutation.
     */
    public TownPlayerChange removeVisitorWithTownName(UUID townId, UUID playerId, UUID actorId,
                                                       String actorName) {
        return membershipStore.removeVisitorWithTownName(townId, playerId, actorId, actorName);
    }

    public Map<UUID, List<UUID>> listMemberIdsByTown() {
        return membershipStore.listMemberIdsByTown();
    }

    public JoinApplicationSnapshot applyToTown(UUID townId, UUID applicantId, Duration lifetime,
                                               Duration rejectionCooldown, Duration leaveCooldown,
                                               int maximumPending) {
        return joinApplications.applyToTown(townId, applicantId, lifetime, rejectionCooldown, leaveCooldown,
                maximumPending);
    }

    public List<JoinApplicationSnapshot> listJoinApplications(UUID applicantId) {
        return joinApplications.listJoinApplications(applicantId);
    }

    public List<JoinApplicationSnapshot> listTownJoinApplications(UUID townId, UUID mayorId) {
        return joinApplications.listTownJoinApplications(townId, mayorId);
    }

    public JoinApplicationSnapshot approveJoinApplication(UUID applicationId, UUID mayorId) {
        return joinApplications.approveJoinApplication(applicationId, mayorId);
    }

    public JoinApplicationSnapshot rejectJoinApplication(UUID applicationId, UUID mayorId) {
        return joinApplications.rejectJoinApplication(applicationId, mayorId);
    }

    public JoinApplicationSnapshot cancelJoinApplication(UUID applicationId, UUID applicantId) {
        return joinApplications.cancelJoinApplication(applicationId, applicantId);
    }

    public InvitationSnapshot invite(UUID townId, UUID mayorId, UUID playerId, Duration lifetime) {
        return invitations.invite(townId, mayorId, playerId, lifetime);
    }

    public InvitationSnapshot adminInvite(UUID townId, UUID playerId, UUID actorId,
                                           String actorName, Duration lifetime, String reason) {
        return invitations.adminInvite(townId, playerId, actorId, actorName, lifetime, reason);
    }

    public List<InvitationSnapshot> listInvitations(UUID playerId) {
        return invitations.listInvitations(playerId);
    }

    public UUID acceptInvitation(UUID invitationId, UUID playerId) {
        return invitations.acceptInvitation(invitationId, playerId);
    }

    public UUID declineInvitation(UUID invitationId, UUID playerId) {
        return invitations.declineInvitation(invitationId, playerId);
    }

    public void leaveTown(UUID playerId) {
        membershipStore.leaveTown(playerId);
    }

    public void removeMember(UUID townId, UUID playerId, UUID actorId, String actorName,
                             String reason) {
        membershipStore.removeMember(townId, playerId, actorId, actorName, reason);
    }

    public void addMember(UUID townId, UUID playerId, UUID actorId, String actorName,
                          String reason) {
        membershipStore.addMember(townId, playerId, actorId, actorName, reason);
    }

    public void transferMayor(UUID townId, UUID newMayorId, UUID actorId, String actorName,
                              String reason) {
        membershipStore.transferMayor(townId, newMayorId, actorId, actorName, reason);
    }

    public void deleteTown(UUID townId, UUID actorId, String actorName, String reason) {
        database.requireWorkerThread();
        TownPersistence.requireReason(reason);
        database.transaction(connection -> {
            prepareTownDeletion(connection, townId, actorId, actorName, reason, false, -1);
            return null;
        });
    }

    public TownSnapshot disbandTown(UUID townId, UUID mayorId, long expectedVersion) {
        database.requireWorkerThread();
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM town_members WHERE town_id = ?")) {
                statement.setBytes(1, uuid(townId));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) {
                        throw new ConflictException("仅剩镇长一名成员时才能解散，请先完成成员管理");
                    }
                }
            }
            return prepareTownDeletion(connection, townId, mayorId,
                    mayorId.toString(), "镇长通过小镇界面解散", true, expectedVersion);
        });
    }

    public void completeTownDeletion(UUID townId, UUID actorId, String actorName, String reason) {
        database.requireWorkerThread();
        TownPersistence.requireReason(reason);
        database.transaction(connection -> {
            TownSnapshot current = TownPersistence.requireTown(connection, townId);
            if (current.status() != TownStatus.ARCHIVED || !townReuseBlocked(connection, townId)) {
                throw new ConflictException("小镇未处于等待资源释放的归档状态");
            }
            try (PreparedStatement town = connection.prepareStatement("""
                    UPDATE towns SET reuse_blocked = FALSE, version = version + 1
                     WHERE town_id = ? AND status = 'ARCHIVED' AND reuse_blocked = TRUE
                    """);
                 PreparedStatement units = connection.prepareStatement("""
                         UPDATE territory_units SET reuse_blocked = FALSE
                          WHERE town_id = ? AND reuse_blocked = TRUE
                         """);
                 PreparedStatement chunks = connection.prepareStatement("""
                         DELETE FROM territory_chunks
                          WHERE unit_id IN (SELECT unit_id FROM territory_units WHERE town_id = ?)
                         """)) {
                town.setBytes(1, uuid(townId));
                TownPersistence.requireUpdated(town, "小镇删除资源已被其他操作释放");
                units.setBytes(1, uuid(townId));
                units.executeUpdate();
                chunks.setBytes(1, uuid(townId));
                chunks.executeUpdate();
            }
            TownPersistence.audit(connection, null, actorId, actorName, "TOWN_DELETE_COMPLETE", "TOWN",
                    townId.toString(), reason, "Residence 已移除；名称、小镇代码和区块已允许复用");
            return null;
        });
    }

    public boolean archiveTownForMissingProjection(UUID townId, String detail) {
        database.requireWorkerThread();
        TownPersistence.requireReason(detail);
        return database.transaction(connection -> {
            try (PreparedStatement town = connection.prepareStatement("""
                    UPDATE towns
                       SET status = 'ARCHIVED', reuse_blocked = TRUE, archived_at = ?,
                           archive_reason = ?, version = version + 1
                     WHERE town_id = ? AND status = 'ACTIVE'
                    """);
                 PreparedStatement units = connection.prepareStatement("""
                         UPDATE territory_units
                            SET projection_status = 'FAILED', projection_error = ?, reuse_blocked = TRUE
                          WHERE town_id = ?
                         """);
                 PreparedStatement members = connection.prepareStatement(
                         "DELETE FROM town_members WHERE town_id = ?");
                 PreparedStatement visitors = connection.prepareStatement(
                         "DELETE FROM town_visitors WHERE town_id = ?");
                 PreparedStatement invitations = connection.prepareStatement("""
                         UPDATE town_invitations
                            SET revoked_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                          WHERE town_id = ? AND accepted_at IS NULL AND revoked_at IS NULL
                         """);
                 PreparedStatement joinApplications = connection.prepareStatement("""
                         UPDATE town_join_applications
                            SET status = 'CANCELLED',
                                decided_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                          WHERE town_id = ? AND status = 'PENDING'
                         """)) {
                snapshotMembers(connection, townId);
                town.setLong(1, Instant.now().toEpochMilli());
                town.setString(2, detail);
                town.setBytes(3, uuid(townId));
                if (town.executeUpdate() != 1) {
                    return false;
                }
                units.setString(1, detail);
                units.setBytes(2, uuid(townId));
                units.executeUpdate();
                members.setBytes(1, uuid(townId));
                members.executeUpdate();
                visitors.setBytes(1, uuid(townId));
                visitors.executeUpdate();
                invitations.setBytes(1, uuid(townId));
                invitations.executeUpdate();
                joinApplications.setBytes(1, uuid(townId));
                joinApplications.executeUpdate();
            }
            TownPersistence.audit(connection, null, null, "SYSTEM", "TOWN_SAFETY_ARCHIVE", "TOWN",
                    townId.toString(), "Residence 投影缺失", detail
                            + "；名称、小镇代码和区块继续锁定，禁止自动复用");
            return true;
        });
    }

    public TownSnapshot updateTownProfile(UUID townId, ApplicationText profile, long expectedVersion,
                                          UUID actorId, String actorName, String reason) {
        database.requireWorkerThread();
        profile.requireValid();
        return database.transaction(connection -> {
            TownPersistence.requireManager(connection, townId, actorId);
            TownSnapshot current = TownPersistence.requireTown(connection, townId);
            if (!current.profile().name().equals(profile.name())
                    || !current.profile().shortName().equals(profile.shortName())
                    || !current.profile().normalizedResidenceName()
                    .equals(profile.normalizedResidenceName())) {
                throw new ConflictException("玩家资料入口不能修改小镇名称或小镇代码");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE towns SET description = ?, rules_text = ?,
                        rules_revision = rules_revision + CASE WHEN rules_text <> ? THEN 1 ELSE 0 END,
                        version = version + 1
                     WHERE town_id = ? AND version = ? AND status <> 'ARCHIVED'
                    """)) {
                statement.setString(1, profile.description());
                statement.setString(2, String.join(RULE_SEPARATOR, profile.rules()));
                statement.setString(3, String.join(RULE_SEPARATOR, profile.rules()));
                statement.setBytes(4, uuid(townId));
                statement.setLong(5, expectedVersion);
                TownPersistence.requireUpdated(statement, "小镇资料已被其他操作修改，请重新读取后再试");
            }
            TownPersistence.audit(connection, null, actorId, actorName, "PROFILE_UPDATE", "TOWN", townId.toString(),
                    reason, current.profile().name());
            return TownPersistence.requireTown(connection, townId);
        });
    }

    public List<AuditSnapshot> auditLog(int limit) {
        database.requireWorkerThread();
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return database.query(connection -> {
            List<AuditSnapshot> records = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT audit_id, actor_name, action, target_type, target_id, reason, detail, created_at
                      FROM audit_logs ORDER BY audit_id DESC LIMIT ?
                    """)) {
                statement.setInt(1, safeLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        records.add(new AuditSnapshot(result.getLong("audit_id"),
                                result.getString("actor_name"), result.getString("action"),
                                result.getString("target_type"), result.getString("target_id"),
                                result.getString("reason"), result.getString("detail"),
                                result.getTimestamp("created_at").toInstant()));
                    }
                }
            }
            return List.copyOf(records);
        });
    }

    public void recordAudit(UUID actorId, String actorName, String action, String targetType,
                            String targetId, String reason, String detail) {
        database.requireWorkerThread();
        database.transaction(connection -> {
            TownPersistence.audit(connection, null, actorId, actorName, action, targetType, targetId, reason, detail);
            return null;
        });
    }

    private TownSnapshot prepareTownDeletion(Connection connection, UUID townId, UUID actorId,
                                              String actorName, String reason, boolean mayorOnly,
                                              long expectedVersion) throws SQLException {
        TownSnapshot current = TownPersistence.requireTown(connection, townId);
        if (mayorOnly) {
            TownPersistence.requireManager(connection, townId, actorId);
            if (current.version() != expectedVersion) {
                throw new ConflictException("小镇状态已经变化，请重新打开界面确认");
            }
        }
        boolean alreadyPrepared = current.status() == TownStatus.ARCHIVED
                && townReuseBlocked(connection, townId);
        if (current.status() == TownStatus.ARCHIVED && !alreadyPrepared) {
            throw new ConflictException("小镇已经删除并完成资源释放");
        }
        if (mayorOnly && current.status() != TownStatus.ACTIVE) {
            throw new ConflictException("只有正常运行的小镇可以由镇长解散");
        }
        try (PreparedStatement town = connection.prepareStatement("""
                UPDATE towns
                   SET status = 'ARCHIVED', reuse_blocked = TRUE, archived_at = ?,
                       archive_reason = ?, version = version + 1
                 WHERE town_id = ? AND status <> 'ARCHIVED'
                """);
             PreparedStatement unit = connection.prepareStatement(
                     "UPDATE territory_units SET reuse_blocked = TRUE WHERE town_id = ?");
             PreparedStatement members = connection.prepareStatement(
                     "DELETE FROM town_members WHERE town_id = ?");
             PreparedStatement visitors = connection.prepareStatement(
                     "DELETE FROM town_visitors WHERE town_id = ?");
             PreparedStatement invitations = connection.prepareStatement("""
                     UPDATE town_invitations
                        SET revoked_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                      WHERE town_id = ? AND accepted_at IS NULL AND revoked_at IS NULL
                     """);
             PreparedStatement joinApplications = connection.prepareStatement("""
                     UPDATE town_join_applications
                        SET status = 'CANCELLED', decided_at = CAST(unixepoch('subsec') * 1000 AS INTEGER)
                      WHERE town_id = ? AND status = 'PENDING'
                     """)) {
            if (!alreadyPrepared) {
                snapshotMembers(connection, townId);
                town.setLong(1, Instant.now().toEpochMilli());
                town.setString(2, reason);
                town.setBytes(3, uuid(townId));
                TownPersistence.requireUpdated(town, "小镇不存在或已经归档");
            }
            unit.setBytes(1, uuid(townId));
            unit.executeUpdate();
            members.setBytes(1, uuid(townId));
            members.executeUpdate();
            visitors.setBytes(1, uuid(townId));
            visitors.executeUpdate();
            invitations.setBytes(1, uuid(townId));
            invitations.executeUpdate();
            joinApplications.setBytes(1, uuid(townId));
            joinApplications.executeUpdate();
        }
        String action = mayorOnly ? "TOWN_DISBAND_PREPARE"
                : alreadyPrepared ? "TOWN_DELETE_RETRY" : "TOWN_DELETE_PREPARE";
        TownPersistence.audit(connection, null, actorId, actorName, action, "TOWN", townId.toString(), reason,
                "已安全归档；名称、小镇代码和区块保持锁定，等待移除投影");
        return current;
    }

    private static void snapshotMembers(Connection connection, UUID townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO town_archived_members
                    (town_id, player_uuid, role, joined_at, last_active_at, rules_revision)
                SELECT town_id, player_uuid, role, joined_at, last_active_at, rules_revision
                  FROM town_members WHERE town_id = ?
                """)) {
            statement.setBytes(1, uuid(townId));
            statement.executeUpdate();
        }
        try (PreparedStatement votes = connection.prepareStatement("""
                UPDATE governance_votes SET status = 'CANCELLED', settled_at = ?,
                       cancelled_reason = '小镇已归档'
                 WHERE town_id = ? AND status = 'OPEN'
                """);
             PreparedStatement transfers = connection.prepareStatement("""
                     UPDATE mayor_transfer_requests SET status = 'CANCELLED', decided_at = ?
                      WHERE town_id = ? AND status = 'PENDING'
                     """)) {
            long now = Instant.now().toEpochMilli();
            votes.setLong(1, now);
            votes.setBytes(2, uuid(townId));
            votes.executeUpdate();
            transfers.setLong(1, now);
            transfers.setBytes(2, uuid(townId));
            transfers.executeUpdate();
        }
    }

    private static boolean townReuseBlocked(Connection connection, UUID townId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT reuse_blocked FROM towns WHERE town_id = ?")) {
            statement.setBytes(1, uuid(townId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new ConflictException("小镇不存在");
                }
                return result.getBoolean("reuse_blocked");
            }
        }
    }

    public record Provisioning(UUID applicationId, TownSnapshot town, List<UUID> members) {
        public Provisioning {
            members = List.copyOf(members);
        }
    }

    public enum RecoveryMode {
        UNLOCK_FOR_CHANGES,
        CANCEL_AND_REFUND,
        FORCE_CLEANUP
    }

    public record PlayerDashboard(TownSnapshot town, ApplicationSnapshot application,
                                  List<JoinApplicationSnapshot> joinApplications,
                                  List<JoinApplicationSnapshot> incomingJoinApplications) {
        public PlayerDashboard {
            joinApplications = List.copyOf(joinApplications);
            incomingJoinApplications = List.copyOf(incomingJoinApplications);
        }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }

        public ConflictException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record MemberConflict(UUID playerId, String conflictType, UUID townId,
                                 String townName) {
    }

    public static final class MemberConflictException extends ConflictException {
        private final MemberConflict conflict;

        public MemberConflictException(MemberConflict conflict) {
            super("初始成员已经属于小镇“" + conflict.townName() + "”");
            this.conflict = Objects.requireNonNull(conflict, "conflict");
        }

        public MemberConflict conflict() {
            return conflict;
        }
    }

    public static final class NotFoundException extends ConflictException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static final class StorageUnavailableException extends RuntimeException {
        public StorageUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

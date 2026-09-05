package org.allivlisey.tianjitown.storage.town;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.land.InitialTerritory;

import static org.allivlisey.tianjitown.storage.town.TownPersistence.RULE_SEPARATOR;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.readUuid;
import static org.allivlisey.tianjitown.storage.town.TownSqlValues.uuid;

public final class TownRepository {
    private final TownJoinApplicationStore joinApplications;
    private final TownInvitationStore invitations;
    private final TownDeletionStore deletionStore;
    private final TownProfileStore profileStore;
    private final TownAuditStore auditStore;
    private final TownQueryStore queryStore;
    private final TownDatabase database;
    private final TownApplicationStore applicationStore;
    private final TownProvisioningStore provisioningStore;
    private final TownMembershipStore membershipStore;

    public TownRepository(DataSource dataSource, BooleanSupplier forbiddenThread) {
        this.database = new TownDatabase(dataSource, forbiddenThread);
        this.queryStore = new TownQueryStore(database);
        this.auditStore = new TownAuditStore(database);
        this.profileStore = new TownProfileStore(database);
        this.deletionStore = new TownDeletionStore(database);
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
        return queryStore.findTown(townId);
    }

    public Optional<TownSnapshot> findTownByName(String townName) {
        return queryStore.findTownByName(townName);
    }

    public Optional<TownSnapshot> findTownByMember(UUID playerId) {
        return queryStore.findTownByMember(playerId);
    }

    public PlayerDashboard dashboard(UUID playerId) {
        return queryStore.dashboard(playerId);
    }

    public List<TownSnapshot> listTowns(boolean includeArchived) {
        return queryStore.listTowns(includeArchived);
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
        deletionStore.deleteTown(townId, actorId, actorName, reason);
    }

    public TownSnapshot disbandTown(UUID townId, UUID mayorId, long expectedVersion) {
        return deletionStore.disbandTown(townId, mayorId, expectedVersion);
    }

    public void completeTownDeletion(UUID townId, UUID actorId, String actorName, String reason) {
        deletionStore.completeTownDeletion(townId, actorId, actorName, reason);
    }

    public boolean archiveTownForMissingProjection(UUID townId, String detail) {
        return deletionStore.archiveTownForMissingProjection(townId, detail);
    }

    public TownSnapshot updateTownProfile(UUID townId, ApplicationText profile, long expectedVersion,
                                          UUID actorId, String actorName, String reason) {
        return profileStore.updateTownProfile(townId, profile, expectedVersion, actorId, actorName, reason);
    }

    public List<AuditSnapshot> auditLog(int limit) {
        return auditStore.auditLog(limit);
    }

    public void recordAudit(UUID actorId, String actorName, String action, String targetType,
                            String targetId, String reason, String detail) {
        auditStore.recordAudit(actorId, actorName, action, targetType, targetId, reason, detail);
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

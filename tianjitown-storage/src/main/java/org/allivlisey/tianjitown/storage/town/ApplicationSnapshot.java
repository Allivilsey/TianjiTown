package org.allivlisey.tianjitown.storage.town;

import org.allivlisey.tianjitown.core.application.ApplicationStatus;
import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.land.InitialTerritory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApplicationSnapshot(
        UUID id,
        UUID applicantId,
        ApplicationText text,
        ApplicationStatus status,
        InitialTerritory territory,
        Instant reservationExpiresAt,
        UUID townId,
        String reviewMessage,
        String lastError,
        List<InitialMemberConfirmation> initialMembers,
        long applicationFeeMinor,
        FeeStatus applicationFeeStatus,
        long version,
        Instant submittedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public ApplicationSnapshot {
        initialMembers = initialMembers == null ? List.of() : List.copyOf(initialMembers);
        applicationFeeStatus = applicationFeeStatus == null
                ? FeeStatus.UNPAID : applicationFeeStatus;
    }

    public boolean initialMembersConfirmed() {
        return initialMembers.size() == 2 && initialMembers.stream().allMatch(member ->
                member.status() == InitialMemberConfirmation.Status.CONFIRMED);
    }

    public boolean needsInitialMemberReselection() {
        return initialMembers.stream().anyMatch(member ->
                member.status() == InitialMemberConfirmation.Status.REJECTED);
    }

    public enum FeeStatus {
        UNPAID,
        ESCROWED,
        CONSUMED,
        REFUND_PENDING,
        REFUNDED
    }
}

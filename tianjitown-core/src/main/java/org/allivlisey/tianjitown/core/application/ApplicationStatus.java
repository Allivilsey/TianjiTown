package cn.tianji.town.core.application;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum ApplicationStatus {
    DRAFT,
    SITE_SELECTED,
    SUBMITTED,
    UNDER_REVIEW,
    NEED_CHANGES,
    APPROVED_PROVISIONING,
    ACTIVE,
    REJECTED,
    CANCELLED,
    PROVISION_FAILED;

    private static final Map<ApplicationStatus, Set<ApplicationStatus>> TRANSITIONS = transitions();

    public boolean canTransitionTo(ApplicationStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean terminal() {
        return this == ACTIVE || this == REJECTED || this == CANCELLED;
    }

    public void requireTransitionTo(ApplicationStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("不允许将申请从 " + this + " 转为 " + target);
        }
    }

    private static Map<ApplicationStatus, Set<ApplicationStatus>> transitions() {
        Map<ApplicationStatus, Set<ApplicationStatus>> result = new EnumMap<>(ApplicationStatus.class);
        result.put(DRAFT, EnumSet.of(SITE_SELECTED, CANCELLED));
        result.put(SITE_SELECTED, EnumSet.of(DRAFT, SUBMITTED, CANCELLED));
        result.put(SUBMITTED, EnumSet.of(UNDER_REVIEW, NEED_CHANGES, APPROVED_PROVISIONING,
                REJECTED, CANCELLED));
        result.put(UNDER_REVIEW, EnumSet.of(NEED_CHANGES, APPROVED_PROVISIONING, REJECTED,
                CANCELLED));
        result.put(NEED_CHANGES, EnumSet.of(SITE_SELECTED, SUBMITTED, CANCELLED));
        result.put(APPROVED_PROVISIONING, EnumSet.of(ACTIVE, PROVISION_FAILED));
        result.put(PROVISION_FAILED, EnumSet.of(APPROVED_PROVISIONING, NEED_CHANGES, CANCELLED));
        return Map.copyOf(result);
    }
}

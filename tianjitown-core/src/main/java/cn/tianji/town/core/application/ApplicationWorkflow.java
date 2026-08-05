package cn.tianji.town.core.application;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class ApplicationWorkflow {
    private static final Map<ApplicationStatus, Set<ApplicationActor>> ACTORS = actors();

    private ApplicationWorkflow() {
    }

    public static void requireAllowed(ApplicationStatus source, ApplicationStatus target,
                                      ApplicationActor actor) {
        source.requireTransitionTo(target);
        if (!ACTORS.getOrDefault(target, Set.of()).contains(actor)) {
            throw new IllegalStateException(actor + " 无权将申请转为 " + target);
        }
    }

    private static Map<ApplicationStatus, Set<ApplicationActor>> actors() {
        Map<ApplicationStatus, Set<ApplicationActor>> result = new EnumMap<>(ApplicationStatus.class);
        result.put(ApplicationStatus.DRAFT, EnumSet.of(ApplicationActor.APPLICANT));
        result.put(ApplicationStatus.SITE_SELECTED, EnumSet.of(ApplicationActor.APPLICANT));
        result.put(ApplicationStatus.SUBMITTED, EnumSet.of(ApplicationActor.APPLICANT));
        result.put(ApplicationStatus.UNDER_REVIEW, EnumSet.of(ApplicationActor.ADMINISTRATOR));
        result.put(ApplicationStatus.NEED_CHANGES, EnumSet.of(ApplicationActor.ADMINISTRATOR));
        result.put(ApplicationStatus.APPROVED_PROVISIONING,
                EnumSet.of(ApplicationActor.ADMINISTRATOR));
        result.put(ApplicationStatus.ACTIVE, EnumSet.of(ApplicationActor.SYSTEM));
        result.put(ApplicationStatus.REJECTED, EnumSet.of(ApplicationActor.ADMINISTRATOR));
        result.put(ApplicationStatus.CANCELLED, EnumSet.of(ApplicationActor.APPLICANT));
        result.put(ApplicationStatus.PROVISION_FAILED, EnumSet.of(ApplicationActor.SYSTEM));
        return Map.copyOf(result);
    }
}

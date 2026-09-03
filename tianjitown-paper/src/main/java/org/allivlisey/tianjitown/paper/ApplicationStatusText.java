package cn.tianji.town.paper;

import cn.tianji.town.core.application.ApplicationStatus;

final class ApplicationStatusText {
    private ApplicationStatusText() {
    }

    static String messageKey(ApplicationStatus status) {
        return switch (status) {
            case DRAFT -> "application.status.draft";
            case SITE_SELECTED -> "application.status.site-selected";
            case SUBMITTED -> "application.status.submitted";
            case UNDER_REVIEW -> "application.status.under-review";
            case NEED_CHANGES -> "application.status.need-changes";
            case APPROVED_PROVISIONING -> "application.status.approved-provisioning";
            case ACTIVE -> "application.status.active";
            case REJECTED -> "application.status.rejected";
            case CANCELLED -> "application.status.cancelled";
            case PROVISION_FAILED -> "application.status.provision-failed";
        };
    }
}

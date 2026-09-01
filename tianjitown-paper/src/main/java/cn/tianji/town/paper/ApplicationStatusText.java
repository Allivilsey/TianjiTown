package cn.tianji.town.paper;

import cn.tianji.town.core.application.ApplicationStatus;

final class ApplicationStatusText {
    private ApplicationStatusText() {
    }

    static String messageKey(ApplicationStatus status) {
        return switch (status) {
            case DRAFT -> "dialog.application.status.draft";
            case SITE_SELECTED -> "dialog.application.status.site-selected";
            case SUBMITTED -> "dialog.application.status.submitted";
            case UNDER_REVIEW -> "dialog.application.status.under-review";
            case NEED_CHANGES -> "dialog.application.status.need-changes";
            case APPROVED_PROVISIONING -> "dialog.application.status.approved-provisioning";
            case ACTIVE -> "dialog.application.status.active";
            case REJECTED -> "dialog.application.status.rejected";
            case CANCELLED -> "dialog.application.status.cancelled";
            case PROVISION_FAILED -> "dialog.application.status.provision-failed";
        };
    }
}

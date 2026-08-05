package cn.tianji.town.core.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApplicationWorkflowTest {
    @Test
    void acceptsProductionHappyPathActors() {
        assertDoesNotThrow(() -> {
            ApplicationWorkflow.requireAllowed(ApplicationStatus.DRAFT,
                    ApplicationStatus.SITE_SELECTED, ApplicationActor.APPLICANT);
            ApplicationWorkflow.requireAllowed(ApplicationStatus.SITE_SELECTED,
                    ApplicationStatus.SUBMITTED, ApplicationActor.APPLICANT);
            ApplicationWorkflow.requireAllowed(ApplicationStatus.SUBMITTED,
                    ApplicationStatus.UNDER_REVIEW, ApplicationActor.ADMINISTRATOR);
            ApplicationWorkflow.requireAllowed(ApplicationStatus.UNDER_REVIEW,
                    ApplicationStatus.APPROVED_PROVISIONING, ApplicationActor.ADMINISTRATOR);
            ApplicationWorkflow.requireAllowed(ApplicationStatus.APPROVED_PROVISIONING,
                    ApplicationStatus.ACTIVE, ApplicationActor.SYSTEM);
        });
    }

    @Test
    void rejectsApplicantApprovalAndSkippingProvisioning() {
        assertThrows(IllegalStateException.class, () -> ApplicationWorkflow.requireAllowed(
                ApplicationStatus.UNDER_REVIEW, ApplicationStatus.APPROVED_PROVISIONING,
                ApplicationActor.APPLICANT));
        assertThrows(IllegalStateException.class, () -> ApplicationWorkflow.requireAllowed(
                ApplicationStatus.UNDER_REVIEW, ApplicationStatus.ACTIVE,
                ApplicationActor.ADMINISTRATOR));
    }
}

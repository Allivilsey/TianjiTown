package org.allivlisey.tianjitown.paper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Presentation contract for the application summary and submission confirmation pages. */
final class ApplicationSubmissionDialogRenderer {
    private static final String SUBMIT_READY_TOOLTIP = "tooltip.application.submit-ready";
    private static final String SUBMIT_WAITING_TOOLTIP = "tooltip.application.submit-waiting";
    private static final String SUBMIT_CONSEQUENCE =
            "dialog.confirmation.submit-application-consequence";

    private ApplicationSubmissionDialogRenderer() {
    }

    static List<String> submitTooltipKeys(boolean initialMembersConfirmed) {
        return initialMembersConfirmed
                ? List.of(SUBMIT_READY_TOOLTIP)
                : List.of(SUBMIT_WAITING_TOOLTIP);
    }

    static String confirmationConsequence(PluginMessages messages, String formattedFee) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(formattedFee, "formattedFee");
        return messages.rawText(SUBMIT_CONSEQUENCE, Map.of("amount", formattedFee));
    }
}

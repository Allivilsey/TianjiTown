package org.allivlisey.tianjitown.paper.ui.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ApplicationSubmissionDialogRendererTest {
    @Test
    void summarySubmitTooltipOnlyExplainsTheNextPage() {
        assertEquals(List.of("tooltip.application.submit-ready"),
                ApplicationSubmissionDialogRenderer.submitTooltipKeys(true));
        assertEquals(List.of("tooltip.application.submit-waiting"),
                ApplicationSubmissionDialogRenderer.submitTooltipKeys(false));
        assertFalse(ApplicationSubmissionDialogRenderer.submitTooltipKeys(true).stream()
                .anyMatch(key -> key.contains("submit-fee")));
    }

}

package org.allivlisey.tianjitown.integrations.residence;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;

class ResidenceDeletionGuardTest {
    @Test
    void resolvesNonPlayerDeletionLogWithSanitizedResidenceName() {
        String rendered = ResidenceDeletionGuard.resolveNonPlayerDeletionMessage(
                (key, placeholders) -> {
                    assertEquals("log.residence.non-player-deletion-cancelled", key);
                    assertEquals("town＆�", placeholders.get("residence"));
                    return "custom deletion notice: " + placeholders.get("residence");
                }, "town&§");

        assertEquals("custom deletion notice: town＆�", rendered);
    }

    @Test
    void resolvesFailureLogsWithSanitizedExceptionDetail() {
        String rendered = ResidenceDeletionGuard.resolveFailureMessage((key, placeholders) -> {
            assertEquals("log.residence.deletion-recovery-failure", key);
            assertEquals("boom＆�", placeholders.get("detail"));
            return "custom recovery failure: " + placeholders.get("detail");
        }, "log.residence.deletion-recovery-failure",
                new IllegalStateException("boom&§"));

        assertEquals("custom recovery failure: boom＆�", rendered);
    }

    @Test
    void fallsBackToStableKeyWhenTheResolverFails() {
        assertEquals("log.residence.deletion-guard-failure {detail=boom}",
                ResidenceDeletionGuard.resolveFailureMessage((key, placeholders) -> {
                    throw new IllegalStateException("resolver unavailable");
                }, "log.residence.deletion-guard-failure", new IllegalStateException("boom")));
    }
}

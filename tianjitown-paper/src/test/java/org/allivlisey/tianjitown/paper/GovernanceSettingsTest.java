package org.allivlisey.tianjitown.paper;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class GovernanceSettingsTest {
    @Test
    void exposesFixedGovernanceDurations() {
        GovernanceSettings settings = GovernanceSettings.fixed();

        assertEquals(Duration.ofHours(24), settings.transferConfirmation());
        assertEquals(Duration.ofDays(30), settings.activeMemberWindow());
        assertEquals(Duration.ZERO, settings.minimumMembership());
        assertEquals(Duration.ofHours(72), settings.voteDuration());
    }

    @Test
    void reusesTheFixedRuleSet() {
        assertSame(GovernanceSettings.fixed(), GovernanceSettings.fixed());
    }
}

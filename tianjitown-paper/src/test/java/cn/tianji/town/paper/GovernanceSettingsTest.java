package cn.tianji.town.paper;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceSettingsTest {
    @Test
    void usesSafeDefaultsWhenGovernanceConfigurationIsMissing() {
        GovernanceSettings settings = GovernanceSettings.load(new MemoryConfiguration());

        assertEquals(Duration.ofHours(24), settings.transferConfirmation());
        assertEquals(Duration.ofDays(30), settings.activeMemberWindow());
        assertEquals(Duration.ZERO, settings.minimumMembership());
        assertEquals(Duration.ofHours(72), settings.voteDuration());
    }

    @Test
    void rejectsEveryOverflowingGovernanceDurationWithConfigurationPath() {
        for (String path : List.of(
                "phase2.governance.transfer-confirmation-hours",
                "phase2.voting.active-member-days",
                "phase2.voting.minimum-membership-days",
                "phase2.voting.duration-hours")) {
            MemoryConfiguration config = new MemoryConfiguration();
            config.set(path, Long.MAX_VALUE);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> GovernanceSettings.load(config));
            assertTrue(exception.getMessage().contains(path));
        }
    }

    @Test
    void rejectsNonPositiveLifetimesAndNegativeWindows() {
        MemoryConfiguration zeroVoteDuration = new MemoryConfiguration();
        zeroVoteDuration.set("phase2.voting.duration-hours", 0);
        assertThrows(IllegalArgumentException.class,
                () -> GovernanceSettings.load(zeroVoteDuration));

        MemoryConfiguration negativeWindow = new MemoryConfiguration();
        negativeWindow.set("phase2.voting.active-member-days", -1);
        assertThrows(IllegalArgumentException.class,
                () -> GovernanceSettings.load(negativeWindow));
    }
}

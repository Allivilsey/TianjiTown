package org.allivlisey.tianjitown.paper.config;

import java.time.Duration;

/**
 * 治理时间窗口属于玩法规则，固定在代码中，不从 config.yml 读取。
 */
public record GovernanceSettings(Duration transferConfirmation, Duration activeMemberWindow,
                          Duration minimumMembership, Duration voteDuration) {
    private static final GovernanceSettings FIXED = new GovernanceSettings(
            Duration.ofHours(24), Duration.ofDays(30), Duration.ZERO, Duration.ofHours(72));

    public static GovernanceSettings fixed() {
        return FIXED;
    }
}

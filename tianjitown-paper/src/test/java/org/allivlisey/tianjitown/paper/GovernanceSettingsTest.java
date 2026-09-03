package org.allivlisey.tianjitown.paper;

import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceSettingsTest {
    @TempDir
    Path temporaryDirectory;

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
                "governance.transfer-confirmation-hours",
                "governance.voting.active-member-days",
                "governance.voting.minimum-membership-days",
                "governance.voting.duration-hours")) {
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
        zeroVoteDuration.set("governance.voting.duration-hours", 0);
        assertThrows(IllegalArgumentException.class,
                () -> GovernanceSettings.load(zeroVoteDuration));

        MemoryConfiguration negativeWindow = new MemoryConfiguration();
        negativeWindow.set("governance.voting.active-member-days", -1);
        assertThrows(IllegalArgumentException.class,
                () -> GovernanceSettings.load(negativeWindow));
    }

    @Test
    void rendersGovernanceValidationMessagesAndUsesReloadedOverrides() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        for (String key : List.of(
                "validation.governance.duration-non-negative",
                "validation.governance.duration-positive")) {
            String rendered = messages.plainText(key, Map.of("path", "governance.example"));
            assertFalse(rendered.isBlank());
            assertFalse(rendered.contains("缺少消息配置"));
            assertFalse(rendered.contains("{path}"));
        }

        assertEquals("governance.voting.active-member-days 必须为可安全计算的非负整数",
                reject(configuration("governance.voting.active-member-days", -1), messages)
                        .getMessage());
        assertEquals("governance.voting.duration-hours 必须为可安全计算的正整数",
                reject(configuration("governance.voting.duration-hours", 0), messages)
                        .getMessage());
        assertEquals("governance.transfer-confirmation-hours 必须为可安全计算的正整数",
                reject(configuration("governance.transfer-confirmation-hours", Long.MAX_VALUE),
                        messages).getMessage());

        YamlConfiguration override = new YamlConfiguration();
        override.set("validation.governance.duration-positive", "自定义治理时长错误: {path}");
        override.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义治理时长错误: governance.voting.duration-hours",
                reject(configuration("governance.voting.duration-hours", 0), messages)
                        .getMessage());
    }

    private static IllegalArgumentException reject(MemoryConfiguration config,
                                                     PluginMessages messages) {
        return assertThrows(IllegalArgumentException.class,
                () -> GovernanceSettings.load(config, messages::plainText));
    }

    private static MemoryConfiguration configuration(String path, Object value) {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set(path, value);
        return config;
    }
}

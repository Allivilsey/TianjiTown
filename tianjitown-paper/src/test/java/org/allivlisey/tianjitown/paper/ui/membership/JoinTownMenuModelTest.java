package org.allivlisey.tianjitown.paper.ui.membership;
import org.allivlisey.tianjitown.paper.message.PluginMessages;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.town.TownStatus;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JoinTownMenuModelTest {
    private static final UUID TOWN_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @TempDir
    Path temporaryDirectory;

    @Test
    void summaryKeepsOnlyCodeAndDescriptionWhileRulesUseTheSharedPage() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        TownSnapshot town = town("欢迎来到小镇", List.of("规则一", "规则二"));

        JoinTownMenuModel model = JoinTownMenuModel.create(messages, town);

        assertEquals(List.of(
                messages.rawText("dialog.common.town-code", Map.of("code", "TESTTOWN")),
                messages.rawText("dialog.common.town-description", Map.of(
                        "description", "欢迎来到小镇"))), model.summaryLore());
        assertEquals(new JoinTownMenuModel.Entry(10, "town.rules", "tooltip.town.rules",
                "JOIN_TOWN_RULES", TOWN_ID.toString()), model.rulesEntry());
        assertEquals(new JoinTownMenuModel.Entry(13, "join.apply", List.of(
                "tooltip.join.submit-expiry", "common.join-application-limit"),
                "CONFIRM_APPLY_JOIN", TOWN_ID.toString()), model.applyEntry());
        assertFalse(String.join("\n", model.summaryLore()).contains("规则一"));
        assertFalse(String.join("\n", model.summaryLore()).contains(" | "));
    }

    private static TownSnapshot town(String description, List<String> rules) {
        ApplicationText profile = new ApplicationText("测试小镇", "TESTTOWN",
                description, rules);
        return new TownSnapshot(TOWN_ID, profile, TownStatus.ACTIVE, UUID.randomUUID(),
                1L, 2L, Instant.parse("2026-01-01T00:00:00Z"), null, "READY", null);
    }
}

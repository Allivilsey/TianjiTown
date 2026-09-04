package org.allivlisey.tianjitown.paper;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownDetailsMenuModelTest {
    private static final UUID TOWN_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @TempDir
    Path temporaryDirectory;

    @Test
    void summaryShowsTheCompleteDescriptionWithoutRuleCountAndKeepsRulesEntry() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String description = "这是完整的小镇简介，欢迎居民彼此尊重";
        TownSnapshot town = town(description, List.of("规则一", "规则二", "规则三"));

        TownDetailsMenuModel model = TownDetailsMenuModel.create(messages, town);

        assertEquals(List.of(
                messages.rawText("dialog.common.residence-name", Map.of(
                        "residence", town.residenceName())),
                messages.rawText("dialog.common.town-description", Map.of(
                        "description", description))), model.summaryLore());
        assertEquals(2, model.summaryLore().size());
        assertTrue(model.summaryLore().get(1).contains(description));
        assertFalse(String.join("\n", model.summaryLore()).contains("规则数"));
        assertEquals(new TownDetailsMenuModel.RulesEntry(10, "town.rules",
                "tooltip.town.rules", "TOWN_RULES", TOWN_ID.toString()), model.rulesEntry());
        assertFalse(messages.hasMessage("dialog.town.rule-count"));
    }

    @Test
    void anEmptyDescriptionStillUsesTheConfiguredDescriptionLine() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        TownSnapshot town = town("", List.of("至少一条规则"));

        TownDetailsMenuModel model = TownDetailsMenuModel.create(messages, town);

        assertEquals(messages.rawText("dialog.common.town-description",
                Map.of("description", "")), model.summaryLore().get(1));
        assertFalse(String.join("\n", model.summaryLore()).contains("规则数"));
    }

    private static TownSnapshot town(String description, List<String> rules) {
        ApplicationText profile = new ApplicationText("测试小镇", "TT", "TESTTOWN",
                description, rules);
        return new TownSnapshot(TOWN_ID, profile, TownStatus.ACTIVE, UUID.randomUUID(),
                1L, 2L, Instant.parse("2026-01-01T00:00:00Z"), null, "READY", null);
    }
}

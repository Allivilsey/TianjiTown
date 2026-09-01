package cn.tianji.town.paper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEditorDialogRendererTest {
    private static final UUID TOWN_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void everyRuleHasOneAdjacentDeleteActionInATwoColumnRow() {
        for (List<String> rules : List.of(
                List.of("不得破坏公共设施", "请尊重其他居民"),
                List.of("不得破坏公共设施", "请尊重其他居民", "长规则".repeat(80)))) {
            RuleEditorDialogRenderer.Layout layout = RuleEditorDialogRenderer.layout(TOWN_ID, 42,
                    rules);

            assertEquals(2, layout.columns());
            assertEquals(rules.size(), layout.rows().size());
            for (int index = 0; index < layout.rows().size(); index++) {
                RuleEditorDialogRenderer.Row row = layout.rows().get(index);
                assertEquals(index + 1, row.displayIndex());
                assertEquals(index, row.deleteTarget().ruleIndex());
                assertEquals(row.rule(), row.deleteTarget().expectedRule());
                assertEquals(42, row.deleteTarget().pageVersion());
            }
        }
    }

    @Test
    void deleteTargetRoundTripsVersionIndexAndExpectedText() {
        RuleEditorDialogRenderer.DeleteTarget target = new RuleEditorDialogRenderer.DeleteTarget(
                TOWN_ID, 9, 1, "规则: 包含冒号、换行\n和 Unicode");

        String encoded = target.encode();

        assertEquals(target, RuleEditorDialogRenderer.DeleteTarget.decode(encoded));
        assertFalse(encoded.contains("规则"));
        assertTrue(encoded.startsWith(TOWN_ID + ":9:1:"));
    }
}

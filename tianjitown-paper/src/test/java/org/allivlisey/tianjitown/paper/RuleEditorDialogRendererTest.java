package org.allivlisey.tianjitown.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEditorDialogRendererTest {
    private static final UUID TOWN_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @TempDir
    Path temporaryDirectory;

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
    void addAndDeleteControlsStayCompact() {
        assertTrue(RuleEditorDialogRenderer.ADD_WIDTH < RuleEditorDialogRenderer.PREVIEW_WIDTH
                + RuleEditorDialogRenderer.DELETE_WIDTH);
        assertEquals(RuleEditorDialogRenderer.DELETE_WIDTH, 28);
    }

    @Test
    void declaresTheRuleInputBeforeAddAndAnyPageActions() {
        RuleEditorDialogRenderer.Layout layout = RuleEditorDialogRenderer.layout(TOWN_ID, 1,
                List.of("规则一"));

        assertEquals(List.of(RuleEditorDialogRenderer.Section.HEADING_AND_RULES,
                RuleEditorDialogRenderer.Section.RULE_INPUT,
                RuleEditorDialogRenderer.Section.ADD_RULE,
                RuleEditorDialogRenderer.Section.DELETE_CONTROLS,
                RuleEditorDialogRenderer.Section.PAGE_ACTIONS), layout.sections());
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

    @Test
    void rendersRuleEditorValidationMessagesThroughTheConfiguredResolver() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        IllegalArgumentException pageVersion = assertThrows(IllegalArgumentException.class,
                () -> RuleEditorDialogRenderer.layout(TOWN_ID, -1, List.of(),
                        messages::rawText));
        IllegalArgumentException deleteRequest = assertThrows(IllegalArgumentException.class,
                () -> RuleEditorDialogRenderer.DeleteTarget.decode("invalid",
                        messages::rawText));

        assertEquals("&c规则页面版本不能为负数", pageVersion.getMessage());
        assertEquals("&c规则删除请求无效，请刷新界面", deleteRequest.getMessage());
        for (String key : List.of(
                "validation.rule-editor.display-index",
                "validation.rule-editor.page-version",
                "validation.rule-editor.rule-index",
                "dialog.rules.delete-request-invalid")) {
            String rendered = messages.rawText(key);
            assertFalse(rendered.isBlank());
            assertFalse(rendered.contains("{"));
            assertFalse(rendered.contains("缺少消息配置"));
        }
    }

    @Test
    void usesReloadedRuleEditorMessagesForNewValidationFailures() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("validation.rule-editor.page-version", "&d自定义页面版本错误");
        configuration.set("dialog.rules.delete-request-invalid", "&e自定义删除请求错误");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("&d自定义页面版本错误", assertThrows(IllegalArgumentException.class,
                () -> RuleEditorDialogRenderer.layout(TOWN_ID, -1, List.of(),
                        messages::rawText)).getMessage());
        assertEquals("&e自定义删除请求错误", assertThrows(IllegalArgumentException.class,
                () -> RuleEditorDialogRenderer.DeleteTarget.decode("invalid",
                        messages::rawText)).getMessage());
    }
}

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
    void everyRuleHasAnInlineDeleteActionInTheSharedSingleColumnLayout() {
        for (List<String> rules : List.of(
                List.of("不得破坏公共设施", "请尊重其他居民"),
                List.of("不得破坏公共设施", "请尊重其他居民", "长规则".repeat(80)))) {
            RuleEditorDialogRenderer.Layout layout = RuleEditorDialogRenderer.layout(TOWN_ID, 42,
                    rules);

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
    void sharedEditorControlsUseTheExpectedWidths() {
        assertEquals(400, RuleEditorDialogRenderer.INLINE_RULE_WIDTH);
        assertEquals(150, RuleEditorDialogRenderer.SINGLE_COLUMN_ACTION_WIDTH);
    }

    @Test
    void usesTheSameInlineRuleLayoutForBothEditors() {
        RuleEditorDialogRenderer.Layout layout = RuleEditorDialogRenderer.layout(TOWN_ID, 1,
                List.of("规则一"));

        assertEquals(List.of(RuleEditorDialogRenderer.Section.HEADING,
                RuleEditorDialogRenderer.Section.RULE_INPUT,
                RuleEditorDialogRenderer.Section.ADD_RULE,
                RuleEditorDialogRenderer.Section.INLINE_RULE_ROWS,
                RuleEditorDialogRenderer.Section.PAGE_ACTIONS), layout.editorSections());
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
    void rendersInvalidDeleteRequestMessageThroughTheConfiguredResolver() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        IllegalArgumentException deleteRequest = assertThrows(IllegalArgumentException.class,
                () -> RuleEditorDialogRenderer.DeleteTarget.decode("invalid",
                        messages::rawText));

        assertEquals("&c规则删除请求无效，请刷新界面", deleteRequest.getMessage());
        String rendered = messages.rawText("dialog.rules.delete-request-invalid");
        assertFalse(rendered.isBlank());
        assertFalse(rendered.contains("{"));
        assertFalse(rendered.contains("缺少消息配置"));
    }

    @Test
    void doesNotValidateInternalPageVersionOrRuleIndex() {
        RuleEditorDialogRenderer.Layout layout = RuleEditorDialogRenderer.layout(TOWN_ID, -1,
                List.of("规则"));
        RuleEditorDialogRenderer.DeleteTarget target =
                new RuleEditorDialogRenderer.DeleteTarget(TOWN_ID, -1, -1, "规则");

        assertEquals(-1, layout.rows().getFirst().deleteTarget().pageVersion());
        assertEquals(-1, target.pageVersion());
        assertEquals(-1, target.ruleIndex());
    }

    @Test
    void usesReloadedRuleEditorMessagesForInvalidDeleteRequests() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("dialog.rules.delete-request-invalid", "&e自定义删除请求错误");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("&e自定义删除请求错误", assertThrows(IllegalArgumentException.class,
                () -> RuleEditorDialogRenderer.DeleteTarget.decode("invalid",
                        messages::rawText)).getMessage());
    }
}

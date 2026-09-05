package org.allivlisey.tianjitown.paper.ui.home;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.paper.ui.DialogRoute;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlyRulesDialogRendererTest {
    private static final UUID TOWN_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @TempDir
    Path temporaryDirectory;

    @Test
    void townDetailsAndJoinApplicationUseIdenticalRuleContent() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        List<String> rules = List.of("不得破坏公共设施", "长规则".repeat(80));
        ReadOnlyRulesDialogRenderer.Layout details = ReadOnlyRulesDialogRenderer.layout(
                "测试小镇", rules, new DialogRoute("TOWN", TOWN_ID.toString()));
        ReadOnlyRulesDialogRenderer.Layout join = ReadOnlyRulesDialogRenderer.layout(
                "测试小镇", rules, new DialogRoute("JOIN_TOWN", TOWN_ID.toString()));

        assertEquals(plain(messages, details), plain(messages, join));
        assertEquals(new DialogRoute("TOWN", TOWN_ID.toString()), details.returnRoute());
        assertEquals(new DialogRoute("JOIN_TOWN", TOWN_ID.toString()), join.returnRoute());
        assertTrue(plain(messages, join).contains("1. 不得破坏公共设施"));
        assertTrue(plain(messages, join).contains("2. " + rules.get(1)));
        assertTrue(plain(messages, join).contains("\n\n1."));
    }

    @Test
    void rendersTheSameEmptyAndNumberedLayoutsForEveryEntryPoint() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        for (List<String> rules : List.<List<String>>of(List.of(), List.of("规则一"),
                List.of("规则一", "规则二", "规则三"))) {
            ReadOnlyRulesDialogRenderer.Layout details = ReadOnlyRulesDialogRenderer.layout(
                    "测试小镇", rules, new DialogRoute("TOWN", TOWN_ID.toString()));
            ReadOnlyRulesDialogRenderer.Layout join = ReadOnlyRulesDialogRenderer.layout(
                    "测试小镇", rules, new DialogRoute("JOIN_TOWN", TOWN_ID.toString()));

            String rendered = plain(messages, details);
            assertEquals(rendered, plain(messages, join));
            assertEquals(rules.isEmpty(), details.showsEmptyState());
            if (rules.isEmpty()) {
                assertTrue(rendered.contains(messages.plainText("dialog.rules.empty")));
            } else {
                assertFalse(rendered.contains(messages.plainText("dialog.rules.empty")));
                for (int index = 0; index < rules.size(); index++) {
                    assertTrue(rendered.contains((index + 1) + ". " + rules.get(index)));
                }
            }
        }
    }

    @Test
    void filtersLegacyFormattingFromTownAndRuleText() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        ReadOnlyRulesDialogRenderer.Layout layout = ReadOnlyRulesDialogRenderer.layout("&a小镇",
                List.of("§c规则"), new DialogRoute("JOIN_TOWN", TOWN_ID.toString()));

        String rendered = plain(messages, layout);

        assertTrue(rendered.contains("＆a小镇"));
        assertTrue(rendered.contains("�c规则"));
        assertFalse(rendered.contains("§"));
    }

    private static String plain(PluginMessages messages, ReadOnlyRulesDialogRenderer.Layout layout) {
        return PlainTextComponentSerializer.plainText().serialize(
                ReadOnlyRulesDialogRenderer.content(messages, layout));
    }
}

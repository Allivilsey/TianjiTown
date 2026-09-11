package org.allivlisey.tianjitown.paper.command;
import org.allivlisey.tianjitown.paper.message.PluginMessages;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TownCommandParserTest {
    private static final List<String> TOWNS = List.of("sky", "skycity");

    @Test
    void parsesTownCodeAndTrailingReason() {
        TownCommandParser.NamedReason parsed = TownCommandParser.namedReason(new String[]{
                "town", "delete", "skycity", "测试", "删除"
        }, 2, TOWNS);

        assertEquals("skycity", parsed.townName());
        assertEquals("测试 删除", parsed.reason());
    }

    @Test
    void acceptsTownCodeIgnoringCase() {
        TownCommandParser.NamedReason parsed = TownCommandParser.namedReason(new String[]{
                "town", "delete", "SKYCITY", "管理员", "批准"
        }, 2, TOWNS);

        assertEquals("skycity", parsed.townName());
        assertEquals("管理员 批准", parsed.reason());
    }

    @Test
    void rejectsDisplayNamesAndSplitCodes() {
        assertThrows(IllegalArgumentException.class, () -> TownCommandParser.exactName(
                new String[]{"天际之城"}, 0, TOWNS));
        assertThrows(IllegalArgumentException.class, () -> TownCommandParser.exactName(
                new String[]{"sky", "city"}, 0, TOWNS));
    }

    @Test
    void parsesPlayerAndRequiredReason() {
        TownCommandParser.NamedPlayerReason parsed = TownCommandParser.namedPlayerReason(new String[]{
                "member", "add", "skycity", "PlayerOne", "管理员", "代办"
        }, 2, TOWNS);

        assertEquals("skycity", parsed.townName());
        assertEquals("PlayerOne", parsed.player());
        assertEquals("管理员 代办", parsed.reason());
    }

    @Test
    void parsesOptionalLiteralAfterTownName() {
        TownCommandParser.NamedAction check = TownCommandParser.namedAction(new String[]{
                "land", "reconcile", "skycity"
        }, 2, TOWNS, List.of("repair"));
        TownCommandParser.NamedAction repair = TownCommandParser.namedAction(new String[]{
                "land", "reconcile", "skycity", "repair"
        }, 2, TOWNS, List.of("repair"));

        assertNull(check.action());
        assertEquals("repair", repair.action());
    }

    @Test
    void rejectsMissingReasonAndInsertedPlaceholder() {
        assertThrows(IllegalArgumentException.class, () -> TownCommandParser.namedReason(
                new String[]{"application", "change", "skycity"}, 2, TOWNS));
        TownCommandParser.ParseException exception = assertThrows(TownCommandParser.ParseException.class,
                () -> TownCommandParser.namedReason(new String[]{
                        "application", "approve", "skycity", "<reason>"
                }, 2, TOWNS));

        assertEquals("chat.parser.placeholder", exception.messageKey());
        assertEquals(Map.of("placeholder", "<reason>"), exception.placeholders());
        assertEquals("chat.parser.placeholder", exception.getMessage());
    }

    @Test
    void resolvesParserErrorFromMessagesAndHonorsReload(@TempDir Path temporaryDirectory)
            throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        TownCommandParser.ParseException exception = assertThrows(
                TownCommandParser.ParseException.class,
                () -> TownCommandParser.namedAction(new String[]{
                        "land", "reconcile", "skycity", "rotate"
                }, 2, TOWNS, List.of("repair")));

        assertEquals("chat.parser.unsupported-action", exception.messageKey());
        assertEquals(Map.of("action", "rotate"), exception.placeholders());
        assertTrue(messages.hasMessage("chat.parser.unsupported-action"));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("chat.parser.unsupported-action", "自定义操作参数错误: {action}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义操作参数错误: rotate",
                messages.plainText(exception.messageKey(), exception.placeholders()));
    }
}

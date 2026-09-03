package org.allivlisey.tianjitown.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TownCommandParserTest {
    private static final List<String> TOWNS = List.of("天际", "天际 之城");

    @Test
    void parsesLongestTownNameAndTrailingReason() {
        TownCommandParser.NamedReason parsed = TownCommandParser.namedReason(new String[]{
                "town", "delete", "天际", "之城", "测试", "删除"
        }, 2, TOWNS);

        assertEquals("天际 之城", parsed.townName());
        assertEquals("测试 删除", parsed.reason());
    }

    @Test
    void acceptsTownNameWithoutStoredSpaces() {
        TownCommandParser.NamedReason parsed = TownCommandParser.namedReason(new String[]{
                "application", "approve", "天际之城", "管理员", "批准"
        }, 2, TOWNS);

        assertEquals("天际 之城", parsed.townName());
        assertEquals("管理员 批准", parsed.reason());
    }

    @Test
    void parsesPlayerAndRequiredReason() {
        TownCommandParser.NamedPlayerReason parsed = TownCommandParser.namedPlayerReason(new String[]{
                "member", "add", "天际", "之城", "PlayerOne", "管理员", "代办"
        }, 2, TOWNS);

        assertEquals("天际 之城", parsed.townName());
        assertEquals("PlayerOne", parsed.player());
        assertEquals("管理员 代办", parsed.reason());
    }

    @Test
    void parsesOptionalLiteralAfterTownName() {
        TownCommandParser.NamedAction check = TownCommandParser.namedAction(new String[]{
                "land", "reconcile", "天际之城"
        }, 2, TOWNS, List.of("repair"));
        TownCommandParser.NamedAction repair = TownCommandParser.namedAction(new String[]{
                "land", "reconcile", "天际", "之城", "repair"
        }, 2, TOWNS, List.of("repair"));

        assertNull(check.action());
        assertEquals("repair", repair.action());
    }

    @Test
    void rejectsMissingReasonAndInsertedPlaceholder() {
        assertThrows(IllegalArgumentException.class, () -> TownCommandParser.namedReason(
                new String[]{"application", "change", "天际之城"}, 2, TOWNS));
        TownCommandParser.ParseException exception = assertThrows(TownCommandParser.ParseException.class,
                () -> TownCommandParser.namedReason(new String[]{
                        "application", "approve", "天际之城", "<reason>"
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
                        "land", "reconcile", "天际之城", "rotate"
                }, 2, TOWNS, List.of("repair")));

        assertEquals("chat.parser.unsupported-action", exception.messageKey());
        assertEquals(Map.of("action", "rotate"), exception.placeholders());
        assertEquals("不支持的操作参数: rotate",
                messages.plainText(exception.messageKey(), exception.placeholders()));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("chat.parser.unsupported-action", "自定义操作参数错误: {action}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义操作参数错误: rotate",
                messages.plainText(exception.messageKey(), exception.placeholders()));
    }
}

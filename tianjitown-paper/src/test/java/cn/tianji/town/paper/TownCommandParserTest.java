package cn.tianji.town.paper;

import org.junit.jupiter.api.Test;

import java.util.List;

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
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> TownCommandParser.namedReason(new String[]{
                        "application", "approve", "天际之城", "<原因>"
                }, 2, TOWNS));

        assertEquals("请将 <原因> 替换为实际内容", exception.getMessage());
    }
}

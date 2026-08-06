package cn.tianji.town.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownCommandParserTest {
    @Test
    void parsesFullTownNameAndReasonOptions() {
        TownCommandParser.NamedReason parsed = TownCommandParser.namedReason(new String[]{
                "town", "delete", "天际", "之城", "--reason", "测试", "删除", "--confirm"
        }, 2, "默认原因");

        assertEquals("天际 之城", parsed.townName());
        assertEquals("测试 删除", parsed.reason());
        assertTrue(parsed.confirmed());
        assertTrue(parsed.explicitReason());
    }

    @Test
    void parsesPlayerAndRequiredReason() {
        TownCommandParser.NamedPlayerReason parsed = TownCommandParser.namedPlayerReason(new String[]{
                "member", "add", "天际", "之城", "--player", "PlayerOne",
                "--reason", "管理员", "代办"
        }, 2);

        assertEquals("天际 之城", parsed.townName());
        assertEquals("PlayerOne", parsed.player());
        assertEquals("管理员 代办", parsed.reason());
    }

    @Test
    void rejectsMissingPlayerDelimiter() {
        assertThrows(IllegalArgumentException.class, () -> TownCommandParser.namedPlayerReason(
                new String[]{"member", "add", "天际城", "PlayerOne"}, 2));
    }
}

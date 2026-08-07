package cn.tianji.town.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownCommandParserTest {
    @Test
    void parsesFullTownNameAndReasonOptions() {
        TownCommandParser.NamedReason parsed = TownCommandParser.requiredNamedReason(new String[]{
                "town", "delete", "天际", "之城", "--reason", "测试", "删除", "--confirm"
        }, 2);

        assertEquals("天际 之城", parsed.townName());
        assertEquals("测试 删除", parsed.reason());
        assertTrue(parsed.confirmed());
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

    @Test
    void rejectsMissingReasonWithoutUsingDefault() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> TownCommandParser.requiredNamedReason(new String[]{
                        "application", "change", "天际", "之城"
                }, 2));

        assertEquals("必须使用 --reason <原因> 填写原因", exception.getMessage());
    }

    @Test
    void rejectsEmptyExplicitReason() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> TownCommandParser.requiredNamedReason(new String[]{
                        "application", "approve", "天际城", "--reason"
                }, 2));

        assertEquals("必须填写原因", exception.getMessage());
    }
}

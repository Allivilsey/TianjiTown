package cn.tianji.town.core.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationTextTest {
    @Test
    void normalizesNamesForUniqueKeys() {
        ApplicationText text = new ApplicationText("  天 际 镇  ", " TJ ", "SKY", "简介",
                List.of("规则"));
        assertEquals("天际镇", text.normalizedName());
        assertEquals("tj", text.normalizedShortName());
        assertEquals("sky", text.normalizedResidenceName());
        assertTrue(text.validate().isEmpty());

        assertEquals(ApplicationText.normalizeNameKey("Å 镇"),
                ApplicationText.normalizeNameKey("A\u030A镇"));
        assertEquals(ApplicationText.normalizeNameKey("天  际镇"),
                ApplicationText.normalizeNameKey(" 天际 镇 "));
        assertEquals("atown", ApplicationText.normalizeNameKey("ATown"));
        assertEquals("ａtown", ApplicationText.normalizeNameKey("ＡTown"));
    }

    @Test
    void rejectsFormattingAndUnsafeNameCharacters() {
        ApplicationText text = new ApplicationText("<red>镇", "T/J", "S K", "§c简介",
                List.of("规则"));
        assertFalse(text.validate().isEmpty());
        assertTrue(text.validate().stream().anyMatch(error -> error.contains("格式")));
        assertTrue(text.validate().stream().anyMatch(error -> error.contains("小镇代码")));
    }

    @Test
    void describesRuleCountWithNaturalRangeNotation() {
        ApplicationText text = new ApplicationText("天际镇", "TJ", "SKY", "简介", List.of());
        assertTrue(text.validate().contains("规则数量必须为 1~50"));
    }
}

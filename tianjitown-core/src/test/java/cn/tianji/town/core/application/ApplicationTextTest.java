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
    }

    @Test
    void rejectsFormattingAndUnsafeNameCharacters() {
        ApplicationText text = new ApplicationText("<red>镇", "T/J", "S K", "§c简介",
                List.of("规则"));
        assertFalse(text.validate().isEmpty());
        assertTrue(text.validate().stream().anyMatch(error -> error.contains("格式")));
        assertTrue(text.validate().stream().anyMatch(error -> error.contains("领地名称")));
    }
}

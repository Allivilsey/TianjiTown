package org.allivlisey.tianjitown.core.application;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertTrue(text.validate().stream().anyMatch(issue ->
                issue.code() == ApplicationText.ValidationIssue.Code.NAME_FORMAT));
        assertTrue(text.validate().stream().anyMatch(issue ->
                issue.code() == ApplicationText.ValidationIssue.Code.RESIDENCE_NAME_CHARACTERS));
    }

    @Test
    void describesRuleCountWithNaturalRangeNotation() {
        ApplicationText text = new ApplicationText("天际镇", "TJ", "SKY", "简介", List.of());
        assertEquals(List.of(new ApplicationText.ValidationIssue(
                        ApplicationText.ValidationIssue.Code.RULE_COUNT,
                        Map.of("minimum", "1", "maximum", "50"))), text.validate());
    }

    @Test
    void exposesStructuredRuleDetailsWithoutRenderingPlayerTextInCore() {
        ApplicationText text = new ApplicationText("天际镇", "TJ", "SKY", "简介",
                List.of("第一条".repeat(101)));

        assertEquals(List.of(new ApplicationText.ValidationIssue(
                        ApplicationText.ValidationIssue.Code.RULE_LENGTH,
                        Map.of("index", "1", "maximum", "300"))), text.validate());

        ApplicationText.ValidationException exception = assertThrows(
                ApplicationText.ValidationException.class, text::requireValid);
        assertEquals(text.validate(), exception.issues());
        assertEquals("APPLICATION_TEXT_VALIDATION_FAILED", exception.getMessage());
    }
}

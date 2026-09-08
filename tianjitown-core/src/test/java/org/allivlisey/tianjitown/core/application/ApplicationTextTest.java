package org.allivlisey.tianjitown.core.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationTextTest {
    @ParameterizedTest
    @CsvSource({"1,false", "2,true", "24,true", "25,false"})
    void checksExactNameLengthBoundaries(int length, boolean valid) {
        var text = new ApplicationText("镇".repeat(length), "sky", "简介", List.of("规则"));
        assertEquals(valid, text.validate().isEmpty());
        if (!valid) assertEquals(List.of(new ApplicationText.ValidationIssue(
                ApplicationText.ValidationIssue.Code.NAME_LENGTH,
                Map.of("minimum", "2", "maximum", "24"))), text.validate());
    }

    @ParameterizedTest
    @CsvSource({"500,true", "501,false"})
    void checksExactDescriptionLengthBoundaries(int length, boolean valid) {
        var text = new ApplicationText("天际镇", "sky", "文".repeat(length), List.of("规则"));
        assertEquals(valid, text.validate().isEmpty());
        if (!valid) assertEquals(List.of(new ApplicationText.ValidationIssue(
                ApplicationText.ValidationIssue.Code.DESCRIPTION_LENGTH,
                Map.of("maximum", "500"))), text.validate());
    }

    @ParameterizedTest
    @CsvSource({"0,false", "1,true", "50,true", "51,false"})
    void checksExactRuleCountBoundaries(int count, boolean valid) {
        var text = new ApplicationText("天际镇", "sky", "简介",
                java.util.Collections.nCopies(count, "规则"));
        assertEquals(valid, text.validate().isEmpty());
        if (!valid) assertEquals(List.of(new ApplicationText.ValidationIssue(
                ApplicationText.ValidationIssue.Code.RULE_COUNT,
                Map.of("minimum", "1", "maximum", "50"))), text.validate());
    }

    @ParameterizedTest
    @CsvSource({"300,true", "301,false"})
    void checksExactRuleLengthAndReportsTheCorrectRule(int length, boolean valid) {
        var text = new ApplicationText("天际镇", "sky", "简介", List.of("规则一", "文".repeat(length)));
        assertEquals(valid, text.validate().isEmpty());
        if (!valid) assertEquals(List.of(new ApplicationText.ValidationIssue(
                ApplicationText.ValidationIssue.Code.RULE_LENGTH,
                Map.of("index", "2", "maximum", "300"))), text.validate());
    }

    @ParameterizedTest
    @ValueSource(strings = {"§c文本", "&A文本", "<red>文本", "文\u0000本", "文\u001b本", "文\u007f本"})
    void rejectsUnsafeDescriptionAndRulesIndependently(String unsafe) {
        var description = new ApplicationText("天际镇", "sky", unsafe, List.of("规则"));
        var rule = new ApplicationText("天际镇", "sky", "简介", List.of("规则一", unsafe));
        assertEquals(List.of(new ApplicationText.ValidationIssue(
                ApplicationText.ValidationIssue.Code.DESCRIPTION_FORMAT, Map.of())), description.validate());
        assertEquals(List.of(new ApplicationText.ValidationIssue(
                ApplicationText.ValidationIssue.Code.RULE_FORMAT, Map.of("index", "2"))), rule.validate());
        assertThrows(ApplicationText.ValidationException.class, description::requireValid);
        assertThrows(ApplicationText.ValidationException.class, rule::requireValid);
    }

    @Test
    void normalizesMultilineInputAndDropsBlankRulesBeforeValidation() {
        var text = new ApplicationText("天际镇", "sky", "  第一行\r\n第二行\r第三行\t内容  ",
                List.of(" \t ", "  规则一\r\n续行  ", "\r\n"));
        assertEquals("第一行\n第二行\n第三行\t内容", text.description());
        assertEquals(List.of("规则一\n续行"), text.rules());
        assertTrue(text.validate().isEmpty());
        var blankRules = new ApplicationText("天际镇", "sky", "简介", List.of(" ", "\t\r\n"));
        assertEquals(List.of(new ApplicationText.ValidationIssue(
                ApplicationText.ValidationIssue.Code.RULE_COUNT,
                Map.of("minimum", "1", "maximum", "50"))), blankRules.validate());
    }

    @Test
    void enforcesCodeBoundsForAllProfiles() {
        for (String code : List.of("abc", "Abcdefghi")) {
            assertTrue(new ApplicationText("天际镇", code, "简介", List.of("规则")).validate().isEmpty());
        }
        for (String code : List.of("", "a", "ab", "abcdefghij", "ab1", "a b", " abc", "abc ", "中文镇", "ab_")) {
            ApplicationText text = new ApplicationText("天际镇", code, "简介", List.of("规则"));
            assertThrows(ApplicationText.ValidationException.class, text::requireValid, code);
        }
        for (String code : List.of("a", "ab", "abcdefghijkl")) {
            ApplicationText text = new ApplicationText("天际镇", code, "简介", List.of("规则"));
            assertThrows(ApplicationText.ValidationException.class, text::requireValid);
        }
    }

    @Test
    void normalizesNamesForUniqueKeys() {
        ApplicationText text = new ApplicationText("  天 际 镇  ", "SKY", "简介",
                List.of("规则"));
        assertEquals("天际镇", text.normalizedName());
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
        ApplicationText text = new ApplicationText("<red>镇", "S K", "§c简介",
                List.of("规则"));
        assertFalse(text.validate().isEmpty());
        assertTrue(text.validate().stream().anyMatch(issue ->
                issue.code() == ApplicationText.ValidationIssue.Code.NAME_FORMAT));
        assertTrue(text.validate().stream().anyMatch(issue ->
                issue.code() == ApplicationText.ValidationIssue.Code.RESIDENCE_NAME_CHARACTERS));
    }

    @Test
    void requiresTownDescription() {
        ApplicationText text = new ApplicationText("天际镇", "SKY", "   ",
                List.of("规则"));

        assertEquals(List.of(new ApplicationText.ValidationIssue(
                        ApplicationText.ValidationIssue.Code.DESCRIPTION_REQUIRED,
                        Map.of())), text.validate());
    }

    @Test
    void describesRuleCountWithNaturalRangeNotation() {
        ApplicationText text = new ApplicationText("天际镇", "SKY", "简介", List.of());
        assertEquals(List.of(new ApplicationText.ValidationIssue(
                        ApplicationText.ValidationIssue.Code.RULE_COUNT,
                        Map.of("minimum", "1", "maximum", "50"))), text.validate());
    }

    @Test
    void exposesStructuredRuleDetailsWithoutRenderingPlayerTextInCore() {
        ApplicationText text = new ApplicationText("天际镇", "SKY", "简介",
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

package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationTextMessagesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersStructuredApplicationValidationIssuesFromBuiltInMessages() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        List<ApplicationText.ValidationIssue> issues = List.of(
                new ApplicationText.ValidationIssue(
                        ApplicationText.ValidationIssue.Code.NAME_LENGTH,
                        Map.of("minimum", "2", "maximum", "24")),
                new ApplicationText.ValidationIssue(
                        ApplicationText.ValidationIssue.Code.RULE_FORMAT,
                        Map.of("index", "3")));

        String rendered = ApplicationTextMessages.join(messages, issues);

        assertTrue(messages.hasMessage("validation.application.name-length"));
        assertTrue(messages.hasMessage("validation.application.rule-format"));
        assertTrue(rendered.contains("2"));
        assertTrue(rendered.contains("24"));
        assertTrue(rendered.contains("3"));
        assertFalse(rendered.contains("缺少消息配置"));
        assertFalse(rendered.contains("{minimum}"));
        assertFalse(rendered.contains("{index}"));
    }

    @Test
    void rendersRequiredDescriptionValidationMessage() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        ApplicationText.ValidationIssue issue = new ApplicationText.ValidationIssue(
                ApplicationText.ValidationIssue.Code.DESCRIPTION_REQUIRED, Map.of());

        assertTrue(messages.hasMessage("validation.application.description-required"));
        assertFalse(ApplicationTextMessages.render(messages, issue).isBlank());
    }

    @Test
    void usesUserOverrideForTheNextValidationRender() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        ApplicationText.ValidationIssue issue = new ApplicationText.ValidationIssue(
                ApplicationText.ValidationIssue.Code.RULE_LENGTH,
                Map.of("index", "2", "maximum", "300"));
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("validation.application.rule-length", "&b自定义规则 {index} 上限 {maximum}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertTrue(ApplicationTextMessages.render(messages, issue)
                .contains("自定义规则 2 上限 300"));
    }
}

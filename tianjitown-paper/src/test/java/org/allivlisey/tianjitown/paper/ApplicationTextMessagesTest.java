package cn.tianji.town.paper;

import cn.tianji.town.core.application.ApplicationText;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertTrue(rendered.contains("名称长度必须为 2~24"));
        assertTrue(rendered.contains("规则 3 含有不允许的格式或控制字符"));
        assertFalse(rendered.contains("缺少消息配置"));
        assertFalse(rendered.contains("{minimum}"));
        assertFalse(rendered.contains("{index}"));
    }

    @Test
    void usesUserOverrideForTheNextValidationRender() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        ApplicationText.ValidationIssue issue = new ApplicationText.ValidationIssue(
                ApplicationText.ValidationIssue.Code.RULE_LENGTH,
                Map.of("index", "2", "maximum", "300"));
        assertTrue(ApplicationTextMessages.render(messages, issue)
                .contains("规则 2 不能超过 300 字符"));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("validation.application.rule-length", "&b自定义规则 {index} 上限 {maximum}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("§b自定义规则 2 上限 300", ApplicationTextMessages.render(messages, issue));
    }
}

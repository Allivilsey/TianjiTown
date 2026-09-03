package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.core.application.ApplicationText;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

final class ApplicationTextMessages {
    private ApplicationTextMessages() {
    }

    static String render(PluginMessages messages, ApplicationText.ValidationIssue issue) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(issue, "issue");
        return messages.text(key(issue.code()), issue.parameters());
    }

    static String join(PluginMessages messages,
                       List<ApplicationText.ValidationIssue> issues) {
        Objects.requireNonNull(issues, "issues");
        return issues.stream()
                .map(issue -> render(messages, issue))
                .collect(Collectors.joining("\n"));
    }

    static String key(ApplicationText.ValidationIssue.Code code) {
        return switch (Objects.requireNonNull(code, "code")) {
            case NAME_LENGTH -> "validation.application.name-length";
            case NAME_CHARACTERS -> "validation.application.name-characters";
            case NAME_FORMAT -> "validation.application.name-format";
            case RESIDENCE_NAME_LENGTH -> "validation.application.residence-name-length";
            case RESIDENCE_NAME_CHARACTERS ->
                    "validation.application.residence-name-characters";
            case DESCRIPTION_LENGTH -> "validation.application.description-length";
            case DESCRIPTION_FORMAT -> "validation.application.description-format";
            case RULE_COUNT -> "validation.application.rule-count";
            case RULE_LENGTH -> "validation.application.rule-length";
            case RULE_FORMAT -> "validation.application.rule-format";
        };
    }
}

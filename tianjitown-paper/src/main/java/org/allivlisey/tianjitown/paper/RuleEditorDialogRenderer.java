package org.allivlisey.tianjitown.paper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Stable layout model for a rule editor dialog. Rules are rendered in the dialog body, before
 * the input section; only their compact delete controls live in the action section.
 */
final class RuleEditorDialogRenderer {
    static final int COLUMNS = 2;
    static final int PREVIEW_WIDTH = 250;
    static final int DELETE_WIDTH = 28;
    static final int ADD_WIDTH = 130;
    private static final String PAGE_VERSION_INVALID =
            "validation.rule-editor.page-version";
    private static final String RULE_INDEX_INVALID = "validation.rule-editor.rule-index";
    private static final String DELETE_REQUEST_INVALID = "dialog.rules.delete-request-invalid";

    private RuleEditorDialogRenderer() {
    }

    static Layout layout(UUID pageId, long pageVersion, List<String> rules) {
        return layout(pageId, pageVersion, rules, RuleEditorDialogRenderer::fallbackMessage);
    }

    static Layout layout(UUID pageId, long pageVersion, List<String> rules,
                         BiFunction<String, Map<String, ?>, String> messageResolver) {
        Objects.requireNonNull(pageId, "pageId");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(messageResolver, "messageResolver");
        requireNonNegative(pageVersion, PAGE_VERSION_INVALID, messageResolver);
        List<Row> rows = new ArrayList<>(rules.size());
        for (int index = 0; index < rules.size(); index++) {
            String rule = Objects.requireNonNull(rules.get(index), "rule");
            rows.add(new Row(index + 1, rule,
                    new DeleteTarget(pageId, pageVersion, index, rule)));
        }
        return new Layout(List.copyOf(rows));
    }

    private static void requireNonNegative(long value, String messageKey,
                                           BiFunction<String, Map<String, ?>, String>
                                                   messageResolver) {
        if (value < 0) {
            throw new IllegalArgumentException(resolveMessage(messageResolver, messageKey));
        }
    }

    record Layout(List<Row> rows) {
        Layout {
            rows = List.copyOf(rows);
        }

        int columns() {
            return COLUMNS;
        }

        /** The fixed client order imposed by Paper's dialog model. */
        List<Section> sections() {
            return List.of(Section.HEADING_AND_RULES, Section.RULE_INPUT,
                    Section.ADD_RULE, Section.DELETE_CONTROLS, Section.PAGE_ACTIONS);
        }
    }

    enum Section {
        HEADING_AND_RULES,
        RULE_INPUT,
        ADD_RULE,
        DELETE_CONTROLS,
        PAGE_ACTIONS
    }

    record Row(int displayIndex, String rule, DeleteTarget deleteTarget) {
        Row {
            rule = Objects.requireNonNull(rule, "rule");
            deleteTarget = Objects.requireNonNull(deleteTarget, "deleteTarget");
        }
    }

    /**
     * Captures the exact page state that produced a delete button.  The encoded form is kept in
     * the dialog action target so a stale page can never delete a different rule at the same slot.
     */
    record DeleteTarget(UUID pageId, long pageVersion, int ruleIndex, String expectedRule) {
        DeleteTarget {
            pageId = Objects.requireNonNull(pageId, "pageId");
            if (pageVersion < 0) {
                throw new IllegalArgumentException(PAGE_VERSION_INVALID);
            }
            if (ruleIndex < 0) {
                throw new IllegalArgumentException(RULE_INDEX_INVALID);
            }
            expectedRule = Objects.requireNonNull(expectedRule, "expectedRule");
        }

        String encode() {
            String encodedRule = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(expectedRule.getBytes(StandardCharsets.UTF_8));
            return pageId + ":" + pageVersion + ":" + ruleIndex + ":" + encodedRule;
        }

        static DeleteTarget decode(String value) {
            return decode(value, RuleEditorDialogRenderer::fallbackMessage);
        }

        static DeleteTarget decode(String value,
                                   BiFunction<String, Map<String, ?>, String> messageResolver) {
            Objects.requireNonNull(messageResolver, "messageResolver");
            String[] parts = Objects.requireNonNull(value, "value").split(":", 4);
            if (parts.length != 4 || parts[3].isEmpty()) {
                throw invalidDeleteRequest(messageResolver);
            }
            try {
                return new DeleteTarget(UUID.fromString(parts[0]), Long.parseLong(parts[1]),
                        Integer.parseInt(parts[2]), new String(Base64.getUrlDecoder()
                        .decode(parts[3]), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException exception) {
                throw invalidDeleteRequest(messageResolver, exception);
            }
        }

        private static IllegalArgumentException invalidDeleteRequest(
                BiFunction<String, Map<String, ?>, String> messageResolver) {
            return invalidDeleteRequest(messageResolver, null);
        }

        private static IllegalArgumentException invalidDeleteRequest(
                BiFunction<String, Map<String, ?>, String> messageResolver,
                IllegalArgumentException cause) {
            String message = resolveMessage(messageResolver, DELETE_REQUEST_INVALID);
            return cause == null
                    ? new IllegalArgumentException(message)
                    : new IllegalArgumentException(message, cause);
        }
    }

    private static String resolveMessage(
            BiFunction<String, Map<String, ?>, String> messageResolver, String key) {
        try {
            String message = messageResolver.apply(key, Map.of());
            if (message != null && !message.isBlank()) {
                return message;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Validation still needs a stable message key if the message file is unavailable.
        }
        return fallbackMessage(key, Map.of());
    }

    static String fallbackMessage(String key, Map<String, ?> placeholders) {
        return key;
    }
}

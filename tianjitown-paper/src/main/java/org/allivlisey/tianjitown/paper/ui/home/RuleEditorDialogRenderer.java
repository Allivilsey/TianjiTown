package org.allivlisey.tianjitown.paper.ui.home;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
/** Stable layout model for a rule editor dialog. */
public final class RuleEditorDialogRenderer {
    /** Keep primary actions visually aligned in the shared single-column editor flow. */
    public static final int SINGLE_COLUMN_ACTION_WIDTH = 150;
    /** Full-width rule actions used by the shared inline-delete list. */
    public static final int INLINE_RULE_WIDTH = 400;
    private static final String DELETE_REQUEST_INVALID = "dialog.rules.delete-request-invalid";

    private RuleEditorDialogRenderer() {
    }

    public static Layout layout(UUID pageId, long pageVersion, List<String> rules) {
        Objects.requireNonNull(pageId, "pageId");
        Objects.requireNonNull(rules, "rules");
        List<Row> rows = new ArrayList<>(rules.size());
        for (int index = 0; index < rules.size(); index++) {
            String rule = Objects.requireNonNull(rules.get(index), "rule");
            rows.add(new Row(index + 1, rule,
                    new DeleteTarget(pageId, pageVersion, index, rule)));
        }
        return new Layout(List.copyOf(rows));
    }

    public record Layout(List<Row> rows) {
        public Layout {
            rows = List.copyOf(rows);
        }

        /** Both town and application editors use this same single-column action order. */
        List<Section> editorSections() {
            return List.of(Section.HEADING, Section.RULE_INPUT, Section.ADD_RULE,
                    Section.INLINE_RULE_ROWS, Section.PAGE_ACTIONS);
        }
    }

    public enum Section {
        HEADING,
        RULE_INPUT,
        ADD_RULE,
        INLINE_RULE_ROWS,
        PAGE_ACTIONS
    }

    public record Row(int displayIndex, String rule, DeleteTarget deleteTarget) {
        public Row {
            rule = Objects.requireNonNull(rule, "rule");
            deleteTarget = Objects.requireNonNull(deleteTarget, "deleteTarget");
        }
    }

    /**
     * Captures the exact page state that produced a delete button.  The encoded form is kept in
     * the dialog action target so a stale page can never delete a different rule at the same slot.
     */
    public record DeleteTarget(UUID pageId, long pageVersion, int ruleIndex, String expectedRule) {
        public DeleteTarget {
            pageId = Objects.requireNonNull(pageId, "pageId");
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

    public static String fallbackMessage(String key, Map<String, ?> placeholders) {
        return key;
    }
}

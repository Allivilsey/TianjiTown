package org.allivlisey.tianjitown.paper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.object.ObjectContents;

/** Stable layout model for a rule editor dialog. */
final class RuleEditorDialogRenderer {
    static final int COLUMNS = 2;
    static final int PREVIEW_WIDTH = 250;
    /** The client action-button height. Keeping this square prevents a rule row from reflowing. */
    static final int DELETE_SIZE = 20;
    static final int ADD_WIDTH = 130;
    /** Barrier is an item sprite; it is not present in the blocks atlas. */
    static final Key ITEM_ATLAS = Key.key("minecraft:items");
    static final Key BARRIER_SPRITE = Key.key("minecraft:item/barrier");
    private static final String DELETE_REQUEST_INVALID = "dialog.rules.delete-request-invalid";

    private RuleEditorDialogRenderer() {
    }

    /** The exact object component sent as the icon-only delete button label. */
    static Component deleteIcon() {
        return Component.object(ObjectContents.sprite(ITEM_ATLAS, BARRIER_SPRITE));
    }

    static Layout layout(UUID pageId, long pageVersion, List<String> rules) {
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

    record Layout(List<Row> rows) {
        Layout {
            rows = List.copyOf(rows);
        }

        int columns() {
            return COLUMNS;
        }

        /** The fixed client order imposed by Paper's dialog model on the add page. */
        List<Section> addSections() {
            return List.of(Section.HEADING, Section.RULE_PREVIEW, Section.RULE_INPUT,
                    Section.ADD_RULE, Section.DELETE_PAGE, Section.PAGE_ACTIONS);
        }

        /** The removal page has no input or unrelated actions. */
        List<Section> deleteSections() {
            return List.of(Section.HEADING, Section.DELETE_RULE_ROWS, Section.PAGE_ACTIONS);
        }
    }

    enum Section {
        HEADING,
        RULE_PREVIEW,
        RULE_INPUT,
        ADD_RULE,
        DELETE_PAGE,
        DELETE_RULE_ROWS,
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

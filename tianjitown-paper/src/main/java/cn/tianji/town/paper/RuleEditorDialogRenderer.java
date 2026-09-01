package cn.tianji.town.paper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Stable layout model for a rule editor dialog. Rules are rendered in the dialog body, before
 * the input section; only their compact delete controls live in the action section.
 */
final class RuleEditorDialogRenderer {
    static final int COLUMNS = 2;
    static final int PREVIEW_WIDTH = 250;
    static final int DELETE_WIDTH = 28;
    static final int ADD_WIDTH = 130;

    private RuleEditorDialogRenderer() {
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
            if (displayIndex < 1) {
                throw new IllegalArgumentException("displayIndex 必须从 1 开始");
            }
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
                throw new IllegalArgumentException("pageVersion 不能为负数");
            }
            if (ruleIndex < 0) {
                throw new IllegalArgumentException("ruleIndex 不能为负数");
            }
            expectedRule = Objects.requireNonNull(expectedRule, "expectedRule");
        }

        String encode() {
            String encodedRule = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(expectedRule.getBytes(StandardCharsets.UTF_8));
            return pageId + ":" + pageVersion + ":" + ruleIndex + ":" + encodedRule;
        }

        static DeleteTarget decode(String value) {
            String[] parts = Objects.requireNonNull(value, "value").split(":", 4);
            if (parts.length != 4 || parts[3].isEmpty()) {
                throw new IllegalArgumentException("规则删除请求无效，请刷新界面");
            }
            try {
                return new DeleteTarget(UUID.fromString(parts[0]), Long.parseLong(parts[1]),
                        Integer.parseInt(parts[2]), new String(Base64.getUrlDecoder()
                        .decode(parts[3]), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("规则删除请求无效，请刷新界面", exception);
            }
        }
    }
}

package org.allivlisey.tianjitown.paper;

import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared presentation model and content renderer for a town's public rules page. */
final class ReadOnlyRulesDialogRenderer {
    static final int CONTENT_WIDTH = 420;

    private ReadOnlyRulesDialogRenderer() {
    }

    static Layout layout(String townName, List<String> rules, DialogRoute returnRoute) {
        Objects.requireNonNull(rules, "rules");
        return new Layout(safeText(townName), rules.stream()
                .map(ReadOnlyRulesDialogRenderer::safeText)
                .toList(), Objects.requireNonNull(returnRoute, "returnRoute"));
    }

    static Component content(PluginMessages messages, Layout layout) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(layout, "layout");

        Component content = messages.component("dialog.rules.current-heading", Map.of(
                "town", layout.townName()));
        if (layout.rules().isEmpty()) {
            return content.append(Component.newline()).append(Component.newline())
                    .append(messages.component("dialog.rules.empty"));
        }
        for (int index = 0; index < layout.rules().size(); index++) {
            content = content.append(Component.newline()).append(Component.newline())
                    .append(messages.component("dialog.rules.item", Map.of(
                            "index", index + 1,
                            "rule", layout.rules().get(index))));
        }
        return content;
    }

    private static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    record Layout(String townName, List<String> rules, DialogRoute returnRoute) {
        Layout {
            townName = Objects.requireNonNull(townName, "townName");
            rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
            returnRoute = Objects.requireNonNull(returnRoute, "returnRoute");
        }

        boolean showsEmptyState() {
            return rules.isEmpty();
        }
    }
}

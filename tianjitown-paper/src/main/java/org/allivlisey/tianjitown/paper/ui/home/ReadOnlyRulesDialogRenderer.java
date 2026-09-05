package org.allivlisey.tianjitown.paper.ui.home;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.paper.ui.DialogRoute;

import net.kyori.adventure.text.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared presentation model and content renderer for a town's public rules page. */
public final class ReadOnlyRulesDialogRenderer {
    public static final int CONTENT_WIDTH = 420;

    private ReadOnlyRulesDialogRenderer() {
    }

    public static Layout layout(String townName, List<String> rules, DialogRoute returnRoute) {
        Objects.requireNonNull(rules, "rules");
        return new Layout(safeText(townName), rules.stream()
                .map(ReadOnlyRulesDialogRenderer::safeText)
                .toList(), Objects.requireNonNull(returnRoute, "returnRoute"));
    }

    public static Component content(PluginMessages messages, Layout layout) {
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

    public record Layout(String townName, List<String> rules, DialogRoute returnRoute) {
        public Layout {
            townName = Objects.requireNonNull(townName, "townName");
            rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
            returnRoute = Objects.requireNonNull(returnRoute, "returnRoute");
        }

        boolean showsEmptyState() {
            return rules.isEmpty();
        }
    }
}

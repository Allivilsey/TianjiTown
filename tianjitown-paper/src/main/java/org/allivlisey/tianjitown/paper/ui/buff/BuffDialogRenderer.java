package org.allivlisey.tianjitown.paper.ui.buff;
import org.allivlisey.tianjitown.paper.message.PluginMessages;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Presentation helpers for the public Buff shop and purchase-parameter page. */
public final class BuffDialogRenderer {
    private BuffDialogRenderer() {
    }

    public static String shopHint(PluginMessages messages, boolean shopEnabled) {
        Objects.requireNonNull(messages, "messages");
        return messages.rawText(shopEnabled
                ? "dialog.buff.shop-enabled-hint"
                : "dialog.buff.shop-paused-hint");
    }

    public static List<String> parameterSummaryLore(PluginMessages messages, String effectDescription,
                                             ActiveState current) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(effectDescription, "effectDescription");
        String state = current == null
                ? messages.rawText("dialog.buff.inactive")
                : messages.rawText("dialog.buff.active", Map.of(
                        "level", roman(current.level()), "expires", current.expiresAt()));
        return List.of(
                messages.rawText("dialog.buff.effect", Map.of("effect", effectDescription)),
                state,
                messages.rawText("dialog.buff.price-hint"));
    }

    public static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(level);
        };
    }

    public record ActiveState(int level, String expiresAt) {
        public ActiveState {
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}

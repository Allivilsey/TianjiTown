package cn.tianji.town.paper;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Keeps configurable completion labels separate from the stable values used by command parsing.
 */
final class TownAdminCompletionHints {
    static final String REASON_KEY = "chat.admin.completion.reason-hint";
    static final String PLAYER_KEY = "chat.admin.completion.player-hint";
    static final String AMOUNT_KEY = "chat.admin.completion.amount-hint";

    static final String REASON_MACHINE_VALUE = "<reason>";
    static final String PLAYER_MACHINE_VALUE = "<player>";
    static final String AMOUNT_MACHINE_VALUE = "<amount>";

    private TownAdminCompletionHints() {
    }

    static String resolve(BiFunction<String, Map<String, ?>, String> messageResolver,
                          String key) {
        Objects.requireNonNull(messageResolver, "messageResolver");
        return Objects.requireNonNull(messageResolver.apply(key, Map.of()), key);
    }
}

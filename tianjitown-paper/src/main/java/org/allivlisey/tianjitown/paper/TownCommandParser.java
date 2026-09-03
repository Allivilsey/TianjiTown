package cn.tianji.town.paper;

import cn.tianji.town.core.application.ApplicationText;

import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

final class TownCommandParser {
    private TownCommandParser() {
    }

    static NamedReason namedReason(String[] args, int nameStart, Collection<String> townNames) {
        return namedReason(args, nameStart, townNames, null);
    }

    static NamedReason namedReason(String[] args, int nameStart, Collection<String> townNames,
                                   BiFunction<String, Map<String, ?>, String> messageResolver) {
        NameMatch match = requireNameMatch(args, nameStart, townNames);
        String reason = join(args, match.end(), args.length, "chat.parser.missing-reason");
        rejectPlaceholder(reason, TownAdminCompletionHints.REASON_MACHINE_VALUE,
                configuredHint(messageResolver, TownAdminCompletionHints.REASON_KEY));
        return new NamedReason(match.name(), reason);
    }

    static NamedPlayerReason namedPlayerReason(String[] args, int nameStart,
                                                Collection<String> townNames) {
        return namedPlayerReason(args, nameStart, townNames, null);
    }

    static NamedPlayerReason namedPlayerReason(String[] args, int nameStart,
                                                Collection<String> townNames,
                                                BiFunction<String, Map<String, ?>, String>
                                                        messageResolver) {
        NameMatch match = requireNameMatch(args, nameStart, townNames);
        if (match.end() >= args.length) {
            throw error("chat.parser.missing-player");
        }
        String player = args[match.end()].strip();
        if (player.isBlank()) {
            throw error("chat.parser.missing-player");
        }
        rejectPlaceholder(player, TownAdminCompletionHints.PLAYER_MACHINE_VALUE,
                configuredHint(messageResolver, TownAdminCompletionHints.PLAYER_KEY));
        String reason = join(args, match.end() + 1, args.length, "chat.parser.missing-reason");
        rejectPlaceholder(reason, TownAdminCompletionHints.REASON_MACHINE_VALUE,
                configuredHint(messageResolver, TownAdminCompletionHints.REASON_KEY));
        return new NamedPlayerReason(match.name(), player, reason);
    }

    static NamedPlayer namedPlayer(String[] args, int nameStart, Collection<String> townNames) {
        return namedPlayer(args, nameStart, townNames, null);
    }

    static NamedPlayer namedPlayer(String[] args, int nameStart, Collection<String> townNames,
                                   BiFunction<String, Map<String, ?>, String> messageResolver) {
        NameMatch match = requireNameMatch(args, nameStart, townNames);
        if (match.end() + 1 != args.length) {
            throw error("chat.parser.exactly-one-player");
        }
        String player = args[match.end()].strip();
        rejectPlaceholder(player, TownAdminCompletionHints.PLAYER_MACHINE_VALUE,
                configuredHint(messageResolver, TownAdminCompletionHints.PLAYER_KEY));
        return new NamedPlayer(match.name(), player);
    }

    static NamedAction namedAction(String[] args, int nameStart, Collection<String> townNames,
                                   Collection<String> actions) {
        NameMatch match = requireNameMatch(args, nameStart, townNames);
        if (match.end() == args.length) {
            return new NamedAction(match.name(), null);
        }
        if (match.end() + 1 != args.length) {
            throw error("chat.parser.one-action");
        }
        String action = args[match.end()].toLowerCase(Locale.ROOT);
        if (actions.stream().noneMatch(action::equalsIgnoreCase)) {
            throw error("chat.parser.unsupported-action", Map.of(
                    "action", args[match.end()]));
        }
        return new NamedAction(match.name(), action);
    }

    static NamedAmountReason namedAmountReason(String[] args, int nameStart,
                                               Collection<String> townNames) {
        return namedAmountReason(args, nameStart, townNames, null);
    }

    static NamedAmountReason namedAmountReason(String[] args, int nameStart,
                                               Collection<String> townNames,
                                               BiFunction<String, Map<String, ?>, String>
                                                       messageResolver) {
        NameMatch match = requireNameMatch(args, nameStart, townNames);
        if (match.end() >= args.length) {
            throw error("chat.parser.missing-amount");
        }
        String amount = args[match.end()].strip();
        rejectPlaceholder(amount, TownAdminCompletionHints.AMOUNT_MACHINE_VALUE,
                configuredHint(messageResolver, TownAdminCompletionHints.AMOUNT_KEY));
        String reason = join(args, match.end() + 1, args.length, "chat.parser.missing-reason");
        rejectPlaceholder(reason, TownAdminCompletionHints.REASON_MACHINE_VALUE,
                configuredHint(messageResolver, TownAdminCompletionHints.REASON_KEY));
        return new NamedAmountReason(match.name(), amount, reason);
    }

    static NamedActionReason namedActionReason(String[] args, int nameStart,
                                               Collection<String> townNames,
                                               Collection<String> actions) {
        return namedActionReason(args, nameStart, townNames, actions, null);
    }

    static NamedActionReason namedActionReason(String[] args, int nameStart,
                                               Collection<String> townNames,
                                               Collection<String> actions,
                                               BiFunction<String, Map<String, ?>, String>
                                                       messageResolver) {
        NameMatch match = requireNameMatch(args, nameStart, townNames);
        if (match.end() >= args.length) {
            throw error("chat.parser.missing-target");
        }
        String action = args[match.end()].strip();
        if (actions.stream().noneMatch(action::equalsIgnoreCase)) {
            throw error("chat.parser.unsupported-action", Map.of("action", action));
        }
        String reason = join(args, match.end() + 1, args.length, "chat.parser.missing-reason");
        rejectPlaceholder(reason, TownAdminCompletionHints.REASON_MACHINE_VALUE,
                configuredHint(messageResolver, TownAdminCompletionHints.REASON_KEY));
        return new NamedActionReason(match.name(), action, reason);
    }

    static NamedPlayerActionQuantityReason namedPlayerActionQuantityReason(
            String[] args, int nameStart, Collection<String> townNames,
            Collection<String> actions) {
        return namedPlayerActionQuantityReason(args, nameStart, townNames, actions, null);
    }

    static NamedPlayerActionQuantityReason namedPlayerActionQuantityReason(
            String[] args, int nameStart, Collection<String> townNames,
            Collection<String> actions,
            BiFunction<String, Map<String, ?>, String> messageResolver) {
        NameMatch match = requireNameMatch(args, nameStart, townNames);
        if (match.end() + 3 > args.length) {
            throw error("chat.parser.missing-player-product-quantity");
        }
        String player = args[match.end()].strip();
        String action = args[match.end() + 1].strip();
        if (actions.stream().noneMatch(action::equalsIgnoreCase)) {
            throw error("chat.parser.unsupported-product", Map.of("action", action));
        }
        int quantity;
        try {
            quantity = Integer.parseInt(args[match.end() + 2]);
        } catch (NumberFormatException exception) {
            throw new ParseException("chat.parser.quantity-integer", Map.of(), exception);
        }
        String reason = join(args, match.end() + 3, args.length, "chat.parser.missing-reason");
        rejectPlaceholder(reason, TownAdminCompletionHints.REASON_MACHINE_VALUE,
                configuredHint(messageResolver, TownAdminCompletionHints.REASON_KEY));
        return new NamedPlayerActionQuantityReason(match.name(), player, action, quantity, reason);
    }

    static String exactName(String[] args, int nameStart, Collection<String> townNames) {
        NameMatch match = requireNameMatch(args, nameStart, townNames);
        if (match.end() != args.length) {
            throw error("chat.parser.extra-town-argument");
        }
        return match.name();
    }

    static String townName(String[] args, int nameStart) {
        return join(args, nameStart, args.length, "chat.parser.town-full-name");
    }

    static String reason(String[] args, int start,
                         BiFunction<String, Map<String, ?>, String> messageResolver) {
        String reason = join(args, start, args.length, "chat.parser.missing-reason");
        rejectPlaceholder(reason, TownAdminCompletionHints.REASON_MACHINE_VALUE,
                configuredHint(messageResolver, TownAdminCompletionHints.REASON_KEY));
        return reason;
    }

    private static NameMatch requireNameMatch(String[] args, int nameStart,
                                              Collection<String> townNames) {
        NameMatch best = null;
        for (String candidate : townNames) {
            String normalizedCandidate = ApplicationText.normalizeNameKey(candidate);
            for (int end = nameStart + 1; end <= args.length; end++) {
                String entered = String.join(" ", Arrays.copyOfRange(args, nameStart, end));
                if (ApplicationText.normalizeNameKey(entered).equals(normalizedCandidate)
                        && (best == null || end > best.end())) {
                    best = new NameMatch(candidate, end);
                }
            }
        }
        if (best == null) {
            throw error("chat.parser.town-not-found");
        }
        return best;
    }

    private static void rejectPlaceholder(String value, String... placeholders) {
        for (String placeholder : placeholders) {
            if (value.equalsIgnoreCase(placeholder)) {
                throw error("chat.parser.placeholder", Map.of("placeholder", placeholder));
            }
        }
    }

    private static String configuredHint(
            BiFunction<String, Map<String, ?>, String> messageResolver, String key) {
        return messageResolver == null ? null : TownAdminCompletionHints.resolve(messageResolver, key);
    }

    private static String join(String[] args, int start, int end, String missingKey) {
        if (start >= end || start < 0 || end > args.length) {
            throw error(missingKey);
        }
        String value = String.join(" ", Arrays.copyOfRange(args, start, end)).strip();
        if (value.isBlank()) {
            throw error(missingKey);
        }
        return value;
    }

    private static ParseException error(String key) {
        return new ParseException(key, Map.of());
    }

    private static ParseException error(String key, Map<String, ?> placeholders) {
        return new ParseException(key, placeholders);
    }

    static final class ParseException extends IllegalArgumentException {
        private final String messageKey;
        private final Map<String, ?> placeholders;

        private ParseException(String messageKey, Map<String, ?> placeholders) {
            super(messageKey);
            this.messageKey = messageKey;
            this.placeholders = Map.copyOf(placeholders);
        }

        private ParseException(String messageKey, Map<String, ?> placeholders, Throwable cause) {
            super(messageKey, cause);
            this.messageKey = messageKey;
            this.placeholders = Map.copyOf(placeholders);
        }

        String messageKey() {
            return messageKey;
        }

        Map<String, ?> placeholders() {
            return placeholders;
        }
    }

    private record NameMatch(String name, int end) {
    }

    record NamedReason(String townName, String reason) {
    }

    record NamedPlayerReason(String townName, String player, String reason) {
    }

    record NamedPlayer(String townName, String player) {
    }

    record NamedAction(String townName, String action) {
    }

    record NamedAmountReason(String townName, String amount, String reason) {
    }

    record NamedActionReason(String townName, String action, String reason) {
    }

    record NamedPlayerActionQuantityReason(String townName, String player, String action,
                                           int quantity, String reason) {
    }
}

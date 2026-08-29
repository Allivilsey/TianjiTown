package cn.tianji.town.paper;

import cn.tianji.town.core.application.ApplicationText;

import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

final class TownCommandParser {
    private TownCommandParser() {
    }

    static NamedReason namedReason(String[] args, int nameStart, Collection<String> townNames) {
        NameMatch match = requireNameMatch(args, nameStart, townNames);
        String reason = join(args, match.end(), args.length, "chat.parser.missing-reason");
        rejectPlaceholder(reason, "<原因>");
        return new NamedReason(match.name(), reason);
    }

    static NamedPlayerReason namedPlayerReason(String[] args, int nameStart,
                                                Collection<String> townNames) {
        NameMatch match = requireNameMatch(args, nameStart, townNames);
        if (match.end() >= args.length) {
            throw error("chat.parser.missing-player");
        }
        String player = args[match.end()].strip();
        if (player.isBlank()) {
            throw error("chat.parser.missing-player");
        }
        rejectPlaceholder(player, "<玩家>");
        String reason = join(args, match.end() + 1, args.length, "chat.parser.missing-reason");
        rejectPlaceholder(reason, "<原因>");
        return new NamedPlayerReason(match.name(), player, reason);
    }

    static NamedPlayer namedPlayer(String[] args, int nameStart, Collection<String> townNames) {
        NameMatch match = requireNameMatch(args, nameStart, townNames);
        if (match.end() + 1 != args.length) {
            throw error("chat.parser.exactly-one-player");
        }
        String player = args[match.end()].strip();
        rejectPlaceholder(player, "<玩家>");
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
        NameMatch match = requireNameMatch(args, nameStart, townNames);
        if (match.end() >= args.length) {
            throw error("chat.parser.missing-amount");
        }
        String amount = args[match.end()].strip();
        String reason = join(args, match.end() + 1, args.length, "chat.parser.missing-reason");
        rejectPlaceholder(reason, "<原因>");
        return new NamedAmountReason(match.name(), amount, reason);
    }

    static NamedActionReason namedActionReason(String[] args, int nameStart,
                                               Collection<String> townNames,
                                               Collection<String> actions) {
        NameMatch match = requireNameMatch(args, nameStart, townNames);
        if (match.end() >= args.length) {
            throw error("chat.parser.missing-target");
        }
        String action = args[match.end()].strip();
        if (actions.stream().noneMatch(action::equalsIgnoreCase)) {
            throw error("chat.parser.unsupported-action", Map.of("action", action));
        }
        String reason = join(args, match.end() + 1, args.length, "chat.parser.missing-reason");
        rejectPlaceholder(reason, "<原因>");
        return new NamedActionReason(match.name(), action, reason);
    }

    static NamedPlayerActionQuantityReason namedPlayerActionQuantityReason(
            String[] args, int nameStart, Collection<String> townNames,
            Collection<String> actions) {
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
        rejectPlaceholder(reason, "<原因>");
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

    private static void rejectPlaceholder(String value, String placeholder) {
        if (value.equalsIgnoreCase(placeholder)) {
            throw error("chat.parser.placeholder", Map.of("placeholder", placeholder));
        }
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
            super(legacyMessage(messageKey, placeholders));
            this.messageKey = messageKey;
            this.placeholders = Map.copyOf(placeholders);
        }

        private ParseException(String messageKey, Map<String, ?> placeholders, Throwable cause) {
            super(legacyMessage(messageKey, placeholders), cause);
            this.messageKey = messageKey;
            this.placeholders = Map.copyOf(placeholders);
        }

        private static String legacyMessage(String messageKey, Map<String, ?> placeholders) {
            return switch (messageKey) {
                case "chat.parser.missing-reason" -> "必须填写原因";
                case "chat.parser.missing-player" -> "必须指定目标玩家";
                case "chat.parser.exactly-one-player" -> "小镇名称后必须且只能指定一个目标玩家";
                case "chat.parser.one-action" -> "目标后只允许一个操作参数";
                case "chat.parser.unsupported-action" -> "不支持的操作参数: "
                        + placeholders.get("action");
                case "chat.parser.missing-amount" -> "必须填写金额或税率";
                case "chat.parser.missing-target" -> "必须指定操作目标";
                case "chat.parser.unsupported-product" -> "不支持的商品: "
                        + placeholders.get("action");
                case "chat.parser.missing-player-product-quantity" -> "必须依次指定玩家、商品和数量";
                case "chat.parser.quantity-integer" -> "数量必须为整数";
                case "chat.parser.extra-town-argument" -> "小镇名称后存在多余参数";
                case "chat.parser.town-full-name" -> "必须填写小镇全名";
                case "chat.parser.town-not-found" -> "找不到匹配的小镇全名";
                case "chat.parser.placeholder" -> "请将 " + placeholders.get("placeholder")
                        + " 替换为实际内容";
                default -> messageKey;
            };
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

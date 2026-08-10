package cn.tianji.town.paper;

import cn.tianji.town.core.application.ApplicationText;

import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;

final class TownCommandParser {
    private TownCommandParser() {
    }

    static NamedReason namedReason(String[] args, int nameStart, Collection<String> townNames) {
        NameMatch match = requireNameMatch(args, nameStart, townNames);
        String reason = join(args, match.end(), args.length, "必须填写原因");
        rejectPlaceholder(reason, "<原因>");
        return new NamedReason(match.name(), reason);
    }

    static NamedPlayerReason namedPlayerReason(String[] args, int nameStart,
                                                Collection<String> townNames) {
        NameMatch match = requireNameMatch(args, nameStart, townNames);
        if (match.end() >= args.length) {
            throw new IllegalArgumentException("必须指定目标玩家");
        }
        String player = args[match.end()].strip();
        if (player.isBlank()) {
            throw new IllegalArgumentException("必须指定目标玩家");
        }
        rejectPlaceholder(player, "<玩家>");
        String reason = join(args, match.end() + 1, args.length, "必须填写原因");
        rejectPlaceholder(reason, "<原因>");
        return new NamedPlayerReason(match.name(), player, reason);
    }

    static NamedPlayer namedPlayer(String[] args, int nameStart, Collection<String> townNames) {
        NameMatch match = requireNameMatch(args, nameStart, townNames);
        if (match.end() + 1 != args.length) {
            throw new IllegalArgumentException("小镇名称后必须且只能指定一个目标玩家");
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
            throw new IllegalArgumentException("目标后只允许一个操作参数");
        }
        String action = args[match.end()].toLowerCase(Locale.ROOT);
        if (actions.stream().noneMatch(action::equalsIgnoreCase)) {
            throw new IllegalArgumentException("不支持的操作参数: " + args[match.end()]);
        }
        return new NamedAction(match.name(), action);
    }

    static NamedAmountReason namedAmountReason(String[] args, int nameStart,
                                               Collection<String> townNames) {
        NameMatch match = requireNameMatch(args, nameStart, townNames);
        if (match.end() >= args.length) {
            throw new IllegalArgumentException("必须填写金额或税率");
        }
        String amount = args[match.end()].strip();
        String reason = join(args, match.end() + 1, args.length, "必须填写原因");
        rejectPlaceholder(reason, "<原因>");
        return new NamedAmountReason(match.name(), amount, reason);
    }

    static String exactName(String[] args, int nameStart, Collection<String> townNames) {
        NameMatch match = requireNameMatch(args, nameStart, townNames);
        if (match.end() != args.length) {
            throw new IllegalArgumentException("小镇名称后存在多余参数");
        }
        return match.name();
    }

    static String townName(String[] args, int nameStart) {
        return join(args, nameStart, args.length, "必须填写小镇全名");
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
            throw new IllegalArgumentException("找不到匹配的小镇全名");
        }
        return best;
    }

    private static void rejectPlaceholder(String value, String placeholder) {
        if (value.equalsIgnoreCase(placeholder)) {
            throw new IllegalArgumentException("请将 " + placeholder + " 替换为实际内容");
        }
    }

    private static String join(String[] args, int start, int end, String missingMessage) {
        if (start >= end || start < 0 || end > args.length) {
            throw new IllegalArgumentException(missingMessage);
        }
        String value = String.join(" ", Arrays.copyOfRange(args, start, end)).strip();
        if (value.isBlank()) {
            throw new IllegalArgumentException(missingMessage);
        }
        return value;
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
}

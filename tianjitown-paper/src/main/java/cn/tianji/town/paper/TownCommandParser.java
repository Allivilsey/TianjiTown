package cn.tianji.town.paper;

import java.util.Arrays;

final class TownCommandParser {
    private TownCommandParser() {
    }

    static NamedReason namedReason(String[] args, int nameStart, String defaultReason) {
        int firstOption = firstOption(args, nameStart);
        String townName = join(args, nameStart, firstOption);
        int reasonIndex = indexOf(args, "--reason");
        String reason = reasonIndex < 0
                ? defaultReason : join(args, reasonIndex + 1, nextOption(args, reasonIndex + 1));
        return new NamedReason(townName, reason, contains(args, "--confirm"), reasonIndex >= 0);
    }

    static NamedPlayerReason namedPlayerReason(String[] args, int nameStart) {
        int playerIndex = indexOf(args, "--player");
        if (playerIndex < nameStart || playerIndex + 1 >= args.length
                || args[playerIndex + 1].startsWith("--")) {
            throw new IllegalArgumentException("必须使用 --player <玩家> 指定目标玩家");
        }
        String townName = join(args, nameStart, playerIndex);
        int reasonIndex = indexOf(args, "--reason");
        if (reasonIndex < 0) {
            throw new IllegalArgumentException("必须使用 --reason <原因> 填写原因");
        }
        String reason = join(args, reasonIndex + 1, nextOption(args, reasonIndex + 1));
        return new NamedPlayerReason(townName, args[playerIndex + 1], reason);
    }

    static String townName(String[] args, int nameStart) {
        return join(args, nameStart, firstOption(args, nameStart));
    }

    static boolean contains(String[] args, String option) {
        return Arrays.stream(args).anyMatch(option::equalsIgnoreCase);
    }

    private static int firstOption(String[] args, int start) {
        for (int index = start; index < args.length; index++) {
            if (args[index].startsWith("--")) {
                return index;
            }
        }
        return args.length;
    }

    private static int nextOption(String[] args, int start) {
        return firstOption(args, start);
    }

    private static int indexOf(String[] args, String option) {
        for (int index = 0; index < args.length; index++) {
            if (option.equalsIgnoreCase(args[index])) {
                return index;
            }
        }
        return -1;
    }

    private static String join(String[] args, int start, int end) {
        if (start >= end || start < 0 || end > args.length) {
            throw new IllegalArgumentException("必须填写小镇全名");
        }
        String value = String.join(" ", Arrays.copyOfRange(args, start, end)).strip();
        if (value.isBlank()) {
            throw new IllegalArgumentException("必须填写小镇全名");
        }
        return value;
    }

    record NamedReason(String townName, String reason, boolean confirmed,
                       boolean explicitReason) {
        NamedReason {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("必须填写原因");
            }
        }
    }

    record NamedPlayerReason(String townName, String player, String reason) {
    }
}

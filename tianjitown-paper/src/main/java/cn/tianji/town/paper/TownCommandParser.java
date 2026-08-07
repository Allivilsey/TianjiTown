package cn.tianji.town.paper;

import java.util.Arrays;

final class TownCommandParser {
    private TownCommandParser() {
    }

    static NamedReason requiredNamedReason(String[] args, int nameStart) {
        int firstOption = firstOption(args, nameStart);
        String townName = join(args, nameStart, firstOption, "必须填写小镇全名");
        int reasonIndex = indexOf(args, "--reason");
        if (reasonIndex < 0) {
            throw new IllegalArgumentException("必须使用 --reason <原因> 填写原因");
        }
        String reason = join(args, reasonIndex + 1, nextOption(args, reasonIndex + 1),
                "必须填写原因");
        return new NamedReason(townName, reason, contains(args, "--confirm"));
    }

    static NamedPlayerReason namedPlayerReason(String[] args, int nameStart) {
        int playerIndex = indexOf(args, "--player");
        if (playerIndex < nameStart || playerIndex + 1 >= args.length
                || args[playerIndex + 1].startsWith("--")) {
            throw new IllegalArgumentException("必须使用 --player <玩家> 指定目标玩家");
        }
        String townName = join(args, nameStart, playerIndex, "必须填写小镇全名");
        int reasonIndex = indexOf(args, "--reason");
        if (reasonIndex < 0) {
            throw new IllegalArgumentException("必须使用 --reason <原因> 填写原因");
        }
        String reason = join(args, reasonIndex + 1, nextOption(args, reasonIndex + 1),
                "必须填写原因");
        return new NamedPlayerReason(townName, args[playerIndex + 1], reason);
    }

    static String townName(String[] args, int nameStart) {
        return join(args, nameStart, firstOption(args, nameStart), "必须填写小镇全名");
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

    record NamedReason(String townName, String reason, boolean confirmed) {
        NamedReason {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("必须填写原因");
            }
        }
    }

    record NamedPlayerReason(String townName, String player, String reason) {
    }
}

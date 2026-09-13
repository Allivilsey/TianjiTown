package org.allivlisey.tianjitown.paper.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

public final class TownAdminPermissions {
    static final List<String> HELP_TOPICS = List.of("system", "station", "application", "town",
            "member", "vote", "land", "money", "tax", "ledger", "buff");
    static final String ROOT = "tianjitown.admin";
    private TownAdminPermissions() {}

    public static boolean hasAny(Predicate<String> hasPermission) {
        return hasPermission.test(ROOT);
    }

    public static boolean has(Predicate<String> hasPermission, String permission) {
        return hasPermission.test(ROOT);
    }

    public static boolean canViewHelpTopic(Predicate<String> hasPermission, String topic) {
        return hasPermission.test(ROOT);
    }
}

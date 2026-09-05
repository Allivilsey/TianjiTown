package org.allivlisey.tianjitown.paper.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

public final class TownAdminPermissions {
    static final String ROOT = "tianjitown.admin";
    static final String MONEY = "tianjitown.admin.money";
    static final String TAX = "tianjitown.admin.tax";
    static final String LEDGER = "tianjitown.admin.ledger";
    static final String EXPAND = "tianjitown.admin.expand";
    static final String BUFF = "tianjitown.admin.buff";
    static final String OPERATIONS = "tianjitown.admin.operations";

    private static final Map<String, String> SCOPED_ROOTS = Map.ofEntries(
            Map.entry("money", MONEY),
            Map.entry("tax", TAX),
            Map.entry("ledger", LEDGER),
            Map.entry("expand", EXPAND),
            Map.entry("buff", BUFF),
            Map.entry("status", OPERATIONS),
            Map.entry("diagnose", OPERATIONS),
            Map.entry("system", OPERATIONS));
    private static final List<String> SCOPED_PERMISSIONS = List.copyOf(
            SCOPED_ROOTS.values());

    private TownAdminPermissions() {
    }

    public static boolean hasAny(Predicate<String> hasPermission) {
        return hasPermission.test(ROOT) || SCOPED_PERMISSIONS.stream().anyMatch(hasPermission);
    }

    public static boolean has(Predicate<String> hasPermission, String permission) {
        return hasPermission.test(ROOT) || hasPermission.test(permission);
    }

    public static boolean canViewHelpTopic(Predicate<String> hasPermission, String topic) {
        if (hasPermission.test(ROOT)) {
            return true;
        }
        String scopedPermission = SCOPED_ROOTS.get(topic.toLowerCase(Locale.ROOT));
        return scopedPermission != null && hasPermission.test(scopedPermission);
    }
}

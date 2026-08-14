package cn.tianji.town.paper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

final class TownAdminPermissions {
    static final String ROOT = "tianjitown.admin";
    static final String PREFLIGHT = "tianjitown.admin.phase0";
    static final String MONEY = "tianjitown.admin.money";
    static final String TAX = "tianjitown.admin.tax";
    static final String LEDGER = "tianjitown.admin.ledger";
    static final String EXPAND = "tianjitown.admin.expand";
    static final String BUFF = "tianjitown.admin.buff";
    static final String OPERATIONS = "tianjitown.admin.operations";

    private static final Map<String, String> SCOPED_ROOTS = Map.ofEntries(
            Map.entry("phase0", PREFLIGHT),
            Map.entry("money", MONEY),
            Map.entry("tax", TAX),
            Map.entry("ledger", LEDGER),
            Map.entry("expand", EXPAND),
            Map.entry("buff", BUFF),
            Map.entry("status", OPERATIONS),
            Map.entry("diagnose", OPERATIONS),
            Map.entry("backup", OPERATIONS),
            Map.entry("system", OPERATIONS));
    private static final List<String> SCOPED_PERMISSIONS = List.copyOf(
            SCOPED_ROOTS.values());

    private TownAdminPermissions() {
    }

    static boolean hasAny(Predicate<String> hasPermission) {
        return hasPermission.test(ROOT) || SCOPED_PERMISSIONS.stream().anyMatch(hasPermission);
    }

    static boolean has(Predicate<String> hasPermission, String permission) {
        return hasPermission.test(ROOT) || hasPermission.test(permission);
    }

    static boolean canUseRoot(Predicate<String> hasPermission, String commandRoot) {
        if (hasPermission.test(ROOT)) {
            return true;
        }
        String normalized = commandRoot.toLowerCase(Locale.ROOT);
        if (normalized.equals("help") || normalized.equals("confirm")
                || normalized.equals("cancel")) {
            return hasAny(hasPermission);
        }
        String scopedPermission = SCOPED_ROOTS.get(normalized);
        return scopedPermission != null && hasPermission.test(scopedPermission);
    }

    static boolean canViewHelpTopic(Predicate<String> hasPermission, String topic) {
        if (hasPermission.test(ROOT)) {
            return true;
        }
        String scopedPermission = SCOPED_ROOTS.get(topic.toLowerCase(Locale.ROOT));
        return scopedPermission != null && hasPermission.test(scopedPermission);
    }
}

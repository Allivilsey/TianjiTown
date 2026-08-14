package cn.tianji.town.paper;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownAdminPermissionsTest {
    @Test
    void grantsEachScopedCommandWithoutRootPermission() {
        assertScopedCommand(TownAdminPermissions.MONEY, "money");
        assertScopedCommand(TownAdminPermissions.TAX, "tax");
        assertScopedCommand(TownAdminPermissions.LEDGER, "ledger");
        assertScopedCommand(TownAdminPermissions.EXPAND, "expand");
        assertScopedCommand(TownAdminPermissions.BUFF, "buff");
        assertScopedCommand(TownAdminPermissions.PREFLIGHT, "phase0");
        assertScopedCommand(TownAdminPermissions.OPERATIONS, "diagnose");
        Predicate<String> operations = Set.of(TownAdminPermissions.OPERATIONS)::contains;
        assertTrue(TownAdminPermissions.canUseRoot(operations, "backup"));
        assertTrue(TownAdminPermissions.canUseRoot(operations, "status"));
        assertTrue(TownAdminPermissions.canViewHelpTopic(operations, "system"));
    }

    @Test
    void rootPermissionGrantsEveryCommandAndScopedCheck() {
        Predicate<String> root = Set.of(TownAdminPermissions.ROOT)::contains;

        assertTrue(TownAdminPermissions.canUseRoot(root, "town"));
        assertTrue(TownAdminPermissions.canUseRoot(root, "money"));
        assertTrue(TownAdminPermissions.has(root, TownAdminPermissions.MONEY));
        assertTrue(TownAdminPermissions.canViewHelpTopic(root, "ledger"));
    }

    @Test
    void scopedPermissionDoesNotGrantUnrelatedCommands() {
        Predicate<String> money = Set.of(TownAdminPermissions.MONEY)::contains;

        assertTrue(TownAdminPermissions.hasAny(money));
        assertTrue(TownAdminPermissions.canUseRoot(money, "help"));
        assertFalse(TownAdminPermissions.canUseRoot(money, "tax"));
        assertFalse(TownAdminPermissions.canUseRoot(money, "status"));
        assertFalse(TownAdminPermissions.canViewHelpTopic(money, "system"));
    }

    private static void assertScopedCommand(String permission, String commandRoot) {
        Predicate<String> granted = Set.of(permission)::contains;

        assertTrue(TownAdminPermissions.canUseRoot(granted, commandRoot));
        assertTrue(TownAdminPermissions.canViewHelpTopic(granted, commandRoot));
        assertTrue(TownAdminPermissions.has(granted, permission));
    }
}

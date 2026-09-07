package org.allivlisey.tianjitown.paper.command;

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
        assertScopedCommand(TownAdminPermissions.BUFF, "buff");
        assertScopedCommand(TownAdminPermissions.OPERATIONS, "diagnose");
        Predicate<String> operations = Set.of(TownAdminPermissions.OPERATIONS)::contains;
        assertTrue(TownAdminPermissions.canViewHelpTopic(operations, "system"));
    }

    @Test
    void rootPermissionGrantsEveryCommandAndScopedCheck() {
        Predicate<String> root = Set.of(TownAdminPermissions.ROOT)::contains;

        assertTrue(TownAdminPermissions.has(root, TownAdminPermissions.MONEY));
        assertTrue(TownAdminPermissions.canViewHelpTopic(root, "ledger"));
    }

    @Test
    void scopedPermissionDoesNotGrantUnrelatedCommands() {
        Predicate<String> money = Set.of(TownAdminPermissions.MONEY)::contains;

        assertTrue(TownAdminPermissions.hasAny(money));
        assertFalse(TownAdminPermissions.canViewHelpTopic(money, "system"));
    }

    private static void assertScopedCommand(String permission, String commandRoot) {
        Predicate<String> granted = Set.of(permission)::contains;

        assertTrue(TownAdminPermissions.canViewHelpTopic(granted, commandRoot));
        assertTrue(TownAdminPermissions.has(granted, permission));
    }
}

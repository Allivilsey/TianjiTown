package org.allivlisey.tianjitown.paper.command;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class TownAdminPermissionsTest {
    @Test void onlyRootGrantsEveryManagementEntry() {
        for (String scope : Set.of("money", "tax", "ledger", "buff", "operations")) {
            var old = Set.of("tianjitown.admin." + scope);
            assertFalse(TownAdminPermissions.hasAny(old::contains));
            assertFalse(TownAdminPermissions.has(old::contains, "tianjitown.admin." + scope));
            assertFalse(TownAdminPermissions.canViewHelpTopic(old::contains, scope));
        }
        var root = Set.of(TownAdminPermissions.ROOT);
        assertTrue(TownAdminPermissions.hasAny(root::contains));
        for (String topic : TownAdminPermissions.HELP_TOPICS) {
            assertTrue(TownAdminPermissions.canViewHelpTopic(root::contains, topic));
        }
    }
}

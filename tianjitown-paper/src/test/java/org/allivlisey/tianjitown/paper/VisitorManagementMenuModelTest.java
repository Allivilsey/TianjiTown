package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.core.town.MemberRole;
import org.allivlisey.tianjitown.storage.governance.MemberGovernanceSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisitorManagementMenuModelTest {
    private static final UUID TOWN_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String PERMISSION_KEY = "dialog.tooltip.governance.visitor-permission";

    @TempDir
    Path temporaryDirectory;

    @Test
    void visitorPermissionHintIsConfiguredAndDoesNotUseMissingMessageFallback() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertTrue(messages.hasMessage(PERMISSION_KEY));
        String hint = MessageTestSupport.assertConfigured(messages, PERMISSION_KEY);
        assertFalse(hint.contains("缺少消息配置"));
        assertTrue(hint.contains("镇长"));
        assertTrue(hint.contains("副镇长"));
    }

    @Test
    void onlyMayorAndDeputySeeVisitorEntryWithTheConfiguredPermissionHint() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        for (MemberRole role : List.of(MemberRole.MAYOR, MemberRole.DEPUTY_MAYOR)) {
            VisitorManagementMenuModel model = VisitorManagementMenuModel.create(
                    messages, governance(role), TOWN_ID);

            assertTrue(model.visible(), role.name());
            assertEquals(messages.rawText("dialog.governance.visitors"), model.label());
            assertEquals(List.of(
                    messages.rawText("dialog.tooltip.governance.visitors"),
                    messages.rawText(PERMISSION_KEY)), model.lore());
            assertEquals("VISITOR_CENTER", model.action());
            assertEquals(TOWN_ID.toString(), model.target());
        }

        VisitorManagementMenuModel memberModel = VisitorManagementMenuModel.create(
                messages, governance(MemberRole.MEMBER), TOWN_ID);
        assertFalse(memberModel.visible());
        assertEquals(List.of(), memberModel.lore());
        assertNull(memberModel.action());
        assertNull(memberModel.target());
    }

    private static MemberGovernanceSnapshot governance(MemberRole role) {
        return new MemberGovernanceSnapshot(TOWN_ID, "测试小镇", role, 1L, 1L,
                List.of(), null, List.of());
    }
}

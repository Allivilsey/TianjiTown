package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.storage.town.TownPlayerChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownPlayerChangeNotificationTest {
    private static final UUID TOWN_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final UUID PLAYER_ID = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");
    private static final List<String> NOTIFICATION_KEYS = List.of(
            "chat.notification.member-removed",
            "chat.notification.visitor-added",
            "chat.notification.visitor-removed");

    @TempDir
    Path temporaryDirectory;

    @Test
    void allMembershipNotificationsDeclareAndRenderTheTownPlaceholder() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        TownPlayerChange change = new TownPlayerChange(TOWN_ID, PLAYER_ID, "改名后的天际镇");

        for (String key : NOTIFICATION_KEYS) {
            MessageContract.Entry contract = MessageContract.entry(key);
            assertEquals(Set.of("town"), contract.placeholders(), key);
            String rendered = MessageTestSupport.assertConfigured(messages, key,
                    TownUiController.townNotificationPlaceholders(change));
            assertTrue(rendered.contains("改名后的天际镇"), key);
            assertFalse(rendered.contains("{town}"), key);
            assertFalse(rendered.contains("当前小镇"), key);
            assertFalse(rendered.contains("一个小镇"), key);
        }
    }

    @Test
    void memberKickCallbackUsesTheCommittedTownNameAndEscapesFormatting() {
        assertCallbackNotification("chat.notification.member-removed");
    }

    @Test
    void visitorAddCallbackUsesTheCommittedTownNameAndEscapesFormatting() {
        assertCallbackNotification("chat.notification.visitor-added");
    }

    @Test
    void visitorRemoveCallbackUsesTheCommittedTownNameAndEscapesFormatting() {
        assertCallbackNotification("chat.notification.visitor-removed");
    }

    private void assertCallbackNotification(String key) {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        TownPlayerChange change = new TownPlayerChange(TOWN_ID, PLAYER_ID,
                "改名镇 &a 不应变色 §c");

        Map<String, String> placeholders = TownUiController.townNotificationPlaceholders(change);
        assertEquals("改名镇 ＆a 不应变色 �c", placeholders.get("town"));
        String rendered = MessageTestSupport.assertConfigured(messages, key, placeholders);
        assertTrue(rendered.contains("改名镇 ＆a 不应变色 �c"));
        assertFalse(rendered.contains("{town}"));
    }
}

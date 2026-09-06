package org.allivlisey.tianjitown.paper.ui.membership;

import org.allivlisey.tianjitown.paper.ui.TownUiController;
import org.allivlisey.tianjitown.storage.town.TownPlayerChange;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TownPlayerChangeNotificationTest {
    @Test
    void callbackUsesTheCommittedTownNameAndEscapesFormatting() {
        TownPlayerChange change = new TownPlayerChange(UUID.randomUUID(), UUID.randomUUID(),
                "改名镇 &a 不应变色 §c");

        assertEquals("改名镇 ＆a 不应变色 �c",
                TownUiController.townNotificationPlaceholders(change).get("town"));
    }
}

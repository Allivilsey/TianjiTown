package org.allivlisey.tianjitown.paper.message;

import java.util.Map;
import org.allivlisey.tianjitown.core.ports.LandProtectionService;
import org.allivlisey.tianjitown.storage.town.TownSnapshot;

/** Residence areas share their parent town's display name and messages. */
public final class TownResidenceMessages {
    private TownResidenceMessages() {}

    public static LandProtectionService.Result sync(PluginMessages messages,
            LandProtectionService land, TownSnapshot town) {
        Map<String, ?> values = Map.of("town", town.profile().name());
        return land.setMessages(town.residenceName(),
                messages.plainText("residence.enter", values),
                messages.plainText("residence.leave", values));
    }
}

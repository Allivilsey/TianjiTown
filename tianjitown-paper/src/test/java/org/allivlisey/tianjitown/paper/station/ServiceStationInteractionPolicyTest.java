package org.allivlisey.tianjitown.paper.station;

import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServiceStationInteractionPolicyTest {
    @Test
    void validStationOnlyOpensFromMainHandRightClick() {
        assertEquals(ServiceStationInteractionPolicy.Outcome.OPEN_MENU,
                ServiceStationInteractionPolicy.decide(true, Action.RIGHT_CLICK_BLOCK,
                        EquipmentSlot.HAND, false, false));
        assertEquals(ServiceStationInteractionPolicy.Outcome.CANCEL,
                ServiceStationInteractionPolicy.decide(true, Action.RIGHT_CLICK_BLOCK,
                        EquipmentSlot.OFF_HAND, false, false));
    }

    @Test
    void validStationCancelsAllLeftClicksExceptExplicitAdminTeardown() {
        assertEquals(ServiceStationInteractionPolicy.Outcome.CANCEL,
                ServiceStationInteractionPolicy.decide(true, Action.LEFT_CLICK_BLOCK,
                        EquipmentSlot.HAND, false, true));
        assertEquals(ServiceStationInteractionPolicy.Outcome.CANCEL,
                ServiceStationInteractionPolicy.decide(true, Action.LEFT_CLICK_BLOCK,
                        EquipmentSlot.OFF_HAND, true, true));
        assertEquals(ServiceStationInteractionPolicy.Outcome.DESTROY,
                ServiceStationInteractionPolicy.decide(true, Action.LEFT_CLICK_BLOCK,
                        EquipmentSlot.HAND, true, true));
    }

    @Test
    void invalidMarkersKeepNormalInteractionBehavior() {
        assertEquals(ServiceStationInteractionPolicy.Outcome.PASS_THROUGH,
                ServiceStationInteractionPolicy.decide(false, Action.RIGHT_CLICK_BLOCK,
                        EquipmentSlot.HAND, true, true));
        assertEquals(ServiceStationInteractionPolicy.Outcome.PASS_THROUGH,
                ServiceStationInteractionPolicy.decide(false, Action.LEFT_CLICK_BLOCK,
                        EquipmentSlot.HAND, true, true));
    }
}

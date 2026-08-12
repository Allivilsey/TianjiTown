package cn.tianji.town.paper;

import cn.tianji.town.storage.phase3.PhaseThreeRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TownActionFailuresTest {
    @Test
    void insufficientBalanceUsesSpecificReasonInsteadOfGenericConflict() {
        TownActionResult result = TownActionFailures.from("BUFF_BUY",
                new PhaseThreeRepository.ConflictException("小镇公共余额不足"));

        assertEquals("INSUFFICIENT_BALANCE", result.reason());
    }

    @Test
    void missingTargetHasStableReason() {
        TownActionResult result = TownActionFailures.from("JOIN_APPLY",
                new IllegalArgumentException("小镇不存在"));

        assertEquals("NOT_FOUND", result.reason());
    }
}

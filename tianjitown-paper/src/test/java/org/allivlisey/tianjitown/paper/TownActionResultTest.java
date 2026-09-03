package org.allivlisey.tianjitown.paper;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TownActionResultTest {
    @Test
    void successOutputIsStableAndKeysAreSorted() {
        TownActionResult result = TownActionResult.success("TOWN_EXPAND",
                Map.of("town_id", "town-1", "balance_minor", 9000,
                        "message", "two words"));

        assertEquals("RESULT success=true action=TOWN_EXPAND balance_minor=9000 "
                        + "message=\"two words\" town_id=town-1",
                result.machineLine());
    }

    @Test
    void failureOutputContainsStableReasonAndEscapedDetail() {
        TownActionResult result = TownActionResult.failure("JOIN_APPLY", "INVALID_STATE",
                Map.of("detail", "line 1\n\"line 2\""));

        assertEquals("RESULT success=false action=JOIN_APPLY reason=INVALID_STATE "
                        + "detail=\"line 1\\n\\\"line 2\\\"\"",
                result.machineLine());
    }

    @Test
    void identifiersMustUseMachineSafeTokens() {
        assertThrows(IllegalArgumentException.class,
                () -> TownActionResult.failure("join apply", "INVALID_STATE"));
    }
}

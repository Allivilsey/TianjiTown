package cn.tianji.town.paper;

import cn.tianji.town.core.application.ApplicationText;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TownActionsTest {
    @Test
    void invalidMiniMessageTextAlwaysUsesValidationFailedReason() {
        ApplicationText text = new ApplicationText("<red>镇", "TJ", "SKY", "简介",
                List.of("友善交流"));
        AtomicReference<TownActionOutcome<Object>> outcome = new AtomicReference<>();

        assertFalse(TownActions.validateText("APPLICATION_CREATE", text, outcome::set));
        assertEquals("VALIDATION_FAILED", outcome.get().result().reason());
    }
}

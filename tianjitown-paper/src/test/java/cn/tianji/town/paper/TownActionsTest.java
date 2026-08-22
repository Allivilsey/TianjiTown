package cn.tianji.town.paper;

import cn.tianji.town.core.application.ApplicationText;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownActionsTest {
    @Test
    void invalidMiniMessageTextAlwaysUsesValidationFailedReason() {
        ApplicationText text = new ApplicationText("<red>镇", "TJ", "SKY", "简介",
                List.of("友善交流"));
        AtomicReference<TownActionOutcome<Object>> outcome = new AtomicReference<>();

        assertFalse(TownActions.validateText("APPLICATION_CREATE", text, outcome::set));
        assertEquals("VALIDATION_FAILED", outcome.get().result().reason());
    }

    @Test
    void uiAndTestCommandShareActionsAndOutcomeContractWithoutCommandForwarding()
            throws Exception {
        var actionsField = TownUiController.class.getDeclaredField("actions");
        assertEquals(TownActions.class, actionsField.getType());
        assertTrue(Modifier.isFinal(actionsField.getModifiers()));

        var testOutput = TestCommand.class.getDeclaredMethod("output", CommandSender.class);
        assertEquals(Consumer.class, testOutput.getReturnType());
        assertTrue(testOutput.getGenericReturnType().getTypeName()
                .contains(TownActionOutcome.class.getName()));
        var uiOutput = TownUiController.class.getDeclaredMethod("handleOutcome", Player.class,
                TownActionOutcome.class, Consumer.class);
        assertTrue(uiOutput.getGenericParameterTypes()[1].getTypeName()
                .contains(TownActionOutcome.class.getName()));

        String testCommandBytecode = classFile(TestCommand.class);
        String townUiBytecode = classFile(TownUiController.class);
        assertTrue(testCommandBytecode.contains("TownActions"));
        assertTrue(testCommandBytecode.contains("notifyMayorJoinApplication"));
        assertTrue(townUiBytecode.contains("TownActions"));
        assertFalse(testCommandBytecode.contains("dispatchCommand"));
        assertFalse(townUiBytecode.contains("dispatchCommand"));
        assertFalse(testCommandBytecode.contains("TownAdminCommand"));
        assertFalse(townUiBytecode.contains("TownAdminCommand"));
    }

    private static String classFile(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var input = type.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("找不到类文件: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}

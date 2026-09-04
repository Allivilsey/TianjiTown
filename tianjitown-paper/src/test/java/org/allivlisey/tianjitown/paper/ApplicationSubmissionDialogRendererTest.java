package org.allivlisey.tianjitown.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationSubmissionDialogRendererTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void summarySubmitTooltipOnlyExplainsTheNextPage() {
        assertEquals(List.of("tooltip.application.submit-ready"),
                ApplicationSubmissionDialogRenderer.submitTooltipKeys(true));
        assertEquals(List.of("tooltip.application.submit-waiting"),
                ApplicationSubmissionDialogRenderer.submitTooltipKeys(false));
        assertFalse(ApplicationSubmissionDialogRenderer.submitTooltipKeys(true).stream()
                .anyMatch(key -> key.contains("submit-fee")));
    }

    @Test
    void confirmationRendersTheFormattedFeeExactlyOnce() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String formattedFee = "¤2,000.00";

        String consequence = ApplicationSubmissionDialogRenderer.confirmationConsequence(
                messages, formattedFee);

        assertEquals(1, occurrences(consequence, formattedFee));
        assertTrue(consequence.contains("提交后需等待管理员审核"));
        assertTrue(consequence.contains("批准时将从申请人余额扣除"));
        assertFalse(consequence.contains("{amount}"));
        assertTrue(messages.hasMessage("dialog.confirmation.submit-application-consequence"));
        assertEquals("amount",
                MessageContract.entry("dialog.confirmation.submit-application-consequence")
                        .placeholders().iterator().next());
    }

    @Test
    void confirmationUsesConfiguredWordingButStillRequiresTheAmountPlaceholder() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("dialog.confirmation.submit-application-consequence",
                "CHECK {amount}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("CHECK formatted-fee",
                ApplicationSubmissionDialogRenderer.confirmationConsequence(messages,
                        "formatted-fee"));
    }

    private static int occurrences(String text, String value) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(value, index)) >= 0) {
            count++;
            index += value.length();
        }
        return count;
    }
}

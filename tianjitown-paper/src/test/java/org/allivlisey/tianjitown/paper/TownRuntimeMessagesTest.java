package org.allivlisey.tianjitown.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TownRuntimeMessagesTest {
    private static final List<String> RUNTIME_KEYS = List.of(
            "log.scheduler.lifecycle-stopped", "log.scheduler.quick-shop-tax-refresh-failure",
            "log.lifecycle.sqlite-recovered", "log.lifecycle.sqlite-interrupted",
            "log.lifecycle.interrupted-provision-reason",
            "log.lifecycle.interrupted-provisions-recovered",
            "log.lifecycle.interrupted-provision-recovery-failure",
            "log.donation.refund-retry-failed", "log.donation.refund-finalization-failed",
            "log.donation.refund-recovered", "log.donation.refund-exhausted",
            "log.donation.settlement-balance-read-failure", "log.donation.settlement-shortfall",
            "log.donation.settlement-reconciliation-failure",
            "log.residence.reconciliation-failure", "log.residence.reconciliation-difference",
            "log.residence.reconciliation-sqlite-read-failure",
            "log.residence.automatic-repair-cancelled", "log.residence.automatic-repair-delayed",
            "log.residence.automatic-repair-sqlite-read-failure",
            "log.residence.automatic-repair-api-failure",
            "log.residence.automatic-repair-consistent",
            "log.residence.automatic-repair-completed",
            "log.residence.automatic-repair-failed");

    @TempDir
    Path temporaryDirectory;

    @Test
    void runtimeRoutesResolveEveryDeclaredKeyAndPlaceholder() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        RUNTIME_KEYS.forEach(key -> MessageTestSupport.assertConfigured(messages, key));
    }

    @Test
    void runtimeRoutesUseReloadedConfiguration() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("log.donation.refund-retry-failed", "RETRY {attempt}/{operation}/{detail}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        assertEquals("RETRY 2/op/boom", messages.plainText("log.donation.refund-retry-failed",
                Map.of("attempt", 2, "operation", "op", "detail", "boom")));
    }
}

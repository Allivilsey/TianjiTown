package org.allivlisey.tianjitown.paper.land;
import org.allivlisey.tianjitown.paper.message.PluginMessages;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProvisionResultTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsConfiguredResultRoutesStructuredUntilTheRenderingBoundary() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        ProvisionResult success = ProvisionResult.success(null);
        ProvisionResult busy = ProvisionResult.busy("busy");
        ProvisionResult timeout = ProvisionResult.timeout(null);

        assertEquals("dialog.provision.success-detail", success.detail().messageKey());
        assertEquals("dialog.provision.busy-recovery-action", busy.recoveryAction().messageKey());
        assertEquals("dialog.provision.timeout-detail", timeout.detail().messageKey());
        assertEquals("dialog.provision.timeout-recovery-action", timeout.recoveryAction().messageKey());
        assertNull(busy.detail().messageKey());

        for (String rendered : List.of(success.detail(messages), busy.recoveryAction(messages),
                timeout.detail(messages), timeout.recoveryAction(messages))) {
            assertFalse(rendered.isBlank());
            assertFalse(rendered.contains("{"));
        }
    }

    @Test
    void usesConfiguredResultMessagesAfterReload() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("dialog.provision.success-detail", "SUCCESS");
        configuration.set("dialog.provision.busy-recovery-action", "BUSY");
        configuration.set("dialog.provision.timeout-detail", "TIMEOUT");
        configuration.set("dialog.provision.timeout-recovery-action", "RECOVER");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        assertEquals("SUCCESS", ProvisionResult.success(null).detail(messages));
        assertEquals("BUSY", ProvisionResult.busy("busy").recoveryAction(messages));
        assertEquals("TIMEOUT", ProvisionResult.timeout(null).detail(messages));
        assertEquals("RECOVER", ProvisionResult.timeout(null).recoveryAction(messages));
    }
}

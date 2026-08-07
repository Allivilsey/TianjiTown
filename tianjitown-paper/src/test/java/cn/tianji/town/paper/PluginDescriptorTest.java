package cn.tianji.town.paper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDescriptorTest {
    @Test
    void runtimeGatedPluginsAreSoftDependencies() throws IOException {
        String descriptor;
        try (InputStream stream = PluginDescriptorTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(stream, "plugin.yml 应进入测试类路径");
            descriptor = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertFalse(descriptor.lines().map(String::stripLeading)
                .anyMatch(line -> line.startsWith("depend:")));
        String softDependencies = descriptor.lines().map(String::strip)
                .filter(line -> line.startsWith("softdepend:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("plugin.yml 缺少 softdepend"));
        for (String plugin : List.of("Vault", "Residence", "QuickShop-Hikari", "XConomy")) {
            assertTrue(softDependencies.contains(plugin), plugin + " 必须由运行时门禁检查");
        }
    }
}

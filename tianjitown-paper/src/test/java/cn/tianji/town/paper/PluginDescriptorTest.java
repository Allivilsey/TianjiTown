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
    void declaresWorldGuardAsRequiredAndOtherIntegrationsAsSoftDependencies() throws IOException {
        String descriptor = descriptor();

        String dependencies = descriptor.lines().map(String::strip)
                .filter(line -> line.startsWith("depend:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("plugin.yml 缺少 depend"));
        assertTrue(dependencies.contains("WorldGuard"), "WorldGuard 必须声明为硬依赖");
        String softDependencies = descriptor.lines().map(String::strip)
                .filter(line -> line.startsWith("softdepend:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("plugin.yml 缺少 softdepend"));
        for (String plugin : List.of("Vault", "Residence", "QuickShop-Hikari", "XConomy",
                "Jobs", "GlobalMarketPlus")) {
            assertTrue(softDependencies.contains(plugin), plugin + " 必须声明为软依赖");
        }
        assertFalse(softDependencies.contains("WorldGuard"));
    }

    @Test
    void adminCommandAllowsScopedPermissionsToReachExecutor() throws IOException {
        String descriptor = descriptor();
        String commandSection = descriptor.substring(descriptor.indexOf("commands:"),
                descriptor.indexOf("permissions:"));

        assertFalse(commandSection.lines().map(String::strip)
                .anyMatch(line -> line.startsWith("permission:")),
                "townadmin 不能在命令根节点要求完整管理员权限");
        for (String permission : List.of("tianjitown.admin.money", "tianjitown.admin.tax",
                "tianjitown.admin.ledger", "tianjitown.admin.expand",
                "tianjitown.admin.buff", "tianjitown.admin.operations")) {
            assertTrue(descriptor.contains(permission + ":"), permission + " 必须被声明");
        }
    }

    @Test
    void testCommandUsesDedicatedPermissionWithoutCommandLevelInterception() throws IOException {
        String descriptor = descriptor();
        String commandSection = descriptor.substring(descriptor.indexOf("commands:"),
                descriptor.indexOf("permissions:"));

        assertTrue(commandSection.contains("testcommand:"));
        assertFalse(commandSection.lines().map(String::strip)
                .anyMatch(line -> line.startsWith("permission:")),
                "测试命令必须由执行器返回稳定的权限失败结果");
        assertTrue(descriptor.contains("tianjitown.testcommand:"));
        assertTrue(descriptor.substring(descriptor.indexOf("tianjitown.testcommand:"))
                .contains("default: false"));
    }

    private static String descriptor() throws IOException {
        try (InputStream stream = PluginDescriptorTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(stream, "plugin.yml 应进入测试类路径");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

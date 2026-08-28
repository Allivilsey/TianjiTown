package cn.tianji.town.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDescriptorTest {
    @Test
    void declaresAllRuntimeIntegrationsAsSoftDependenciesForDiagnosableLocking()
            throws IOException {
        String descriptor = descriptor();

        String softDependencies = descriptor.lines().map(String::strip)
                .filter(line -> line.startsWith("softdepend:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("plugin.yml 缺少 softdepend"));
        for (String plugin : List.of("Vault", "Residence", "QuickShop-Hikari", "XConomy",
                "Jobs", "GlobalMarketPlus", "WorldBorder")) {
            assertTrue(softDependencies.contains(plugin), plugin + " 必须声明为软依赖");
        }
        assertFalse(descriptor.lines().map(String::strip)
                .anyMatch(line -> line.startsWith("depend:")),
                "硬依赖会让 Paper 在插件门禁运行前直接拒绝加载");
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
    void buffPermissionDescriptionMatchesNonRefundablePolicy() throws IOException {
        String descriptor = descriptor();
        String buffPermission = descriptor.substring(
                descriptor.lastIndexOf("\n  tianjitown.admin.buff:"),
                descriptor.lastIndexOf("\n  tianjitown.admin.operations:"));

        assertTrue(buffPermission.contains("description: 查询和代购公共 Buff"));
        assertFalse(buffPermission.contains("退款取消"));
    }

    @Test
    void packagesPlayerMessageConfiguration() throws IOException {
        try (InputStream stream = PluginDescriptorTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(stream, "messages.yml 应进入插件 JAR");
            String messages = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(messages.contains("handbook:"));
            assertTrue(messages.contains("application:"));
            assertTrue(messages.contains("operation-failed:"));
        }
    }

    @Test
    void packagesDialogTextConfiguration() throws IOException {
        try (InputStream stream = PluginDescriptorTest.class.getResourceAsStream("/messages.yml")) {
            assertNotNull(stream, "messages.yml 应进入插件 JAR");
            YamlConfiguration messages = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));

            for (String section : List.of("common", "confirmation", "notice", "tax",
                    "territory", "buff", "rules", "review", "application", "donation",
                    "invitation")) {
                assertNotNull(messages.getConfigurationSection("dialog." + section),
                        "messages.yml 缺少 Dialog 文案分组: " + section);
            }
            for (String key : List.of("dialog.common.cancel", "dialog.tax.title",
                    "dialog.territory.cell.expand-hint", "dialog.application.title",
                    "dialog.notice.operation-failed-title")) {
                assertFalse(messages.getString(key, "").isBlank(),
                        "messages.yml 缺少 Dialog 文案: " + key);
            }
        }
    }

    @Test
    void existingMessageFileUsesPackagedDialogDefaults(@TempDir Path dataFolder)
            throws IOException {
        Files.writeString(dataFolder.resolve("messages.yml"), """
                dialog:
                  common:
                    cancel: '自定义取消'
                """, StandardCharsets.UTF_8);

        PluginMessages messages = new PluginMessages(dataFolder.toFile());

        assertEquals("自定义取消", messages.plainText("dialog.common.cancel"));
        assertEquals("设置收入税率", messages.plainText("dialog.tax.title"));
        assertEquals("领地单元: 9/25", messages.plainText("dialog.territory.summary",
                Map.of("current", 9, "maximum", 25)));
    }

    private static String descriptor() throws IOException {
        try (InputStream stream = PluginDescriptorTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(stream, "plugin.yml 应进入测试类路径");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

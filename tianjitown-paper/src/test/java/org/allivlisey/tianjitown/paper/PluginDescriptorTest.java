package org.allivlisey.tianjitown.paper;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
    void keepsOnlyBootstrapSafeDescriptorMetadata() throws IOException {
        String descriptor = descriptor();

        assertFalse(descriptor.lines().map(String::strip)
                .anyMatch(line -> line.startsWith("description:")));
        assertTrue(descriptor.contains("usage: /townadmin help"));
        assertFalse(descriptor.contains("/townadmin reload"));
        for (String localizedText : List.of("天际服小镇治理与统一经济系统", "TianjiTown 管理命令",
                "TianjiTown 全部管理权限", "查询、调整和对账小镇公共资金", "强制调整小镇统一收入税率",
                "查询小镇完整公共账本", "查询和代办小镇领地扩张", "查询和代购公共 Buff",
                "执行统一诊断与在线备份")) {
            assertFalse(descriptor.contains(localizedText), localizedText);
        }
    }

    @Test
    void remainsLoadableWithOptionalLocalizedDescriptionsOmitted() throws Exception {
        PluginDescriptionFile parsed = new PluginDescriptionFile(new StringReader(descriptor()));

        assertEquals("TianjiTown", parsed.getName());
        assertEquals("org.allivlisey.tianjitown.paper.TianjiTownPlugin", parsed.getMain());
        assertEquals("/townadmin help", parsed.getCommands().get("townadmin").get("usage"));
        assertEquals(7, parsed.getPermissions().size());
    }

    @Test
    void appliesConfigurableDescriptorDescriptionsAndUsesOverridesAfterReload(@TempDir Path dataFolder)
            throws Exception {
        PluginMessages messages = new PluginMessages(dataFolder.toFile());
        TestCommand command = new TestCommand();
        Map<String, Permission> permissions = new HashMap<>();
        for (String permission : List.of("tianjitown.admin", "tianjitown.admin.money",
                "tianjitown.admin.tax", "tianjitown.admin.ledger", "tianjitown.admin.expand",
                "tianjitown.admin.buff", "tianjitown.admin.operations")) {
            permissions.put(permission, new Permission(permission));
        }

        PluginDescriptorMessages.apply(messages, command, permissions::get);

        assertEquals("TianjiTown 管理命令", command.getDescription());
        assertEquals("TianjiTown 全部管理权限",
                permissions.get("tianjitown.admin").getDescription());
        assertEquals("查询和代购公共 Buff",
                permissions.get("tianjitown.admin.buff").getDescription());

        YamlConfiguration overrides = new YamlConfiguration();
        overrides.set("plugin.command.townadmin.description", "自定义管理命令");
        overrides.set("plugin.permission.admin.description", "自定义全部权限");
        overrides.set("plugin.permission.admin-buff.description", "自定义 Buff 权限");
        overrides.save(dataFolder.resolve("messages.yml").toFile());
        messages.reload();
        PluginDescriptorMessages.apply(messages, command, permissions::get);

        assertEquals("自定义管理命令", command.getDescription());
        assertEquals("自定义全部权限", permissions.get("tianjitown.admin").getDescription());
        assertEquals("自定义 Buff 权限",
                permissions.get("tianjitown.admin.buff").getDescription());
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

            for (String section : List.of("common", "confirmation", "notice", "member-role", "tax",
                    "territory", "buff", "rules", "review", "application", "donation",
                    "invitation")) {
                assertNotNull(messages.getConfigurationSection("dialog." + section),
                        "messages.yml 缺少 Dialog 文案分组: " + section);
            }
            for (String key : List.of("dialog.common.cancel", "dialog.tax.title",
                    "dialog.territory.cell.expand-hint", "dialog.territory.cell.selected",
                    "dialog.application.title",
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
                    cancel: '&b自定义取消'
                """, StandardCharsets.UTF_8);

        PluginMessages messages = new PluginMessages(dataFolder.toFile());

        assertEquals("§b自定义取消", messages.text("dialog.common.cancel"));
        assertEquals("自定义取消", messages.plainText("dialog.common.cancel"));
        assertEquals(NamedTextColor.AQUA, messages.component("dialog.common.cancel").color());
        assertEquals("设置收入税率", messages.plainText("dialog.tax.title"));
        assertEquals(NamedTextColor.GOLD, messages.component("dialog.tax.title").color());
        assertEquals("已拥有区域: 9/25", messages.plainText("dialog.territory.summary",
                Map.of("current", 9, "maximum", 25)));
        assertEquals("§7已拥有区域: 9/25", messages.text("dialog.territory.summary",
                Map.of("current", 9, "maximum", 25)));
    }

    private static String descriptor() throws IOException {
        try (InputStream stream = PluginDescriptorTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(stream, "plugin.yml 应进入测试类路径");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class TestCommand extends Command {
        private TestCommand() {
            super("townadmin");
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            return true;
        }
    }
}

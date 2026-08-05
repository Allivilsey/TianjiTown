package cn.tianji.town.integrations;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DependencyVersions {
    private final PluginManager pluginManager;

    public DependencyVersions(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    public Map<String, Check> verify(Map<String, String> expectedVersions) {
        Map<String, Check> checks = new LinkedHashMap<>();
        expectedVersions.forEach((name, expected) -> {
            Plugin plugin = pluginManager.getPlugin(name);
            if (plugin == null) {
                checks.put(name, new Check(false, "未安装", expected, null));
            } else if (!plugin.isEnabled()) {
                checks.put(name, new Check(false, "已安装但未启用", expected,
                        plugin.getPluginMeta().getVersion()));
            } else {
                String actual = plugin.getPluginMeta().getVersion();
                checks.put(name, new Check(expected.equals(actual),
                        expected.equals(actual) ? "正常" : "版本不匹配", expected, actual));
            }
        });
        return Map.copyOf(checks);
    }

    public record Check(boolean healthy, String message, String expected, String actual) {
    }
}


package cn.tianji.town.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProvisionResultTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersProvisionResultMessagesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        ProvisionResult success = ProvisionResult.success(null);
        ProvisionResult busy = ProvisionResult.busy("当前建镇流程仍在执行");
        ProvisionResult timeout = ProvisionResult.timeout(null);

        assertEquals("&f小镇创建完成。", success.detail(messages));
        assertEquals("当前建镇流程仍在执行", busy.detail(messages));
        assertEquals("&7返回审核列表后刷新状态", busy.recoveryAction(messages));
        assertEquals("&f服务器在限定时间内没有返回最终结果。", timeout.detail(messages));
        assertEquals("&7返回审核列表并刷新状态；不要重复扣费",
                timeout.recoveryAction(messages));

        for (String rendered : List.of(success.detail(messages), busy.recoveryAction(messages),
                timeout.detail(messages), timeout.recoveryAction(messages))) {
            assertFalse(rendered.isBlank());
            assertFalse(rendered.contains("{"));
            assertFalse(rendered.contains("缺少消息配置"));
        }
    }

    @Test
    void resolvesConfiguredResultMessagesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        ProvisionResult success = ProvisionResult.success(null);
        ProvisionResult busy = ProvisionResult.busy("detail");
        ProvisionResult timeout = ProvisionResult.timeout(null);

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("dialog.provision.success-detail", "&b自定义建镇完成详情");
        configuration.set("dialog.provision.busy-recovery-action", "&e自定义繁忙恢复操作");
        configuration.set("dialog.provision.timeout-detail", "&c自定义超时详情");
        configuration.set("dialog.provision.timeout-recovery-action", "&d自定义超时恢复操作");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("&b自定义建镇完成详情", success.detail(messages));
        assertEquals("&e自定义繁忙恢复操作", busy.recoveryAction(messages));
        assertEquals("&c自定义超时详情", timeout.detail(messages));
        assertEquals("&d自定义超时恢复操作", timeout.recoveryAction(messages));
    }

    @Test
    void preservesDynamicFailureDetailsAsLiteralValues() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        ProvisionResult failure = ProvisionResult.failure(null, "dynamic detail",
                "dynamic recovery action");

        assertEquals("dynamic detail", failure.detail(messages));
        assertEquals("dynamic recovery action", failure.recoveryAction(messages));
    }
}

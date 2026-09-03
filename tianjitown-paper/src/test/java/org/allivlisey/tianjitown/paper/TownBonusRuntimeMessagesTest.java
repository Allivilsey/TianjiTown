package cn.tianji.town.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TownBonusRuntimeMessagesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersTownBonusRuntimeTemplatesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("备份完成；文件=backups/tianjitown.db",
                messages.plainText("chat.bonus.backup-result-success", Map.of(
                        "detail", "备份完成", "file", "backups/tianjitown.db")));
        assertEquals("备份完成",
                messages.plainText("chat.bonus.backup-result-success-no-file",
                        Map.of("detail", "备份完成")));
        assertEquals("备份失败: boom",
                messages.plainText("chat.bonus.backup-result-failure",
                        Map.of("detail", "备份失败: boom")));
        assertEquals("刷新领地加成缓存失败，将继续使用旧快照: boom",
                messages.plainText("log.bonus.index-refresh-failure",
                        Map.of("detail", "boom")));
        assertEquals("清理建筑返还周计数失败: boom",
                messages.plainText("log.bonus.refund-counter-cleanup-failure",
                        Map.of("detail", "boom")));
        assertEquals("备份完成；文件=backups/tianjitown.db",
                messages.plainText("log.bonus.scheduled-backup-success", Map.of(
                        "detail", "备份完成", "file", "backups/tianjitown.db")));
        assertEquals("信标刷新遇到已失效的玩家、世界或依赖对象，已跳过该对象: boom",
                messages.plainText("log.bonus.beacon-refresh-object-failure",
                        Map.of("detail", "boom")));
        assertEquals("信标对象在延迟回调前已失效，已跳过记录: boom",
                messages.plainText("log.bonus.beacon-record-object-failure",
                        Map.of("detail", "boom")));
        assertEquals("清理托管信标效果时对象已失效: boom",
                messages.plainText("log.bonus.beacon-cleanup-object-failure",
                        Map.of("detail", "boom")));
        assertEquals("Vault ERROR 清算账户不可读取",
                messages.plainText("diagnostic.bonus.settlement-account-unavailable"));
        assertEquals("QuickShop reconciliation=INCOMPLETE（历史不可用或超过 1000 条上限）",
                messages.plainText("diagnostic.bonus.quick-shop-reconciliation-incomplete"));
        assertEquals("写入一键诊断报告失败: boom",
                messages.plainText("log.bonus.diagnostic-report-write-failure",
                        Map.of("detail", "boom")));

        Map<String, ?> placeholders = Map.of(
                "detail", "boom", "file", "backups/tianjitown.db");
        for (String key : List.of(
                "chat.bonus.backup-result-success",
                "chat.bonus.backup-result-success-no-file",
                "chat.bonus.backup-result-failure",
                "log.bonus.index-refresh-failure",
                "log.bonus.refund-counter-cleanup-failure",
                "log.bonus.scheduled-backup-success",
                "log.bonus.beacon-refresh-object-failure",
                "log.bonus.beacon-record-object-failure",
                "log.bonus.beacon-cleanup-object-failure",
                "diagnostic.bonus.settlement-account-unavailable",
                "diagnostic.bonus.quick-shop-reconciliation-incomplete",
                "log.bonus.diagnostic-report-write-failure")) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank());
            assertFalse(rendered.contains("缺少消息配置"));
            assertFalse(rendered.contains("{"));
        }
    }

    @Test
    void usesTownBonusRuntimeOverridesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String logKey = "log.bonus.beacon-record-object-failure";
        String chatKey = "chat.bonus.backup-result-success";

        assertEquals("信标对象在延迟回调前已失效，已跳过记录: boom",
                messages.plainText(logKey, Map.of("detail", "boom")));
        assertEquals("备份完成；文件=backups/tianjitown.db",
                messages.plainText(chatKey, Map.of(
                        "detail", "备份完成", "file", "backups/tianjitown.db")));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(logKey, "自定义信标记录日志: {detail}");
        configuration.set(chatKey, "自定义备份成功: {detail} [{file}]");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义信标记录日志: boom",
                messages.plainText(logKey, Map.of("detail", "boom")));
        assertEquals("自定义备份成功: 备份完成 [backups/tianjitown.db]",
                messages.plainText(chatKey, Map.of(
                        "detail", "备份完成", "file", "backups/tianjitown.db")));
    }
}

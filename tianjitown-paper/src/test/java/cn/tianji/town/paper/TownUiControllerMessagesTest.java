package cn.tianji.town.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TownUiControllerMessagesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersStationHandbookEntryAndDecisionMessages() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("关闭玩家小镇界面失败 viewer-1: close boom",
                messages.plainText("log.lifecycle.ui-viewer-close-failure", Map.of(
                        "player", "viewer-1", "detail", "close boom")));
        assertEquals("忽略无效的小镇服务台登记: invalid UUID",
                messages.plainText("log.station.invalid-record",
                        Map.of("detail", "invalid UUID")));
        assertEquals("忽略无效的小镇服务台登记: 缺少整数 x",
                messages.plainText("log.station.invalid-record-missing-integer",
                        Map.of("key", "x")));
        assertEquals("公共", messages.plainText("chat.station.public-owner"));
        assertEquals("小镇手册", messages.plainText("handbook.item-title"));
        assertEquals("§6小镇手册", messages.text("handbook.item-display-name"));
        assertEquals("§6小镇服务\n\n§0右键本手册可打开小镇菜单。\n\n"
                        + "§8本物品通过内部标识识别，改名不会复制其功能。",
                messages.text("handbook.item-pages"));
        assertEquals("小镇服务 · 正在读取", messages.plainText("dialog.main.loading-title"));
        assertEquals("你的小镇申请需要补充资料：请查看申请详情 ",
                messages.plainText("chat.notification.application-needs-changes", Map.of(
                        "reason", messages.plainText(
                                "chat.notification.application-needs-changes-default-reason"))));
        assertEquals("你的小镇申请已被拒绝：未提供原因 ",
                messages.plainText("chat.notification.application-rejected", Map.of(
                        "reason", messages.plainText(
                                "chat.notification.application-rejected-default-reason"))));

        for (String key : List.of(
                "log.lifecycle.ui-viewer-close-failure",
                "log.station.invalid-record",
                "log.station.invalid-record-missing-integer",
                "chat.station.public-owner",
                "handbook.item-title",
                "handbook.item-display-name",
                "handbook.item-pages",
                "dialog.main.loading-title",
                "chat.notification.application-needs-changes-default-reason",
                "chat.notification.application-rejected-default-reason")) {
            String rendered = messages.plainText(key, Map.of(
                    "player", "viewer-1", "detail", "boom", "key", "x"));
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{player}"), key);
            assertFalse(rendered.contains("{detail}"), key);
            assertFalse(rendered.contains("{key}"), key);
        }
    }

    @Test
    void usesConfiguredStationHandbookAndFallbackTextAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("chat.station.public-owner", "公共区域");
        configuration.set("handbook.item-title", "小镇指南");
        configuration.set("handbook.item-display-name", "&b小镇指南");
        configuration.set("dialog.main.loading-title", "&d正在读取小镇服务");
        configuration.set("chat.notification.application-needs-changes-default-reason",
                "请补充申请资料");
        configuration.set("chat.notification.application-rejected-default-reason",
                "管理员未填写原因");
        configuration.set("log.station.invalid-record-missing-integer",
                "自定义服务台配置诊断: 缺少 {key}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        messages.reload();

        assertEquals("公共区域", messages.plainText("chat.station.public-owner"));
        assertEquals("小镇指南", messages.plainText("handbook.item-title"));
        assertEquals("§b小镇指南", messages.text("handbook.item-display-name"));
        assertEquals("§d正在读取小镇服务", messages.text("dialog.main.loading-title"));
        assertEquals("请补充申请资料", messages.plainText(
                "chat.notification.application-needs-changes-default-reason"));
        assertEquals("管理员未填写原因", messages.plainText(
                "chat.notification.application-rejected-default-reason"));
        assertEquals("自定义服务台配置诊断: 缺少 x", messages.plainText(
                "log.station.invalid-record-missing-integer", Map.of("key", "x")));
    }

    @Test
    void rendersMainGovernanceAndVisitorMenuMessagesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.of(
                "town", "青石镇",
                "balance", "1,234.00",
                "taxRate", "5.00%",
                "count", 3,
                "maximum", 25,
                "message", "请补充资料",
                "player", "访客A",
                "page", 2);

        assertEquals("青石镇", messages.plainText("dialog.main.town-summary", placeholders));
        assertEquals("公共资金: 1,234.00 | 税率: 5.00%",
                messages.plainText("dialog.main.finance-summary", placeholders));
        assertEquals("领地单元: 3/25",
                messages.plainText("dialog.main.territory-summary", placeholders));
        assertEquals("待办中心 · 3",
                messages.plainText("dialog.main.pending-count", placeholders));
        assertEquals("管理员意见: 请补充资料",
                messages.plainText("dialog.main.application-review", placeholders));
        assertEquals("小镇访客 · 第 2 页",
                messages.plainText("dialog.visitor.list-title", placeholders));
        assertEquals("邀请访客 · 第 2 页",
                messages.plainText("dialog.visitor.invite-title", placeholders));
        assertEquals("访客A", messages.plainText("dialog.visitor.entry-player", placeholders));
        assertEquals("访客A", messages.plainText("dialog.visitor.invite-player", placeholders));

        for (String key : List.of(
                "dialog.main.title",
                "dialog.main.town-summary",
                "dialog.main.finance-summary",
                "dialog.main.territory-summary",
                "dialog.main.no-pending",
                "dialog.main.town-info",
                "dialog.main.finance",
                "dialog.main.governance",
                "dialog.main.pending",
                "dialog.main.pending-count",
                "dialog.main.personal",
                "dialog.main.application-title",
                "dialog.main.application-incomplete",
                "dialog.main.application-review",
                "dialog.main.application-continue",
                "dialog.main.no-town",
                "dialog.main.no-town-actions",
                "dialog.main.create-application",
                "dialog.main.join-application",
                "dialog.main.my-join-applications",
                "dialog.main.handbook",
                "dialog.main.admin-review",
                "dialog.main.admin-review-count",
                "dialog.governance.title",
                "dialog.governance.town-summary",
                "dialog.governance.pending-joins",
                "dialog.governance.members",
                "dialog.governance.applications",
                "dialog.governance.applications-count",
                "dialog.governance.visitors",
                "dialog.visitor.title",
                "dialog.visitor.list",
                "dialog.visitor.invite",
                "dialog.visitor.entry-player",
                "dialog.visitor.invite-player",
                "dialog.visitor.list-empty",
                "dialog.visitor.list-empty-hint",
                "dialog.visitor.no-candidates",
                "dialog.visitor.previous",
                "dialog.visitor.next",
                "dialog.visitor.list-title",
                "dialog.visitor.invite-title")) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void usesConfiguredMainGovernanceAndVisitorMessagesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("dialog.main.title", "&d自定义小镇入口");
        configuration.set("dialog.main.finance-summary", "&b资金 {balance} / 税率 {taxRate}");
        configuration.set("dialog.governance.title", "&a自定义成员中心");
        configuration.set("dialog.visitor.list-title", "&e访客页 {page}");
        configuration.set("dialog.visitor.entry-player", "&c访客 {player}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        messages.reload();

        assertEquals("自定义小镇入口", messages.plainText("dialog.main.title"));
        assertEquals("§b资金 1,234 / 税率 5%", messages.text("dialog.main.finance-summary",
                Map.of("balance", "1,234", "taxRate", "5%")));
        assertEquals("自定义成员中心", messages.plainText("dialog.governance.title"));
        assertEquals("访客页 3", messages.plainText("dialog.visitor.list-title",
                Map.of("page", 3)));
        assertEquals("访客 玩家A", messages.plainText("dialog.visitor.entry-player",
                Map.of("player", "玩家A")));
    }
}

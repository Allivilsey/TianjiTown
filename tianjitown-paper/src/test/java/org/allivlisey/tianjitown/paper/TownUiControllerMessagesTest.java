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

    @Test
    void rendersPendingPersonalFinanceTaxAndLedgerMessagesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.ofEntries(
                Map.entry("count", 3),
                Map.entry("player", "玩家A"),
                Map.entry("town", "青石镇"),
                Map.entry("balance", "1,234.00"),
                Map.entry("rate", "5%"),
                Map.entry("maximum", 25),
                Map.entry("amount", "123.00"),
                Map.entry("refresh", "2026-09-03 12:00"),
                Map.entry("page", 2),
                Map.entry("type", "CUSTOM_TYPE"),
                Map.entry("actor", "玩家A"),
                Map.entry("time", "2026-09-03T00:00:00Z"),
                Map.entry("note", "成员捐款"),
                Map.entry("reason", "对账失败"),
                Map.entry("playerId", "abcdef"));

        assertEquals("待办中心 · 3",
                messages.plainText("dialog.pending.count-title", placeholders));
        assertEquals("入镇申请 · 3",
                messages.plainText("dialog.pending.applications-count", placeholders));
        assertEquals("玩家A",
                messages.plainText("dialog.personal.player", placeholders));
        assertEquals("所在小镇: 青石镇",
                messages.plainText("dialog.personal.town", placeholders));
        assertEquals("小镇: 青石镇",
                messages.plainText("dialog.finance.town", placeholders));
        assertEquals("公共余额: 1,234.00",
                messages.plainText("dialog.finance.balance", placeholders));
        assertEquals("QuickShop 补贴剩余（12 小时）: 123.00 · 刷新 2026-09-03 12:00",
                messages.plainText("dialog.finance.subsidy-twelve-hour", placeholders));
        assertEquals("当前税率: 5%",
                messages.plainText("dialog.tax.current-rate", placeholders));
        assertEquals("123.00 CUSTOM_TYPE",
                messages.plainText("dialog.ledger.entry-title", placeholders));
        assertEquals("+123.00",
                messages.plainText("dialog.ledger.income-amount", placeholders));
        assertEquals("-123.00",
                messages.plainText("dialog.ledger.expense-amount", placeholders));
        assertEquals("未知玩家（abcdef）",
                messages.plainText("dialog.ledger.unknown-player", placeholders));

        for (String key : List.of(
                "chat.runtime.town-required",
                "dialog.pending.title",
                "dialog.pending.count-title",
                "dialog.pending.empty-title",
                "dialog.pending.decisions-only",
                "dialog.pending.applications-count",
                "dialog.pending.transfer",
                "dialog.personal.title",
                "dialog.personal.player",
                "dialog.personal.no-town",
                "dialog.personal.town",
                "dialog.personal.handbook-hint",
                "dialog.personal.handbook",
                "dialog.personal.disband",
                "dialog.personal.leave",
                "dialog.finance.title",
                "dialog.finance.summary-title",
                "dialog.finance.town",
                "dialog.finance.balance",
                "dialog.finance.tax-rate",
                "dialog.finance.territory-units",
                "dialog.finance.subsidy-twelve-hour",
                "dialog.finance.subsidy-week",
                "dialog.finance.locked",
                "dialog.finance.donation",
                "dialog.finance.tax",
                "dialog.finance.ledger",
                "dialog.finance.buff",
                "dialog.finance.expansion",
                "dialog.tax.menu-title",
                "dialog.tax.summary-title",
                "dialog.tax.current-rate",
                "dialog.tax.scope",
                "dialog.tax.editable-hint",
                "dialog.tax.readonly-hint",
                "dialog.ledger.title",
                "dialog.ledger.page-title",
                "dialog.ledger.page",
                "dialog.ledger.balance",
                "dialog.ledger.empty",
                "dialog.ledger.empty-hint",
                "dialog.ledger.income-amount",
                "dialog.ledger.expense-amount",
                "dialog.ledger.entry-title",
                "dialog.ledger.entry-balance",
                "dialog.ledger.entry-actor",
                "dialog.ledger.entry-time",
                "dialog.ledger.entry-note",
                "dialog.ledger.previous",
                "dialog.ledger.next",
                "dialog.ledger.unknown-player",
                "dialog.ledger.type.quickshop-tax",
                "dialog.ledger.type.jobs-tax",
                "dialog.ledger.type.global-market-plus-tax",
                "dialog.ledger.type.server-tax-subsidy",
                "dialog.ledger.type.application-fee",
                "dialog.ledger.type.donation",
                "dialog.ledger.type.expansion",
                "dialog.ledger.type.expansion-refund",
                "dialog.ledger.type.admin-adjustment",
                "dialog.ledger.type.buff-purchase",
                "dialog.ledger.type.buff-refund",
                "dialog.ledger.type.resource-purchase",
                "dialog.ledger.type.resource-refund",
                "dialog.ledger.type.unknown",
                "dialog.buff.shop-title",
                "dialog.buff.shop-summary-title",
                "dialog.buff.shop-enabled-hint",
                "dialog.buff.shop-paused-hint")) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void usesConfiguredPendingFinanceTaxAndLedgerMessagesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("chat.runtime.town-required", "自定义归属提示");
        configuration.set("dialog.pending.count-title", "&d待办数量 {count}");
        configuration.set("dialog.personal.town", "&b所属小镇 {town}");
        configuration.set("dialog.finance.balance", "&a余额 {balance}");
        configuration.set("dialog.tax.current-rate", "&e税率 {rate}");
        configuration.set("dialog.ledger.page-title", "&6账本页 {page}");
        configuration.set("dialog.ledger.entry-title", "&b流水 {amount}/{type}");
        configuration.set("dialog.ledger.type.quickshop-tax", "QuickShop 税费");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        messages.reload();

        Map<String, ?> placeholders = Map.of(
                "count", 4, "town", "青石镇", "balance", "2,000",
                "rate", "8%", "page", 3, "amount", "+100", "type", "QuickShop 税费");
        assertEquals("自定义归属提示", messages.plainText("chat.runtime.town-required"));
        assertEquals("§d待办数量 4", messages.text("dialog.pending.count-title", placeholders));
        assertEquals("所属小镇 青石镇", messages.plainText("dialog.personal.town", placeholders));
        assertEquals("余额 2,000", messages.plainText("dialog.finance.balance", placeholders));
        assertEquals("税率 8%", messages.plainText("dialog.tax.current-rate", placeholders));
        assertEquals("账本页 3", messages.plainText("dialog.ledger.page-title", placeholders));
        assertEquals("流水 +100/QuickShop 税费",
                messages.plainText("dialog.ledger.entry-title", placeholders));
        assertEquals("QuickShop 税费",
                messages.plainText("dialog.ledger.type.quickshop-tax"));
    }

    @Test
    void rendersExpansionAndBuffShopMessagesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("公共 Buff", messages.plainText("dialog.finance.buff"));
        assertEquals("领地扩张", messages.plainText("dialog.finance.expansion"));
        assertEquals("公共 Buff 商店", messages.plainText("dialog.buff.shop-title"));
        assertEquals("小镇公共 Buff", messages.plainText("dialog.buff.shop-summary-title"));
        assertEquals("使用公共资金购买，效果作用于全体成员且不限制世界",
                messages.plainText("dialog.buff.shop-enabled-hint"));
        assertEquals("商店已暂停新购买，现有效果仍持续到期",
                messages.plainText("dialog.buff.shop-paused-hint"));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("dialog.finance.buff", "&a自定义公共 Buff");
        configuration.set("dialog.finance.expansion", "&b自定义领地扩张");
        configuration.set("dialog.buff.shop-title", "&6自定义 Buff 商店");
        configuration.set("dialog.buff.shop-summary-title", "&d自定义公共 Buff");
        configuration.set("dialog.buff.shop-enabled-hint", "&7自定义可购买说明");
        configuration.set("dialog.buff.shop-paused-hint", "&e自定义暂停说明");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        messages.reload();

        assertEquals("§a自定义公共 Buff", messages.text("dialog.finance.buff"));
        assertEquals("§b自定义领地扩张", messages.text("dialog.finance.expansion"));
        assertEquals("自定义 Buff 商店", messages.plainText("dialog.buff.shop-title"));
        assertEquals("自定义公共 Buff", messages.plainText("dialog.buff.shop-summary-title"));
        assertEquals("自定义可购买说明", messages.plainText("dialog.buff.shop-enabled-hint"));
        assertEquals("自定义暂停说明", messages.plainText("dialog.buff.shop-paused-hint"));
    }

    @Test
    void rendersJoinApplicationMenusWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.ofEntries(
                Map.entry("page", 2),
                Map.entry("town", "青石镇"),
                Map.entry("code", "qingshi"),
                Map.entry("description", "一个友好的小镇"),
                Map.entry("rules", "礼貌交流 | 不得破坏公共设施"),
                Map.entry("count", 3),
                Map.entry("applicant", "玩家乙"),
                Map.entry("time", "2026-09-05 12:00"));

        assertEquals("申请加入小镇 · 第 2 页",
                messages.plainText("dialog.join.list-title", placeholders));
        assertEquals("青石镇", messages.plainText("dialog.join.town-name", placeholders));
        assertEquals("申请加入 · 青石镇",
                messages.plainText("dialog.join.town-title", placeholders));
        assertEquals("小镇代码: qingshi",
                messages.plainText("dialog.join.town-code", placeholders));
        assertEquals("简介: 一个友好的小镇",
                messages.plainText("dialog.join.town-description", placeholders));
        assertEquals("规则: 礼貌交流 | 不得破坏公共设施",
                messages.plainText("dialog.join.town-rules", placeholders));
        assertEquals("提交入镇申请", messages.plainText("dialog.join.apply", placeholders));
        assertEquals("暂无开放的小镇",
                messages.plainText("dialog.join.empty-title", placeholders));
        assertEquals("稍后再来查看", messages.plainText("dialog.join.empty-hint", placeholders));
        assertEquals("上一页", messages.plainText("dialog.join.previous", placeholders));
        assertEquals("下一页", messages.plainText("dialog.join.next", placeholders));
        assertEquals("我的入镇申请 · 3",
                messages.plainText("dialog.my-join.list-title", placeholders));
        assertEquals("青石镇", messages.plainText("dialog.my-join.entry-title", placeholders));
        assertEquals("玩家乙", messages.plainText("dialog.town-join.entry-title", placeholders));
        assertEquals("暂无待处理入镇申请",
                messages.plainText("dialog.town-join.list-empty", placeholders));
        assertEquals("新的申请会出现在待办中心",
                messages.plainText("dialog.town-join.list-empty-hint", placeholders));
        assertEquals("入镇申请 · 3 · 第 2 页",
                messages.plainText("dialog.town-join.list-title", placeholders));
        assertEquals("小镇不存在或已停止运行",
                messages.plainText("chat.runtime.town-unavailable", placeholders));

        for (String key : List.of(
                "chat.runtime.town-unavailable",
                "dialog.join.list-title",
                "dialog.join.town-name",
                "dialog.join.town-title",
                "dialog.join.town-code",
                "dialog.join.town-description",
                "dialog.join.town-rules",
                "dialog.join.apply",
                "dialog.join.empty-title",
                "dialog.join.empty-hint",
                "dialog.join.previous",
                "dialog.join.next",
                "dialog.my-join.list-title",
                "dialog.my-join.entry-title",
                "dialog.town-join.entry-title",
                "dialog.town-join.list-empty",
                "dialog.town-join.list-empty-hint",
                "dialog.town-join.previous",
                "dialog.town-join.next",
                "dialog.town-join.list-title")) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void usesConfiguredJoinApplicationMessagesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("chat.runtime.town-unavailable", "自定义小镇不可用");
        configuration.set("dialog.join.list-title", "&d浏览小镇第 {page} 页");
        configuration.set("dialog.join.town-rules", "&b自定义规则: {rules}");
        configuration.set("dialog.join.apply", "&a自定义提交");
        configuration.set("dialog.my-join.list-title", "&e我的申请 {count}");
        configuration.set("dialog.town-join.entry-title", "&a申请人 {applicant}");
        configuration.set("dialog.town-join.list-title", "&6待审 {count}/{page}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        messages.reload();

        assertEquals("自定义小镇不可用",
                messages.plainText("chat.runtime.town-unavailable"));
        assertEquals("§d浏览小镇第 3 页", messages.text("dialog.join.list-title",
                Map.of("page", 3)));
        assertEquals("§b自定义规则: 礼貌交流",
                messages.text("dialog.join.town-rules", Map.of("rules", "礼貌交流")));
        assertEquals("§a自定义提交", messages.text("dialog.join.apply"));
        assertEquals("§e我的申请 4", messages.text("dialog.my-join.list-title",
                Map.of("count", 4)));
        assertEquals("§a申请人 玩家乙",
                messages.text("dialog.town-join.entry-title", Map.of("applicant", "玩家乙")));
        assertEquals("§6待审 5/2", messages.text("dialog.town-join.list-title",
                Map.of("count", 5, "page", 2)));
    }

    @Test
    void rendersRulesApplicationAndTownMessagesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.ofEntries(
                Map.entry("town", "青石镇"),
                Map.entry("applicant", "玩家甲"),
                Map.entry("status", "草稿"),
                Map.entry("name", "青石镇"),
                Map.entry("residence", "qingshi"),
                Map.entry("description", "一个友好的小镇"),
                Map.entry("player", "成员甲"),
                Map.entry("index", 2),
                Map.entry("rule", "不得破坏公共设施"),
                Map.entry("x", 10),
                Map.entry("z", -4),
                Map.entry("message", "请补充资料"),
                Map.entry("error", "Residence 不可用"),
                Map.entry("count", 5),
                Map.entry("revision", 7));

        assertEquals("小镇不存在", messages.plainText("chat.runtime.town-not-found"));
        assertEquals("青石镇", messages.plainText("dialog.town.summary-title", placeholders));
        assertEquals("申请人: 玩家甲",
                messages.plainText("dialog.application.applicant", placeholders));
        assertEquals("申请状态: 草稿",
                messages.plainText("dialog.application.status-line", placeholders));
        assertEquals("初始成员: 成员甲 [待确认]",
                messages.plainText("dialog.application.initial-member", Map.of(
                        "player", "成员甲", "status", "待确认")));
        assertEquals("规则 2: 不得破坏公共设施",
                messages.plainText("dialog.application.rule", placeholders));
        assertEquals("中心区块: 10, -4",
                messages.plainText("dialog.application.territory-center", placeholders));
        assertEquals("&f2. 不得破坏公共设施",
                messages.rawText("dialog.rules.item", placeholders));

        for (String key : List.of(
                "chat.runtime.town-not-found",
                "dialog.rules.town",
                "dialog.rules.revision",
                "dialog.rules.locked-hint",
                "dialog.rules.acknowledgement",
                "dialog.rules.updated-title",
                "dialog.rules.confirm",
                "dialog.rules.later",
                "dialog.rules.later-tooltip",
                "dialog.rules.required-title",
                "dialog.rules.required-message",
                "dialog.rules.title",
                "dialog.rules.current-heading",
                "dialog.rules.edit-title",
                "dialog.rules.edit-heading",
                "dialog.rules.preview-tooltip",
                "dialog.rules.input-label",
                "dialog.rules.add",
                "dialog.rules.add-tooltip",
                "dialog.rules.delete",
                "dialog.rules.delete-tooltip",
                "dialog.rules.delete-request-invalid",
                "dialog.rules.limit-reached",
                "dialog.rules.refresh-title",
                "dialog.rules.refresh-message",
                "dialog.rules.invalid-title",
                "dialog.rules.invalid-empty",
                "dialog.rules.minimum-one",
                "dialog.rules.delete-missing",
                "dialog.rules.delete-conflict",
                "dialog.rules.item",
                "dialog.rules.empty",
                "dialog.application.summary-title",
                "dialog.application.applicant",
                "dialog.application.status-line",
                "dialog.application.name",
                "dialog.application.residence-name",
                "dialog.application.description",
                "dialog.application.initial-member",
                "dialog.application.initial-member-status.pending",
                "dialog.application.initial-member-status.confirmed",
                "dialog.application.initial-member-status.rejected",
                "dialog.application.rule",
                "dialog.application.territory-center",
                "dialog.application.review-message",
                "dialog.application.last-error",
                "dialog.application.edit",
                "dialog.application.remind",
                "dialog.application.select-site",
                "dialog.application.preview-site",
                "dialog.application.submit",
                "dialog.application.waiting-members",
                "dialog.application.cancel",
                "dialog.town.title",
                "dialog.town.summary-title",
                "dialog.town.residence-name",
                "dialog.town.description",
                "dialog.town.rule-count",
                "dialog.town.rules",
                "dialog.town.members",
                "dialog.town.edit-description",
                "dialog.town.edit-rules",
                "dialog.town.territory",
                "dialog.town.set-teleport")) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void usesConfiguredRulesApplicationAndTownMessagesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("chat.runtime.town-not-found", "自定义小镇不存在提示");
        configuration.set("dialog.rules.title", "&d自定义规则");
        configuration.set("dialog.rules.delete-conflict", "&c自定义规则冲突");
        configuration.set("dialog.application.summary-title", "&6自定义申请摘要");
        configuration.set("dialog.application.status-line", "&b申请状态 {status}");
        configuration.set("dialog.application.initial-member-status.pending", "&e尚未确认");
        configuration.set("dialog.town.title", "&a自定义小镇详情");
        configuration.set("dialog.town.description", "&7概况: {description}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        messages.reload();

        assertEquals("自定义小镇不存在提示",
                messages.plainText("chat.runtime.town-not-found"));
        assertEquals("§d自定义规则", messages.text("dialog.rules.title"));
        assertEquals("自定义规则冲突", messages.plainText("dialog.rules.delete-conflict"));
        assertEquals("§6自定义申请摘要", messages.text("dialog.application.summary-title"));
        assertEquals("申请状态 已提交", messages.plainText("dialog.application.status-line",
                Map.of("status", "已提交")));
        assertEquals("§e尚未确认",
                messages.text("dialog.application.initial-member-status.pending"));
        assertEquals("自定义小镇详情", messages.plainText("dialog.town.title"));
        assertEquals("概况: 一个新小镇", messages.plainText("dialog.town.description",
                Map.of("description", "一个新小镇")));
    }

    @Test
    void rendersMemberTransferAndVoteMessagesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.of(
                "page", 2,
                "name", "成员甲",
                "id", "123e4567-e89b-12d3-a456-426614174000",
                "town", "青石镇",
                "time", "2026-09-04 12:00",
                "pending", "待投票",
                "type", "投票移除成员",
                "target", "成员乙",
                "playerId", "abcdef12");

        assertEquals("小镇成员 · 第 2 页",
                messages.plainText("dialog.town-members.title", placeholders));
        assertEquals("成员管理 · 成员甲",
                messages.plainText("dialog.member-detail.title", placeholders));
        assertEquals("UUID: 123e4567-e89b-12d3-a456-426614174000",
                messages.plainText("dialog.member-detail.uuid", placeholders));
        assertEquals("接任镇长邀请",
                messages.plainText("dialog.transfer.summary-title", placeholders));
        assertEquals("小镇: 青石镇", messages.plainText("dialog.transfer.town", placeholders));
        assertEquals("有效期至: 2026-09-04 12:00",
                messages.plainText("dialog.transfer.expires", placeholders));
        assertEquals("投票移除成员 · 成员乙",
                messages.plainText("dialog.votes.entry-title", placeholders));
        assertEquals("待投票 · 投票移除成员 · 成员乙",
                messages.plainText("dialog.votes.pending-entry-title", placeholders));
        assertEquals("未知玩家（abcdef12）",
                messages.plainText("dialog.common.unknown-player", placeholders));

        for (String key : List.of(
                "chat.runtime.town-required",
                "chat.runtime.town-not-found",
                "dialog.town-members.title",
                "dialog.town-members.previous",
                "dialog.town-members.next",
                "dialog.member-detail.title",
                "dialog.member-detail.uuid",
                "dialog.member-detail.promote-deputy",
                "dialog.member-detail.demote-member",
                "dialog.member-detail.kick",
                "dialog.member-detail.transfer",
                "dialog.transfer.title",
                "dialog.transfer.summary-title",
                "dialog.transfer.town",
                "dialog.transfer.expires",
                "dialog.transfer.consequence",
                "dialog.transfer.accept",
                "dialog.transfer.reject",
                "dialog.votes.entry-title",
                "dialog.votes.pending-entry-title",
                "dialog.common.unknown",
                "dialog.common.unknown-player")) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void usesConfiguredMemberTransferAndVoteMessagesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("dialog.town-members.title", "&d成员页 {page}");
        configuration.set("dialog.member-detail.title", "&b管理 {name}");
        configuration.set("dialog.member-detail.promote-deputy", "&a自定义任命");
        configuration.set("dialog.transfer.summary-title", "&6自定义接任邀请");
        configuration.set("dialog.transfer.town", "&e所属小镇 {town}");
        configuration.set("dialog.votes.pending-entry-title", "&6待处理 {pending}/{type}/{target}");
        configuration.set("dialog.common.unknown-player", "未知编号 {playerId}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        messages.reload();

        assertEquals("§d成员页 3", messages.text("dialog.town-members.title",
                Map.of("page", 3)));
        assertEquals("管理 成员甲", messages.plainText("dialog.member-detail.title",
                Map.of("name", "成员甲")));
        assertEquals("§a自定义任命", messages.text("dialog.member-detail.promote-deputy"));
        assertEquals("§6自定义接任邀请", messages.text("dialog.transfer.summary-title"));
        assertEquals("所属小镇 青石镇", messages.plainText("dialog.transfer.town",
                Map.of("town", "青石镇")));
        assertEquals("待处理 待投票/投票移除成员/成员乙",
                messages.plainText("dialog.votes.pending-entry-title", Map.of(
                        "pending", "待投票", "type", "投票移除成员", "target", "成员乙")));
        assertEquals("未知编号 abcdef12", messages.plainText("dialog.common.unknown-player",
                Map.of("playerId", "abcdef12")));
    }

    @Test
    void rendersAdminApplicationReviewMessagesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.ofEntries(
                Map.entry("count", 3),
                Map.entry("page", 2),
                Map.entry("town", "青石镇"),
                Map.entry("applicant", "玩家甲"),
                Map.entry("status", "审核中"),
                Map.entry("time", "2026-09-03 10:00"),
                Map.entry("name", "青石镇"),
                Map.entry("residence", "qingshi"),
                Map.entry("description", "一个友好的小镇"),
                Map.entry("rules", "礼貌交流 | 不得破坏公共设施"),
                Map.entry("world", "world_nether"),
                Map.entry("x", 10),
                Map.entry("z", -4),
                Map.entry("error", "Residence 不可用"));

        assertEquals("申请审核 · 3 · 第 2 页",
                messages.plainText("dialog.admin.list-title", placeholders));
        assertEquals("青石镇", messages.plainText("dialog.admin.entry-title", placeholders));
        assertEquals("暂无待审核申请",
                messages.plainText("dialog.admin.list-empty", placeholders));
        assertEquals("收到新申请时会播放提醒音效",
                messages.plainText("dialog.admin.list-empty-hint", placeholders));
        assertEquals("上一页", messages.plainText("dialog.admin.previous", placeholders));
        assertEquals("下一页", messages.plainText("dialog.admin.next", placeholders));
        assertEquals("申请详情", messages.plainText("dialog.admin.summary-title", placeholders));
        assertEquals("申请人: 玩家甲",
                messages.plainText("dialog.admin.applicant", placeholders));
        assertEquals("申请状态: 审核中",
                messages.plainText("dialog.admin.status-line", placeholders));
        assertEquals("提交时间: 2026-09-03 10:00",
                messages.plainText("dialog.admin.submitted-at", placeholders));
        assertEquals("更新时间: 2026-09-03 10:00",
                messages.plainText("dialog.admin.updated-at", placeholders));
        assertEquals("尚未提交", messages.plainText("dialog.admin.not-submitted", placeholders));
        assertEquals("名称: 青石镇", messages.plainText("dialog.admin.name", placeholders));
        assertEquals("小镇领地名: qingshi",
                messages.plainText("dialog.admin.residence-name", placeholders));
        assertEquals("简介: 一个友好的小镇",
                messages.plainText("dialog.admin.description", placeholders));
        assertEquals("规则: 礼貌交流 | 不得破坏公共设施",
                messages.plainText("dialog.admin.rules", placeholders));
        assertEquals("选址: world_nether 10,-4",
                messages.plainText("dialog.admin.territory", placeholders));
        assertEquals("创建错误: Residence 不可用",
                messages.plainText("dialog.admin.creation-error", placeholders));
        assertEquals("批准", messages.plainText("dialog.admin.approve", placeholders));
        assertEquals("拒绝", messages.plainText("dialog.admin.reject", placeholders));
        assertEquals("要求补件",
                messages.plainText("dialog.admin.request-changes", placeholders));
        assertEquals("重试批准",
                messages.plainText("dialog.admin.retry-approve", placeholders));
        assertEquals("解除锁定并要求修改",
                messages.plainText("dialog.admin.unlock-for-changes", placeholders));
        assertEquals("回滚临时小镇并保留托管申请费",
                messages.plainText("dialog.admin.unlock-for-changes-hint", placeholders));
        assertEquals("取消申请并退款",
                messages.plainText("dialog.admin.cancel-and-refund", placeholders));
        assertEquals("安全回滚后退还申请费",
                messages.plainText("dialog.admin.cancel-and-refund-hint", placeholders));
        assertEquals("强制清理失败申请",
                messages.plainText("dialog.admin.force-cleanup", placeholders));
        assertEquals("会执行完整事务回滚并退款",
                messages.plainText("dialog.admin.force-cleanup-hint", placeholders));
        assertEquals("预览选址",
                messages.plainText("dialog.admin.preview-site", placeholders));
        assertEquals("审核 · 青石镇",
                messages.plainText("dialog.admin.detail-title", placeholders));

        for (String key : List.of(
                "dialog.admin.list-title",
                "dialog.admin.entry-title",
                "dialog.admin.list-empty",
                "dialog.admin.list-empty-hint",
                "dialog.admin.previous",
                "dialog.admin.next",
                "dialog.admin.summary-title",
                "dialog.admin.applicant",
                "dialog.admin.status-line",
                "dialog.admin.submitted-at",
                "dialog.admin.updated-at",
                "dialog.admin.not-submitted",
                "dialog.admin.name",
                "dialog.admin.residence-name",
                "dialog.admin.description",
                "dialog.admin.rules",
                "dialog.admin.territory",
                "dialog.admin.creation-error",
                "dialog.admin.approve",
                "dialog.admin.reject",
                "dialog.admin.request-changes",
                "dialog.admin.retry-approve",
                "dialog.admin.unlock-for-changes",
                "dialog.admin.unlock-for-changes-hint",
                "dialog.admin.cancel-and-refund",
                "dialog.admin.cancel-and-refund-hint",
                "dialog.admin.force-cleanup",
                "dialog.admin.force-cleanup-hint",
                "dialog.admin.preview-site",
                "dialog.admin.detail-title")) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void usesConfiguredAdminApplicationReviewMessagesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("chat.application.not-found", "自定义申请不存在");
        configuration.set("dialog.admin.list-title", "&d审核列表 {count}/{page}");
        configuration.set("dialog.admin.entry-title", "&b待审小镇 {town}");
        configuration.set("dialog.admin.status-line", "&e状态 {status}");
        configuration.set("dialog.admin.territory", "&7地点 {world}:{x},{z}");
        configuration.set("dialog.admin.unlock-for-changes-hint", "&a自定义回滚提示");
        configuration.set("dialog.admin.force-cleanup", "&c自定义强制清理");
        configuration.set("dialog.admin.detail-title", "&6审核详情 {town}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        messages.reload();

        assertEquals("自定义申请不存在", messages.plainText("chat.application.not-found"));
        assertEquals("§d审核列表 4/3", messages.text("dialog.admin.list-title",
                Map.of("count", 4, "page", 3)));
        assertEquals("待审小镇 青石镇", messages.plainText("dialog.admin.entry-title",
                Map.of("town", "青石镇")));
        assertEquals("状态 已提交", messages.plainText("dialog.admin.status-line",
                Map.of("status", "已提交")));
        assertEquals("地点 world:10,-4", messages.plainText("dialog.admin.territory",
                Map.of("world", "world", "x", 10, "z", -4)));
        assertEquals("自定义回滚提示",
                messages.plainText("dialog.admin.unlock-for-changes-hint"));
        assertEquals("自定义强制清理",
                messages.plainText("dialog.admin.force-cleanup"));
        assertEquals("审核详情 青石镇", messages.plainText("dialog.admin.detail-title",
                Map.of("town", "青石镇")));
    }

    @Test
    void rendersAdminProvisionAndApplicationFormMessagesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, Object> placeholders = Map.of(
                "detail", "Residence 不可用",
                "recovery", "返回审核列表重试",
                "application", "application-1",
                "time", "2026-09-03 12:00");

        assertEquals("申请不存在", messages.plainText("chat.application.not-found"));
        assertEquals("未知申请表单步骤",
                messages.plainText("chat.application.invalid-form-step"));
        assertEquals("正在创建小镇", messages.plainText("dialog.provision.progress-title"));
        assertEquals("系统正在执行费用托管、领地投影和数据库提交。\n"
                        + "此页面只读，你可以返回审核列表稍后查看。",
                messages.plainText("dialog.provision.progress-message"));
        assertEquals("创建仍在处理中", messages.plainText("dialog.provision.timeout-title"));
        assertEquals("服务器暂未返回最终结果。流程使用同一幂等键，可安全返回审核列表刷新；请勿重复扣费。",
                messages.plainText("dialog.provision.timeout-message"));
        assertEquals("返回审核列表", messages.plainText("dialog.provision.back-to-list"));
        assertEquals("小镇创建未完成",
                messages.plainText("dialog.provision.application-failed-title"));
        assertEquals("正在安全恢复申请", messages.plainText("dialog.provision.recovery-title"));
        assertEquals("系统正在验证 Residence 投影并回滚临时数据。",
                messages.plainText("dialog.provision.recovery-message"));
        assertEquals("临时数据已回滚，申请已转为需要修改，托管申请费会在再次批准时复用。",
                messages.plainText("dialog.provision.recovery-unlocked-message"));
        assertEquals("临时数据已回滚，申请已取消，申请费已退款。",
                messages.plainText("dialog.provision.recovery-cancelled-message"));
        assertEquals("失败申请已处理",
                messages.plainText("dialog.provision.recovery-completed-title"));
        assertEquals("恢复操作未完成",
                messages.plainText("dialog.provision.recovery-failed-title"));
        assertEquals("Residence 不可用\n\n可执行操作：返回审核列表重试",
                messages.plainText("dialog.provision.failure-with-recovery", placeholders));
        assertEquals("管理员通过玩家界面批准申请",
                messages.plainText("log.provision.admin-approval-reason"));
        assertEquals("建镇 UI 回调已在页面关闭后完成 application=application-1 at=2026-09-03 12:00",
                messages.plainText("log.provision.ui-callback-after-close", placeholders));
        assertEquals("完成资料并发送邀请", messages.plainText("dialog.application.complete"));
        assertEquals("保存草稿并退出", messages.plainText("dialog.application.save-draft"));
        assertEquals("允许成员尚未选择完整",
                messages.plainText("dialog.application.incomplete-members-hint"));
        assertEquals("放弃草稿", messages.plainText("dialog.application.discard-draft"));
        assertEquals("删除已持久化的未提交内容",
                messages.plainText("dialog.application.discard-draft-hint"));

        for (String key : List.of(
                "chat.application.not-found",
                "chat.application.invalid-form-step",
                "dialog.provision.progress-title",
                "dialog.provision.progress-message",
                "dialog.provision.timeout-title",
                "dialog.provision.timeout-message",
                "dialog.provision.back-to-list",
                "dialog.provision.application-failed-title",
                "dialog.provision.recovery-title",
                "dialog.provision.recovery-message",
                "dialog.provision.recovery-unlocked-message",
                "dialog.provision.recovery-cancelled-message",
                "dialog.provision.recovery-completed-title",
                "dialog.provision.recovery-failed-title",
                "dialog.provision.failure-with-recovery",
                "log.provision.admin-approval-reason",
                "log.provision.ui-callback-after-close",
                "dialog.application.complete",
                "dialog.application.save-draft",
                "dialog.application.incomplete-members-hint",
                "dialog.application.discard-draft",
                "dialog.application.discard-draft-hint")) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void usesConfiguredAdminProvisionAndApplicationFormMessagesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("dialog.provision.progress-title", "&d自定义创建进度");
        configuration.set("dialog.provision.progress-message", "&b自定义进度说明");
        configuration.set("dialog.provision.timeout-title", "&e自定义仍处理中");
        configuration.set("dialog.provision.timeout-message", "&7自定义超时说明");
        configuration.set("dialog.provision.back-to-list", "&a自定义返回审核");
        configuration.set("dialog.provision.application-failed-title", "&c自定义创建失败");
        configuration.set("dialog.provision.recovery-title", "&6自定义恢复中");
        configuration.set("dialog.provision.recovery-message", "&7自定义恢复说明");
        configuration.set("dialog.provision.recovery-unlocked-message", "&a自定义解锁成功");
        configuration.set("dialog.provision.recovery-cancelled-message", "&a自定义取消成功");
        configuration.set("dialog.provision.recovery-completed-title", "&d自定义恢复完成");
        configuration.set("dialog.provision.recovery-failed-title", "&c自定义恢复失败");
        configuration.set("dialog.provision.failure-with-recovery",
                "&c详情 {detail}\n&7操作 {recovery}");
        configuration.set("log.provision.admin-approval-reason", "自定义审批原因");
        configuration.set("log.provision.ui-callback-after-close", "自定义回调 {application}/{time}");
        configuration.set("chat.application.invalid-form-step", "自定义表单步骤错误");
        configuration.set("dialog.application.complete", "&a自定义完成");
        configuration.set("dialog.application.incomplete-members-hint", "&e自定义成员提示");
        configuration.set("dialog.application.save-draft", "&b自定义保存");
        configuration.set("dialog.application.discard-draft", "&c自定义放弃");
        configuration.set("dialog.application.discard-draft-hint", "&7自定义删除提示");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        messages.reload();

        assertEquals("§d自定义创建进度",
                messages.text("dialog.provision.progress-title"));
        assertEquals("自定义进度说明",
                messages.plainText("dialog.provision.progress-message"));
        assertEquals("自定义仍处理中",
                messages.plainText("dialog.provision.timeout-title"));
        assertEquals("自定义超时说明",
                messages.plainText("dialog.provision.timeout-message"));
        assertEquals("自定义返回审核",
                messages.plainText("dialog.provision.back-to-list"));
        assertEquals("自定义创建失败",
                messages.plainText("dialog.provision.application-failed-title"));
        assertEquals("自定义恢复中", messages.plainText("dialog.provision.recovery-title"));
        assertEquals("自定义恢复说明", messages.plainText("dialog.provision.recovery-message"));
        assertEquals("自定义解锁成功",
                messages.plainText("dialog.provision.recovery-unlocked-message"));
        assertEquals("自定义取消成功",
                messages.plainText("dialog.provision.recovery-cancelled-message"));
        assertEquals("自定义恢复完成",
                messages.plainText("dialog.provision.recovery-completed-title"));
        assertEquals("自定义恢复失败",
                messages.plainText("dialog.provision.recovery-failed-title"));
        assertEquals("§c详情 外部故障\n§7操作 重试恢复",
                messages.text("dialog.provision.failure-with-recovery",
                        Map.of("detail", "外部故障", "recovery", "重试恢复")));
        assertEquals("自定义审批原因",
                messages.plainText("log.provision.admin-approval-reason"));
        assertEquals("自定义回调 application-1/2026-09-03 12:00",
                messages.plainText("log.provision.ui-callback-after-close", Map.of(
                        "application", "application-1", "time", "2026-09-03 12:00")));
        assertEquals("自定义表单步骤错误",
                messages.plainText("chat.application.invalid-form-step"));
        assertEquals("自定义完成", messages.plainText("dialog.application.complete"));
        assertEquals("自定义成员提示",
                messages.plainText("dialog.application.incomplete-members-hint"));
        assertEquals("自定义保存", messages.plainText("dialog.application.save-draft"));
        assertEquals("自定义放弃", messages.plainText("dialog.application.discard-draft"));
        assertEquals("自定义删除提示",
                messages.plainText("dialog.application.discard-draft-hint"));
    }

    @Test
    void rendersActionSiteAndTeleportMessagesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("小镇没有已生效的领地单元",
                messages.plainText("chat.site.no-active-territory"));
        assertEquals("只有镇长可以设置领地传送点",
                messages.plainText("chat.site.teleport-point-mayor-required"));
        assertEquals("确认处理创建失败申请",
                messages.plainText("dialog.confirmation.failed-recovery-title"));
        assertEquals("将验证外部投影、事务回滚临时数据，并按所选方式处理托管申请费。",
                messages.plainText("dialog.confirmation.failed-recovery-consequence"));
        assertEquals("管理员审核中，批准前仍可撤回申请。",
                messages.plainText("dialog.notice.application-submitted-message"));
        assertEquals("无法设置传送点",
                messages.plainText("dialog.site.teleport-point-outside-title"));
        assertEquals("请站在本镇有效 Residence 内重试。",
                messages.plainText("dialog.site.teleport-point-outside-message"));
        assertEquals("脚部或头部空间被方块占用。",
                messages.plainText("dialog.site.teleport-point-space-occupied"));
        assertEquals("脚下没有可安全站立的实体方块。",
                messages.plainText("dialog.site.teleport-point-floor-unsafe"));
        assertEquals("该位置包含危险方块。",
                messages.plainText("dialog.site.teleport-point-dangerous-block"));
        assertEquals("领地传送未完成",
                messages.plainText("dialog.site.territory-teleport-timeout-title"));
        assertEquals("Residence 未在等待时间内完成传送；冷却、费用和安全点规则仍由 Residence 管理。",
                messages.plainText("dialog.site.territory-teleport-timeout-message"));

        for (String key : List.of(
                "chat.application.not-found",
                "chat.runtime.town-not-found",
                "chat.site.no-active-territory",
                "chat.site.teleport-point-mayor-required",
                "dialog.confirmation.failed-recovery-title",
                "dialog.confirmation.failed-recovery-consequence",
                "dialog.notice.application-submitted-message",
                "dialog.site.teleport-point-outside-title",
                "dialog.site.teleport-point-outside-message",
                "dialog.site.teleport-point-unsafe-title",
                "dialog.site.teleport-point-space-occupied",
                "dialog.site.teleport-point-floor-unsafe",
                "dialog.site.teleport-point-dangerous-block",
                "dialog.site.teleport-point-set-title",
                "dialog.site.teleport-point-failed-title",
                "dialog.site.territory-teleport-unavailable-title",
                "dialog.site.territory-teleport-unavailable-message",
                "dialog.site.territory-teleport-failed-title",
                "dialog.site.territory-teleport-failed-message",
                "dialog.site.territory-teleport-timeout-title",
                "dialog.site.territory-teleport-timeout-message")) {
            String rendered = messages.plainText(key);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void usesConfiguredActionSiteAndTeleportMessagesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("chat.site.no-active-territory", "自定义领地尚未生效");
        configuration.set("dialog.confirmation.failed-recovery-title", "&d自定义恢复确认");
        configuration.set("dialog.notice.application-submitted-message", "&b自定义审核中提示");
        configuration.set("dialog.site.teleport-point-unsafe-title", "&c自定义安全提示");
        configuration.set("dialog.site.territory-teleport-failed-message",
                "&f自定义 Residence 传送失败");
        configuration.set("dialog.site.territory-teleport-timeout-message",
                "&e自定义传送等待超时");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        messages.reload();

        assertEquals("自定义领地尚未生效",
                messages.plainText("chat.site.no-active-territory"));
        assertEquals("§d自定义恢复确认",
                messages.text("dialog.confirmation.failed-recovery-title"));
        assertEquals("§b自定义审核中提示",
                messages.text("dialog.notice.application-submitted-message"));
        assertEquals("§c自定义安全提示",
                messages.text("dialog.site.teleport-point-unsafe-title"));
        assertEquals("自定义 Residence 传送失败",
                messages.plainText("dialog.site.territory-teleport-failed-message"));
        assertEquals("自定义传送等待超时",
                messages.plainText("dialog.site.territory-teleport-timeout-message"));
    }

    @Test
    void rendersDonationApplicationSaveAndInitialMemberMessagesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, Object> placeholders = Map.of(
                "player", "成员甲",
                "town", "青石镇",
                "detail", "数据库暂不可用");

        assertEquals("你不属于任何小镇",
                messages.plainText("chat.runtime.town-required"));
        assertEquals("捐款金额必须大于 0",
                messages.plainText("validation.vault.donation-amount-positive"));
        assertEquals("金额格式无效，请输入有效数额。",
                messages.plainText("dialog.donation.invalid-amount"));
        assertEquals("申请不存在",
                messages.plainText("chat.application.not-found"));
        assertEquals("必须填写初始成员",
                messages.plainText("dialog.application.initial-member-required"));
        assertEquals("初始成员必须在线并使用准确玩家名",
                messages.plainText("dialog.application.initial-member-online-required"));
        assertEquals("初始成员不能是申请人本人",
                messages.plainText("dialog.application.initial-member-applicant-forbidden"));
        assertEquals("两名初始成员不能相同",
                messages.plainText("dialog.application.initial-members-distinct-required"));
        assertEquals("两名初始成员必须在线并使用准确玩家名",
                messages.plainText("dialog.application.initial-members-online-required"));
        assertEquals("初始成员不能包含申请人",
                messages.plainText("dialog.application.initial-members-applicant-forbidden"));
        assertEquals("必须填写两名不同的初始成员",
                messages.plainText("dialog.application.initial-members-distinct-validation"));
        assertEquals("初始成员无法使用",
                messages.plainText("dialog.application.initial-members-unavailable-title"));
        assertEquals("成员甲 已属于小镇“青石镇”",
                messages.plainText("dialog.application.initial-member-conflict", placeholders));
        assertEquals("初始成员预检失败",
                messages.plainText("dialog.application.initial-members-precheck-failed-title"));
        assertEquals("成员甲 已属于小镇“青石镇”，请更换后再保存。",
                messages.plainText("dialog.application.initial-member-conflict-retry", placeholders));
        assertEquals("未知玩家",
                messages.plainText("dialog.application.unknown-player"));
        assertEquals("未知小镇",
                messages.plainText("dialog.application.unknown-town"));
        assertEquals("清理已提交申请草稿失败 成员甲: 数据库暂不可用",
                messages.plainText("log.application.draft-cleanup-failure", placeholders));

        for (String key : List.of(
                "chat.runtime.town-required",
                "validation.vault.donation-amount-positive",
                "dialog.donation.invalid-amount",
                "chat.application.not-found",
                "dialog.application.initial-member-required",
                "dialog.application.initial-member-online-required",
                "dialog.application.initial-member-applicant-forbidden",
                "dialog.application.initial-members-distinct-required",
                "dialog.application.initial-members-online-required",
                "dialog.application.initial-members-applicant-forbidden",
                "dialog.application.initial-members-distinct-validation",
                "dialog.application.initial-members-unavailable-title",
                "dialog.application.initial-member-conflict",
                "dialog.application.initial-members-precheck-failed-title",
                "dialog.application.initial-member-conflict-retry",
                "dialog.application.unknown-player",
                "dialog.application.unknown-town",
                "log.application.draft-cleanup-failure")) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void usesConfiguredDonationApplicationSaveAndInitialMemberMessagesAfterMessagesReload()
            throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("chat.runtime.town-required", "自定义归属提示");
        configuration.set("validation.vault.donation-amount-positive", "自定义捐款正数提示");
        configuration.set("dialog.donation.invalid-amount", "&c自定义金额格式");
        configuration.set("chat.application.not-found", "自定义申请不存在");
        configuration.set("dialog.application.initial-member-required", "&e自定义成员必填");
        configuration.set("dialog.application.initial-member-online-required", "&b自定义成员在线");
        configuration.set("dialog.application.initial-member-applicant-forbidden", "&c自定义申请人");
        configuration.set("dialog.application.initial-members-distinct-required", "&d自定义成员重复");
        configuration.set("dialog.application.initial-members-online-required", "&a自定义两名成员在线");
        configuration.set("dialog.application.initial-members-applicant-forbidden", "&c自定义成员包含申请人");
        configuration.set("dialog.application.initial-members-distinct-validation", "&6自定义两名成员不同");
        configuration.set("dialog.application.initial-members-unavailable-title", "&d自定义成员不可用");
        configuration.set("dialog.application.initial-member-conflict", "&f自定义冲突 {player}/{town}");
        configuration.set("dialog.application.initial-members-precheck-failed-title", "&6自定义预检失败");
        configuration.set("dialog.application.initial-member-conflict-retry", "&c自定义重试 {player}/{town}");
        configuration.set("dialog.application.unknown-player", "自定义未知玩家");
        configuration.set("dialog.application.unknown-town", "自定义未知小镇");
        configuration.set("log.application.draft-cleanup-failure", "自定义草稿清理 {player}: {detail}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        messages.reload();

        Map<String, Object> placeholders = Map.of(
                "player", "成员乙",
                "town", "云杉镇",
                "detail", "写入失败");
        assertEquals("自定义归属提示",
                messages.plainText("chat.runtime.town-required"));
        assertEquals("自定义捐款正数提示",
                messages.plainText("validation.vault.donation-amount-positive"));
        assertEquals("§c自定义金额格式",
                messages.text("dialog.donation.invalid-amount"));
        assertEquals("自定义申请不存在",
                messages.plainText("chat.application.not-found"));
        assertEquals("自定义成员必填",
                messages.plainText("dialog.application.initial-member-required"));
        assertEquals("自定义成员在线",
                messages.plainText("dialog.application.initial-member-online-required"));
        assertEquals("自定义申请人",
                messages.plainText("dialog.application.initial-member-applicant-forbidden"));
        assertEquals("自定义成员重复",
                messages.plainText("dialog.application.initial-members-distinct-required"));
        assertEquals("自定义两名成员在线",
                messages.plainText("dialog.application.initial-members-online-required"));
        assertEquals("自定义成员包含申请人",
                messages.plainText("dialog.application.initial-members-applicant-forbidden"));
        assertEquals("自定义两名成员不同",
                messages.plainText("dialog.application.initial-members-distinct-validation"));
        assertEquals("§d自定义成员不可用",
                messages.text("dialog.application.initial-members-unavailable-title"));
        assertEquals("自定义冲突 成员乙/云杉镇",
                messages.plainText("dialog.application.initial-member-conflict", placeholders));
        assertEquals("自定义预检失败",
                messages.plainText("dialog.application.initial-members-precheck-failed-title"));
        assertEquals("自定义重试 成员乙/云杉镇",
                messages.plainText("dialog.application.initial-member-conflict-retry", placeholders));
        assertEquals("自定义未知玩家",
                messages.plainText("dialog.application.unknown-player"));
        assertEquals("自定义未知小镇",
                messages.plainText("dialog.application.unknown-town"));
        assertEquals("自定义草稿清理 成员乙: 写入失败",
                messages.plainText("log.application.draft-cleanup-failure", placeholders));
    }

    @Test
    void rendersOutcomeAndDialogHelperMessagesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("这项功能目前暂停使用。",
                messages.plainText("dialog.notice.operation-failed-feature-disabled"));
        assertEquals("小镇数据暂时不可用，请稍后再试。",
                messages.plainText("dialog.notice.operation-failed-storage-unavailable"));
        assertEquals("余额不足，无法完成这项操作。",
                messages.plainText("dialog.notice.operation-failed-insufficient-balance"));
        assertEquals("你没有执行这项操作的权限。",
                messages.plainText("dialog.notice.operation-failed-forbidden"));
        assertEquals("目标已经不存在，请刷新界面。",
                messages.plainText("dialog.notice.operation-failed-not-found"));
        assertEquals("暂时无法完成这项操作，请刷新后重试。",
                messages.plainText("dialog.notice.operation-failed-default"));
        assertEquals("玩家界面已经关闭", messages.plainText("log.scheduler.dialog-closed"));
        assertEquals("领地网格坐标无效", messages.plainText("chat.site.invalid-grid-target"));
        assertEquals("服务台 ID 为空", messages.plainText("log.station.record-id-empty"));
        assertEquals("服务台世界名为空",
                messages.plainText("log.station.record-world-name-empty"));

        for (String key : List.of(
                "dialog.notice.operation-failed-feature-disabled",
                "dialog.notice.operation-failed-storage-unavailable",
                "dialog.notice.operation-failed-insufficient-balance",
                "dialog.notice.operation-failed-forbidden",
                "dialog.notice.operation-failed-not-found",
                "dialog.notice.operation-failed-default",
                "log.scheduler.dialog-closed",
                "chat.site.invalid-grid-target",
                "log.station.record-id-empty",
                "log.station.record-world-name-empty",
                "dialog.application.field.name.label",
                "dialog.application.field.name.requirement",
                "dialog.application.field.name.suggestion",
                "dialog.application.field.residence-name.label",
                "dialog.application.field.residence-name.requirement",
                "dialog.application.field.residence-name.suggestion",
                "dialog.application.field.description.label",
                "dialog.application.field.description.requirement",
                "dialog.application.field.description.suggestion",
                "dialog.application.field.rules.label",
                "dialog.application.field.rules.requirement",
                "dialog.application.field.rules.suggestion",
                "dialog.application.field.initial-member-one.label",
                "dialog.application.field.initial-member-one.requirement",
                "dialog.application.field.initial-member-one.suggestion",
                "dialog.application.field.initial-member-two.label",
                "dialog.application.field.initial-member-two.requirement",
                "dialog.application.field.initial-member-two.suggestion")) {
            String rendered = messages.plainText(key);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void usesConfiguredOutcomeAndDialogHelperMessagesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("dialog.notice.operation-failed-default", "自定义通用失败详情");
        configuration.set("dialog.notice.operation-failed-forbidden", "&d自定义权限失败详情");
        configuration.set("log.scheduler.dialog-closed", "自定义界面关闭诊断");
        configuration.set("chat.site.invalid-grid-target", "自定义领地坐标错误");
        configuration.set("log.station.record-id-empty", "自定义服务台 ID 缺失");
        configuration.set("dialog.application.field.name.requirement", "自定义名称要求");
        configuration.set("dialog.application.field.initial-member-two.suggestion",
                "&e自定义成员建议");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        messages.reload();

        assertEquals("自定义通用失败详情",
                messages.plainText("dialog.notice.operation-failed-default"));
        assertEquals("§d自定义权限失败详情",
                messages.text("dialog.notice.operation-failed-forbidden"));
        assertEquals("自定义界面关闭诊断",
                messages.plainText("log.scheduler.dialog-closed"));
        assertEquals("自定义领地坐标错误",
                messages.plainText("chat.site.invalid-grid-target"));
        assertEquals("自定义服务台 ID 缺失",
                messages.plainText("log.station.record-id-empty"));
        assertEquals("自定义名称要求",
                messages.plainText("dialog.application.field.name.requirement"));
        assertEquals("&e自定义成员建议",
                messages.rawText("dialog.application.field.initial-member-two.suggestion"));
    }
}

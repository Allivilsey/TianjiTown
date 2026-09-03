package cn.tianji.town.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginMessagesTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsBuiltInMessagesAndValidRangeFormats() {
        assertDoesNotThrow(() -> new PluginMessages(temporaryDirectory.toFile()));
    }

    @Test
    void rendersLifecycleAndSchedulingTemplatesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("正在执行启动门禁",
                messages.plainText("diagnostic.lifecycle.startup-checking"));
        assertEquals("plugin.yml 缺少 townadmin",
                messages.plainText("diagnostic.lifecycle.admin-command-missing"));
        assertEquals("配置 schema 门禁未通过",
                messages.plainText("diagnostic.lifecycle.config-schema-gate-failed"));
        assertEquals("OK 配置类型、范围与世界引用校验通过",
                messages.plainText("diagnostic.lifecycle.configuration-validation-passed"));
        assertEquals("FAIL 配置校验: bad value",
                messages.plainText("diagnostic.lifecycle.configuration-validation-failed",
                        Map.of("detail", "bad value")));
        assertEquals("业务配置门禁未通过",
                messages.plainText("diagnostic.lifecycle.business-config-gate-failed"));
        assertEquals("同步门禁未通过",
                messages.plainText("diagnostic.lifecycle.synchronous-gate-failed"));
        assertEquals("delayTicks 不能为负数",
                messages.plainText("diagnostic.scheduler.negative-delay"));
        assertEquals("FAIL 业务运行时激活异常: activation boom",
                messages.plainText("diagnostic.lifecycle.runtime-activation-failure",
                        Map.of("detail", "activation boom")));
        assertEquals("业务运行时门禁未通过",
                messages.plainText("diagnostic.lifecycle.runtime-gate-failed"));
        assertEquals("FAIL 业务配置、WorldBorder、玩家界面或清算账户: initialization boom",
                messages.plainText("diagnostic.lifecycle.runtime-initialization-failure",
                        Map.of("detail", "initialization boom")));
        assertEquals("OK WorldBorder 边界 API 已接入",
                messages.plainText("diagnostic.lifecycle.world-border-ready"));
        assertEquals("OK 玩家界面=DIALOG",
                messages.plainText("diagnostic.lifecycle.dialog-ui-ready"));
        assertEquals("OK 建筑返还、信标增强、启动诊断与定时备份已启用",
                messages.plainText("diagnostic.lifecycle.runtime-features-ready"));
        assertEquals("WorldBorder API 无法加载",
                messages.plainText("diagnostic.world-border.api-load-failure"));

        assertEquals("停服关闭玩家界面失败: ui boom",
                messages.plainText("log.lifecycle.ui-close-failure",
                        Map.of("detail", "ui boom")));
        assertEquals("等待异步任务结束超时，仍有 3 个任务；将继续关闭数据源。请检查阻塞的第三方 API。",
                messages.plainText("log.lifecycle.async-shutdown-timeout",
                        Map.of("active", 3)));
        assertEquals("停服清理信标效果失败: beacon boom",
                messages.plainText("log.lifecycle.beacon-cleanup-failure",
                        Map.of("detail", "beacon boom")));
        assertEquals("停服清理公共 Buff 失败: buff boom",
                messages.plainText("log.lifecycle.buff-cleanup-failure",
                        Map.of("detail", "buff boom")));
        assertEquals("关闭 SQLite 数据源失败: database boom",
                messages.plainText("log.lifecycle.database-close-failure",
                        Map.of("detail", "database boom")));
        assertEquals("异步任务异常，已在插件边界隔离: async boom",
                messages.plainText("log.scheduler.async-task-failure",
                        Map.of("detail", "async boom")));
        assertEquals("主线程回调异常，已在插件边界隔离: main boom",
                messages.plainText("log.scheduler.main-thread-callback-failure",
                        Map.of("detail", "main boom")));
        assertEquals("主线程回调提交失败: submit boom",
                messages.plainText("log.scheduler.main-thread-callback-submit-failure",
                        Map.of("detail", "submit boom")));
        assertEquals("读取系统 Residence 名称清单失败: residence boom",
                messages.plainText("log.lifecycle.residence-names-load-failure",
                        Map.of("detail", "residence boom")));
        assertEquals("业务运行时启动完成；玩家入口仅限服务台和小镇手册。",
                messages.plainText("log.lifecycle.runtime-started"));
        assertEquals("业务运行时门禁未通过；TianjiTown 所有写功能保持锁定。使用 /townadmin status 查看详情。",
                messages.plainText("log.lifecycle.runtime-locked",
                        Map.of("reason", "业务运行时门禁未通过")));

        Map<String, String> periodicLabels = Map.of(
                "log.scheduler.periodic.sqlite-recovery-failure", "SQLite 恢复检查",
                "log.scheduler.periodic.residence-reconciliation-failure", "Residence 对账",
                "log.scheduler.periodic.vote-settlement-failure", "投票结算",
                "log.scheduler.periodic.settlement-reconciliation-failure", "清算对账",
                "log.scheduler.periodic.territory-bonus-index-refresh-failure", "领地加成索引刷新",
                "log.scheduler.periodic.beacon-effect-refresh-failure", "信标效果刷新",
                "log.scheduler.periodic.refund-counter-cleanup-failure", "返还计数清理",
                "log.scheduler.periodic.startup-diagnostic-failure", "启动诊断",
                "log.scheduler.periodic.scheduled-backup-failure", "定时备份");
        periodicLabels.forEach((key, label) -> assertEquals(
                label + "失败，后续周期仍会继续尝试: periodic boom",
                messages.plainText(key, Map.of("detail", "periodic boom"))));

        for (String key : List.of(
                "diagnostic.lifecycle.configuration-validation-failed",
                "log.lifecycle.ui-close-failure",
                "log.lifecycle.async-shutdown-timeout",
                "log.lifecycle.beacon-cleanup-failure",
                "log.lifecycle.buff-cleanup-failure",
                "log.lifecycle.database-close-failure",
                "log.scheduler.async-task-failure",
                "log.scheduler.main-thread-callback-failure",
                "log.scheduler.main-thread-callback-submit-failure")) {
            Map<String, ?> placeholders = key.endsWith("async-shutdown-timeout")
                    ? Map.of("active", 1)
                    : Map.of("detail", "boom");
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank());
            assertFalse(rendered.contains("缺少消息配置"));
            assertFalse(rendered.contains("{detail}"));
            assertFalse(rendered.contains("{active}"));
        }
    }

    @Test
    void usesRuntimeActivationAndPeriodicOverridesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String activationKey = "diagnostic.lifecycle.runtime-activation-failure";
        String periodicKey = "log.scheduler.periodic.sqlite-recovery-failure";
        String lockedKey = "log.lifecycle.runtime-locked";

        assertEquals("FAIL 业务运行时激活异常: boom",
                messages.plainText(activationKey, Map.of("detail", "boom")));
        assertEquals("SQLite 恢复检查失败，后续周期仍会继续尝试: boom",
                messages.plainText(periodicKey, Map.of("detail", "boom")));
        assertEquals("业务运行时门禁未通过；TianjiTown 所有写功能保持锁定。使用 /townadmin status 查看详情。",
                messages.plainText(lockedKey, Map.of("reason", "业务运行时门禁未通过")));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(activationKey, "自定义运行时激活日志: {detail}");
        configuration.set(periodicKey, "自定义 SQLite 周期日志: {detail}");
        configuration.set(lockedKey, "自定义锁定日志: {reason}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义运行时激活日志: boom",
                messages.plainText(activationKey, Map.of("detail", "boom")));
        assertEquals("自定义 SQLite 周期日志: boom",
                messages.plainText(periodicKey, Map.of("detail", "boom")));
        assertEquals("自定义锁定日志: 业务运行时门禁未通过",
                messages.plainText(lockedKey, Map.of("reason", "业务运行时门禁未通过")));
    }

    @Test
    void rendersTownAdminSystemOperationTemplatesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.of(
                "detail", "操作已完成",
                "report", "diagnostics/diagnostic.txt",
                "file", "backups/tianjitown.db");

        assertEquals("&6维护模式: &f已开启",
                messages.rawText("chat.admin.maintenance-status-enabled"));
        assertEquals("&6维护模式: &f已关闭",
                messages.rawText("chat.admin.maintenance-status-disabled"));
        assertEquals("用法: /townadmin diagnose [1~180天]",
                messages.plainText("chat.admin.usage-diagnose"));
        assertEquals("用法: /townadmin backup",
                messages.plainText("chat.admin.usage-backup"));
        assertEquals("用法: /townadmin maintenance <on|off|status>",
                messages.plainText("chat.admin.usage-maintenance"));
        assertEquals("用法: /townadmin audit [1~200]",
                messages.plainText("chat.admin.usage-audit"));
        assertEquals("audit 数量必须是 1~200 的整数",
                messages.plainText("chat.admin.audit-limit-integer"));
        assertEquals("audit 数量必须在 1~200",
                messages.plainText("chat.admin.audit-limit-range"));
        assertEquals("&7- 最近统一诊断: 操作已完成，报告=diagnostics/diagnostic.txt",
                messages.rawText("chat.admin.status-diagnostic", placeholders));
        assertEquals("&7- 最近统一诊断: 操作已完成",
                messages.rawText("chat.admin.status-diagnostic-no-report", placeholders));
        assertEquals("&7- 最近在线备份: 操作已完成，文件=backups/tianjitown.db",
                messages.rawText("chat.admin.status-backup", placeholders));
        assertEquals("&7- 最近在线备份: 操作已完成",
                messages.rawText("chat.admin.status-backup-no-file", placeholders));
        assertEquals("危险操作启动失败: operation boom",
                messages.plainText("log.admin.confirmation-start-failure",
                        Map.of("detail", "operation boom")));
        assertEquals("管理员命令补全缓存已恢复。",
                messages.plainText("log.admin.completion-cache-restored"));
        assertEquals("刷新管理员命令补全缓存失败，将继续使用旧缓存: completion boom",
                messages.plainText("log.admin.completion-cache-refresh-failed",
                        Map.of("detail", "completion boom")));

        for (String key : List.of(
                "chat.admin.maintenance-status-enabled",
                "chat.admin.maintenance-status-disabled",
                "chat.admin.usage-diagnose",
                "chat.admin.usage-backup",
                "chat.admin.usage-maintenance",
                "chat.admin.usage-audit",
                "chat.admin.audit-limit-integer",
                "chat.admin.audit-limit-range",
                "chat.admin.status-diagnostic",
                "chat.admin.status-diagnostic-no-report",
                "chat.admin.status-backup",
                "chat.admin.status-backup-no-file",
                "log.admin.confirmation-start-failure",
                "log.admin.completion-cache-restored",
                "log.admin.completion-cache-refresh-failed")) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank());
            assertFalse(rendered.contains("缺少消息配置"));
            assertFalse(rendered.contains("{detail}"));
            assertFalse(rendered.contains("{report}"));
            assertFalse(rendered.contains("{file}"));
        }
    }

    @Test
    void usesTownAdminSystemOperationOverridesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String diagnosticKey = "chat.admin.status-diagnostic";
        String maintenanceKey = "chat.admin.maintenance-status-enabled";
        String logKey = "log.admin.confirmation-start-failure";
        String completionRestoredKey = "log.admin.completion-cache-restored";
        String completionFailureKey = "log.admin.completion-cache-refresh-failed";

        assertEquals("&7- 最近统一诊断: 通过，报告=report.txt",
                messages.rawText(diagnosticKey,
                        Map.of("detail", "通过", "report", "report.txt")));
        assertEquals("&6维护模式: &f已开启", messages.rawText(maintenanceKey));
        assertEquals("危险操作启动失败: boom",
                messages.plainText(logKey, Map.of("detail", "boom")));
        assertEquals("管理员命令补全缓存已恢复。", messages.plainText(completionRestoredKey));
        assertEquals("刷新管理员命令补全缓存失败，将继续使用旧缓存: boom",
                messages.plainText(completionFailureKey, Map.of("detail", "boom")));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(diagnosticKey, "自定义诊断: {detail} / {report}");
        configuration.set(maintenanceKey, "自定义维护状态");
        configuration.set(logKey, "自定义确认启动日志: {detail}");
        configuration.set(completionRestoredKey, "自定义管理员补全恢复日志");
        configuration.set(completionFailureKey, "自定义管理员补全刷新失败: {detail}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义诊断: 通过 / report.txt",
                messages.plainText(diagnosticKey,
                        Map.of("detail", "通过", "report", "report.txt")));
        assertEquals("自定义维护状态", messages.plainText(maintenanceKey));
        assertEquals("自定义确认启动日志: boom",
                messages.plainText(logKey, Map.of("detail", "boom")));
        assertEquals("自定义管理员补全恢复日志", messages.plainText(completionRestoredKey));
        assertEquals("自定义管理员补全刷新失败: boom",
                messages.plainText(completionFailureKey, Map.of("detail", "boom")));
    }

    @Test
    void rendersTownAdminApplicationTownAndMemberTemplatesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.of(
                "action", "approve",
                "town", "青石镇",
                "residence", "青石镇",
                "detail", "Residence 未响应");

        assertEquals("用法: /townadmin application approve <小镇全名> <原因>",
                messages.plainText("chat.admin.usage-application-review", placeholders));
        assertEquals("找不到待处理申请 “青石镇”",
                messages.plainText("chat.admin.application-not-found", placeholders));
        assertEquals("用法: /townadmin town <view|delete> <小镇全名>",
                messages.plainText("chat.admin.usage-town", placeholders));
        assertEquals("用法: /townadmin town delete <小镇全名> <原因>",
                messages.plainText("chat.admin.usage-town-delete", placeholders));
        assertEquals("找不到小镇 “青石镇”",
                messages.plainText("chat.admin.town-not-found", placeholders));
        assertEquals("删除小镇“青石镇”（审计记录会保留）",
                messages.plainText("chat.admin.town-delete-confirmation", placeholders));
        assertEquals("town 只支持 view 或 delete",
                messages.plainText("chat.admin.town-action-unsupported", placeholders));
        assertEquals("用法: /townadmin member <add|remove|role> <小镇全名> <玩家> <原因|角色>",
                messages.plainText("chat.admin.usage-member", placeholders));
        assertEquals("member 只支持 add、remove 或 role",
                messages.plainText("chat.admin.member-action-unsupported", placeholders));
        assertEquals("删除小镇后 Residence 移除失败 青石镇/青石镇: Residence 未响应",
                messages.plainText("log.admin.town-delete-residence-failure", placeholders));
        assertEquals("管理员调整成员角色",
                messages.plainText("log.admin.member-role-change-reason", placeholders));

        for (String key : List.of(
                "chat.admin.usage-application-review",
                "chat.admin.application-not-found",
                "chat.admin.usage-town",
                "chat.admin.usage-town-delete",
                "chat.admin.town-not-found",
                "chat.admin.town-delete-confirmation",
                "chat.admin.town-action-unsupported",
                "chat.admin.usage-member",
                "chat.admin.member-action-unsupported",
                "log.admin.town-delete-residence-failure",
                "log.admin.member-role-change-reason")) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void usesTownAdminApplicationTownAndMemberOverridesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String applicationUsageKey = "chat.admin.usage-application-review";
        String confirmationKey = "chat.admin.town-delete-confirmation";
        String warningKey = "log.admin.town-delete-residence-failure";
        String auditKey = "log.admin.member-role-change-reason";

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(applicationUsageKey, "自定义申请用法: {action}");
        configuration.set(confirmationKey, "自定义删除确认: {town}");
        configuration.set(warningKey, "自定义删除日志: {town}/{residence}/{detail}");
        configuration.set(auditKey, "自定义成员角色审计");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        Map<String, ?> placeholders = Map.of(
                "action", "reject", "town", "青石镇", "residence", "青石镇",
                "detail", "boom");
        assertEquals("自定义申请用法: reject",
                messages.plainText(applicationUsageKey, placeholders));
        assertEquals("自定义删除确认: 青石镇",
                messages.plainText(confirmationKey, placeholders));
        assertEquals("自定义删除日志: 青石镇/青石镇/boom",
                messages.plainText(warningKey, placeholders));
        assertEquals("自定义成员角色审计", messages.plainText(auditKey, placeholders));
    }

    @Test
    void rendersTownAdminVoteMayorAndLandTemplatesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.of(
                "action", "create-kick", "town", "青石镇", "count", 3,
                "detail", "governance.voting.duration-hours 必须为正整数");

        assertEquals("用法: /townadmin vote <create-kick|create-mayor|settle|cancel> <小镇全名|voteId> ...",
                messages.plainText("chat.admin.usage-vote", placeholders));
        assertEquals("用法: /townadmin vote cancel <voteId> <原因>",
                messages.plainText("chat.admin.usage-vote-cancel", placeholders));
        assertEquals("vote 只支持 create-kick、create-mayor、settle 或 cancel",
                messages.plainText("chat.admin.vote-action-unsupported", placeholders));
        assertEquals("用法: /townadmin vote create-kick <小镇全名> <玩家>",
                messages.plainText("chat.admin.usage-vote-create", placeholders));
        assertEquals("找不到可操作的小镇",
                messages.plainText("chat.admin.vote-town-not-found", placeholders));
        assertEquals("找不到可操作的小镇 “青石镇”",
                messages.plainText("chat.admin.member-town-not-found", placeholders));
        assertEquals("用法: /townadmin mayor transfer <小镇全名> <玩家> <原因>",
                messages.plainText("chat.admin.usage-mayor", placeholders));
        assertEquals("用法: /townadmin land <preview|reconcile|rebuild> <小镇全名|all>",
                messages.plainText("chat.admin.usage-land", placeholders));
        assertEquals("land 只支持 preview/reconcile/rebuild",
                messages.plainText("chat.admin.land-action-unsupported", placeholders));
        assertEquals("移除并重建全部 3 个小镇的 Residence 投影",
                messages.plainText("chat.admin.land-rebuild-confirmation-all", placeholders));
        assertEquals("移除并重建小镇“青石镇”的 Residence 投影",
                messages.plainText("chat.admin.land-rebuild-confirmation-town", placeholders));
        assertEquals("拒绝创建治理投票: governance.voting.duration-hours 必须为正整数",
                messages.plainText("log.admin.governance-vote-creation-rejected", placeholders));

        for (String key : List.of(
                "chat.admin.usage-vote",
                "chat.admin.usage-vote-cancel",
                "chat.admin.vote-action-unsupported",
                "chat.admin.usage-vote-create",
                "chat.admin.vote-town-not-found",
                "chat.admin.member-town-not-found",
                "chat.admin.usage-mayor",
                "chat.admin.usage-land",
                "chat.admin.land-action-unsupported",
                "chat.admin.land-rebuild-confirmation-all",
                "chat.admin.land-rebuild-confirmation-town",
                "log.admin.governance-vote-creation-rejected")) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void usesTownAdminVoteMayorAndLandOverridesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String usageKey = "chat.admin.usage-vote-create";
        String townConfirmationKey = "chat.admin.land-rebuild-confirmation-town";
        String allConfirmationKey = "chat.admin.land-rebuild-confirmation-all";
        String logKey = "log.admin.governance-vote-creation-rejected";

        assertEquals("用法: /townadmin vote create-mayor <小镇全名> <玩家>",
                messages.plainText(usageKey, Map.of("action", "create-mayor")));
        assertEquals("移除并重建小镇“青石镇”的 Residence 投影",
                messages.plainText(townConfirmationKey, Map.of("town", "青石镇")));
        assertEquals("移除并重建全部 2 个小镇的 Residence 投影",
                messages.plainText(allConfirmationKey, Map.of("count", 2)));
        assertEquals("拒绝创建治理投票: boom",
                messages.plainText(logKey, Map.of("detail", "boom")));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(usageKey, "自定义投票用法: {action}");
        configuration.set(townConfirmationKey, "自定义单镇领地确认: {town}");
        configuration.set(allConfirmationKey, "自定义批量领地确认: {count}");
        configuration.set(logKey, "自定义治理投票日志: {detail}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义投票用法: create-mayor",
                messages.plainText(usageKey, Map.of("action", "create-mayor")));
        assertEquals("自定义单镇领地确认: 青石镇",
                messages.plainText(townConfirmationKey, Map.of("town", "青石镇")));
        assertEquals("自定义批量领地确认: 2",
                messages.plainText(allConfirmationKey, Map.of("count", 2)));
        assertEquals("自定义治理投票日志: boom",
                messages.plainText(logKey, Map.of("detail", "boom")));
    }

    @Test
    void rendersTownAdminEconomyExpansionAndBuffTemplatesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.of(
                "town", "青石镇", "buff", "速度", "permission", "tianjitown.admin.money");

        assertEquals("用法: /townadmin money <view|adjust|reconcile> ...",
                messages.plainText("chat.admin.usage-money-root", placeholders));
        assertEquals("用法: /townadmin money <view|adjust> <小镇全名> [金额 原因]",
                messages.plainText("chat.admin.usage-money", placeholders));
        assertEquals("用法: /townadmin money adjust <小镇全名> <带符号金额> <原因>",
                messages.plainText("chat.admin.usage-money-adjust", placeholders));
        assertEquals("小镇不存在",
                messages.plainText("chat.admin.town-not-found-generic", placeholders));
        assertEquals("调整金额不能为 0",
                messages.plainText("chat.admin.money-adjust-zero", placeholders));
        assertEquals("money 只支持 view、adjust 或 reconcile",
                messages.plainText("chat.admin.money-action-unsupported", placeholders));
        assertEquals("用法: /townadmin tax set <小镇全名> <百分比> <原因>",
                messages.plainText("chat.admin.usage-tax", placeholders));
        assertEquals("tax 只支持 set",
                messages.plainText("chat.admin.tax-action-unsupported", placeholders));
        assertEquals("用法: /townadmin ledger view <小镇全名>",
                messages.plainText("chat.admin.usage-ledger", placeholders));
        assertEquals("ledger 只支持 view",
                messages.plainText("chat.admin.ledger-action-unsupported", placeholders));
        assertEquals("用法: /townadmin expand <view|preview> <小镇全名> [方向]",
                messages.plainText("chat.admin.usage-expand", placeholders));
        assertEquals("必须指定 north/east/south/west",
                messages.plainText("chat.admin.expand-direction-required", placeholders));
        assertEquals("expand 只支持 view 或 preview",
                messages.plainText("chat.admin.expand-action-unsupported", placeholders));
        assertEquals("用法: /townadmin buff <list|grant> ...",
                messages.plainText("chat.admin.usage-buff-root", placeholders));
        assertEquals("用法: /townadmin buff list <小镇全名>",
                messages.plainText("chat.admin.usage-buff-list", placeholders));
        assertEquals("公共 Buff 新购买已由功能开关暂停",
                messages.plainText("chat.admin.buff-purchase-paused", placeholders));
        assertEquals("用法: /townadmin buff grant <小镇全名> <buffKey> <原因>",
                messages.plainText("chat.admin.usage-buff-grant", placeholders));
        assertEquals("为小镇“青石镇”代购 Buff “速度”并扣除公共资金",
                messages.plainText("chat.admin.buff-purchase-confirmation", placeholders));
        assertEquals("buff 只支持 list 或 grant；公共 Buff 不接受退款",
                messages.plainText("chat.admin.buff-action-unsupported", placeholders));
        assertEquals("小镇“青石镇”已归档，请重新发起操作",
                messages.plainText("chat.admin.town-archived", placeholders));
        assertEquals("没有可操作的小镇",
                messages.plainText("chat.admin.no-operable-town", placeholders));
        assertEquals("找不到小镇记录",
                messages.plainText("chat.admin.town-record-not-found", placeholders));
        assertEquals("缺少权限 tianjitown.admin.money",
                messages.plainText("chat.admin.permission-missing", placeholders));
        assertEquals("小镇“青石镇”在确认期间发生变化，请重新发起操作",
                messages.plainText("chat.admin.confirmation-stale", placeholders));

        for (String key : List.of(
                "chat.admin.usage-money-root",
                "chat.admin.usage-money",
                "chat.admin.usage-money-adjust",
                "chat.admin.town-not-found-generic",
                "chat.admin.money-adjust-zero",
                "chat.admin.money-action-unsupported",
                "chat.admin.usage-tax",
                "chat.admin.tax-action-unsupported",
                "chat.admin.usage-ledger",
                "chat.admin.ledger-action-unsupported",
                "chat.admin.usage-expand",
                "chat.admin.expand-direction-required",
                "chat.admin.expand-action-unsupported",
                "chat.admin.usage-buff-root",
                "chat.admin.usage-buff-list",
                "chat.admin.buff-purchase-paused",
                "chat.admin.usage-buff-grant",
                "chat.admin.buff-purchase-confirmation",
                "chat.admin.buff-action-unsupported",
                "chat.admin.town-archived",
                "chat.admin.no-operable-town",
                "chat.admin.town-record-not-found",
                "chat.admin.permission-missing",
                "chat.admin.confirmation-stale")) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void usesTownAdminEconomyExpansionAndBuffOverridesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String moneyUsageKey = "chat.admin.usage-money";
        String confirmationKey = "chat.admin.buff-purchase-confirmation";
        String permissionKey = "chat.admin.permission-missing";
        String staleKey = "chat.admin.confirmation-stale";

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(moneyUsageKey, "自定义资金用法: {town}");
        configuration.set(confirmationKey, "自定义 Buff 确认: {town}/{buff}");
        configuration.set(permissionKey, "自定义权限提示: {permission}");
        configuration.set(staleKey, "自定义确认已失效: {town}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        Map<String, ?> placeholders = Map.of(
                "town", "青石镇", "buff", "速度", "permission", "tianjitown.admin.money");
        assertEquals("自定义资金用法: 青石镇",
                messages.plainText(moneyUsageKey, placeholders));
        assertEquals("自定义 Buff 确认: 青石镇/速度",
                messages.plainText(confirmationKey, placeholders));
        assertEquals("自定义权限提示: tianjitown.admin.money",
                messages.plainText(permissionKey, placeholders));
        assertEquals("自定义确认已失效: 青石镇",
                messages.plainText(staleKey, placeholders));
    }

    @Test
    void rendersTownAdminHelpTemplatesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.of(
                "version", "1.4.0", "topic", "unknown");

        assertEquals("TianjiTown 1.4.0 管理帮助",
                messages.plainText("chat.admin.help-title", placeholders));
        assertEquals("未知帮助分类：unknown",
                messages.plainText("chat.admin.help-unknown", placeholders));

        for (String key : List.of(
                "chat.admin.help-application-list",
                "chat.admin.help-application-review",
                "chat.admin.help-title",
                "chat.admin.help-usage",
                "chat.admin.help-placeholder-hint",
                "chat.admin.help-forbidden",
                "chat.admin.help-unknown",
                "chat.admin.help-entry-system",
                "chat.admin.help-entry-station",
                "chat.admin.help-entry-application",
                "chat.admin.help-entry-town",
                "chat.admin.help-entry-member",
                "chat.admin.help-entry-vote",
                "chat.admin.help-entry-land",
                "chat.admin.help-entry-money",
                "chat.admin.help-entry-tax",
                "chat.admin.help-entry-ledger",
                "chat.admin.help-entry-expand",
                "chat.admin.help-entry-buff",
                "chat.admin.help-system-title",
                "chat.admin.help-system-status",
                "chat.admin.help-system-reload",
                "chat.admin.help-system-maintenance",
                "chat.admin.help-system-audit",
                "chat.admin.help-system-diagnose",
                "chat.admin.help-system-backup",
                "chat.admin.help-economy-title",
                "chat.admin.help-economy-money-view",
                "chat.admin.help-economy-money-adjust",
                "chat.admin.help-economy-money-reconcile",
                "chat.admin.help-economy-tax",
                "chat.admin.help-economy-ledger",
                "chat.admin.help-economy-expand",
                "chat.admin.help-buff-title",
                "chat.admin.help-buff-list",
                "chat.admin.help-buff-grant",
                "chat.admin.help-buff-note",
                "chat.admin.help-town-title",
                "chat.admin.help-town-view",
                "chat.admin.help-town-delete",
                "chat.admin.help-member-title",
                "chat.admin.help-member-add-remove",
                "chat.admin.help-member-role",
                "chat.admin.help-member-note",
                "chat.admin.help-member-mayor",
                "chat.admin.help-vote-title",
                "chat.admin.help-vote-kick",
                "chat.admin.help-vote-mayor",
                "chat.admin.help-vote-settle",
                "chat.admin.help-vote-cancel",
                "chat.admin.help-land-title",
                "chat.admin.help-land-preview",
                "chat.admin.help-land-reconcile",
                "chat.admin.help-land-rebuild")) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{version}"), key);
            assertFalse(rendered.contains("{topic}"), key);
        }
    }

    @Test
    void usesTownAdminHelpOverridesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String titleKey = "chat.admin.help-title";
        String entryKey = "chat.admin.help-entry-system";
        String pageKey = "chat.admin.help-system-status";
        String unknownKey = "chat.admin.help-unknown";

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(titleKey, "&aCustom admin help {version}");
        configuration.set(entryKey, "&bCustom system entry");
        configuration.set(pageKey, "&dCustom status page");
        configuration.set(unknownKey, "&cCustom unknown topic: {topic}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        Map<String, ?> placeholders = Map.of("version", "1.4.0", "topic", "mystery");
        assertEquals("Custom admin help 1.4.0",
                messages.plainText(titleKey, placeholders));
        assertEquals("Custom system entry", messages.plainText(entryKey, placeholders));
        assertEquals("Custom status page", messages.plainText(pageKey, placeholders));
        assertEquals("Custom unknown topic: mystery",
                messages.plainText(unknownKey, placeholders));
    }

    @Test
    void rendersDependencyDatabaseAndSchemaGateTemplatesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("FAIL Residence 未安装",
                messages.plainText("diagnostic.lifecycle.dependency-missing",
                        Map.of("dependency", "Residence")));
        assertEquals("FAIL Vault 1.7（未启用）",
                messages.plainText("diagnostic.lifecycle.dependency-disabled",
                        Map.of("dependency", "Vault", "version", "1.7")));
        assertEquals("FAIL QuickShop-Hikari 依赖探测异常: NoSuchMethodError",
                messages.plainText("diagnostic.lifecycle.dependency-probe-failure",
                        Map.of("dependency", "QuickShop-Hikari", "detail", "NoSuchMethodError")));
        assertEquals("FAIL Vault Economy provider=不可用 (Vault 未启用，已跳过服务探测)",
                messages.plainText("diagnostic.lifecycle.vault-economy-unavailable"));
        assertEquals("FAIL SQLite/Flyway: database locked",
                messages.plainText("diagnostic.lifecycle.database-gate-failed",
                        Map.of("detail", "database locked")));
        assertEquals("数据库门禁未通过",
                messages.plainText("diagnostic.lifecycle.database-gate-locked"));
        assertEquals("FAIL SQLite config: invalid path",
                messages.plainText("diagnostic.lifecycle.database-config-invalid",
                        Map.of("detail", "invalid path")));
        assertEquals("数据库配置无效",
                messages.plainText("diagnostic.lifecycle.database-config-gate-failed"));
        assertEquals("FAIL config schema=11 高于本插件支持的 10，拒绝降级读取",
                messages.plainText("diagnostic.lifecycle.config-schema-too-new",
                        Map.of("schema", 11, "supported", 10)));
        assertEquals("FAIL config schema=9 不能直接安全升级到 10；请先按对应版本升级手册处理",
                messages.plainText("diagnostic.lifecycle.config-schema-upgrade-required",
                        Map.of("schema", 9, "supported", 10)));
        assertEquals("database.file 不能为空",
                messages.plainText("validation.runtime-configuration.database-file-required"));
        assertEquals("database.file 必须指向数据库文件",
                messages.plainText("validation.runtime-configuration.database-file-path-invalid"));
        assertEquals("无法创建 SQLite 目录: plugins/TianjiTown",
                messages.plainText(
                        "validation.runtime-configuration.database-directory-create-failure",
                        Map.of("path", "plugins/TianjiTown")));

        Map<String, ?> placeholders = Map.of(
                "dependency", "Residence", "version", "1.0", "detail", "boom",
                "schema", 9, "supported", 10, "path", "plugins/TianjiTown");
        for (String key : List.of(
                "diagnostic.lifecycle.dependency-missing",
                "diagnostic.lifecycle.dependency-disabled",
                "diagnostic.lifecycle.dependency-probe-failure",
                "diagnostic.lifecycle.vault-economy-unavailable",
                "diagnostic.lifecycle.database-gate-failed",
                "diagnostic.lifecycle.database-gate-locked",
                "diagnostic.lifecycle.database-config-invalid",
                "diagnostic.lifecycle.database-config-gate-failed",
                "diagnostic.lifecycle.config-schema-too-new",
                "diagnostic.lifecycle.config-schema-upgrade-required",
                "validation.runtime-configuration.database-file-required",
                "validation.runtime-configuration.database-file-path-invalid",
                "validation.runtime-configuration.database-directory-create-failure")) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank());
            assertFalse(rendered.contains("缺少消息配置"));
            assertFalse(rendered.contains("{"));
        }
    }

    @Test
    void usesDependencyAndDatabaseGateOverridesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("FAIL Residence 未安装",
                messages.plainText("diagnostic.lifecycle.dependency-missing",
                        Map.of("dependency", "Residence")));
        assertEquals("无法创建 SQLite 目录: plugins/TianjiTown",
                messages.plainText(
                        "validation.runtime-configuration.database-directory-create-failure",
                        Map.of("path", "plugins/TianjiTown")));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("diagnostic.lifecycle.dependency-missing", "自定义依赖提示: {dependency}");
        configuration.set("validation.runtime-configuration.database-directory-create-failure",
                "自定义数据库目录错误: {path}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义依赖提示: Residence",
                messages.plainText("diagnostic.lifecycle.dependency-missing",
                        Map.of("dependency", "Residence")));
        assertEquals("自定义数据库目录错误: plugins/TianjiTown",
                messages.plainText(
                        "validation.runtime-configuration.database-directory-create-failure",
                        Map.of("path", "plugins/TianjiTown")));
    }

    @Test
    void usesLifecycleLogOverrideAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String key = "log.scheduler.async-task-failure";
        assertEquals("异步任务异常，已在插件边界隔离: boom",
                messages.plainText(key, Map.of("detail", "boom")));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(key, "自定义调度异常: {detail}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义调度异常: boom",
                messages.plainText(key, Map.of("detail", "boom")));
    }

    @Test
    void reportsMissingLifecycleMessageThroughConfiguredDiagnostic() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("diagnostic.lifecycle.startup-checking", "");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new PluginMessages(temporaryDirectory.toFile()));

        assertEquals("缺少必需的消息配置: diagnostic.lifecycle.startup-checking",
                exception.getMessage());
    }

    @Test
    void rendersTownTerritoryNameWithoutResidenceBranding() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("§7小镇领地名: 青石镇",
                messages.text("dialog.town.residence-name", Map.of("name", "青石镇")));
    }

    @Test
    void keepsOnlyTheSafetyRequirementInTheTeleportPointTooltip() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("§7必须站在本镇领地内的安全位置",
                messages.text("dialog.tooltip.town.set-teleport"));
    }

    @Test
    void providesTheFixedApplicationFormDraftSavedNotice() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("§6草稿已保存", messages.text("dialog.notice.form-draft-saved-title"));
        assertEquals("§f已保存小镇申请草稿",
                messages.text("dialog.notice.form-draft-saved-message"));
    }

    @Test
    void keepsTheReviewedPlayerFacingCopyAndTerritorySelectionKey() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("§f管理员审核中，批准前仍可撤回申请。",
                messages.text("dialog.notice.application-submitted-message"));
        assertEquals("§f小镇创建成功。",
                messages.text("dialog.notice.application-created-message"));
        assertEquals("§6小镇领地扩张", messages.text("dialog.territory.title"));
        assertEquals("§a已选中（再次点击取消）",
                messages.text("dialog.territory.cell.selected"));
        assertEquals("§f申请人邀请你加入青石镇", messages.text("dialog.invitation.message",
                Map.of("player", "申请人", "town", "青石镇")));
    }

    @Test
    void rendersGlobalMarketPlusDiagnosticTemplatesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("GlobalMarketPlus 1.2.3 成交与拍卖结果事件已通过能力检查",
                messages.plainText("diagnostic.global-market-plus.capability-success",
                        Map.of("version", "1.2.3")));
        assertEquals("GlobalMarketPlus 收入税 API 能力检查失败: NoSuchMethodError",
                messages.plainText("diagnostic.global-market-plus.capability-failure",
                        Map.of("detail", "NoSuchMethodError")));

        for (String key : List.of(
                "log.global-market-plus.transaction-failure",
                "log.global-market-plus.auction-failure",
                "log.global-market-plus.main-thread-failure",
                "log.global-market-plus.boundary-failure")) {
            String rendered = messages.plainText(key, Map.of("detail", "boom"));
            assertFalse(rendered.contains("缺少消息配置"));
            assertFalse(rendered.contains("{detail}"));
        }
    }

    @Test
    void usesGlobalMarketPlusLogOverrideAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String key = "log.global-market-plus.transaction-failure";
        assertTrue(messages.plainText(key, Map.of("detail", "boom"))
                .contains("GlobalMarketPlus 成交收入税处理失败"));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(key, "自定义市场税日志: {detail}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义市场税日志: boom",
                messages.plainText(key, Map.of("detail", "boom")));
    }

    @Test
    void rendersJobsDiagnosticAndLogTemplatesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("Jobs 5.2.6.6 收入事件、异步转主线程结算与可变付款金额已通过能力检查",
                messages.plainText("diagnostic.jobs.capability-success",
                        Map.of("version", "5.2.6.6")));
        assertEquals("Jobs 收入税 API 能力检查失败: NoSuchMethodError",
                messages.plainText("diagnostic.jobs.capability-failure",
                        Map.of("detail", "NoSuchMethodError")));
        assertEquals("等待 Jobs 主线程税务结算时被中断",
                messages.plainText("log.jobs.await-interrupted"));
        assertEquals("等待 Jobs 主线程税务结算超时",
                messages.plainText("log.jobs.await-timeout"));
        assertEquals("Jobs 主线程税务结算失败",
                messages.plainText("log.jobs.main-thread-failure"));

        for (String key : List.of(
                "log.jobs.payment-failure",
                "log.jobs.boundary-failure")) {
            String rendered = messages.plainText(key, Map.of("detail", "boom"));
            assertFalse(rendered.contains("缺少消息配置"));
            assertFalse(rendered.contains("{detail}"));
        }
    }

    @Test
    void usesJobsLogOverrideAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String key = "log.jobs.payment-failure";
        assertTrue(messages.plainText(key, Map.of("detail", "boom"))
                .contains("Jobs 收入税处理失败"));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(key, "自定义 Jobs 税日志: {detail}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义 Jobs 税日志: boom",
                messages.plainText(key, Map.of("detail", "boom")));
    }

    @Test
    void rendersQuickShopHistoryDiagnosticTemplatesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("QuickShop 版本未通过交易历史适配器验证",
                messages.plainText("diagnostic.quick-shop.history-version-unsupported"));
        assertEquals("MetricQuery 构造器不存在",
                messages.plainText("diagnostic.quick-shop.history-query-constructor-missing"));
        assertEquals("QuickShop 交易历史返回类型异常",
                messages.plainText("diagnostic.quick-shop.history-result-type-invalid"));
        assertEquals("QuickShop 交易历史读取失败: NoSuchMethodError",
                messages.plainText("diagnostic.quick-shop.history-read-failure",
                        Map.of("detail", "NoSuchMethodError")));
        assertEquals("目标对象未实现 QuickShop API: com.example.QuickShopAPI",
                messages.plainText("diagnostic.quick-shop.api-target-type-mismatch",
                        Map.of("type", "com.example.QuickShopAPI")));
        assertEquals("已读取 QuickShop transaction metric 历史",
                messages.plainText("diagnostic.quick-shop.history-success"));

        for (String key : List.of(
                "diagnostic.quick-shop.history-version-unsupported",
                "diagnostic.quick-shop.history-query-constructor-missing",
                "diagnostic.quick-shop.history-result-type-invalid",
                "diagnostic.quick-shop.history-read-failure",
                "diagnostic.quick-shop.api-target-type-mismatch",
                "diagnostic.quick-shop.history-success")) {
            String rendered = switch (key) {
                case "diagnostic.quick-shop.history-read-failure" -> messages.plainText(key,
                        Map.of("detail", "boom"));
                case "diagnostic.quick-shop.api-target-type-mismatch" -> messages.plainText(key,
                        Map.of("type", "ExampleApi"));
                default -> messages.plainText(key);
            };
            assertFalse(rendered.contains("缺少消息配置"));
            assertFalse(rendered.contains("{detail}"));
            assertFalse(rendered.contains("{type}"));
        }
    }

    @Test
    void usesQuickShopHistoryOverrideAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String key = "diagnostic.quick-shop.history-read-failure";
        assertEquals("QuickShop 交易历史读取失败: boom",
                messages.plainText(key, Map.of("detail", "boom")));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(key, "自定义 QuickShop 历史错误: {detail}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义 QuickShop 历史错误: boom",
                messages.plainText(key, Map.of("detail", "boom")));
    }

    @Test
    void rendersQuickShopTaxDiagnosticAndLogTemplatesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("QuickShop-Hikari 版本至少为 6.3.0.0，当前为 6.2.0.0；动态税已保持关闭",
                messages.plainText("diagnostic.quick-shop.tax-version-unsupported",
                        Map.of("minimum", "6.3.0.0", "version", "6.2.0.0")));
        assertEquals("QuickShop 6.3.0.0 税率、交易账户、精确事件分流和成功事件签名已通过能力检查",
                messages.plainText("diagnostic.quick-shop.tax-capability-success",
                        Map.of("version", "6.3.0.0")));
        assertEquals("QuickShop 税务 API 能力检查失败: NoSuchMethodError",
                messages.plainText("diagnostic.quick-shop.tax-capability-failure",
                        Map.of("detail", "NoSuchMethodError")));
        assertEquals("QuickShop 返回了无效金额",
                messages.plainText("diagnostic.quick-shop.invalid-amount"));

        for (String key : List.of(
                "log.quick-shop.tax-event-failure",
                "log.quick-shop.transaction-account-failure",
                "log.quick-shop.success-settlement-failure",
                "log.quick-shop.boundary-failure")) {
            String rendered = messages.plainText(key, Map.of("detail", "boom"));
            assertFalse(rendered.contains("缺少消息配置"));
            assertFalse(rendered.contains("{detail}"));
        }
    }

    @Test
    void usesQuickShopTaxLogOverrideAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String key = "log.quick-shop.tax-event-failure";
        assertEquals("QuickShop 税率事件处理失败: boom；同类后续错误将被抑制",
                messages.plainText(key, Map.of("detail", "boom")));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(key, "自定义 QuickShop 税率日志: {detail}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义 QuickShop 税率日志: boom",
                messages.plainText(key, Map.of("detail", "boom")));
    }

    @Test
    void rendersVaultEconomyDiagnosticTemplatesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("Vault 未注册 Economy 服务",
                messages.plainText("diagnostic.vault.economy-not-registered"));
        assertEquals("正常",
                messages.plainText("diagnostic.vault.economy-provider-enabled"));
        assertEquals("Economy provider 未启用",
                messages.plainText("diagnostic.vault.economy-provider-disabled"));
        assertEquals("Vault Economy 探测异常: NoSuchMethodError",
                messages.plainText("diagnostic.vault.economy-probe-failure",
                        Map.of("detail", "NoSuchMethodError")));

        for (String key : List.of(
                "diagnostic.vault.economy-not-registered",
                "diagnostic.vault.economy-provider-enabled",
                "diagnostic.vault.economy-provider-disabled",
                "diagnostic.vault.economy-probe-failure")) {
            String rendered = key.endsWith("failure")
                    ? messages.plainText(key, Map.of("detail", "boom"))
                    : messages.plainText(key);
            assertFalse(rendered.contains("缺少消息配置"));
            assertFalse(rendered.contains("{detail}"));
        }
    }

    @Test
    void usesVaultEconomyOverrideAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String key = "diagnostic.vault.economy-probe-failure";
        assertEquals("Vault Economy 探测异常: boom",
                messages.plainText(key, Map.of("detail", "boom")));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(key, "自定义 Vault 探测错误: {detail}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义 Vault 探测错误: boom",
                messages.plainText(key, Map.of("detail", "boom")));
    }

    @Test
    void rendersVaultSettlementTemplatesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("清算账户名不能为空",
                messages.plainText("validation.vault.account-name-required"));
        assertEquals("无法解析 Vault 离线清算账户: boom",
                messages.plainText("diagnostic.vault.settlement.account-resolve-failure",
                        Map.of("detail", "boom")));
        assertEquals("Vault provider 无法创建或重新读取离线清算账户 tax",
                messages.plainText("diagnostic.vault.settlement.account-create-failure",
                        Map.of("account", "tax")));
        assertEquals("Vault 玩家扣款调用异常，资金结果需要人工复核: boom",
                messages.plainText("log.vault.settlement.player-debit-ambiguous",
                        Map.of("detail", "boom")));

        for (String key : List.of(
                "validation.vault.account-name-required",
                "validation.vault.scale-range",
                "validation.vault.donation-amount-positive",
                "validation.vault.refund-amount-positive",
                "validation.vault.return-amount-positive",
                "validation.vault.adjustment-non-zero",
                "diagnostic.vault.provider-unavailable",
                "diagnostic.vault.provider-probe-failure",
                "diagnostic.vault.invalid-amount",
                "diagnostic.vault.main-thread-required",
                "diagnostic.vault.settlement.account-resolve-failure",
                "diagnostic.vault.settlement.scale-read-failure",
                "diagnostic.vault.settlement.account-ready",
                "diagnostic.vault.settlement.account-create-failure",
                "diagnostic.vault.settlement.account-initialization-failure",
                "diagnostic.vault.settlement.balance-read-failure",
                "diagnostic.vault.settlement.account-available",
                "diagnostic.vault.settlement.account-unavailable",
                "diagnostic.vault.settlement.player-debit-failure",
                "diagnostic.vault.settlement.settlement-credit-failure",
                "diagnostic.vault.settlement.funds-transferred",
                "diagnostic.vault.settlement.player-debit-compensated",
                "diagnostic.vault.settlement.player-compensation-failure",
                "diagnostic.vault.settlement.settlement-debit-failure",
                "diagnostic.vault.settlement.funds-returned",
                "diagnostic.vault.settlement.player-credit-failure",
                "diagnostic.vault.settlement.account-adjusted",
                "diagnostic.vault.settlement.account-adjustment-failure",
                "diagnostic.vault.settlement.account-not-available",
                "diagnostic.vault.settlement.account-check-failure",
                "log.vault.settlement.player-debit-ambiguous",
                "log.vault.settlement.settlement-credit-ambiguous",
                "log.vault.settlement.player-compensation-ambiguous",
                "log.vault.settlement.player-refund-ambiguous",
                "log.vault.settlement.settlement-debit-ambiguous",
                "log.vault.settlement.player-credit-ambiguous",
                "log.vault.settlement.settlement-compensation-ambiguous",
                "log.vault.settlement.account-adjustment-ambiguous",
                "log.vault.settlement.empty-response")) {
            String rendered = messages.plainText(key,
                    Map.of("account", "tax", "detail", "boom"));
            assertFalse(rendered.isBlank());
            assertFalse(rendered.contains("缺少消息配置"));
            assertFalse(rendered.contains("{account}"));
            assertFalse(rendered.contains("{detail}"));
        }
    }

    @Test
    void usesVaultSettlementOverrideAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String key = "diagnostic.vault.settlement.player-debit-failure";
        assertEquals("玩家扣款失败: boom",
                messages.plainText(key, Map.of("detail", "boom")));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(key, "自定义 Vault 扣款错误: {detail}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义 Vault 扣款错误: boom",
                messages.plainText(key, Map.of("detail", "boom")));
    }

    @Test
    void rendersWorldBorderDiagnosticTemplatesAndSupportsOverrideAfterMessagesReload()
            throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("WorldBorder API 必须在 Paper 主线程调用",
                messages.plainText("diagnostic.world-border.main-thread-required"));
        assertEquals("WorldBorder 未启用",
                messages.plainText("diagnostic.world-border.plugin-disabled"));
        assertEquals("WorldBorder 缺少 Config.Border/BorderData.insideBorder 能力",
                messages.plainText("diagnostic.world-border.api-capability-missing"));
        assertEquals("WorldBorder insideBorder 返回值无效",
                messages.plainText("diagnostic.world-border.invalid-return"));
        assertEquals("WorldBorder API 不可访问",
                messages.plainText("diagnostic.world-border.api-inaccessible"));
        assertEquals("WorldBorder API 调用失败",
                messages.plainText("diagnostic.world-border.api-call-failed"));

        for (String key : List.of(
                "diagnostic.world-border.main-thread-required",
                "diagnostic.world-border.plugin-disabled",
                "diagnostic.world-border.api-capability-missing",
                "diagnostic.world-border.invalid-return",
                "diagnostic.world-border.api-inaccessible",
                "diagnostic.world-border.api-call-failed")) {
            String rendered = messages.plainText(key);
            assertFalse(rendered.isBlank());
            assertFalse(rendered.contains("缺少消息配置"));
        }

        String key = "diagnostic.world-border.api-call-failed";
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(key, "自定义 WorldBorder 调用诊断");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义 WorldBorder 调用诊断", messages.plainText(key));
    }

    @Test
    void rendersResidenceCommandGuardLogAndSupportsOverrideAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String key = "log.residence.command-guard-failure";

        assertEquals("Residence 命令保护异常，已按失败关闭策略拒绝命令: boom；同类后续错误将被抑制",
                messages.plainText(key, Map.of("detail", "boom")));
        assertFalse(messages.plainText(key, Map.of("detail", "boom"))
                .contains("{detail}"));
        assertFalse(messages.plainText(key, Map.of("detail", "boom"))
                .contains("缺少消息配置"));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(key, "自定义 Residence 命令日志: {detail}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义 Residence 命令日志: boom",
                messages.plainText(key, Map.of("detail", "boom")));
    }

    @Test
    void rendersResidenceDeletionLogsAndSupportsOverrideAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("系统领地删除保护事件已取消；来源=非玩家，领地=town-1",
                messages.plainText("log.residence.non-player-deletion-cancelled",
                        Map.of("residence", "town-1")));
        assertEquals("Residence 删除后的对账恢复失败: boom；同类后续错误将被抑制",
                messages.plainText("log.residence.deletion-recovery-failure",
                        Map.of("detail", "boom")));
        assertEquals("Residence 删除保护异常，已按失败关闭策略取消删除: boom；同类后续错误将被抑制",
                messages.plainText("log.residence.deletion-guard-failure",
                        Map.of("detail", "boom")));

        for (String key : new String[] {
                "log.residence.non-player-deletion-cancelled",
                "log.residence.deletion-recovery-failure",
                "log.residence.deletion-guard-failure"}) {
            String rendered = messages.plainText(key, Map.of(
                    key.endsWith("cancelled") ? "residence" : "detail", "boom"));
            org.junit.jupiter.api.Assertions.assertFalse(rendered.contains("{detail}"));
            org.junit.jupiter.api.Assertions.assertFalse(rendered.contains("{residence}"));
            org.junit.jupiter.api.Assertions.assertFalse(rendered.contains("缺少消息配置"));
        }

        String key = "log.residence.deletion-recovery-failure";
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(key, "自定义 Residence 恢复日志: {detail}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义 Residence 恢复日志: boom",
                messages.plainText(key, Map.of("detail", "boom")));
    }

    @Test
    void rendersBuffRuntimeTemplatesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("公共 Buff 商店当前暂停新购买",
                messages.plainText("chat.buff.shop-paused"));
        assertEquals("Buff 应用失败，已自动取消并退回公共资金: boom",
                messages.plainText("chat.buff.application-failure-refunded",
                        Map.of("detail", "boom")));
        assertEquals("玩家缺少 Attribute: minecraft:movement_speed",
                messages.plainText("diagnostic.buff.missing-attribute",
                        Map.of("attribute", "minecraft:movement_speed")));
        assertEquals("玩家缺少 MAX_HEALTH Attribute",
                messages.plainText("diagnostic.buff.max-health-missing"));
        assertEquals("玩家 MAX_HEALTH Attribute 数值无效: 0.0",
                messages.plainText("diagnostic.buff.max-health-invalid",
                        Map.of("amount", 0.0D)));
        assertEquals("检测到玩家 player-1 的 Buff Attribute 异常（minecraft:movement_speed，实际 缺失，期望 amount=0.2, operation=ADD_SCALAR），已自动修复",
                messages.plainText("log.buff.attribute-repair-missing", Map.of(
                        "player", "player-1", "attribute", "minecraft:movement_speed",
                        "amount", 0.2D, "operation", "ADD_SCALAR")));
        assertEquals("检测到玩家 player-1 的 Buff Attribute 异常（minecraft:movement_speed，实际 amount=0.4, operation=ADD_SCALAR，期望 amount=0.2, operation=ADD_SCALAR），已自动修复",
                messages.plainText("log.buff.attribute-repair-mismatch", Map.of(
                        "player", "player-1", "attribute", "minecraft:movement_speed",
                        "actual", "amount=0.4, operation=ADD_SCALAR", "amount", 0.2D,
                        "operation", "ADD_SCALAR")));

        List<String> keys = List.of(
                "chat.buff.shop-paused",
                "chat.buff.application-failure-refunded",
                "diagnostic.buff.missing-attribute",
                "diagnostic.buff.max-health-missing",
                "diagnostic.buff.max-health-invalid",
                "log.buff.refresh-failure",
                "log.buff.refresh-check-failure",
                "log.buff.refund-reason",
                "log.buff.expiration-schedule-failure",
                "log.buff.expiration-cleanup-failure",
                "log.buff.expiration-cancel-failure",
                "log.buff.attribute-repair-missing",
                "log.buff.attribute-repair-mismatch",
                "log.buff.cleanup-invalid-object");
        Map<String, Object> placeholders = Map.of(
                "player", "player-1", "detail", "boom", "attribute", "minecraft:movement_speed",
                "amount", 0.2D, "operation", "ADD_SCALAR",
                "actual", "amount=0.4, operation=ADD_SCALAR");
        for (String key : keys) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank());
            assertFalse(rendered.contains("缺少消息配置"));
            assertFalse(rendered.contains("{player}"));
            assertFalse(rendered.contains("{detail}"));
            assertFalse(rendered.contains("{attribute}"));
            assertFalse(rendered.contains("{amount}"));
            assertFalse(rendered.contains("{operation}"));
            assertFalse(rendered.contains("{actual}"));
        }
    }

    @Test
    void usesBuffRuntimeLogOverrideAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String key = "log.buff.refresh-failure";
        Map<String, ?> placeholders = Map.of("player", "player-1", "detail", "boom");

        assertEquals("刷新玩家公共 Buff 失败 player-1: boom",
                messages.plainText(key, placeholders));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(key, "自定义 Buff 刷新日志: {player}/{detail}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义 Buff 刷新日志: player-1/boom",
                messages.plainText(key, placeholders));
    }

    @Test
    void rendersBuffSettingsValidationTemplatesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, Object> placeholders = Map.of(
                "key", "missing",
                "maximum", 36,
                "path", "buffs.catalog.speed.display-name",
                "value", "INVALID");

        assertEquals("未知 Buff: missing",
                messages.plainText("validation.buff.unknown", placeholders));
        assertEquals("buffs.catalog 至少需要一个 Buff",
                messages.plainText("validation.buff.catalog-required", placeholders));
        assertEquals("buffs.catalog 最多支持 36 个 Buff",
                messages.plainText("validation.buff.catalog-limit", placeholders));
        assertEquals("重复 Buff key: missing",
                messages.plainText("validation.buff.duplicate-key", placeholders));
        assertEquals("buffs.catalog.speed.purchasing-roles 至少需要一个角色",
                messages.plainText("validation.buff.purchasing-roles-required",
                        Map.of("path", "buffs.catalog.speed.purchasing-roles")));
        assertEquals("buffs.catalog.speed 必须为配置节",
                messages.plainText("validation.buff.section-required",
                        Map.of("path", "buffs.catalog.speed")));
        assertEquals("buffs.catalog.speed.display-name 不能为空",
                messages.plainText("validation.buff.value-required", placeholders));
        assertEquals("金额超过 long 次级单位上限",
                messages.plainText("validation.buff.price-overflow", placeholders));
        assertEquals("buffs.catalog.speed.base-price 产生的价格超出次级货币单位范围",
                messages.plainText("validation.buff.price-range",
                        Map.of("path", "buffs.catalog.speed.base-price")));
        assertEquals("buffs.catalog.speed.effect-kind 的值不受支持: INVALID",
                messages.plainText("validation.buff.enum-unsupported",
                        Map.of("path", "buffs.catalog.speed.effect-kind", "value", "INVALID")));

        for (String key : List.of(
                "validation.buff.unknown",
                "validation.buff.catalog-required",
                "validation.buff.catalog-limit",
                "validation.buff.duplicate-key",
                "validation.buff.purchasing-roles-required",
                "validation.buff.section-required",
                "validation.buff.value-required",
                "validation.buff.price-overflow",
                "validation.buff.price-range",
                "validation.buff.enum-unsupported")) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank());
            assertFalse(rendered.contains("缺少消息配置"));
            assertFalse(rendered.contains("{key}"));
            assertFalse(rendered.contains("{maximum}"));
            assertFalse(rendered.contains("{path}"));
            assertFalse(rendered.contains("{value}"));
        }
    }

    @Test
    void usesBuffSettingsValidationOverrideAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String key = "validation.buff.price-range";
        Map<String, ?> placeholders = Map.of("path", "buffs.catalog.speed.base-price");

        assertEquals("buffs.catalog.speed.base-price 产生的价格超出次级货币单位范围",
                messages.plainText(key, placeholders));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(key, "自定义 Buff 价格校验: {path}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义 Buff 价格校验: buffs.catalog.speed.base-price",
                messages.plainText(key, placeholders));
    }

    @Test
    void rendersDonationCompensationTemplatesWithoutUnresolvedPlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("只有带玩家身份的捐款操作可以自动补偿",
                messages.plainText("validation.donation.compensation-operation"));
        assertEquals("Vault 自动补偿调用异常: boom",
                messages.plainText("diagnostic.donation.compensation-call-failure",
                        Map.of("detail", "boom")));
        assertEquals("玩家扣款已由自动补偿恢复",
                messages.plainText("log.donation.compensation-resolved"));

        for (String key : List.of(
                "validation.donation.compensation-operation",
                "diagnostic.donation.compensation-call-failure",
                "log.donation.compensation-resolved")) {
            String rendered = key.contains("call-failure")
                    ? messages.plainText(key, Map.of("detail", "boom"))
                    : messages.plainText(key);
            assertFalse(rendered.isBlank());
            assertFalse(rendered.contains("缺少消息配置"));
            assertFalse(rendered.contains("{detail}"));
        }
    }

    @Test
    void usesDonationCompensationOverrideAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        String key = "diagnostic.donation.compensation-call-failure";
        assertEquals("Vault 自动补偿调用异常: boom",
                messages.plainText(key, Map.of("detail", "boom")));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(key, "自定义捐款补偿异常: {detail}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义捐款补偿异常: boom",
                messages.plainText(key, Map.of("detail", "boom")));
    }

    @Test
    void rendersPluginMessagesDiagnosticsWithCompletePlaceholders() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("缺少必需的投票消息配置: dialog.votes.type-kick",
                messages.plainText("diagnostic.messages.required-vote-missing",
                        Map.of("key", "dialog.votes.type-kick")));
        assertEquals("缺少范围输入格式配置: dialog.tax.rate-format",
                messages.plainText("diagnostic.messages.range-format-missing",
                        Map.of("key", "dialog.tax.rate-format")));
        assertEquals("dialog.tax.rate-format 只支持 %s 占位符，不支持浮点格式",
                messages.plainText("diagnostic.messages.range-format-unsupported",
                        Map.of("key", "dialog.tax.rate-format")));
        assertEquals("dialog.buff.duration-format 必须恰好包含两个未转义的 %s 占位符",
                messages.plainText("diagnostic.messages.range-format-placeholder-count",
                        Map.of("key", "dialog.buff.duration-format")));
    }

    @Test
    void usesConfiguredMissingMessageAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());

        assertEquals("&c缺少消息配置: missing.key", messages.rawText("missing.key"));
        assertEquals("§c缺少消息配置: missing.key", messages.text("missing.key"));

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("system.missing-message", "&e自定义缺失消息: {key}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("§e自定义缺失消息: missing.key", messages.text("missing.key"));
    }

    @Test
    void reportsMissingRequiredMessageThroughConfiguredDiagnostic() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("dialog.votes.type-kick", "");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new PluginMessages(temporaryDirectory.toFile()));

        assertEquals("缺少必需的投票消息配置: dialog.votes.type-kick", exception.getMessage());
    }

    @Test
    void reportsMissingRangeFormatThroughConfiguredDiagnostic() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("dialog.tax.rate-format", "");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new PluginMessages(temporaryDirectory.toFile()));

        assertEquals("缺少范围输入格式配置: dialog.tax.rate-format", exception.getMessage());
    }

    @Test
    void rejectsInvalidRangePlaceholders() throws Exception {
        assertInvalidRangeFormat("dialog.tax.rate-format", "&f%s: %f%%");
        assertInvalidRangeFormat("dialog.buff.duration-format", "&f%s: %.0f 周");
        assertInvalidRangeFormat("dialog.buff.duration-format", "&f%s 周");
        assertInvalidRangeFormat("dialog.buff.intensity-format", "&f%s: 等级 %s（%s）");
    }

    @Test
    void acceptsTwoRangePlaceholdersAndEscapedPercents() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("dialog.tax.rate-format", "&f%% %s: %s%%");
        configuration.set("dialog.buff.duration-format", "&f%% %s: %s 周");
        configuration.set("dialog.buff.intensity-format", "&f%s: 等级 %s");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        assertDoesNotThrow(() -> new PluginMessages(temporaryDirectory.toFile()));
    }

    private void assertInvalidRangeFormat(String key, String format) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set(key, format);
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new PluginMessages(temporaryDirectory.toFile()));
        assertTrue(exception.getMessage().contains(key));
    }
}

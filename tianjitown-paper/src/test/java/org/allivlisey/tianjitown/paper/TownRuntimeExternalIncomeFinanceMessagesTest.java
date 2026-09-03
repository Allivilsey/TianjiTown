package cn.tianji.town.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TownRuntimeExternalIncomeFinanceMessagesTest {
    private static final List<String> MESSAGE_KEYS = List.of(
            "log.global-market-plus.income-tax-debit-failure",
            "log.global-market-plus.subsidy-settlement-failure-refunded",
            "log.global-market-plus.subsidy-settlement-failure-refund-failed",
            "log.jobs.income-tax-settlement-failure",
            "log.external-income-tax.ledger-write-failure",
            "log.settlement.balance-read-failure",
            "log.settlement.shortfall",
            "log.settlement.reconciliation-failure",
            "log.donation.operation-reason",
            "log.tax.rate-change-reason");

    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersExternalIncomeAndSettlementMessages() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.of(
                "source", "JOBS",
                "businessKey", "jobs:business-1",
                "detail", "Vault 余额不足",
                "external", "12.00",
                "required", "13.00");

        assertEquals("Jobs 收入税转入清算账户失败，已保留玩家原始收入: Vault 余额不足",
                messages.plainText("log.jobs.income-tax-settlement-failure", placeholders));
        assertEquals("GlobalMarketPlus 收入税扣取失败，未写入小镇账本: Vault 余额不足",
                messages.plainText("log.global-market-plus.income-tax-debit-failure",
                        placeholders));
        assertEquals("GlobalMarketPlus 税收服务器补贴入账失败，税款已返还玩家: Vault 余额不足",
                messages.plainText(
                        "log.global-market-plus.subsidy-settlement-failure-refunded", placeholders));
        assertEquals("GlobalMarketPlus 税收服务器补贴入账失败，税款返还玩家也失败: Vault 余额不足",
                messages.plainText(
                        "log.global-market-plus.subsidy-settlement-failure-refund-failed",
                        placeholders));
        assertEquals("JOBS 税款 jobs:business-1 已进入清算账户但账本暂未写入，将自动重试: Vault 余额不足",
                messages.plainText("log.external-income-tax.ledger-write-failure", placeholders));
        assertEquals("读取 Vault 清算账户失败: Vault 余额不足",
                messages.plainText("log.settlement.balance-read-failure", placeholders));
        assertEquals("清算账户少于小镇分账负债，所有小镇消费已锁定: 外部=12.00，应有=13.00",
                messages.plainText("log.settlement.shortfall", placeholders));
        assertEquals("清算账户对账失败: Vault 余额不足",
                messages.plainText("log.settlement.reconciliation-failure", placeholders));
        assertEquals("成员捐款", messages.plainText("log.donation.operation-reason"));
        assertEquals("镇长通过公共资金界面修改",
                messages.plainText("log.tax.rate-change-reason"));

        for (String key : MESSAGE_KEYS) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void usesExternalIncomeAndFinanceOverridesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("log.jobs.income-tax-settlement-failure",
                "自定义 Jobs 结算失败: {detail}");
        configuration.set("log.global-market-plus.subsidy-settlement-failure-refunded",
                "自定义补贴失败且已退款: {detail}");
        configuration.set("log.external-income-tax.ledger-write-failure",
                "自定义账本重试: {source}/{businessKey}/{detail}");
        configuration.set("log.settlement.shortfall",
                "自定义清算短款: {external}/{required}");
        configuration.set("log.donation.operation-reason", "自定义捐款原因");
        configuration.set("log.tax.rate-change-reason", "自定义税率修改原因");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义 Jobs 结算失败: Vault 异常",
                messages.plainText("log.jobs.income-tax-settlement-failure",
                        Map.of("detail", "Vault 异常")));
        assertEquals("自定义补贴失败且已退款: Vault 异常",
                messages.plainText("log.global-market-plus.subsidy-settlement-failure-refunded",
                        Map.of("detail", "Vault 异常")));
        assertEquals("自定义账本重试: JOBS/jobs:business-2/SQLite 不可用",
                messages.plainText("log.external-income-tax.ledger-write-failure",
                        Map.of("source", "JOBS", "businessKey", "jobs:business-2",
                                "detail", "SQLite 不可用")));
        assertEquals("自定义清算短款: 12.00/13.00",
                messages.plainText("log.settlement.shortfall",
                        Map.of("external", "12.00", "required", "13.00")));
        assertEquals("自定义捐款原因", messages.plainText("log.donation.operation-reason"));
        assertEquals("自定义税率修改原因",
                messages.plainText("log.tax.rate-change-reason"));
    }
}

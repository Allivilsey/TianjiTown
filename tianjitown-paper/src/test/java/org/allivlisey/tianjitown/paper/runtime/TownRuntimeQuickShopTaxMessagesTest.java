package org.allivlisey.tianjitown.paper.runtime;
import org.allivlisey.tianjitown.paper.message.PluginMessages;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TownRuntimeQuickShopTaxMessagesTest {
    private static final List<String> QUICK_SHOP_TAX_KEYS = List.of(
            "log.quick-shop.subsidy-quota-exhausted",
            "log.quick-shop.subsidy-settlement-failure",
            "log.quick-shop.tax-ledger-write-failure");

    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersQuickShopTaxRuntimeMessages() {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        Map<String, ?> placeholders = Map.of(
                "businessKey", "quickshop:business-1",
                "detail", "Vault 余额不足");

        assertEquals("本周期补贴额度已用完",
                messages.plainText("log.quick-shop.subsidy-quota-exhausted"));
        assertEquals("QuickShop 税收服务器补贴入账失败，税款账本暂不写入: Vault 余额不足",
                messages.plainText("log.quick-shop.subsidy-settlement-failure", placeholders));
        assertEquals("QuickShop 税款 quickshop:business-1 已进入清算账户但账本暂未写入，将自动重试: Vault 余额不足",
                messages.plainText("log.quick-shop.tax-ledger-write-failure", placeholders));

        for (String key : QUICK_SHOP_TAX_KEYS) {
            String rendered = messages.plainText(key, placeholders);
            assertFalse(rendered.isBlank(), key);
            assertFalse(rendered.contains("缺少消息配置"), key);
            assertFalse(rendered.contains("{"), key);
        }
    }

    @Test
    void usesQuickShopTaxOverridesAfterMessagesReload() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("log.quick-shop.subsidy-quota-exhausted", "自定义补贴额度状态");
        configuration.set("log.quick-shop.subsidy-settlement-failure", "自定义补贴失败: {detail}");
        configuration.set("log.quick-shop.tax-ledger-write-failure",
                "自定义账本重试: {businessKey}/{detail}");
        configuration.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义补贴额度状态",
                messages.plainText("log.quick-shop.subsidy-quota-exhausted"));
        assertEquals("自定义补贴失败: Vault 不可用",
                messages.plainText("log.quick-shop.subsidy-settlement-failure",
                        Map.of("detail", "Vault 不可用")));
        assertEquals("自定义账本重试: quickshop:business-2/SQLite 不可用",
                messages.plainText("log.quick-shop.tax-ledger-write-failure",
                        Map.of("businessKey", "quickshop:business-2",
                                "detail", "SQLite 不可用")));
    }
}

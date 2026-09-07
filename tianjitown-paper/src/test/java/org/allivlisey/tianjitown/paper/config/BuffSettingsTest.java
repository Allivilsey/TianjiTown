package org.allivlisey.tianjitown.paper.config;
import org.allivlisey.tianjitown.paper.message.PluginMessages;

import org.allivlisey.tianjitown.core.consumption.BuffDefinition;
import org.allivlisey.tianjitown.core.town.MemberRole;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuffSettingsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void bundledCatalogLoadsWithResolvedLabelsAndPurchasablePrices() throws Exception {
        try (var reader = new java.io.InputStreamReader(
                java.util.Objects.requireNonNull(getClass().getResourceAsStream("/config.yml")),
                java.nio.charset.StandardCharsets.UTF_8)) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(reader);
            BuffSettings settings = BuffSettings.load(config, messages());
            assertFalse(config.contains("schema-version"));
            assertFalse(settings.buffs().isEmpty());
            assertEquals(config.getConfigurationSection("buffs.catalog").getKeys(false),
                    settings.buffs().keySet());
            for (BuffDefinition buff : settings.buffs().values()) {
                assertTrue(org.allivlisey.tianjitown.core.consumption.BuffPricing
                        .weeklyPrice(buff, 1, 1, 2).positive(), buff.key());
                assertTrue(buff.maximumLevel() > 0, buff.key());
                assertEquals(BuffDefinition.EffectKind.ATTRIBUTE, buff.effectKind());
                assertFalse(buff.displayName().isBlank());
                assertFalse(buff.displayName().startsWith("dialog."));
            }
        }
    }

    @Test
    void loadsConfiguredBuffCatalog() throws Exception {
        YamlConfiguration config = configuration("speed", "100.00");
        PluginMessages messages = messages();

        BuffSettings settings = BuffSettings.load(config, messages);

        assertTrue(settings.buffShopEnabled());
        assertEquals("速度", settings.label("speed"));
        assertEquals(new java.math.BigDecimal("100.00"), settings.requireBuff("speed").basePrice());
        assertEquals(2, settings.requireBuff("speed").maximumLevel());
        assertEquals("速度", settings.requireBuff("speed").displayName());
        assertEquals(BuffDefinition.EffectKind.ATTRIBUTE,
                settings.requireBuff("speed").effectKind());
        assertEquals("minecraft:movement_speed", settings.requireBuff("speed").effectKey());
        assertEquals("ADD_SCALAR", settings.requireBuff("speed").effectOperation());
        assertEquals(0.2D, settings.requireBuff("speed").amountPerLevel());
        assertTrue(settings.requireBuff("speed").allowsRole(MemberRole.MAYOR));
        assertTrue(settings.requireBuff("speed").allowsRole(MemberRole.DEPUTY_MAYOR));
        assertFalse(settings.requireBuff("speed").allowsRole(MemberRole.MEMBER));
    }

    @Test
    void rejectsInvalidCatalogValues() throws Exception {
        PluginMessages messages = messages();
        assertThrows(IllegalArgumentException.class,
                () -> BuffSettings.load(configuration("speed", "0"),
                        messages));
        YamlConfiguration negativeEffect = configuration("speed", "100.00");
        negativeEffect.set("buffs.catalog.speed.amount-per-level", -0.2D);
        assertThrows(IllegalArgumentException.class,
                () -> BuffSettings.load(negativeEffect, messages));
    }

    @Test
    void preservesLargeDecimalPricesWithoutExpandingScientificNotation() throws Exception {
        PluginMessages messages = messages();
        BuffSettings hugePrice = BuffSettings.load(
                configuration("speed", "1E1000000"), messages);
        assertEquals(0, hugePrice.requireBuff("speed").basePrice()
                .compareTo(new java.math.BigDecimal("1E1000000")));
    }

    @Test
    void rejectsPotionCatalog() throws Exception {
        YamlConfiguration config = configuration("speed", "100.00");
        config.set("buffs.catalog.speed.effect-kind", "POTION");
        assertThrows(IllegalArgumentException.class, () -> BuffSettings.load(config, messages()));
    }

    @Test
    void allowsMissingOrEmptyBuffCatalog() throws Exception {
        PluginMessages messages = messages();
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                buffs:
                  shop-enabled: false
                """);

        BuffSettings missing = BuffSettings.load(config, messages);
        assertTrue(missing.buffs().isEmpty());

        config.loadFromString("""
                buffs:
                  shop-enabled: false
                  catalog: {}
                """);
        BuffSettings empty = BuffSettings.load(config, messages);
        assertTrue(empty.buffs().isEmpty());
    }

    @Test
    void resolvesUnknownBuffUsingCurrentMessagesAfterReload() throws Exception {
        PluginMessages messages = messages();
        BuffSettings settings = BuffSettings.load(
                configuration("speed", "100.00"), messages);

        IllegalArgumentException initial = assertThrows(IllegalArgumentException.class,
                () -> settings.requireBuff("missing"));
        assertEquals("未知 Buff: missing", initial.getMessage());

        YamlConfiguration override = new YamlConfiguration();
        override.set("validation.buff.unknown", "自定义 Buff 校验: {key}");
        override.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        IllegalArgumentException reloaded = assertThrows(IllegalArgumentException.class,
                () -> settings.requireBuff("missing"));
        assertEquals("自定义 Buff 校验: missing", reloaded.getMessage());
    }

    @Test
    void resolvesBuffLabelUsingCurrentMessagesAfterReload() throws Exception {
        PluginMessages messages = messages();
        BuffSettings settings = BuffSettings.load(
                configuration("speed", "100.00"), messages);

        assertEquals("速度", settings.label("speed"));

        YamlConfiguration override = new YamlConfiguration();
        override.set("dialog.buff.labels.speed", "&d疾速");
        override.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("疾速", settings.label("speed"));
    }

    @Test
    void requiresMessageLabelForEveryCatalogKey() throws Exception {
        PluginMessages messages = messages();

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> BuffSettings.load(configuration("custom", "100.00"),
                        messages));
        assertEquals("Buff custom 缺少显示标签，请在 messages.yml 添加 dialog.buff.labels.custom",
                missing.getMessage());
        assertFalse(missing.getMessage().contains("缺少消息配置"));

        YamlConfiguration override = new YamlConfiguration();
        override.set("dialog.buff.labels.custom", "自定义增益");
        override.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        BuffSettings settings = BuffSettings.load(
                configuration("custom", "100.00"), messages);
        assertEquals("自定义增益", settings.label("custom"));
        assertEquals("dialog.buff.labels.custom", BuffSettings.labelMessageKey("custom"));
    }

    private PluginMessages messages() {
        return new PluginMessages(temporaryDirectory.toFile());
    }

    private static YamlConfiguration configuration(String buffKey, String basePrice) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString("""
                buffs:
                  shop-enabled: true
                  catalog:
                    %s:
                      effect-kind: ATTRIBUTE
                      effect-key: minecraft:movement_speed
                      operation: ADD_SCALAR
                      base-price: '%s'
                      maximum-level: 2
                      amount-per-level: 0.2
                """.formatted(buffKey, basePrice));
        return config;
    }
}

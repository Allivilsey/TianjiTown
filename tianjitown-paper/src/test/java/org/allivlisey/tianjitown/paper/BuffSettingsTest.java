package cn.tianji.town.paper;

import cn.tianji.town.core.consumption.BuffDefinition;
import cn.tianji.town.core.consumption.BuffStackingRule;
import cn.tianji.town.core.town.MemberRole;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuffSettingsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsConfiguredBuffCatalog() throws Exception {
        YamlConfiguration config = configuration("speed", "100.00", "MAYOR", "LEVEL_UP");
        PluginMessages messages = messages();

        BuffSettings settings = BuffSettings.load(config, 2, messages);

        assertTrue(settings.buffShopEnabled());
        assertTrue(messages.hasMessage("validation.buff.label-required"));
        assertTrue(messages.hasMessage("dialog.buff.labels.speed"));
        assertTrue(messages.hasMessage("dialog.buff.labels.health"));
        assertEquals("速度", settings.label("speed"));
        assertEquals("生命", messages.plainText("dialog.buff.labels.health"));
        assertEquals("速度", settings.requireBuff("speed").displayName());
        assertEquals(BuffDefinition.EffectKind.ATTRIBUTE,
                settings.requireBuff("speed").effectKind());
        assertEquals("minecraft:movement_speed", settings.requireBuff("speed").effectKey());
        assertEquals("ADD_SCALAR", settings.requireBuff("speed").effectOperation());
        assertEquals(0.2D, settings.requireBuff("speed").amountPerLevel());
        assertEquals(BuffStackingRule.LEVEL_UP,
                settings.requireBuff("speed").stackingRule());
        assertEquals(Set.of(MemberRole.MAYOR),
                settings.requireBuff("speed").purchasingRoles());
        assertEquals(BuffStackingRule.EXTEND,
                BuffSettings.load(configuration("speed", "100.00", "MAYOR", "EXTEND"),
                                2, messages)
                        .requireBuff("speed").stackingRule());
        assertEquals(BuffStackingRule.REFRESH,
                BuffSettings.load(configuration("speed", "100.00", "MAYOR", "REFRESH"),
                                2, messages)
                        .requireBuff("speed").stackingRule());
    }

    @Test
    void rejectsInvalidCatalogValues() throws Exception {
        PluginMessages messages = messages();
        assertThrows(IllegalArgumentException.class,
                () -> BuffSettings.load(configuration("speed", "100.00", "OFFICER",
                                "LEVEL_UP"), 2, messages));
        assertThrows(IllegalArgumentException.class,
                () -> BuffSettings.load(configuration("speed", "0", "MAYOR", "LEVEL_UP"),
                        2, messages));
        assertThrows(IllegalArgumentException.class,
                () -> new BuffDefinition("negative", "负数效果",
                        BuffDefinition.EffectKind.ATTRIBUTE, "minecraft:movement_speed",
                        "ADD_SCALAR", new java.math.BigDecimal("10.00"), 1,
                        BuffStackingRule.LEVEL_UP, -0.2D, Set.of(MemberRole.MAYOR)));
        IllegalArgumentException overflow = assertThrows(IllegalArgumentException.class,
                () -> BuffSettings.load(configuration("speed", "1E1000000", "MAYOR",
                                "LEVEL_UP"), 2, messages));
        assertTrue(overflow.getMessage().contains("次级货币单位范围"));
    }

    @Test
    void resolvesUnknownBuffUsingCurrentMessagesAfterReload() throws Exception {
        PluginMessages messages = messages();
        BuffSettings settings = BuffSettings.load(
                configuration("speed", "100.00", "MAYOR", "LEVEL_UP"), 2, messages);

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
                configuration("speed", "100.00", "MAYOR", "LEVEL_UP"), 2, messages);

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
                () -> BuffSettings.load(configuration("custom", "100.00", "MAYOR",
                                "LEVEL_UP"), 2, messages));
        assertEquals("Buff custom 缺少显示标签，请在 messages.yml 添加 dialog.buff.labels.custom",
                missing.getMessage());
        assertFalse(missing.getMessage().contains("缺少消息配置"));

        YamlConfiguration override = new YamlConfiguration();
        override.set("dialog.buff.labels.custom", "自定义增益");
        override.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        BuffSettings settings = BuffSettings.load(
                configuration("custom", "100.00", "MAYOR", "LEVEL_UP"), 2, messages);
        assertEquals("自定义增益", settings.label("custom"));
        assertEquals("dialog.buff.labels.custom", BuffSettings.labelMessageKey("custom"));
    }

    private PluginMessages messages() {
        return new PluginMessages(temporaryDirectory.toFile());
    }

    private static YamlConfiguration configuration(String buffKey, String basePrice,
                                                    String role, String stacking) throws Exception {
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
                      stacking: %s
                      amount-per-level: 0.2
                      purchasing-roles: [%s]
                """.formatted(buffKey, basePrice, stacking, role));
        return config;
    }
}

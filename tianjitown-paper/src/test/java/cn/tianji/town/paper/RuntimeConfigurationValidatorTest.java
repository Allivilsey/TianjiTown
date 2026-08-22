package cn.tianji.town.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConfigurationValidatorTest {
    @Test
    void acceptsDefaultConfigurationAndReturnsExactTimeouts() {
        RuntimeConfigurationValidator.DatabaseSettings settings =
                RuntimeConfigurationValidator.validate(configuration(),
                        world -> world.equals("world"));

        assertEquals(5_000, settings.connectionTimeoutMillis());
        assertEquals(5_000, settings.busyTimeoutMillis());
    }

    @Test
    void rejectsWrongScalarTypesInsteadOfUsingBukkitFallbacks() {
        for (Setting setting : List.of(
                new Setting("database.connection-timeout-ms", "5000"),
                new Setting("database.busy-timeout-ms", "5000"),
                new Setting("phase1.application.reservation-minutes", "60"),
                new Setting("phase2.voting.duration-hours", "72"),
                new Setting("phase3.tax.enabled", "true"),
                new Setting("phase4.buffs.shop-enabled", "true"))) {
            YamlConfiguration config = configuration();
            config.set(setting.path(), setting.value());

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> RuntimeConfigurationValidator.validate(config,
                            world -> world.equals("world")));
            assertTrue(exception.getMessage().contains(setting.path()), exception.getMessage());
        }
    }

    @Test
    void rejectsReferencesToWorldsThatAreNotLoaded() {
        YamlConfiguration beacon = configuration();
        beacon.set("phase5.beacon.allowed-worlds", List.of("missing_world"));
        IllegalArgumentException beaconFailure = assertThrows(IllegalArgumentException.class,
                () -> RuntimeConfigurationValidator.validate(beacon,
                        world -> world.equals("world")));
        assertTrue(beaconFailure.getMessage().contains("missing_world"));

        YamlConfiguration blacklist = configuration();
        LinkedHashMap<String, Object> area = new LinkedHashMap<>();
        blacklist.getMapList("phase1.site.blacklist").getFirst()
                .forEach((key, value) -> area.put(String.valueOf(key), value));
        area.put("world", "missing_world");
        blacklist.set("phase1.site.blacklist", List.of(area));
        IllegalArgumentException blacklistFailure = assertThrows(IllegalArgumentException.class,
                () -> RuntimeConfigurationValidator.validate(blacklist,
                        world -> world.equals("world")));
        assertTrue(blacklistFailure.getMessage().contains("missing_world"));
    }

    private static YamlConfiguration configuration() {
        try (InputStream stream = RuntimeConfigurationValidatorTest.class
                .getResourceAsStream("/config.yml")) {
            if (stream == null) {
                throw new AssertionError("config.yml 未进入测试类路径");
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream,
                    StandardCharsets.UTF_8));
        } catch (java.io.IOException exception) {
            throw new AssertionError("读取默认配置失败", exception);
        }
    }

    private record Setting(String path, Object value) {
    }
}

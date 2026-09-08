package org.allivlisey.tianjitown.paper.bonus;

import io.papermc.paper.registry.RegistryAccess;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.config.TownBonusSettings;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.storage.bonus.TownBonusRepository;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Map;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TownBeaconEffectsTest {
    @ParameterizedTest
    @ValueSource(strings = {"cancelled", "replaced", "owned", "signature"})
    @SuppressWarnings({"unchecked", "rawtypes"})
    void removesOnlySuccessfullyAppliedUnchangedEffects(String scenario) throws Exception {
        try (var accessMock = mockStatic(RegistryAccess.class); var bukkit = mockStatic(Bukkit.class)) {
            RegistryAccess access = (RegistryAccess) java.lang.reflect.Proxy.newProxyInstance(
                    RegistryAccess.class.getClassLoader(), new Class<?>[]{RegistryAccess.class},
                    (proxy, method, arguments) -> java.lang.reflect.Proxy.newProxyInstance(
                            Registry.class.getClassLoader(), new Class<?>[]{Registry.class},
                            (registry, operation, keys) -> {
                                if (operation.getName().equals("getOrThrow")) return mock(PotionEffectType.class);
                                return null;
                            }));
            accessMock.when(RegistryAccess::registryAccess).thenReturn(access);
            PotionEffectType type = PotionEffectType.SPEED;
            var plugin = mock(TianjiTownPlugin.class);
            var server = mock(Server.class);
            var player = mock(Player.class);
            UUID playerId = UUID.randomUUID();
            when(plugin.getServer()).thenReturn(server);
            when(server.getPlayer(playerId)).thenReturn(player);
            when(player.getUniqueId()).thenReturn(playerId);
            var settings = new TownBonusSettings.BeaconEnhancement(true, 100);
            var effects = new TownBeaconEffects(plugin, mock(TownRuntime.class),
                    mock(TownBonusRepository.class), settings, () -> null, () -> {});
            var keyType = Class.forName(TownBeaconEffects.class.getName() + "$PlayerEffectKey");
            var constructor = keyType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object key = constructor.newInstance(playerId, type);
            var apply = TownBeaconEffects.class.getDeclaredMethod("applyManagedEffects", Map.class,
                    TownBonusSettings.BeaconEnhancement.class);
            apply.setAccessible(true);
            PotionEffect original = new PotionEffect(type, 240, 0, true, true, true);
            when(player.getPotionEffect(type)).thenReturn(original);
            when(player.addPotionEffect(any())).thenReturn(!scenario.equals("cancelled"));
            apply.invoke(effects, Map.of(key, 0), settings);
            if (scenario.equals("replaced")) {
                var event = mock(EntityPotionEffectEvent.class);
                when(event.getEntity()).thenReturn(player);
                when(event.getModifiedType()).thenReturn(type);
                effects.onPotionEffectChange(event);
            } else if (scenario.equals("signature")) {
                when(player.getPotionEffect(type)).thenReturn(new PotionEffect(type, 100, 0, true, true, true));
            }
            effects.clearAll();
            verify(player, times(scenario.equals("owned") ? 1 : 0)).removePotionEffect(type);
        }
    }
}

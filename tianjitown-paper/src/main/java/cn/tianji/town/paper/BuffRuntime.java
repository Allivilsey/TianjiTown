package cn.tianji.town.paper;

import cn.tianji.town.core.consumption.BuffDefinition;
import cn.tianji.town.core.consumption.BuffDurationOption;
import cn.tianji.town.storage.commerce.CommerceRepository;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class BuffRuntime implements Listener {
    private final TianjiTownPlugin plugin;
    private final TownRuntime host;
    private final CommerceRepository repository;
    private final BuffSettings settings;
    private final NamespacedKey potionKeysKey;
    private final Map<UUID, AppliedEffects> appliedEffects = new HashMap<>();
    private final AtomicBoolean cleanupFailureLogged = new AtomicBoolean();

    BuffRuntime(TianjiTownPlugin plugin, TownRuntime host,
                     CommerceRepository repository, BuffSettings settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.host = Objects.requireNonNull(host, "host");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.potionKeysKey = new NamespacedKey(plugin, "buff_potion_keys");
        validateEffects();
    }

    CommerceRepository repository() {
        return repository;
    }

    BuffSettings settings() {
        return settings;
    }

    boolean buffShopEnabled() {
        return plugin.getConfig().getBoolean("phase4.buffs.shop-enabled",
                settings.buffShopEnabled());
    }

    void buyBuffAction(Player player, String key, BuffDurationOption duration,
                       Consumer<CommerceRepository.BuffPurchase> success,
                       Consumer<RuntimeException> failure) {
        if (!buffShopEnabled() || !host.consumptionEnabled()) {
            failure.accept(new IllegalStateException("公共 Buff 商店当前暂停新购买"));
            return;
        }
        try {
            BuffDefinition definition = settings.requireBuff(key);
            host.writeAction(player, () -> repository.purchaseBuff(player.getUniqueId(),
                            player.getName(), definition, duration, host.settlement().scale(),
                            "buff-purchase:" + UUID.randomUUID(), Instant.now()),
                    purchase -> verifyBuffPurchase(player, purchase, success, failure),
                    failure);
        } catch (RuntimeException exception) {
            failure.accept(exception);
        }
    }

    void buyBuffAction(Player player, String key, int weeks, int level,
                       Consumer<CommerceRepository.BuffPurchase> success,
                       Consumer<RuntimeException> failure) {
        if (!buffShopEnabled() || !host.consumptionEnabled()) {
            failure.accept(new IllegalStateException("公共 Buff 商店当前暂停新购买"));
            return;
        }
        try {
            BuffDefinition definition = settings.requireBuff(key);
            host.writeAction(player, () -> repository.purchaseBuff(player.getUniqueId(),
                            player.getName(), definition, weeks, level, host.settlement().scale(),
                            "buff-purchase:" + UUID.randomUUID(), Instant.now()),
                    purchase -> verifyBuffPurchase(player, purchase, success, failure), failure);
        } catch (RuntimeException exception) {
            failure.accept(exception);
        }
    }

    void cleanupExpired() {
        host.write(org.bukkit.Bukkit.getConsoleSender(), () -> repository.expireBuffs(Instant.now()),
                ignored -> refreshAllPlayers());
    }

    void refreshAllPlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            refreshPlayer(player);
        }
    }

    void refreshPlayer(Player player) {
        if (!player.isOnline()) {
            return;
        }
        host.read(player, () -> repository.activeBuffsForPlayer(player.getUniqueId(), Instant.now()),
                buffs -> {
            if (player.isOnline()) {
                try {
                    applyBuffs(player, buffs);
                } catch (RuntimeException | LinkageError exception) {
                    tryClearManagedEffects(player);
                    plugin.getLogger().severe("刷新玩家公共 Buff 失败 " + player.getUniqueId()
                            + ": " + safeMessage(exception));
                }
            }
        });
    }

    void clearAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            tryClearManagedEffects(player);
        }
        appliedEffects.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.runMain(() -> refreshPlayer(player));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.runMain(() -> refreshPlayer(event.getPlayer()));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        refreshPlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        tryClearManagedEffects(event.getPlayer());
        appliedEffects.remove(event.getPlayer().getUniqueId());
    }

    private void verifyBuffPurchase(Player player, CommerceRepository.BuffPurchase purchase,
                                    Consumer<CommerceRepository.BuffPurchase> success,
                                    Consumer<RuntimeException> failure) {
        host.readAction(player,
                () -> repository.activeBuffsForPlayer(player.getUniqueId(), Instant.now()), buffs -> {
            try {
                applyBuffs(player, buffs);
                refreshAllPlayers();
                success.accept(purchase);
            } catch (RuntimeException | LinkageError exception) {
                tryClearManagedEffects(player);
                host.writeAction(player,
                        () -> repository.refundActiveBuff(purchase.buff().buffId(), null,
                                "SYSTEM", "Buff 应用失败自动补偿: " + safeMessage(exception)),
                        refunded -> {
                    refreshAllPlayers();
                    failure.accept(new IllegalStateException(
                            "Buff 应用失败，已自动取消并退回公共资金: "
                                    + safeMessage(exception), exception));
                }, failure);
            }
        }, failure);
    }

    private void applyBuffs(Player player, List<CommerceRepository.ActiveBuff> buffs) {
        clearManagedEffects(player);
        Set<PotionEffectType> potions = new HashSet<>();
        Set<AttributeKey> attributes = new HashSet<>();
        Instant now = Instant.now();
        for (CommerceRepository.ActiveBuff buff : buffs) {
            if (!buff.expiresAt().isAfter(now)) {
                continue;
            }
            if (buff.effectKind() == BuffDefinition.EffectKind.POTION) {
                PotionEffectType type = requirePotion(buff.effectKey());
                long remainingTicks = Math.max(1, java.time.Duration.between(now,
                        buff.expiresAt()).toMillis() / 50);
                int ticks = (int) Math.min(Integer.MAX_VALUE, remainingTicks);
                int amplifier = Math.max(0, (int) Math.round(
                        buff.amountPerLevel() * buff.level()) - 1);
                player.addPotionEffect(new PotionEffect(type, ticks, amplifier,
                        true, false, true), true);
                potions.add(type);
            } else {
                Attribute attribute = requireAttribute(buff.effectKey());
                AttributeInstance instance = player.getAttribute(attribute);
                if (instance == null) {
                    throw new IllegalStateException("玩家缺少 Attribute: " + buff.effectKey());
                }
                NamespacedKey modifierKey = modifierKey(buff.buffKey());
                instance.removeModifier(modifierKey);
                AttributeModifier.Operation operation = AttributeModifier.Operation.valueOf(
                        buff.effectOperation());
                instance.addTransientModifier(new AttributeModifier(modifierKey,
                        buff.amountPerLevel() * buff.level(), operation));
                attributes.add(new AttributeKey(attribute, modifierKey));
            }
        }
        appliedEffects.put(player.getUniqueId(), new AppliedEffects(potions, attributes));
        if (potions.isEmpty()) {
            player.getPersistentDataContainer().remove(potionKeysKey);
        } else {
            player.getPersistentDataContainer().set(potionKeysKey, PersistentDataType.STRING,
                    potions.stream().map(type -> type.getKey().toString()).sorted()
                            .collect(java.util.stream.Collectors.joining("\n")));
        }
        double maximumHealth = Objects.requireNonNull(player.getAttribute(Attribute.MAX_HEALTH))
                .getValue();
        if (player.getHealth() > maximumHealth) {
            player.setHealth(maximumHealth);
        }
    }

    private void clearManagedEffects(Player player) {
        AppliedEffects previous = appliedEffects.get(player.getUniqueId());
        Set<PotionEffectType> potionTypes = new HashSet<>();
        if (previous != null) {
            potionTypes.addAll(previous.potions());
            for (AttributeKey key : previous.attributes()) {
                AttributeInstance instance = player.getAttribute(key.attribute());
                if (instance != null) {
                    instance.removeModifier(key.modifierKey());
                }
            }
        }
        String persistedPotions = player.getPersistentDataContainer().get(potionKeysKey,
                PersistentDataType.STRING);
        if (persistedPotions != null) {
            persistedPotions.lines().filter(value -> !value.isBlank())
                    .map(this::requirePotion).forEach(potionTypes::add);
        }
        potionTypes.forEach(player::removePotionEffect);
        player.getPersistentDataContainer().remove(potionKeysKey);
        // Attribute 使用本插件独占的稳定 key，可安全清理当前配置内的重载残留。
        for (BuffDefinition definition : settings.buffs().values()) {
            if (definition.effectKind() == BuffDefinition.EffectKind.ATTRIBUTE) {
                AttributeInstance instance = player.getAttribute(requireAttribute(
                        definition.effectKey()));
                if (instance != null) {
                    instance.removeModifier(modifierKey(definition.key()));
                }
            }
        }
    }

    private void tryClearManagedEffects(Player player) {
        try {
            clearManagedEffects(player);
        } catch (RuntimeException | LinkageError exception) {
            if (cleanupFailureLogged.compareAndSet(false, true)) {
                try {
                    plugin.getLogger().warning("清理玩家公共 Buff 时对象已失效: "
                            + safeMessage(exception) + "；同类后续错误将被抑制");
                } catch (RuntimeException | LinkageError ignored) {
                    // 停服期间记录器失效时继续完成其余清理。
                }
            }
        }
    }

    private void validateEffects() {
        Set<String> potionKeys = new HashSet<>();
        Set<String> attributeKeys = new HashSet<>();
        for (BuffDefinition definition : settings.buffs().values()) {
            if (definition.effectKind() == BuffDefinition.EffectKind.POTION) {
                requirePotion(definition.effectKey());
                if (!definition.effectOperation().equals("AMPLIFIER")) {
                    throw new IllegalArgumentException("Potion Buff 的 operation 必须为 AMPLIFIER: "
                            + definition.key());
                }
                if (!potionKeys.add(definition.effectKey())) {
                    throw new IllegalArgumentException("多个 Buff 不能使用同一个 Potion Effect: "
                            + definition.effectKey());
                }
            } else {
                requireAttribute(definition.effectKey());
                AttributeModifier.Operation.valueOf(definition.effectOperation());
                if (!attributeKeys.add(definition.effectKey())) {
                    throw new IllegalArgumentException("多个 Buff 不能使用同一个 Attribute: "
                            + definition.effectKey());
                }
            }
        }
    }

    private PotionEffectType requirePotion(String key) {
        NamespacedKey namespacedKey = NamespacedKey.fromString(key);
        PotionEffectType type = namespacedKey == null ? null : Registry.MOB_EFFECT.get(namespacedKey);
        if (type == null) {
            throw new IllegalArgumentException("无效 Potion Effect: " + key);
        }
        return type;
    }

    private Attribute requireAttribute(String key) {
        NamespacedKey namespacedKey = NamespacedKey.fromString(key);
        Attribute attribute = namespacedKey == null ? null : Registry.ATTRIBUTE.get(namespacedKey);
        if (attribute == null) {
            throw new IllegalArgumentException("无效 Attribute: " + key);
        }
        return attribute;
    }

    private NamespacedKey modifierKey(String buffKey) {
        return new NamespacedKey(plugin, "buff_" + buffKey);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    private record AttributeKey(Attribute attribute, NamespacedKey modifierKey) {
    }

    private record AppliedEffects(Set<PotionEffectType> potions, Set<AttributeKey> attributes) {
        AppliedEffects {
            potions = Set.copyOf(potions);
            attributes = Set.copyOf(attributes);
        }
    }
}

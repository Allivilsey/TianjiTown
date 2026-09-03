package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.core.consumption.BuffDefinition;
import org.allivlisey.tianjitown.core.consumption.BuffDurationOption;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
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
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
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
    private static final long REFRESH_RETRY_DELAY_TICKS = 20L * 5;
    private static final String BUFF_SHOP_PAUSED = "chat.buff.shop-paused";
    private static final String REFRESH_FAILURE = "log.buff.refresh-failure";
    private static final String REFRESH_CHECK_FAILURE = "log.buff.refresh-check-failure";
    private static final String REFUND_REASON = "log.buff.refund-reason";
    private static final String APPLICATION_FAILURE_REFUNDED =
            "chat.buff.application-failure-refunded";
    private static final String EXPIRATION_SCHEDULE_FAILURE =
            "log.buff.expiration-schedule-failure";
    private static final String EXPIRATION_CLEANUP_FAILURE =
            "log.buff.expiration-cleanup-failure";
    private static final String EXPIRATION_CANCEL_FAILURE =
            "log.buff.expiration-cancel-failure";
    private static final String MISSING_ATTRIBUTE = "diagnostic.buff.missing-attribute";
    private static final String ATTRIBUTE_REPAIR_MISSING =
            "log.buff.attribute-repair-missing";
    private static final String ATTRIBUTE_REPAIR_MISMATCH =
            "log.buff.attribute-repair-mismatch";
    private static final String MAX_HEALTH_MISSING = "diagnostic.buff.max-health-missing";
    private static final String MAX_HEALTH_INVALID = "diagnostic.buff.max-health-invalid";
    private static final String CLEANUP_INVALID_OBJECT = "log.buff.cleanup-invalid-object";

    private final TianjiTownPlugin plugin;
    private final TownRuntime host;
    private final CommerceRepository repository;
    private final BuffSettings settings;
    private final NamespacedKey potionKeysKey;
    private final Map<UUID, AppliedEffects> appliedEffects = new HashMap<>();
    private final Map<UUID, Long> refreshGenerations = new HashMap<>();
    private final Map<UUID, BukkitTask> expirationTasks = new HashMap<>();
    private final Map<UUID, Instant> expirationDeadlines = new HashMap<>();
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
        return plugin.getConfig().getBoolean("buffs.shop-enabled",
                settings.buffShopEnabled());
    }

    void buyBuffAction(Player player, String key, BuffDurationOption duration,
                       Consumer<CommerceRepository.BuffPurchase> success,
                       Consumer<RuntimeException> failure) {
        if (!buffShopEnabled() || !host.consumptionEnabled()) {
            failure.accept(new IllegalStateException(plugin.messages().plainText(BUFF_SHOP_PAUSED)));
            return;
        }
        try {
            BuffDefinition definition = settings.requireBuff(key);
            host.writeAction(player, () -> repository.purchaseBuff(player.getUniqueId(),
                            player.getName(), definition, settings.label(key), duration,
                            host.settlement().scale(),
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
            failure.accept(new IllegalStateException(plugin.messages().plainText(BUFF_SHOP_PAUSED)));
            return;
        }
        try {
            BuffDefinition definition = settings.requireBuff(key);
            host.writeAction(player, () -> repository.purchaseBuff(player.getUniqueId(),
                            player.getName(), definition, settings.label(key), weeks, level,
                            host.settlement().scale(),
                            "buff-purchase:" + UUID.randomUUID(), Instant.now()),
                    purchase -> verifyBuffPurchase(player, purchase, success, failure), failure);
        } catch (RuntimeException exception) {
            failure.accept(exception);
        }
    }

    void refreshAllPlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            refreshPlayer(player);
        }
    }

    void refreshPlayer(Player player) {
        refreshPlayer(player, false, false);
    }

    private void refreshPlayer(Player player, boolean expireRecords,
                                boolean normalizeRespawnHealth) {
        if (!player.isOnline()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        long refreshGeneration = nextRefreshGeneration(playerId);
        Consumer<List<CommerceRepository.ActiveBuff>> apply = buffs -> {
            if (player.isOnline() && isCurrentRefresh(playerId, refreshGeneration)) {
                try {
                    applyBuffs(player, buffs, normalizeRespawnHealth);
                    scheduleExpiration(playerId, buffs, refreshGeneration);
                } catch (RuntimeException | LinkageError exception) {
                    plugin.getLogger().severe(plugin.messages().plainText(REFRESH_FAILURE,
                            Map.of("player", player.getUniqueId(),
                                    "detail", safeText(safeMessage(exception)))));
                    retryRefresh(player, playerId, refreshGeneration, expireRecords, exception);
                }
            }
        };
        if (expireRecords) {
            host.writeAction(player, () -> {
                Instant now = Instant.now();
                repository.expireBuffsForPlayer(playerId, now);
                return repository.activeBuffsForPlayer(playerId, now);
            }, apply, exception -> retryRefresh(player, playerId, refreshGeneration,
                    true, exception));
        } else {
            host.readAction(player,
                    () -> repository.activeBuffsForPlayer(playerId, Instant.now()), apply,
                    exception -> retryRefresh(player, playerId, refreshGeneration,
                            false, exception));
        }
    }

    private void retryRefresh(Player player, UUID playerId, long refreshGeneration,
                              boolean expireRecords, Throwable exception) {
        if (!isCurrentRefresh(playerId, refreshGeneration)) {
            return;
        }
        plugin.getLogger().warning(plugin.messages().plainText(REFRESH_CHECK_FAILURE,
                Map.of("player", playerId, "detail", safeText(safeMessage(exception)))));
        if (!plugin.runMainLater(() -> {
            if (!isCurrentRefresh(playerId, refreshGeneration)) {
                return;
            }
            if (!player.isOnline()) {
                refreshGenerations.remove(playerId, refreshGeneration);
                return;
            }
            refreshPlayer(player, expireRecords, false);
        }, REFRESH_RETRY_DELAY_TICKS)) {
            refreshGenerations.remove(playerId, refreshGeneration);
        }
    }

    void clearAll() {
        clearExpirationTasks();
        expirationDeadlines.clear();
        refreshGenerations.clear();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            tryClearManagedEffects(player);
        }
        appliedEffects.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // 登录时先收尾停服期间已到期的记录，再校验并恢复玩家效果。
        plugin.runMain(() -> refreshPlayer(player, true, false));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        // 重生后重新校验 Buff，避免已到期效果残留。
        plugin.runMain(() -> refreshPlayer(event.getPlayer(), true, true));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        refreshPlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        tryClearManagedEffects(event.getPlayer());
        appliedEffects.remove(playerId);
        if (!expirationTasks.containsKey(playerId)) {
            expirationDeadlines.remove(playerId);
            refreshGenerations.remove(playerId);
        }
    }

    private void verifyBuffPurchase(Player player, CommerceRepository.BuffPurchase purchase,
                                    Consumer<CommerceRepository.BuffPurchase> success,
                                    Consumer<RuntimeException> failure) {
        UUID playerId = player.getUniqueId();
        long refreshGeneration = nextRefreshGeneration(playerId);
        ensureExpirationAt(playerId, refreshGeneration, purchase.buff().expiresAt());
        host.readAction(player,
                () -> repository.activeBuffsForPlayer(playerId, Instant.now()), buffs -> {
            if (!player.isOnline() || !isCurrentRefresh(playerId, refreshGeneration)) {
                refreshAllPlayers();
                success.accept(purchase);
                return;
            }
            try {
                applyBuffs(player, buffs, false);
                scheduleExpiration(playerId, buffs, refreshGeneration);
                refreshAllPlayers();
                success.accept(purchase);
            } catch (RuntimeException | LinkageError exception) {
                host.writeAction(player,
                        () -> repository.refundActiveBuff(purchase.buff().buffId(), null,
                                "SYSTEM", plugin.messages().plainText(REFUND_REASON,
                                        Map.of("detail", safeText(safeMessage(exception))))),
                        refunded -> {
                    refreshAllPlayers();
                    failure.accept(new IllegalStateException(
                            plugin.messages().plainText(APPLICATION_FAILURE_REFUNDED,
                                    Map.of("detail", safeText(safeMessage(exception)))), exception));
                        }, refundFailure -> {
                            retryRefresh(player, playerId, refreshGeneration, false, refundFailure);
                            failure.accept(refundFailure);
                        });
            }
        }, exception -> {
            retryRefresh(player, playerId, refreshGeneration, false, exception);
            failure.accept(exception);
        });
    }

    private long nextRefreshGeneration(UUID playerId) {
        Instant previousDeadline = expirationDeadlines.get(playerId);
        cancelExpirationTask(playerId);
        long current = refreshGenerations.getOrDefault(playerId, 0L);
        long next = current == Long.MAX_VALUE ? 1L : current + 1L;
        refreshGenerations.put(playerId, next);
        if (previousDeadline != null) {
            scheduleExpirationTask(playerId, next, previousDeadline);
        }
        return next;
    }

    private boolean isCurrentRefresh(UUID playerId, long generation) {
        return Objects.equals(refreshGenerations.get(playerId), generation);
    }

    private void scheduleExpiration(UUID playerId, List<CommerceRepository.ActiveBuff> buffs,
                                     long refreshGeneration) {
        if (!isCurrentRefresh(playerId, refreshGeneration)) {
            return;
        }
        cancelExpirationTask(playerId);
        expirationDeadlines.remove(playerId);
        Instant now = Instant.now();
        CommerceRepository.ActiveBuff next = buffs.stream()
                .filter(buff -> buff.expiresAt().isAfter(now))
                .min(Comparator.comparing(CommerceRepository.ActiveBuff::expiresAt))
                .orElse(null);
        if (next == null) {
            return;
        }
        if (!scheduleExpirationTask(playerId, refreshGeneration, next.expiresAt())) {
            expirationDeadlines.put(playerId, next.expiresAt());
        }
    }

    private void ensureExpirationAt(UUID playerId, long refreshGeneration, Instant deadline) {
        if (!isCurrentRefresh(playerId, refreshGeneration) || deadline == null) {
            return;
        }
        Instant currentDeadline = expirationDeadlines.get(playerId);
        if (currentDeadline != null && !currentDeadline.isAfter(deadline)) {
            return;
        }
        cancelExpirationTask(playerId);
        expirationDeadlines.remove(playerId);
        if (!scheduleExpirationTask(playerId, refreshGeneration, deadline)) {
            expirationDeadlines.put(playerId, deadline);
        }
    }

    private boolean scheduleExpirationTask(UUID playerId, long refreshGeneration,
                                           Instant deadline) {
        if (!isCurrentRefresh(playerId, refreshGeneration)) {
            return false;
        }
        long delayTicks = ticksUntil(Instant.now(), deadline);
        try {
            BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!isCurrentRefresh(playerId, refreshGeneration)) {
                    return;
                }
                expirationTasks.remove(playerId);
                expirationDeadlines.remove(playerId);
                try {
                    handleExpiration(playerId, refreshGeneration);
                } catch (RuntimeException | LinkageError exception) {
                    retryExpiration(playerId, refreshGeneration, exception);
                }
            }, delayTicks);
            expirationTasks.put(playerId, task);
            expirationDeadlines.put(playerId, deadline);
            return true;
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().warning(plugin.messages().plainText(EXPIRATION_SCHEDULE_FAILURE,
                    Map.of("player", playerId, "detail", safeText(safeMessage(exception)))));
            return false;
        }
    }

    private void handleExpiration(UUID playerId, long refreshGeneration) {
        if (!isCurrentRefresh(playerId, refreshGeneration)) {
            return;
        }
        host.writeAction(Bukkit.getConsoleSender(), () -> repository.expireBuffsForPlayer(playerId,
                        Instant.now()),
                ignored -> {
                    if (!isCurrentRefresh(playerId, refreshGeneration)) {
                        return;
                    }
                    Player player = Bukkit.getPlayer(playerId);
                    if (player == null || !player.isOnline()) {
                        refreshGenerations.remove(playerId, refreshGeneration);
                        return;
                    }
                    refreshPlayer(player);
                }, exception -> {
                    retryExpiration(playerId, refreshGeneration, exception);
                });
    }

    private void retryExpiration(UUID playerId, long refreshGeneration, Throwable exception) {
        if (!isCurrentRefresh(playerId, refreshGeneration)) {
            return;
        }
        plugin.getLogger().warning(plugin.messages().plainText(EXPIRATION_CLEANUP_FAILURE,
                Map.of("player", playerId, "detail", safeText(safeMessage(exception)))));
        Instant retryAt = Instant.now().plusSeconds(1);
        if (!scheduleExpirationTask(playerId, refreshGeneration, retryAt)) {
            expirationDeadlines.put(playerId, retryAt);
        }
    }

    private void cancelExpirationTask(UUID playerId) {
        BukkitTask task = expirationTasks.remove(playerId);
        if (task != null) {
            try {
                task.cancel();
            } catch (RuntimeException | LinkageError exception) {
                plugin.getLogger().warning(plugin.messages().plainText(EXPIRATION_CANCEL_FAILURE,
                        Map.of("player", playerId,
                                "detail", safeText(safeMessage(exception)))));
            }
        }
    }

    private void clearExpirationTasks() {
        for (UUID playerId : List.copyOf(expirationTasks.keySet())) {
            cancelExpirationTask(playerId);
        }
        expirationDeadlines.clear();
    }

    private static long ticksUntil(Instant now, Instant expiresAt) {
        long remainingMillis = Math.max(0L, Duration.between(now, expiresAt).toMillis());
        return Math.max(1L, (remainingMillis + 49L) / 50L);
    }

    private void applyBuffs(Player player, List<CommerceRepository.ActiveBuff> buffs,
                            boolean normalizeRespawnHealth) {
        double previousHealth = player.getHealth();
        Map<PotionEffectType, PotionEffect> desiredPotions = new HashMap<>();
        Map<AttributeKey, AttributeExpectation> desiredAttributes = new HashMap<>();
        Set<Attribute> desiredAttributeTypes = new HashSet<>();
        Set<AttributeKey> activeAttributeKeys = collectAttributeKeys(buffs);
        Instant now = Instant.now();
        try {
            for (CommerceRepository.ActiveBuff buff : buffs) {
                validateActiveBuff(buff);
                if (!buff.expiresAt().isAfter(now)) {
                    continue;
                }
                if (buff.effectKind() == BuffDefinition.EffectKind.POTION) {
                    PotionEffectType type = requirePotion(buff.effectKey());
                    long remainingTicks = Math.max(1, Duration.between(now,
                            buff.expiresAt()).toMillis() / 50);
                    int ticks = (int) Math.min(Integer.MAX_VALUE, remainingTicks);
                    long roundedStrength = Math.round(buff.amountPerLevel() * buff.level());
                    int amplifier = (int) Math.max(0L, Math.min(Integer.MAX_VALUE,
                            roundedStrength - 1L));
                    PotionEffect effect = new PotionEffect(type, ticks, amplifier,
                            true, false, true);
                    if (desiredPotions.put(type, effect) != null) {
                        throw new IllegalStateException("多个 Buff 不能使用同一个 Potion Effect: "
                                + buff.effectKey());
                    }
                } else {
                    Attribute attribute = requireAttribute(buff.effectKey());
                    if (!desiredAttributeTypes.add(attribute)) {
                        throw new IllegalStateException("多个生效 Buff 使用同一个 Attribute: "
                                + buff.effectKey());
                    }
                    AttributeModifier.Operation operation = AttributeModifier.Operation.valueOf(
                            buff.effectOperation());
                    double amount = buff.amountPerLevel() * buff.level();
                    if (!Double.isFinite(amount) || amount <= 0) {
                        throw new IllegalStateException("Buff Attribute 数值无效: "
                                + buff.buffKey());
                    }
                    AttributeKey key = new AttributeKey(attribute, modifierKey(buff.buffKey()));
                    if (desiredAttributes.put(key,
                            new AttributeExpectation(key, amount, operation)) != null) {
                        throw new IllegalStateException("重复的 Buff Attribute modifier: "
                                + buff.buffKey());
                    }
                }
            }

            reconcileAttributes(player, desiredAttributes);
            reconcilePotions(player, desiredPotions);
            appliedEffects.put(player.getUniqueId(), new AppliedEffects(
                    desiredPotions.keySet(), desiredAttributes.keySet()));
            updatePersistedPotions(player, desiredPotions.keySet());
            normalizeHealth(player, previousHealth, normalizeRespawnHealth);
        } catch (RuntimeException | LinkageError exception) {
            Set<AttributeKey> attemptedAttributes = new HashSet<>(activeAttributeKeys);
            attemptedAttributes.addAll(desiredAttributes.keySet());
            rollbackFailedApply(player, attemptedAttributes, desiredPotions.keySet(), exception);
            throw exception;
        }
    }

    private void validateActiveBuff(CommerceRepository.ActiveBuff buff) {
        if (buff == null) {
            throw new IllegalStateException("Buff 记录为空");
        }
        if (buff.expiresAt() == null) {
            throw new IllegalStateException("Buff 到期时间为空: " + buff.buffKey());
        }
        if (buff.buffKey() == null || buff.buffKey().isBlank()) {
            throw new IllegalStateException("Buff key 无效");
        }
        if (buff.level() < 1 || buff.level() > 255) {
            throw new IllegalStateException("Buff 等级无效: " + buff.buffKey());
        }
        if (buff.stackCount() < 1) {
            throw new IllegalStateException("Buff 层数无效: " + buff.buffKey());
        }
        if (buff.effectKind() == null || buff.effectKey() == null
                || buff.effectKey().isBlank() || buff.effectOperation() == null
                || buff.effectOperation().isBlank()) {
            throw new IllegalStateException("Buff 效果描述无效: " + buff.buffKey());
        }
        if (!Double.isFinite(buff.amountPerLevel()) || buff.amountPerLevel() <= 0) {
            throw new IllegalStateException("Buff 每级效果值无效: " + buff.buffKey());
        }
        double effectiveAmount = buff.amountPerLevel() * buff.level();
        if (!Double.isFinite(effectiveAmount) || effectiveAmount <= 0) {
            throw new IllegalStateException("Buff 生效效果值无效: " + buff.buffKey());
        }
        if (buff.effectKind() == BuffDefinition.EffectKind.POTION
                && !"AMPLIFIER".equals(buff.effectOperation())) {
            throw new IllegalStateException("Potion Buff 的 operation 无效: " + buff.buffKey());
        }
    }

    private Set<AttributeKey> collectAttributeKeys(List<CommerceRepository.ActiveBuff> buffs) {
        Set<AttributeKey> result = new HashSet<>();
        for (CommerceRepository.ActiveBuff buff : buffs) {
            if (buff == null || buff.effectKind() != BuffDefinition.EffectKind.ATTRIBUTE
                    || buff.buffKey() == null) {
                continue;
            }
            Attribute attribute = resolveAttribute(buff.effectKey());
            if (attribute == null) {
                continue;
            }
            try {
                result.add(new AttributeKey(attribute, modifierKey(buff.buffKey())));
            } catch (RuntimeException ignored) {
                // 数据库中的非法 Buff key 没有可用的 modifier key。
            }
        }
        return result;
    }

    private void rollbackFailedApply(Player player, Set<AttributeKey> attributes,
                                     Set<PotionEffectType> potions, Throwable failure) {
        try {
            removeAttributeModifiers(player, attributes);
        } catch (RuntimeException | LinkageError rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
        for (PotionEffectType potion : potions) {
            try {
                player.removePotionEffect(potion);
            } catch (RuntimeException | LinkageError rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
        try {
            clearManagedEffects(player);
        } catch (RuntimeException | LinkageError rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
        appliedEffects.remove(player.getUniqueId());
    }

    private void reconcileAttributes(Player player,
                                     Map<AttributeKey, AttributeExpectation> desired) {
        AppliedEffects previous = appliedEffects.get(player.getUniqueId());
        Set<AttributeKey> managed = new HashSet<>();
        if (previous != null) {
            managed.addAll(previous.attributes());
        }
        for (BuffDefinition definition : settings.buffs().values()) {
            if (definition.effectKind() == BuffDefinition.EffectKind.ATTRIBUTE) {
                managed.add(new AttributeKey(requireAttribute(definition.effectKey()),
                        modifierKey(definition.key())));
            }
        }
        managed.removeAll(desired.keySet());
        removeAttributeModifiers(player, managed);

        for (AttributeExpectation expectation : desired.values()) {
            AttributeInstance instance = player.getAttribute(expectation.key().attribute());
            if (instance == null) {
                throw new IllegalStateException(plugin.messages().plainText(MISSING_ATTRIBUTE,
                        Map.of("attribute", safeText(String.valueOf(
                                expectation.key().attribute().getKey())))));
            }
            AttributeModifier current = instance.getModifier(expectation.key().modifierKey());
            boolean wasPreviouslyManaged = previous != null
                    && previous.attributes().contains(expectation.key());
            if (current != null
                    && current.getOperation() == expectation.operation()
                    && Double.compare(current.getAmount(), expectation.amount()) == 0) {
                continue;
            }
            if (wasPreviouslyManaged) {
                logAttributeRepair(player, expectation, current);
            }
            if (current != null) {
                instance.removeModifier(expectation.key().modifierKey());
            }
            instance.addTransientModifier(new AttributeModifier(expectation.key().modifierKey(),
                    expectation.amount(), expectation.operation()));
        }
    }

    private void logAttributeRepair(Player player, AttributeExpectation expectation,
                                    AttributeModifier current) {
        String key = current == null ? ATTRIBUTE_REPAIR_MISSING : ATTRIBUTE_REPAIR_MISMATCH;
        Map<String, ?> placeholders = current == null
                ? Map.of("player", player.getUniqueId(),
                        "attribute", safeText(String.valueOf(
                                expectation.key().attribute().getKey())),
                        "amount", expectation.amount(), "operation", expectation.operation())
                : Map.of("player", player.getUniqueId(),
                        "attribute", safeText(String.valueOf(
                                expectation.key().attribute().getKey())),
                        "actual", safeText("amount=" + current.getAmount()
                                + ", operation=" + current.getOperation()),
                        "amount", expectation.amount(), "operation", expectation.operation());
        plugin.getLogger().warning(plugin.messages().plainText(key, placeholders));
    }

    private void reconcilePotions(Player player, Map<PotionEffectType, PotionEffect> desired) {
        Set<PotionEffectType> managed = managedPotionTypes(player);
        managed.removeAll(desired.keySet());
        managed.forEach(player::removePotionEffect);
        desired.values().forEach(effect -> player.addPotionEffect(effect, true));
    }

    private Set<PotionEffectType> managedPotionTypes(Player player) {
        Set<PotionEffectType> result = new HashSet<>();
        AppliedEffects previous = appliedEffects.get(player.getUniqueId());
        if (previous != null) {
            result.addAll(previous.potions());
        }
        String persistedPotions = player.getPersistentDataContainer().get(potionKeysKey,
                PersistentDataType.STRING);
        if (persistedPotions != null) {
            persistedPotions.lines().filter(value -> !value.isBlank())
                    .map(this::resolvePotion).filter(Objects::nonNull).forEach(result::add);
        }
        for (BuffDefinition definition : settings.buffs().values()) {
            if (definition.effectKind() == BuffDefinition.EffectKind.POTION) {
                result.add(requirePotion(definition.effectKey()));
            }
        }
        return result;
    }

    private void updatePersistedPotions(Player player, Set<PotionEffectType> potions) {
        if (potions.isEmpty()) {
            player.getPersistentDataContainer().remove(potionKeysKey);
        } else {
            player.getPersistentDataContainer().set(potionKeysKey, PersistentDataType.STRING,
                    potions.stream().map(type -> type.getKey().toString()).sorted()
                            .collect(java.util.stream.Collectors.joining("\n")));
        }
    }

    private void normalizeHealth(Player player, double previousHealth,
                                 boolean normalizeRespawnHealth) {
        AttributeInstance instance = Objects.requireNonNull(
                player.getAttribute(Attribute.MAX_HEALTH),
                plugin.messages().plainText(MAX_HEALTH_MISSING));
        double maximumHealth = instance.getValue();
        if (!Double.isFinite(maximumHealth) || maximumHealth <= 0) {
            throw new IllegalStateException(plugin.messages().plainText(MAX_HEALTH_INVALID,
                    Map.of("amount", maximumHealth)));
        }
        double targetHealth = normalizeRespawnHealth || !Double.isFinite(previousHealth)
                ? maximumHealth : Math.max(0, Math.min(previousHealth, maximumHealth));
        if (Double.compare(player.getHealth(), targetHealth) != 0) {
            player.setHealth(targetHealth);
        }
    }

    private void clearManagedEffects(Player player) {
        AppliedEffects previous = appliedEffects.get(player.getUniqueId());
        Set<AttributeKey> attributes = new HashSet<>();
        if (previous != null) {
            attributes.addAll(previous.attributes());
        }
        for (BuffDefinition definition : settings.buffs().values()) {
            if (definition.effectKind() == BuffDefinition.EffectKind.ATTRIBUTE) {
                attributes.add(new AttributeKey(requireAttribute(definition.effectKey()),
                        modifierKey(definition.key())));
            }
        }
        removeAttributeModifiers(player, attributes);

        managedPotionTypes(player).forEach(player::removePotionEffect);
        player.getPersistentDataContainer().remove(potionKeysKey);
        clampCurrentHealth(player);
    }

    private void removeAttributeModifiers(Player player, Set<AttributeKey> attributes) {
        for (AttributeKey key : attributes) {
            AttributeInstance instance = player.getAttribute(key.attribute());
            if (instance != null && instance.getModifier(key.modifierKey()) != null) {
                instance.removeModifier(key.modifierKey());
            }
        }
    }

    private void clampCurrentHealth(Player player) {
        AttributeInstance instance = player.getAttribute(Attribute.MAX_HEALTH);
        if (instance == null) {
            return;
        }
        double maximumHealth = instance.getValue();
        if (player.getHealth() > maximumHealth) {
            player.setHealth(maximumHealth);
        }
    }

    private void tryClearManagedEffects(Player player) {
        try {
            clearManagedEffects(player);
        } catch (RuntimeException | LinkageError exception) {
            if (cleanupFailureLogged.compareAndSet(false, true)) {
                try {
                    plugin.getLogger().warning(plugin.messages().plainText(CLEANUP_INVALID_OBJECT,
                            Map.of("detail", safeText(safeMessage(exception)))));
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
        PotionEffectType type = resolvePotion(key);
        if (type == null) {
            throw new IllegalArgumentException("无效 Potion Effect: " + key);
        }
        return type;
    }

    private PotionEffectType resolvePotion(String key) {
        try {
            NamespacedKey namespacedKey = NamespacedKey.fromString(key);
            return namespacedKey == null ? null : Registry.MOB_EFFECT.get(namespacedKey);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Attribute requireAttribute(String key) {
        Attribute attribute = resolveAttribute(key);
        if (attribute == null) {
            throw new IllegalArgumentException("无效 Attribute: " + key);
        }
        return attribute;
    }

    private Attribute resolveAttribute(String key) {
        try {
            NamespacedKey namespacedKey = NamespacedKey.fromString(key);
            return namespacedKey == null ? null : Registry.ATTRIBUTE.get(namespacedKey);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private NamespacedKey modifierKey(String buffKey) {
        return new NamespacedKey(plugin, "buff_" + buffKey);
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    private static String safeText(String text) {
        return text == null ? "" : text.replace('&', '＆').replace('§', '�');
    }

    private record AttributeKey(Attribute attribute, NamespacedKey modifierKey) {
    }

    private record AttributeExpectation(AttributeKey key, double amount,
                                       AttributeModifier.Operation operation) {
    }

    private record AppliedEffects(Set<PotionEffectType> potions, Set<AttributeKey> attributes) {
        AppliedEffects {
            potions = Set.copyOf(potions);
            attributes = Set.copyOf(attributes);
        }
    }
}

package cn.tianji.town.paper;

import cn.tianji.town.core.consumption.BuffDefinition;
import cn.tianji.town.storage.phase4.PhaseFourRepository;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class PhaseFourRuntime implements Listener {
    private final TianjiTownPlugin plugin;
    private final PhaseOneRuntime host;
    private final PhaseFourRepository repository;
    private final PhaseFourSettings settings;
    private final NamespacedKey orderKey;
    private final NamespacedKey claimKey;
    private final NamespacedKey potionKeysKey;
    private final Map<UUID, AppliedEffects> appliedEffects = new HashMap<>();

    PhaseFourRuntime(TianjiTownPlugin plugin, PhaseOneRuntime host,
                     PhaseFourRepository repository, PhaseFourSettings settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.host = Objects.requireNonNull(host, "host");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.orderKey = new NamespacedKey(plugin, "resource_order");
        this.claimKey = new NamespacedKey(plugin, "resource_claim");
        this.potionKeysKey = new NamespacedKey(plugin, "buff_potion_keys");
        validateEffects();
    }

    PhaseFourRepository repository() {
        return repository;
    }

    PhaseFourSettings settings() {
        return settings;
    }

    boolean buffShopEnabled() {
        return plugin.getConfig().getBoolean("phase4.buffs.shop-enabled",
                settings.buffShopEnabled());
    }

    void buyBuff(Player player, String key) {
        if (!buffShopEnabled() || !host.consumptionEnabled()) {
            player.sendMessage("§c公共 Buff 商店当前暂停新购买。");
            return;
        }
        BuffDefinition definition = settings.requireBuff(key);
        host.write(player, () -> repository.purchaseBuff(player.getUniqueId(), player.getName(),
                definition, host.settlement().scale(), "buff-purchase:" + UUID.randomUUID(),
                Instant.now()), purchase -> {
            verifyBuffPurchase(player, definition, purchase);
        });
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
                } catch (RuntimeException exception) {
                    clearManagedEffects(player);
                    plugin.getLogger().severe("刷新玩家公共 Buff 失败 " + player.getUniqueId()
                            + ": " + safeMessage(exception));
                }
            }
        });
    }

    void clearAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            clearManagedEffects(player);
        }
        appliedEffects.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            recoverTaggedClaims(player);
            refreshPlayer(player);
        });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> refreshPlayer(event.getPlayer()));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        refreshPlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearManagedEffects(event.getPlayer());
        appliedEffects.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isProtected(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c该物品正在确认资源订单，暂时不能丢弃。");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (isProtected(event.getCurrentItem()) || isProtected(event.getCursor())
                || event.getHotbarButton() >= 0 && isProtected(
                event.getWhoClicked().getInventory().getItem(event.getHotbarButton()))) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                player.sendMessage("§c该物品正在确认资源订单，暂时不能移动。");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (isProtected(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (isProtected(event.getItem())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c该物品正在确认资源订单，暂时不能使用。");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isProtected(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isProtected(event.getMainHandItem()) || isProtected(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        boolean protectedItems = java.util.Arrays.stream(event.getPlayer().getInventory()
                .getStorageContents()).anyMatch(this::isProtected);
        if (protectedItems) {
            // 订单确认窗口内优先保护所有背包内容，避免已扣款物品因死亡丢失。
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.getPlayer().sendMessage("§e资源订单正在确认，本次死亡已临时保留背包。");
        }
    }

    private void verifyBuffPurchase(Player player, BuffDefinition definition,
                                    PhaseFourRepository.BuffPurchase purchase) {
        host.read(player, () -> repository.activeBuffsForPlayer(player.getUniqueId(), Instant.now()),
                buffs -> {
            try {
                applyBuffs(player, buffs);
                player.sendMessage("§a已购买 " + definition.displayName() + "，等级 "
                        + purchase.buff().level() + "，到期时间 "
                        + purchase.buff().expiresAt() + "；公共余额 "
                        + host.money(purchase.balanceAfterMinor()));
                refreshAllPlayers();
                TownUiController ui = plugin.townUi();
                if (ui != null) {
                    ui.openBuffShop(player);
                }
            } catch (RuntimeException exception) {
                clearManagedEffects(player);
                host.write(player, () -> repository.refundActiveBuff(purchase.buff().buffId(),
                        null, "SYSTEM", "Buff 应用失败自动补偿: " + safeMessage(exception)),
                        refunded -> {
                    player.sendMessage("§cBuff 应用失败，已自动取消并退回公共资金。原因: "
                            + safeMessage(exception));
                    refreshAllPlayers();
                });
            }
        });
    }

    private void deliverReservedOrder(Player player, PhaseFourRepository.ResourceOrder order,
                                      Material material) {
        List<ItemStack> stacks = taggedStacks(material, order.quantity(), order.orderId(),
                Objects.requireNonNull(order.claimToken(), "claimToken"));
        Map<Integer, ItemStack> leftovers = player.getInventory()
                .addItem(stacks.toArray(ItemStack[]::new));
        if (!leftovers.isEmpty()) {
            clearTaggedItems(player, order.orderId(), order.claimToken());
            releaseClaim(player, order, "交付时背包容量发生变化");
            player.sendMessage("§c交付时背包容量发生变化，订单仍保持待领取状态。");
            return;
        }
        host.write(player, () -> repository.completeClaim(order.orderId(), player.getUniqueId(),
                order.claimToken(), Instant.now()), completed -> {
            clearTaggedItems(player, completed.orderId(), completed.claimToken());
            player.sendMessage("§a已领取 " + completed.resourceName() + " x"
                    + completed.quantity() + "。");
        });
    }

    private void recoverTaggedClaims(Player player) {
        Map<ClaimMarker, Integer> marked = new HashMap<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            ClaimMarker marker = marker(item);
            if (marker != null) {
                marked.merge(marker, item.getAmount(), Math::addExact);
            }
        }
        for (Map.Entry<ClaimMarker, Integer> entry : marked.entrySet()) {
            ClaimMarker marker = entry.getKey();
            host.write(player, () -> repository.reserveClaim(marker.orderId(),
                    player.getUniqueId(), Instant.now()), order -> {
                if (!Objects.equals(order.claimToken(), marker.claimToken())) {
                    player.sendMessage("§c订单恢复标记不匹配，物品保持锁定，请联系管理员。order="
                            + order.orderId());
                    return;
                }
                if (order.status().equals("CLAIMED")) {
                    clearTaggedItems(player, marker.orderId(), marker.claimToken());
                    return;
                }
                if (entry.getValue() != order.quantity()) {
                    player.sendMessage("§c订单恢复数量异常，物品保持锁定，请联系管理员。order="
                            + order.orderId());
                    return;
                }
                completeMarkedClaim(player, order);
            });
        }
    }

    private void completeMarkedClaim(Player player, PhaseFourRepository.ResourceOrder order) {
        host.write(player, () -> repository.completeClaim(order.orderId(), player.getUniqueId(),
                order.claimToken(), Instant.now()), completed -> {
            clearTaggedItems(player, completed.orderId(), completed.claimToken());
            player.sendMessage("§a已恢复并确认资源订单 " + completed.orderId() + "。");
        });
    }

    private void releaseClaim(Player player, PhaseFourRepository.ResourceOrder order,
                              String error) {
        host.write(player, () -> repository.releaseClaim(order.orderId(), player.getUniqueId(),
                order.claimToken(), error), ignored -> {
        });
    }

    private void markRefundRequired(Player player, PhaseFourRepository.ResourceOrder order,
                                    String error) {
        host.write(player, () -> {
            if (order.status().equals("CLAIMING")) {
                repository.releaseClaim(order.orderId(), player.getUniqueId(), order.claimToken(),
                        error);
            }
            return repository.requireOrderRefund(order.orderId(), error);
        }, ignored -> player.sendMessage("§c订单无法交付，已标记为待管理员退款: " + error));
    }

    private void applyBuffs(Player player, List<PhaseFourRepository.ActiveBuff> buffs) {
        clearManagedEffects(player);
        Set<PotionEffectType> potions = new HashSet<>();
        Set<AttributeKey> attributes = new HashSet<>();
        Instant now = Instant.now();
        for (PhaseFourRepository.ActiveBuff buff : buffs) {
            if (!buff.allowsWorld(player.getWorld().getName()) || !buff.expiresAt().isAfter(now)) {
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

    private boolean hasCapacity(Player player, Material material, int quantity) {
        int needed = (quantity + material.getMaxStackSize() - 1) / material.getMaxStackSize();
        int empty = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType().isAir()) {
                empty++;
            }
        }
        return empty >= needed;
    }

    private List<ItemStack> taggedStacks(Material material, int quantity, UUID orderId,
                                         UUID claimToken) {
        List<ItemStack> result = new ArrayList<>();
        int remaining = quantity;
        while (remaining > 0) {
            int amount = Math.min(remaining, material.getMaxStackSize());
            ItemStack item = new ItemStack(material, amount);
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(orderKey, PersistentDataType.STRING,
                    orderId.toString());
            meta.getPersistentDataContainer().set(claimKey, PersistentDataType.STRING,
                    claimToken.toString());
            item.setItemMeta(meta);
            result.add(item);
            remaining -= amount;
        }
        return result;
    }

    private void clearTaggedItems(Player player, UUID orderId, UUID claimToken) {
        if (claimToken == null) {
            return;
        }
        for (ItemStack item : player.getInventory().getStorageContents()) {
            ClaimMarker marker = marker(item);
            if (marker == null || !marker.orderId().equals(orderId)
                    || !marker.claimToken().equals(claimToken)) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().remove(orderKey);
            meta.getPersistentDataContainer().remove(claimKey);
            item.setItemMeta(meta);
        }
    }

    private int taggedAmount(Player player, UUID orderId, UUID claimToken) {
        if (claimToken == null) {
            return 0;
        }
        int amount = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            ClaimMarker marker = marker(item);
            if (marker != null && marker.orderId().equals(orderId)
                    && marker.claimToken().equals(claimToken)) {
                amount = Math.addExact(amount, item.getAmount());
            }
        }
        return amount;
    }

    private boolean isProtected(ItemStack item) {
        return marker(item) != null;
    }

    private ClaimMarker marker(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String order = item.getItemMeta().getPersistentDataContainer()
                .get(orderKey, PersistentDataType.STRING);
        String claim = item.getItemMeta().getPersistentDataContainer()
                .get(claimKey, PersistentDataType.STRING);
        if (order == null || claim == null) {
            return null;
        }
        try {
            return new ClaimMarker(UUID.fromString(order), UUID.fromString(claim));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    private record ClaimMarker(UUID orderId, UUID claimToken) {
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

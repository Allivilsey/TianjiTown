package org.allivlisey.tianjitown.paper.station;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

final class HandbookService {
    private final TianjiTownPlugin plugin;
    private final NamespacedKey handbookKey;
    private final NamespacedKey handbookCooldownKey;

    HandbookService(TianjiTownPlugin plugin) {
        this.plugin = plugin;
        handbookKey = new NamespacedKey(plugin, "handbook");
        handbookCooldownKey = new NamespacedKey(plugin, "handbook_received_at");
    }

    boolean isHandbook(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(handbookKey,
                PersistentDataType.BYTE);
    }

    public boolean giveHandbook(Player player, boolean notifyPlayer) {
        long now = Instant.now().toEpochMilli();
        Long lastReceived = player.getPersistentDataContainer().get(handbookCooldownKey,
                PersistentDataType.LONG);
        long cooldownMillis = Duration.ofMinutes(Math.max(1, plugin.getConfig().getLong(
                "town.handbook-cooldown-minutes", 60))).toMillis();
        if (lastReceived != null && now - lastReceived < cooldownMillis) {
            long remainingMinutes = Math.max(1,
                    (cooldownMillis - (now - lastReceived) + 59_999L) / 60_000L);
            if (notifyPlayer) {
                plugin.messages().send(player, "handbook.cooldown",
                        Map.of("minutes", remainingMinutes));
            }
            return false;
        }
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle(plugin.messages().plainText("handbook.item-title"));
        meta.setAuthor("TianjiTown");
        meta.setDisplayName(plugin.messages().text("handbook.item-display-name"));
        meta.setPages(plugin.messages().text("handbook.item-pages"));
        meta.getPersistentDataContainer().set(handbookKey, PersistentDataType.BYTE, (byte) 1);
        book.setItemMeta(meta);
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(book);
        leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        player.getPersistentDataContainer().set(handbookCooldownKey, PersistentDataType.LONG, now);
        if (notifyPlayer) {
            plugin.messages().send(player, "handbook.received");
        }
        return true;
    }

}

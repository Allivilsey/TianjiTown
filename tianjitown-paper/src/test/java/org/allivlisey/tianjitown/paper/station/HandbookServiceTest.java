package org.allivlisey.tianjitown.paper.station;

import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HandbookServiceTest {
    @Test
    void adminCanGiveRepeatedlyDuringCooldownWithoutChangingSelfServiceCooldown() {
        TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
        when(plugin.namespace()).thenReturn("tianjitown");
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        PluginMessages messages = mock(PluginMessages.class);
        when(plugin.messages()).thenReturn(messages);
        Player player = mock(Player.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(player.getPersistentDataContainer()).thenReturn(data);
        NamespacedKey cooldown = new NamespacedKey(plugin, "handbook_received_at");
        when(data.get(cooldown, PersistentDataType.LONG)).thenReturn(Instant.now().toEpochMilli());
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new java.util.HashMap<>());
        BookMeta meta = mock(BookMeta.class);
        when(meta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        HandbookService service = new HandbookService(plugin);

        assertFalse(service.giveHandbook(player, false));
        verifyNoInteractions(inventory);
        try (var books = mockConstruction(ItemStack.class,
                (book, context) -> when(book.getItemMeta()).thenReturn(meta))) {
            service.giveHandbookByAdmin(player);
            service.giveHandbookByAdmin(player);
            verify(inventory, times(2)).addItem(any(ItemStack.class));
            verify(messages, times(2)).send(player, "handbook.received");
        }
        verify(data, never()).set(eq(cooldown), eq(PersistentDataType.LONG), anyLong());
        assertFalse(service.giveHandbook(player, false));
    }
}

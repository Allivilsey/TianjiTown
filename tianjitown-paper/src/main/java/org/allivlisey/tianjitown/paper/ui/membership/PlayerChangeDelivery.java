package org.allivlisey.tianjitown.paper.ui.membership;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation;
import org.allivlisey.tianjitown.storage.town.TownRepository;

/** Main-thread delivery with durable pending records and serialized acknowledgement. */
public final class PlayerChangeDelivery {
    private final TianjiTownPlugin plugin;
    private final TownRepository repository;
    private final Set<UUID> busy = new HashSet<>();
    private final Map<UUID, List<Long>> acknowledgements = new HashMap<>();
    public PlayerChangeDelivery(TianjiTownPlugin plugin, TownRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }
    public void poll() {
        Set<UUID> players = new HashSet<>(acknowledgements.keySet());
        Bukkit.getOnlinePlayers().forEach(player -> players.add(player.getUniqueId()));
        players.forEach(this::deliver);
    }
    public void deliver(UUID playerId) {
        if (!busy.add(playerId)) return;
        List<Long> pendingAck = acknowledgements.get(playerId);
        if (!plugin.runAsync(() -> {
            try {
                if (pendingAck != null) {
                    repository.acknowledgePlayerChanges(playerId, pendingAck);
                    plugin.runMain(() -> { acknowledgements.remove(playerId); busy.remove(playerId); });
                    return;
                }
                var records = repository.pendingPlayerChanges(playerId);
                plugin.runMain(() -> {
                    try {
                        Player player = Bukkit.getPlayer(playerId);
                        if (player == null || !player.isOnline()) return;
                        List<Long> sent = new ArrayList<>();
                        acknowledgements.put(playerId, sent);
                        for (var record : records) {
                            plugin.messages().send(player, "chat.notification.identity-changed", Map.of(
                                    "town", TownUiPresentation.safeText(record.townName()),
                                    "oldRole", role(record.oldRole()), "newRole", role(record.newRole())));
                            sent.add(record.id());
                        }
                        if (sent.isEmpty()) acknowledgements.remove(playerId);
                    } finally {
                        busy.remove(playerId);
                        if (acknowledgements.containsKey(playerId)) deliver(playerId);
                    }
                });
            } catch (RuntimeException exception) {
                plugin.getLogger().warning(plugin.messages().plainText("log.notification.delivery-failed",
                        Map.of("detail", TownUiPresentation.safeText(exception.getMessage()))));
                plugin.runMain(() -> busy.remove(playerId));
            }
        })) busy.remove(playerId);
    }
    private String role(String role) {
        return plugin.messages().plainText("dialog.member-role." + role.toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}

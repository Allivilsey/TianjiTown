package org.allivlisey.tianjitown.paper.ui;

import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Player-bound chat links using vanilla commands, safe for chat component serializers. */
public final class ChatCallbackService {
    public static final String COMMAND = "tianjitown-callback";
    private static final int MAX_CALLBACKS = 10_000;
    private final Clock clock;
    private final Map<String, Callback> callbacks = new LinkedHashMap<>();

    public ChatCallbackService() {
        this(Clock.systemUTC());
    }

    ChatCallbackService(Clock clock) {
        this.clock = clock;
    }

    public void register(TianjiTownPlugin plugin) {
        var command = java.util.Objects.requireNonNull(plugin.getCommand(COMMAND));
        command.setExecutor((sender, ignored, label, args) -> {
            if (sender instanceof Player player && args.length == 1) {
                plugin.runMain(() -> {
                    if (player.isOnline()) execute(player.getUniqueId(), args[0]);
                });
            }
            return true;
        });
        command.setTabCompleter((sender, ignored, alias, args) -> java.util.List.of());
    }

    public ClickEvent create(UUID recipient, BooleanSupplier active, Runnable action) {
        long now = clock.millis();
        callbacks.values().removeIf(callback -> callback.expiresAt <= now);
        while (callbacks.size() >= MAX_CALLBACKS) {
            callbacks.remove(callbacks.keySet().iterator().next());
        }
        String token = UUID.randomUUID().toString();
        callbacks.put(token, new Callback(recipient, active, action,
                now + Duration.ofDays(7).toMillis()));
        return ClickEvent.runCommand("/tianjitown:" + COMMAND + " " + token);
    }

    void execute(UUID player, String token) {
        Callback callback = callbacks.get(token);
        if (callback == null || !callback.recipient.equals(player)) return;
        if (callback.expiresAt <= clock.millis() || !callback.active.getAsBoolean()) {
            callbacks.remove(token);
            return;
        }
        if (--callback.uses == 0) callbacks.remove(token);
        callback.action.run();
    }

    public void clear() {
        callbacks.clear();
    }

    private static final class Callback {
        private final UUID recipient;
        private final BooleanSupplier active;
        private final Runnable action;
        private final long expiresAt;
        private int uses = 5;

        private Callback(UUID recipient, BooleanSupplier active, Runnable action, long expiresAt) {
            this.recipient = recipient;
            this.active = active;
            this.action = action;
            this.expiresAt = expiresAt;
        }
    }
}

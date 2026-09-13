package org.allivlisey.tianjitown.paper.ui;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/** Owns dialog session tokens and the PDC protocol shared by every UI page. */
public final class TownDialogService {
    static final int MENU_TIMEOUT_TICKS = 20 * 60;

    private final TianjiTownPlugin plugin;
    private final ActionDispatcher dispatcher;
    private final NamespacedKey actionKey;
    private final NamespacedKey targetKey;
    private final DialogSessions sessions = new DialogSessions();

    public TownDialogService(TianjiTownPlugin plugin, ActionDispatcher dispatcher) {
        this.plugin = plugin;
        this.dispatcher = dispatcher;
        this.actionKey = new NamespacedKey(plugin, "gui_action");
        this.targetKey = new NamespacedKey(plugin, "target_id");
    }

    public UUID open(Player player, Component title, List<? extends DialogBody> bodies,
              List<? extends DialogInput> inputs, DialogBase.DialogAfterAction afterAction,
              Function<UUID, DialogType> typeFactory) {
        if (!sessions.isActive()) {
            throw new IllegalStateException(plugin.messages().plainText("log.scheduler.dialog-closed"));
        }
        UUID session = sessions.open(player.getUniqueId());
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(title)
                        .externalTitle(title)
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(afterAction)
                        .body(bodies)
                        .inputs(inputs)
                        .build())
                .type(typeFactory.apply(session)));
        player.showDialog(dialog);
        plugin.runMainLater(() -> {
            if (isCurrent(player, session)) {
                sessions.clear(player.getUniqueId(), session);
                player.closeDialog();
            }
        }, MENU_TIMEOUT_TICKS);
        return session;
    }

    public DialogAction action(Player recipient, UUID session, String action, String target) {
        return action(recipient, session, response -> {
            if ("ADMIN_APPROVE".equals(action)) {
                plugin.getLogger().info("Provision dialog dispatch: actor=" + recipient.getUniqueId()
                        + " session=" + session + " application=" + target + " source=dialog-action");
            }
            dispatcher.dispatch(recipient, action, target);
        });
    }

    public DialogAction action(Player recipient, UUID session, Consumer<DialogResponseView> handler) {
        return DialogAction.customClick((response, audience) -> {
            if (!sessions.isActive() || !(audience instanceof Player clicked)
                    || !clicked.getUniqueId().equals(recipient.getUniqueId())) {
                return;
            }
            plugin.runMain(() -> {
                if (!clicked.isOnline() || !isCurrent(clicked, session)) {
                    return;
                }
                // The first valid response consumes its session, making duplicate packets harmless.
                if (!sessions.consume(clicked.getUniqueId(), session)) {
                    return;
                }
                handler.accept(response);
            });
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(1)).build());
    }

    public void writeAction(org.bukkit.inventory.meta.ItemMeta meta, String action, String target) {
        if (action != null) {
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        }
        if (target != null) {
            meta.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, target);
        }
    }

    public String action(org.bukkit.inventory.meta.ItemMeta meta) {
        return meta == null ? null : meta.getPersistentDataContainer().get(actionKey,
                PersistentDataType.STRING);
    }

    public String target(org.bukkit.inventory.meta.ItemMeta meta) {
        return meta == null ? null : meta.getPersistentDataContainer().get(targetKey,
                PersistentDataType.STRING);
    }

    public boolean isCurrent(Player player, UUID session) {
        return sessions.isCurrent(player.getUniqueId(), session);
    }

    public void clear(Player player) {
        sessions.clear(player.getUniqueId());
    }

    public boolean isActive() {
        return sessions.isActive();
    }

    public List<UUID> close() {
        return sessions.close();
    }

    /** Session token owner, kept separate so consumption and shutdown rules stay testable. */
    static final class DialogSessions {
        private final Map<UUID, UUID> current = new java.util.HashMap<>();
        private boolean active = true;

        UUID open(UUID playerId) {
            if (!active) {
                throw new IllegalStateException("dialog sessions are closed");
            }
            UUID session = UUID.randomUUID();
            current.put(playerId, session);
            return session;
        }

        boolean isActive() {
            return active;
        }

        boolean isCurrent(UUID playerId, UUID session) {
            return session != null && session.equals(current.get(playerId));
        }

        boolean consume(UUID playerId, UUID session) {
            return isCurrent(playerId, session) && current.remove(playerId, session);
        }

        void clear(UUID playerId) {
            current.remove(playerId);
        }

        void clear(UUID playerId, UUID session) {
            current.remove(playerId, session);
        }

        List<UUID> close() {
            if (!active) {
                return List.of();
            }
            active = false;
            List<UUID> viewers = List.copyOf(current.keySet());
            current.clear();
            return viewers;
        }
    }

    @FunctionalInterface
    interface ActionDispatcher {
        void dispatch(Player player, String action, String target);
    }
}

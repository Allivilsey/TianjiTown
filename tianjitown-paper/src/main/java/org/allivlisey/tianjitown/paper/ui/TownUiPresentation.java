package org.allivlisey.tianjitown.paper.ui;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;

import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;


/** Renders shared dialog controls and binds callbacks to the active player session. */
public final class TownUiPresentation {
    public static String safeText(Object value) {
        return org.allivlisey.tianjitown.core.time.TownTime.display(value).replace('&', '＆').replace('§', '�');
    }

    private final TianjiTownPlugin plugin;
    private final TownDialogService dialogs;

    public TownUiPresentation(TianjiTownPlugin plugin, TownDialogService dialogs) {
        this.plugin = plugin;
        this.dialogs = dialogs;
    }

    public void openConfirmation(Player player, String title, String confirmedAction,
                                  String target, String consequence, String returnAction,
                                  String returnTarget) {
        openConfirmation(player, title, confirmedAction, target, consequence, returnAction,
                returnTarget, "common.confirm", null);
    }

    public void openConfirmation(Player player, String title, String confirmedAction,
                                 String target, String consequence, String returnAction,
                                 String returnTarget, String confirmKey, String tooltipKey) {
        boolean irreversible = confirmedAction.equals("DISBAND")
                || confirmedAction.equals("CANCEL_VOTE");
        String body = consequence + "\n" + (irreversible
                ? dialogText("common.irreversible")
                : dialogText("confirmation.check-details"));
        DialogRoute parent = new DialogRoute(returnAction, returnTarget);
        openDialogPage(player, title,
                List.of(DialogBody.plainMessage(legacyComponent(body), 420)), List.of(),
                DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE, session ->
                        DialogType.multiAction(List.of(
                                ActionButton.create(dialogComponent(confirmKey),
                                        tooltipKey == null ? legacyComponent(consequence) : dialogComponent(tooltipKey), 170,
                                        dialogAction(player, session, confirmedAction, target)),
                                ActionButton.create(dialogComponent("common.cancel"),
                                        null, 170,
                                         dialogAction(player, session, returnAction, returnTarget))))
                        .exitAction(returnButton(player, session, parent))
                        .columns(2).build(), parent);
    }

    public UUID openMenu(Player player, int size, String title, DialogRoute parent,
                          List<MenuItem> items) {
        List<MenuItem> ordered = items.stream()
                .sorted(java.util.Comparator.comparingInt(MenuItem::slot))
                .toList();
        title = dialogMenuTitle(title);
        List<DialogBody> bodies = ordered.stream()
                .filter(item -> itemAction(item.item()) == null)
                .map(item -> dialogTextBody(item.item()))
                .toList();
        List<MenuItem> actions = ordered.stream()
                .filter(item -> itemAction(item.item()) != null)
                .toList();
        return openDialogPage(player, title, bodies, List.of(),
                DialogBase.DialogAfterAction.NONE, session -> {
                    // ESC only closes the dialog. The bottom control follows this page's route.
                    ActionButton exit = returnButton(player, session, parent);
                    if (actions.isEmpty()) {
                        return DialogType.notice(exit);
                    }
                    List<ActionButton> buttons = actions.stream()
                            .map(item -> dialogButton(player, item.item(), session))
                            .toList();
                    return DialogType.multiAction(buttons)
                            .exitAction(exit)
                            .columns(buttons.size() == 1 ? 1 : 2)
                            .build();
                }, parent);
    }

    public UUID openDialogPage(Player player, String title, List<? extends DialogBody> bodies,
                                List<? extends DialogInput> inputs,
                                DialogBase.DialogAfterAction afterAction,
        Function<UUID, DialogType> typeFactory, DialogRoute parent) {
        return dialogs.open(player, legacyComponent(title), bodies, inputs, afterAction, typeFactory);
    }

    public void openNotice(Player player, String title, String message, String actionLabel,
                            String action, String target) {
        DialogRoute parent = DialogRoute.parent(action, target);
        openDialogPage(player, title,
                List.of(DialogBody.plainMessage(legacyComponent(message), 380)), List.of(),
                DialogBase.DialogAfterAction.NONE, session -> DialogType.notice(
                        returnButton(player, session, parent)), parent);
    }

    public DialogBody dialogTextBody(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        Component message = meta != null && meta.hasDisplayName() && meta.displayName() != null
                ? meta.displayName() : Component.empty();
        Component details = dialogTooltip(meta);
        if (details != null) {
            message = message.append(Component.newline()).append(details);
        }
        return DialogBody.plainMessage(message, 400);
    }

    public ActionButton returnButton(Player player, UUID session, DialogRoute parent) {
        DialogNavigation navigation = DialogNavigation.bottom(parent);
        String action = parent.isRoot() ? "CLOSE" : parent.action();
        return ActionButton.create(dialogComponent(navigation.labelKey()),
                dialogComponent(navigation.tooltipKey()), 140,
                dialogAction(player, session, action, parent.target()));
    }

    public ActionButton dialogButton(Player player, ItemStack item, UUID session) {
        ItemMeta meta = item.getItemMeta();
        Component label = meta != null && meta.hasDisplayName() && meta.displayName() != null
                ? meta.displayName() : Component.text(item.getType().name());
        Component tooltip = dialogTooltip(meta);
        String action = itemAction(item);
        String target = dialogs.target(meta);
        return ActionButton.create(label, tooltip, 180,
                action == null ? null : dialogAction(player, session, action, target));
    }

    private Component dialogTooltip(ItemMeta meta) {
        if (meta == null || !meta.hasLore() || meta.lore() == null || meta.lore().isEmpty()) {
            return null;
        }
        Component tooltip = Component.empty();
        List<Component> lore = meta.lore();
        for (int index = 0; index < lore.size(); index++) {
            if (index > 0) {
                tooltip = tooltip.append(Component.newline());
            }
            tooltip = tooltip.append(lore.get(index));
        }
        return tooltip;
    }

    public DialogAction dialogAction(Player recipient, UUID session, String action,
                                      String target) {
        return dialogs.action(recipient, session, action, target);
    }

    public DialogAction dialogAction(Player recipient, UUID session,
                                      Consumer<DialogResponseView> handler) {
        return dialogs.action(recipient, session, handler);
    }

    public String dialogText(String key) {
        // Dialog 文案保留配置中的 & 颜色码，由各个渲染入口统一转换。
        return plugin.messages().rawText("dialog." + key);
    }

    public String dialogText(String key, Map<String, ?> placeholders) {
        return plugin.messages().rawText("dialog." + key, placeholders);
    }

    public String dialogFormat(String key) {
        return plugin.messages().text("dialog." + key);
    }

    public Component dialogComponent(String key) {
        return plugin.messages().component("dialog." + key);
    }

    public Component dialogComponent(String key, Map<String, ?> placeholders) {
        return plugin.messages().component("dialog." + key, placeholders);
    }

    private String dialogMenuTitle(String title) {
        // 动态菜单标题仍由菜单代码统一使用金色；已带颜色的文案保持原样。
        if (title.indexOf('&') >= 0 || title.indexOf('§') >= 0) {
            return title;
        }
        return "§6" + title;
    }

    public static Component legacyComponent(String value) {
        return LegacyComponentSerializer.legacySection().deserialize(value.replace('&', '§'));
    }

    private String dialogItemText(String value) {
        // 菜单代码中的 § 颜色和 messages.yml 中的 & 颜色各自直接生效。
        return value.replace('&', '§');
    }

    private String itemAction(ItemStack item) {
        return dialogs.action(item.getItemMeta());
    }

    public Component callbackButton(Player recipient, String labelKey, Runnable action) {
        return plugin.messages().component(labelKey).decorate(TextDecoration.BOLD)
                .clickEvent(callbackEvent(recipient, action))
                .hoverEvent(HoverEvent.showText(
                        plugin.messages().component("chat.buttons.open-tooltip")));
    }

    private ClickEvent callbackEvent(Player recipient, Runnable action) {
        return ClickEvent.callback(audience -> {
            if (!dialogs.isActive() || !(audience instanceof Player clicked)
                    || !clicked.getUniqueId().equals(recipient.getUniqueId())) {
                return;
            }
            plugin.runMain(() -> {
                if (!clicked.isOnline()) {
                    return;
                }
                playSound(clicked, Sound.UI_BUTTON_CLICK);
                action.run();
            });
        }, options -> options.uses(5).lifetime(Duration.ofDays(7)));
    }

    public void playSound(Player player, Sound sound) {
        player.playSound(player.getLocation(), sound, SoundCategory.MASTER, 0.8F, 1.0F);
    }

    public ItemStack button(Material material, String name, List<String> lore,
                             String action, String target) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(dialogItemText(name));
        meta.setLore(lore.stream().map(this::dialogItemText).toList());
        dialogs.writeAction(meta, action, target);
        item.setItemMeta(meta);
        return item;
    }

    public record MenuItem(int slot, ItemStack item) {
    }
}

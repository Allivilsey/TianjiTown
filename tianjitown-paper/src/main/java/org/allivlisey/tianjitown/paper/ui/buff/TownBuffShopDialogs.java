package org.allivlisey.tianjitown.paper.ui.buff;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation;
import org.allivlisey.tianjitown.paper.action.TownActions;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.ui.DialogRoute;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.allivlisey.tianjitown.core.consumption.BuffDefinition;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.allivlisey.tianjitown.paper.ui.TownUiPresentation.MenuItem;

/** Displays buff offers and duration choices, and submits buff purchases. */
public final class TownBuffShopDialogs {
    private final TownUiPresentation presentation;
    private final TownUiLegacyFacade facade;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownActions actions;

    public TownBuffShopDialogs(TownUiLegacyFacade facade) {
        this.facade = facade;
        this.presentation = facade.presentation();
        this.plugin = facade.plugin();
        this.runtime = facade.runtime();
        this.actions = facade.actions();
    }

    public void openBuffShop(Player player) {
        runtime.read(player, () -> {
            Map<String, CommerceRepository.SelectedBuffQuote> quotes = new LinkedHashMap<>();
            Map<String, String> errors = new LinkedHashMap<>();
            for (BuffDefinition definition : runtime.buffs().settings().buffs().values()) {
                try {
                    quotes.put(definition.key(), runtime.buffs().repository().quoteBuff(
                            player.getUniqueId(), definition, 1, 1,
                            runtime.settlement().scale(), Instant.now()));
                } catch (IllegalArgumentException | CommerceRepository.ConflictException exception) {
                    errors.put(definition.key(), exception.getMessage());
                }
            }
            List<CommerceRepository.ActiveBuff> active = runtime.buffs().repository()
                    .activeBuffsForPlayer(player.getUniqueId(), Instant.now());
            return new BuffShopView(active, quotes, errors);
        }, view -> {
            Map<String, CommerceRepository.ActiveBuff> active = view.active().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            CommerceRepository.ActiveBuff::buffKey, value -> value));
            List<MenuItem> items = new ArrayList<>();
            items.add(new MenuItem(4, presentation.button(Material.NETHER_STAR,
                    presentation.dialogText("buff.shop-summary-title"),
                    List.of(BuffDialogRenderer.shopHint(plugin.messages(),
                            runtime.buffs().buffShopEnabled())), null, null)));
            int slot = 9;
            for (BuffDefinition definition : runtime.buffs().settings().buffs().values()) {
                CommerceRepository.SelectedBuffQuote quote = view.quotes().get(definition.key());
                CommerceRepository.ActiveBuff current = active.get(definition.key());
                boolean purchasable = runtime.buffs().buffShopEnabled() && quote != null;
                items.add(new MenuItem(slot++, presentation.button(purchasable ? Material.POTION
                                : Material.GLASS_BOTTLE,
                        (purchasable ? "§d" : "§7")
                                + runtime.buffs().settings().label(definition.key())
                                + (current == null ? "" : " · "
                                + BuffDialogRenderer.roman(current.level())),
                        List.of(),
                        purchasable ? "BUFF_DURATIONS" : null, definition.key())));
            }
            presentation.openMenu(player, 54, presentation.dialogText("buff.shop-title"),
                    new DialogRoute("FINANCE", "0"), items, 1);
        });
    }

    public void openBuffDurations(Player player, String buffKey) {
        runtime.read(player, () -> {
            BuffDefinition definition = runtime.buffs().settings().requireBuff(buffKey);
            CommerceRepository.SelectedBuffQuote quote = runtime.buffs().repository().quoteBuff(
                    player.getUniqueId(), definition, 1, 1, runtime.settlement().scale(),
                    Instant.now());
            return quote;
        }, quote -> {
            BuffDefinition definition = runtime.buffs().settings().requireBuff(buffKey);
            BuffDialogRenderer.ActiveState current = quote.current() == null ? null
                    : new BuffDialogRenderer.ActiveState(quote.current().level(),
                            org.allivlisey.tianjitown.core.time.TownTime.display(quote.current().expiresAt()));
            ItemStack summary = presentation.button(Material.POTION, "§d"
                            + runtime.buffs().settings().label(definition.key()),
                    BuffDialogRenderer.parameterSummaryLore(plugin.messages(),
                            buffEffectDescription(definition), current), null, null);
            DialogInput duration = DialogInput.numberRange("buff_weeks", 420,
                    presentation.dialogComponent("buff.duration-label"),
                    presentation.dialogFormat("buff.duration-format"),
                    1.0F, 4.0F, 1.0F, 1.0F);
            int maximumLevel = Math.min(5, definition.maximumLevel());
            List<DialogInput> inputs = new ArrayList<>();
            inputs.add(duration);
            if (maximumLevel > 1) {
                DialogInput intensity = DialogInput.numberRange("buff_level", 420,
                    presentation.dialogComponent("buff.intensity-label"),
                    presentation.dialogFormat("buff.intensity-format"),
                    1.0F, maximumLevel,
                    quote.current() == null ? 1.0F
                            : Math.min((float) maximumLevel, quote.current().level()), 1.0F);
                inputs.add(intensity);
            }
            presentation.openDialogPage(player, presentation.dialogText("buff.title"), List.of(presentation.dialogTextBody(summary)),
                    inputs, DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE,
                    session -> DialogType.multiAction(List.of(
                                    ActionButton.create(presentation.dialogComponent("buff.continue"),
                                            null, 170, presentation.dialogAction(player, session,
                                                    response -> applyBuffDurationDialog(
                                                            player, buffKey, response))),
                                    ActionButton.create(presentation.dialogComponent("common.cancel"),
                                            null, 170,
                                            presentation.dialogAction(player, session, "BUFF_SHOP", null))))
                            .exitAction(presentation.returnButton(player, session,
                                    new DialogRoute("BUFF_SHOP", null)))
                            .columns(2).build(), new DialogRoute("BUFF_SHOP", null));
        });
    }

    private void applyBuffDurationDialog(Player player, String buffKey,
                                         DialogResponseView response) {
        Float selectedWeeks = response.getFloat("buff_weeks");
        Float selectedLevel = runtime.buffs().settings().requireBuff(buffKey).maximumLevel() == 1
                ? Float.valueOf(1.0F) : response.getFloat("buff_level");
        if (selectedWeeks == null || selectedLevel == null) {
            presentation.openNotice(player, presentation.dialogText("buff.select-title"),
                    presentation.dialogText("buff.select-message"), presentation.dialogText("common.back"),
                    "BUFF_DURATIONS", buffKey);
            return;
        }
        int weeks = Math.round(selectedWeeks);
        int level = Math.round(selectedLevel);
        runtime.read(player, () -> {
            BuffDefinition definition = runtime.buffs().settings().requireBuff(buffKey);
            return runtime.buffs().repository().quoteBuff(player.getUniqueId(), definition,
                    weeks, level, runtime.settlement().scale(), Instant.now());
        }, quote -> presentation.openConfirmation(player, presentation.dialogText("buff.confirm-title"), "BUY_BUFF",
                buffKey + ":" + weeks + ":" + level,
                presentation.dialogText("buff.confirm-consequence", Map.of(
                        "level", BuffDialogRenderer.roman(level), "weeks", weeks,
                        "price", runtime.money(quote.priceMinor()))),
                "BUFF_DURATIONS", buffKey));
    }

    public void buyBuff(Player player, String target) {
        String[] parts = target.split(":");
        String buffKey = parts[0];
        int weeks = Integer.parseInt(parts[1]);
        int level = Integer.parseInt(parts[2]);
        actions.buyBuff(player, buffKey, weeks, level, outcome ->
                facade.handleOutcome(player, outcome, purchase -> {
                    BuffDefinition definition = runtime.buffs().settings().requireBuff(buffKey);
                    presentation.openNotice(player, presentation.dialogText("buff.success-title"),
                            presentation.dialogText("buff.success-message", Map.of(
                                    "name", runtime.buffs().settings().label(definition.key()),
                                    "level", BuffDialogRenderer.roman(purchase.buff().level()),
                                    "expires", purchase.buff().expiresAt(),
                                    "balance", runtime.money(purchase.balanceAfterMinor()))),
                            presentation.dialogText("common.back"), "FINANCE", "0");
                }));
    }

    private String buffEffectDescription(BuffDefinition definition) {
        if (List.of("night_vision", "water_breathing", "safe_fall", "mining",
                "fire_resistance").contains(definition.key())) {
            return presentation.dialogText("buff." + definition.key() + "-effect");
        }
        if (definition.key().equals("health")) {
            return presentation.dialogText("buff.health-effect");
        }
        if (definition.key().equals("speed")) {
            return presentation.dialogText("buff.speed-effect");
        }
        return presentation.dialogText("buff.generic-effect", Map.of(
                "effect", definition.effectKey(),
                "amount", (definition.amountPerLevel() >= 0 ? "+" : "")
                        + definition.amountPerLevel()));
    }

    private record BuffShopView(List<CommerceRepository.ActiveBuff> active,
                                Map<String, CommerceRepository.SelectedBuffQuote> quotes,
                                Map<String, String> errors) {
    }
}

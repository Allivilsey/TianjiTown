package org.allivlisey.tianjitown.paper.ui.governance;
import org.allivlisey.tianjitown.paper.ui.TownUiPresentation;
import org.allivlisey.tianjitown.paper.action.TownActions;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.ui.DialogRoute;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.allivlisey.tianjitown.storage.governance.TransferSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.allivlisey.tianjitown.paper.ui.TownUiPresentation.MenuItem;

/** Mayor transfer requests, decisions and player notifications. */
public final class TownMayorTransferDialogs {
    private final TownUiPresentation presentation;
    private final TownUiLegacyFacade facade;
    private final TianjiTownPlugin plugin;
    private final TownRuntime runtime;
    private final TownActions actions;
    public TownMayorTransferDialogs(TownUiLegacyFacade facade) {
        this.facade = facade;
        this.presentation = facade.presentation();
        this.plugin = facade.plugin();
        this.runtime = facade.runtime();
        this.actions = facade.actions();
    }

    public void openTransferRequest(Player player, UUID transferId) {
        runtime.read(player, () -> runtime.governance().dashboard(player.getUniqueId())
                .orElseThrow(() -> new IllegalArgumentException(
                        plugin.messages().plainText("chat.runtime.town-required"))), governance -> {
            TransferSnapshot transfer = governance.pendingTransfer();
            if (transfer == null || !transfer.id().equals(transferId)) {
                presentation.openNotice(player, presentation.dialogText("notice.transfer-expired-title"),
                        presentation.dialogText("notice.transfer-expired-message"),
                        presentation.dialogText("common.back"), "MAIN", null);
                return;
            }
            List<MenuItem> items = List.of(
                    new MenuItem(4, presentation.button(Material.NETHER_STAR,
                            presentation.dialogText("transfer.summary-title"),
                            List.of(presentation.dialogText("common.town", Map.of(
                                            "town", TownUiLegacyFacade.safeText(governance.townName()))),
                                    presentation.dialogText("transfer.expires", Map.of(
                                            "time", TownUiLegacyFacade.safeText(transfer.expiresAt()))),
                                    presentation.dialogText("transfer.consequence")), null, null)),
                    new MenuItem(11, presentation.button(Material.LIME_CONCRETE,
                            presentation.dialogText("transfer.accept"),
                            List.of(presentation.dialogText("common.confirmation-required")),
                            "CONFIRM_TRANSFER_DECISION",
                            transfer.id() + ":true")),
                    new MenuItem(15, presentation.button(Material.RED_CONCRETE,
                            presentation.dialogText("common.reject"),
                            List.of(),
                            "CONFIRM_TRANSFER_DECISION",
                            transfer.id() + ":false")));
            presentation.openMenu(player, 27, presentation.dialogText("transfer.title"),
                    new DialogRoute("MAIN", null), items);
        });
    }

    public void requestMayorTransfer(Player mayor, UUID townId, UUID candidateId) {
        actions.requestMayorTransfer(mayor, townId, candidateId, outcome ->
                facade.handleOutcome(mayor, outcome, transfer -> {
            Player candidate = Bukkit.getPlayer(candidateId);
            if (candidate != null) {
                candidate.sendMessage(plugin.messages().component(
                                "chat.notification.transfer-request")
                        .append(presentation.callbackButton(candidate, "chat.buttons.handle",
                                () -> openTransferRequest(candidate, transfer.id()))));
            }
            presentation.openNotice(mayor, presentation.dialogText("notice.transfer-requested-title"),
                    presentation.dialogText("notice.transfer-requested-message", Map.of(
                            "expires", transfer.expiresAt())),
                    presentation.dialogText("common.back"), "MAIN", null);
        }));
    }

    public void decideMayorTransfer(Player candidate, UUID transferId, boolean accept) {
        actions.decideMayorTransfer(candidate, transferId, accept, outcome ->
                facade.handleOutcome(candidate, outcome, transfer -> {
            Player oldMayor = Bukkit.getPlayer(transfer.requestedBy());
            if (oldMayor != null && !accept) {
                plugin.messages().send(oldMayor, accept
                        ? "chat.notification.transfer-accepted"
                        : "chat.notification.transfer-rejected");
            }
            presentation.openNotice(candidate, accept ? presentation.dialogText("notice.transfer-complete-title")
                            : presentation.dialogText("notice.transfer-rejected-title"),
                    accept ? presentation.dialogText("notice.transfer-complete-message")
                            : presentation.dialogText("notice.transfer-rejected-message"),
                    presentation.dialogText("common.back"), "MAIN", null);
        }));
    }

}

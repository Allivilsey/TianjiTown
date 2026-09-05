package org.allivlisey.tianjitown.paper.ui.application;
import org.allivlisey.tianjitown.paper.message.PluginMessages;
import org.allivlisey.tianjitown.paper.runtime.TownActions;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.ui.TownUiLegacyFacade;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.paper.ui.application.TownApplicationFormUi.ApplicationFormSession;
import org.allivlisey.tianjitown.paper.ui.application.TownApplicationFormUi.FormPurpose;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TownApplicationDraftsTest {
    private final TownUiLegacyFacade facade = mock(TownUiLegacyFacade.class);
    private final TownActions actions = mock(TownActions.class);
    private final TownRuntime runtime = mock(TownRuntime.class);
    private final Player player = mock(Player.class);
    private final UUID playerId = UUID.randomUUID();
    private final TownApplicationFormUi forms = new TownApplicationFormUi(facade);
    private final org.allivlisey.tianjitown.paper.ui.TownUiPresentation presentation =
            mock(org.allivlisey.tianjitown.paper.ui.TownUiPresentation.class);
    private TownApplicationDrafts drafts;

    @BeforeEach
    void setUp() {
        TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
        when(plugin.messages()).thenReturn(mock(PluginMessages.class));
        when(facade.plugin()).thenReturn(plugin);
        when(facade.actions()).thenReturn(actions);
        when(facade.runtime()).thenReturn(runtime);
        when(facade.applicationFormUi()).thenReturn(forms);
        when(facade.presentation()).thenReturn(presentation);
        when(presentation.dialogText(anyString())).thenAnswer(call -> call.getArgument(0));
        when(player.getUniqueId()).thenReturn(playerId);
        drafts = new TownApplicationDrafts(facade);
    }

    @Test
    void staleSaveCannotOverwriteOrDiscardTheCurrentForm() {
        ApplicationFormSession current = newForm();
        forms.putSession(playerId, current);

        drafts.saveApplicationForm(player, UUID.randomUUID());

        assertSame(current, forms.session(playerId));
        verifyNoInteractions(actions, runtime);
        verify(presentation).openNotice(player, "notice.edit-expired-title",
                "notice.edit-expired-message", "common.reopen", "MAIN", null);
    }

    @Test
    void maintenanceClosesTheDraftWithoutSubmittingIt() {
        ApplicationFormSession current = newForm();
        forms.putSession(playerId, current);
        when(facade.maintenanceMode()).thenReturn(true);

        drafts.saveApplicationForm(player, current.id());

        assertNull(forms.session(playerId));
        verifyNoInteractions(actions, runtime);
        verify(presentation).openNotice(player, "notice.draft-not-saved-title", null,
                "common.close", "CLOSE", null);
    }

    @Test
    void missingSessionCannotSubmitAnApplication() {
        drafts.saveApplicationForm(player, UUID.randomUUID());

        verifyNoInteractions(actions, runtime);
        verify(presentation).openNotice(player, "notice.edit-expired-title",
                "notice.edit-expired-message", "common.reopen", "MAIN", null);
    }

    private static ApplicationFormSession newForm() {
        return new ApplicationFormSession(UUID.randomUUID(), FormPurpose.APPLICATION, null, 0,
                new ApplicationText("", "", "", "", List.of()), List.of());
    }
}

package org.allivlisey.tianjitown.paper.ui.application;
import org.allivlisey.tianjitown.paper.ui.membership.TownJoinApplicationUi;

import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TownApplicationRouteTargetTest {
    private static final UUID TOWN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID APPLICATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void joinApplicationPagesRequireTownAndNonNegativePage() {
        TownJoinApplicationUi.TownPage target = TownJoinApplicationUi.TownPage.parse(TOWN_ID + ":3");

        assertEquals(TOWN_ID, target.townId());
        assertEquals(3, target.page());
        assertThrows(IllegalArgumentException.class,
                () -> TownJoinApplicationUi.TownPage.parse(TOWN_ID + ":-1"));
        assertThrows(IllegalArgumentException.class,
                () -> TownJoinApplicationUi.TownPage.parse(TOWN_ID + ":3:extra"));
    }

    @Test
    void adminRecoveryTargetRequiresKnownModeAndUuid() {
        TownAdminApplicationUi.RecoveryTarget target = TownAdminApplicationUi.RecoveryTarget.parse(
                TownRepository.RecoveryMode.UNLOCK_FOR_CHANGES + ":" + APPLICATION_ID);

        assertEquals(TownRepository.RecoveryMode.UNLOCK_FOR_CHANGES, target.mode());
        assertEquals(APPLICATION_ID, target.applicationId());
        assertEquals("UNLOCK_FOR_CHANGES:" + APPLICATION_ID, target.encode());
        assertThrows(IllegalArgumentException.class,
                () -> TownAdminApplicationUi.RecoveryTarget.parse("UNKNOWN:" + APPLICATION_ID));
        assertThrows(IllegalArgumentException.class,
                () -> TownAdminApplicationUi.RecoveryTarget.parse("UNLOCK_FOR_CHANGES:not-a-uuid"));
        assertThrows(IllegalArgumentException.class,
                () -> TownAdminApplicationUi.RecoveryTarget.parse("UNLOCK_FOR_CHANGES:" + APPLICATION_ID + ":x"));
    }

    @Test
    void standalonePagesRejectNegativeOrMalformedTargets() {
        assertEquals(0, TownJoinApplicationUi.Page.parse("0").page());
        assertEquals(2, TownAdminApplicationUi.Page.parse("2").page());
        assertThrows(IllegalArgumentException.class, () -> TownJoinApplicationUi.Page.parse("-1"));
        assertThrows(IllegalArgumentException.class, () -> TownAdminApplicationUi.Page.parse("two"));
    }

    @Test
    void applicationFormMemberTargetsRequireExactlyOneKnownMemberSlot() {
        TownApplicationFormUi.InitialMemberTarget target =
                TownApplicationFormUi.InitialMemberTarget.parse(APPLICATION_ID + ":1");
        TownApplicationFormUi.InitialMemberChoice choice =
                TownApplicationFormUi.InitialMemberChoice.parse(APPLICATION_ID + ":0:Alex");

        assertEquals(APPLICATION_ID, target.formId());
        assertEquals(1, target.index());
        assertEquals(APPLICATION_ID + ":0:Alex", choice.encode());
        assertThrows(IllegalArgumentException.class,
                () -> TownApplicationFormUi.InitialMemberTarget.parse(APPLICATION_ID + ":2"));
        assertThrows(IllegalArgumentException.class,
                () -> TownApplicationFormUi.InitialMemberChoice.parse(APPLICATION_ID + ":0"));
    }

    @Test
    void applicationLifecycleTargetIsOnlyOneUuid() {
        assertEquals(APPLICATION_ID,
                TownApplicationUi.ApplicationTarget.parse(APPLICATION_ID.toString()).id());
        assertThrows(IllegalArgumentException.class,
                () -> TownApplicationUi.ApplicationTarget.parse(APPLICATION_ID + ":extra"));
    }

    @Test
    void applicationFormStagesAreNamedAndKeepTheExistingDraftStepValues() {
        assertEquals(1, TownApplicationFormUi.FormStage.BASICS.step());
        assertEquals(2, TownApplicationFormUi.FormStage.CONTENT.step());
        assertEquals(3, TownApplicationFormUi.FormStage.MEMBERS.step());
    }
}

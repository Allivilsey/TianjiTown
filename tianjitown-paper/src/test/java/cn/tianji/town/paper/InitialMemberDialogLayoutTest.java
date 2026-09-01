package cn.tianji.town.paper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InitialMemberDialogLayoutTest {
    @Test
    void keepsDraftActionsAsTheFinalTwoButtonRow() {
        InitialMemberDialogLayout.Layout layout = InitialMemberDialogLayout.layout();

        assertEquals(2, InitialMemberDialogLayout.COLUMNS);
        assertEquals(List.of(
                InitialMemberDialogLayout.Action.FIRST_MEMBER,
                InitialMemberDialogLayout.Action.SECOND_MEMBER,
                InitialMemberDialogLayout.Action.PREVIOUS,
                InitialMemberDialogLayout.Action.COMPLETE), layout.primaryActions());
        assertEquals(List.of(
                InitialMemberDialogLayout.Action.SAVE_DRAFT,
                InitialMemberDialogLayout.Action.DISCARD_DRAFT), layout.bottomActions());
        assertEquals(List.of(
                InitialMemberDialogLayout.Action.FIRST_MEMBER,
                InitialMemberDialogLayout.Action.SECOND_MEMBER,
                InitialMemberDialogLayout.Action.PREVIOUS,
                InitialMemberDialogLayout.Action.COMPLETE,
                InitialMemberDialogLayout.Action.SAVE_DRAFT,
                InitialMemberDialogLayout.Action.DISCARD_DRAFT), layout.actions());
    }
}

package org.allivlisey.tianjitown.paper;

import java.util.List;
import java.util.stream.Stream;

final class InitialMemberDialogLayout {
    static final int COLUMNS = 2;

    private InitialMemberDialogLayout() {
    }

    static Layout layout() {
        return new Layout(
                List.of(Action.FIRST_MEMBER, Action.SECOND_MEMBER, Action.COMPLETE),
                List.of(Action.SAVE_DRAFT, Action.DISCARD_DRAFT));
    }

    enum Action {
        FIRST_MEMBER,
        SECOND_MEMBER,
        COMPLETE,
        SAVE_DRAFT,
        DISCARD_DRAFT
    }

    record Layout(List<Action> primaryActions, List<Action> bottomActions) {
        Layout {
            primaryActions = List.copyOf(primaryActions);
            bottomActions = List.copyOf(bottomActions);
        }

        List<Action> actions() {
            return Stream.concat(primaryActions.stream(), bottomActions.stream()).toList();
        }
    }
}

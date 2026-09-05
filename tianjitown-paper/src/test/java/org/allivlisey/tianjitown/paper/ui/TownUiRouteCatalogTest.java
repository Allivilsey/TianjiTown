package org.allivlisey.tianjitown.paper.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TownUiRouteCatalogTest {
    @Test
    void routesRegisteredActionsToTheirSingleOwner() {
        List<String> calls = new ArrayList<>();
        TownUiActionRouter router = TownUiActionRouter.builder(
                        (player, action) -> false, player -> calls.add("unknown"))
                .register("MAIN", (player, target) -> calls.add("home:" + target))
                .build();

        router.route(null, "MAIN", null);

        assertEquals(List.of("home:null"), calls);
    }

    @Test
    void unknownActionsUseTheSafeExpiredPageOwner() {
        List<String> calls = new ArrayList<>();
        TownUiActionRouter router = TownUiActionRouter.builder(
                        (player, action) -> false, player -> calls.add("unknown"))
                .build();

        router.route(null, "MEMBERS", "not-a-uuid");

        assertEquals(List.of("unknown"), calls);
    }

    @Test
    void duplicateActionOwnersFailWhileTheRouteCatalogIsBuilt() {
        TownUiActionRouter.Builder builder = TownUiActionRouter.builder(
                (player, action) -> false, player -> { });
        builder.register("MAIN", (player, target) -> { });

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> builder.register("MAIN", (player, target) -> { }));

        assertEquals("duplicate UI action owner: MAIN", error.getMessage());
    }

    @Test
    void maintenanceGatePreventsBothOwnedAndLegacyActions() {
        List<String> calls = new ArrayList<>();
        TownUiActionRouter router = TownUiActionRouter.builder(
                        (player, action) -> true, player -> calls.add("unknown"))
                .register("MAIN", (player, target) -> calls.add("home"))
                .build();

        router.route(null, "MAIN", null);
        router.route(null, "UNKNOWN", null);

        assertEquals(List.of(), calls);
    }
}

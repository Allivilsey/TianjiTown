package org.allivlisey.tianjitown.paper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TownUiStructureTest {
    private static final Path SOURCES = Path.of("src", "main", "java", "org", "allivlisey",
            "tianjitown", "paper");
    private static final List<String> FEATURE_OWNERS = List.of("TownHomeUi", "TownBuffShopUi",
            "TownFinanceUi", "TownTerritoryUi", "TownMembershipUi", "TownGovernanceUi",
            "TownJoinApplicationUi", "TownAdminApplicationUi", "TownApplicationUi",
            "TownApplicationFormUi");
    private static final List<String> IMPLEMENTATIONS = List.of("TownHomeDialogs",
            "TownBuffShopDialogs", "TownFinanceDialogs", "TownMembershipDialogs",
            "TownGovernanceDialogs", "TownJoinApplicationDialogs", "TownAdminApplicationDialogs",
            "TownApplicationDialogs", "TownApplicationFormDialogs", "TownApplicationDrafts",
            "TownInitialMemberDialogs", "TownUiPresentation", "TownAdminApplicationCommands",
            "TownAdminGovernanceCommands", "TownAdminEconomyCommands", "TownAdminLandCommands");

    @Test
    void controllerRemainsTheSmallCompositionRoot() throws IOException {
        String controller = source("TownUiController");

        assertTrue(controller.lines().count() <= 350, "TownUiController must stay below 350 lines");
        assertFalse(controller.contains("extends TownUiLegacyFacade"));
        assertFalse(controller.contains("handleAction"));
        assertFalse(controller.contains("renderMain("));
    }

    @Test
    void routerHasNoLegacyFallbackAndFeatureOwnersDoNotReferenceEachOther() throws IOException {
        String router = source("TownUiActionRouter");
        assertFalse(router.contains("LegacyActionOwner"));
        assertFalse(router.contains("legacyFallback"));

        for (String owner : FEATURE_OWNERS) {
            String source = source(owner);
            for (String other : FEATURE_OWNERS) {
                if (!owner.equals(other)) {
                    assertFalse(source.contains(other), owner + " must not depend on " + other);
                }
            }
        }
    }

    @Test
    void featureCoordinatorsStayWithinTheReviewThreshold() throws IOException {
        for (String owner : FEATURE_OWNERS) {
            assertTrue(source(owner).lines().count() <= 700,
                    owner + " must stay below the 700-line review threshold");
        }
        assertTrue(source("ServiceStationController").lines().count() <= 700,
                "ServiceStationController must stay below the 700-line review threshold");
    }

    @Test
    void extractedImplementationsAndCompatibilityEntrypointsStayWithinTheReviewThreshold()
            throws IOException {
        for (String implementation : IMPLEMENTATIONS) {
            assertTrue(source(implementation).lines().count() <= 700,
                    implementation + " must stay below the 700-line review threshold");
        }
        assertTrue(source("TownUiLegacyFacade").lines().count() <= 700);
        assertTrue(source("TownAdminCommand").lines().count() <= 700);
    }

    @Test
    void featureImplementationsUseTheFacadeInsteadOfDependingOnEachOther() throws IOException {
        for (String implementation : IMPLEMENTATIONS) {
            if (implementation.equals("TownUiPresentation")) {
                continue;
            }
            for (String other : IMPLEMENTATIONS) {
                if (!implementation.equals(other) && !other.equals("TownUiPresentation")) {
                    assertFalse(source(implementation).contains(other),
                            implementation + " must not depend on " + other);
                }
            }
        }
        assertFalse(source("TownUiPresentation").contains("TownUiLegacyFacade"));
        assertFalse(source("TownUiPresentation").contains("TownRuntime"));
    }

    private static String source(String name) throws IOException {
        return Files.readString(SOURCES.resolve(name + ".java"));
    }
}

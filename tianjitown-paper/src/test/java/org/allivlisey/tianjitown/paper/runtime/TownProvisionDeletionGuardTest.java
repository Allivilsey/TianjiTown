package org.allivlisey.tianjitown.paper.runtime;

import java.util.Optional;
import java.util.UUID;
import org.allivlisey.tianjitown.paper.land.ProvisionCoordinator;
import org.allivlisey.tianjitown.storage.town.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TownProvisionDeletionGuardTest {
    private final TownRepository repository = mock(TownRepository.class);
    private final ProvisionCoordinator coordinator = new ProvisionCoordinator();
    private final UUID townId = UUID.randomUUID(), applicationId = UUID.randomUUID(), admin = UUID.randomUUID();
    private final TownSnapshot town = mock(TownSnapshot.class);
    private final TownProvisionDeletionGuard guard = new TownProvisionDeletionGuard(repository, coordinator);

    TownProvisionDeletionGuardTest() {
        when(repository.applicationIdForTown(townId)).thenReturn(Optional.of(applicationId));
        when(repository.findTown(townId)).thenReturn(Optional.of(town));
        when(town.version()).thenReturn(4L);
    }

    @Test void queuedProjectionPreventsDeletionAndRetainsItsCoordinatorClaim() {
        assertTrue(coordinator.tryBegin(applicationId));
        assertThrows(TownRepository.ConflictException.class, () -> guard.delete(townId, 4, admin, "Admin", "删除"));
        verify(repository, never()).deleteTown(any(), any(), anyString(), anyString());
        assertFalse(coordinator.tryBegin(applicationId));
    }

    @Test void deletionHoldsApprovalClaimThroughArchivalAndReleasesItAfterward() {
        doAnswer(call -> { assertFalse(coordinator.tryBegin(applicationId)); return null; })
                .when(repository).deleteTown(townId, admin, "Admin", "删除");
        assertSame(town, guard.delete(townId, 4, admin, "Admin", "删除"));
        verify(repository).deleteTown(townId, admin, "Admin", "删除");
        assertTrue(coordinator.tryBegin(applicationId));
    }

    @Test void staleDeletionConfirmationReleasesGuardWithoutArchiving() {
        assertThrows(TownRepository.ConflictException.class, () -> guard.delete(townId, 3, admin, "Admin", "删除"));
        verify(repository, never()).deleteTown(any(), any(), anyString(), anyString());
        assertTrue(coordinator.tryBegin(applicationId));
    }
}

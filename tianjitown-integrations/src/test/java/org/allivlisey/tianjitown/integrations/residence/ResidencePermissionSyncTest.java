package org.allivlisey.tianjitown.integrations.residence;

import com.bekvon.bukkit.residence.commands.padd;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.FlagPermissions;
import com.bekvon.bukkit.residence.protection.ResidencePermissions;
import org.allivlisey.tianjitown.core.ports.LandProtectionService.ResultCode;
import org.bukkit.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResidencePermissionSyncTest {
    private final Server server = mock(Server.class);
    private final ClaimedResidence residence = mock(ClaimedResidence.class);
    private final ResidencePermissions permissions = mock(ResidencePermissions.class);
    private final UUID member = UUID.randomUUID();
    private final Map<String, Boolean> flags = new HashMap<>();
    private ResidencePermissionSync sync;
    private org.mockito.MockedStatic<com.bekvon.bukkit.residence.containers.ResidencePlayer> players;
    private Map<String, Object> groups;
    private Object previousGroup;

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (previousGroup == null) groups.remove(padd.groupedFlag);
        else groups.put(padd.groupedFlag, previousGroup);
        players.close();
    }


    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        players = mockStatic(com.bekvon.bukkit.residence.containers.ResidencePlayer.class);
        var field = FlagPermissions.class.getDeclaredField("validFlagGroups");
        field.setAccessible(true);
        groups = (Map<String, Object>) field.get(null);
        previousGroup = groups.put(padd.groupedFlag, Map.of("build", FlagPermissions.FlagState.TRUE));
        when(server.isPrimaryThread()).thenReturn(true);
        when(residence.getPermissions()).thenReturn(permissions);
        when(permissions.has("nomobs", false)).thenReturn(true);
        flags.put("build", true);
        flags.put("ignite", true);
        when(permissions.getPlayerFlags()).thenReturn(Map.of(member, flags));
        when(permissions.getPlayerFlags(member)).thenReturn(flags);
        sync = new ResidencePermissionSync(server, new ResidenceMutationContext(server));
    }

    @Test
    void detectsMissingOrDeniedVehiclePermissionWithoutWriting() {
        assertEquals(ResultCode.MEMBER_VEHICLE_DESTROY_PERMISSION_MISMATCH,
                sync.verifyPermissions("town", residence, List.of(member), false).code());
        flags.put("vehicledestroy", false);
        assertEquals(ResultCode.MEMBER_VEHICLE_DESTROY_PERMISSION_MISMATCH,
                sync.verifyPermissions("town", residence, List.of(member), false).code());
        verify(permissions, never()).setPlayerFlag(any(UUID.class), anyString(), any());
    }

    @Test
    void acceptsExplicitVehiclePermission() {
        flags.put("vehicledestroy", true);
        assertTrue(sync.verifyPermissions("town", residence, List.of(member), false).success());
    }

    @Test
    void acceptsStoredTrustedFlagsWithoutCachedResidencePlayerOrAnyWrites() {
        flags.put("vehicledestroy", true);
        assertNull(com.bekvon.bukkit.residence.containers.ResidencePlayer.get(member));
        players.clearInvocations();

        assertTrue(sync.verifyPermissions("town", residence, List.of(member), false).success());

        verify(residence, never()).isTrusted(any(UUID.class));
        players.verifyNoInteractions();
        verify(permissions, never()).setPlayerFlag(any(UUID.class), anyString(), any());
        verify(permissions, never()).setFlag(any(), anyString(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void detectsMissingAndDeniedStoredFlagsEvenWhenEffectiveTrustIsTrue() {
        when(residence.isTrusted(member)).thenReturn(true);
        flags.put("vehicledestroy", true);
        flags.remove("build");
        assertEquals(ResultCode.MEMBER_PADD_PERMISSION_MISMATCH,
                sync.verifyPermissions("town", residence, List.of(member), false).code());
        flags.put("build", false);
        assertEquals(ResultCode.MEMBER_PADD_PERMISSION_MISMATCH,
                sync.verifyPermissions("town", residence, List.of(member), false).code());
    }

    @Test
    void honorsFalseAndNeitherGroupedFlagsAndExplicitTownGrants() {
        groups.put(padd.groupedFlag, Map.of("build", FlagPermissions.FlagState.TRUE,
                "use", FlagPermissions.FlagState.FALSE, "container", FlagPermissions.FlagState.NEITHER,
                "ignite", FlagPermissions.FlagState.FALSE,
                "vehicledestroy", FlagPermissions.FlagState.NEITHER));
        flags.put("use", false);
        flags.put("vehicledestroy", true);
        assertTrue(sync.verifyPermissions("town", residence, List.of(member), false).success());
        flags.put("container", false);
        assertEquals(ResultCode.MEMBER_PADD_PERMISSION_MISMATCH,
                sync.verifyPermissions("town", residence, List.of(member), false).code());
        flags.remove("container");
        flags.remove("use");
        assertEquals(ResultCode.MEMBER_PADD_PERMISSION_MISMATCH,
                sync.verifyPermissions("town", residence, List.of(member), false).code());
    }

    @Test
    void reinspectionAcceptsSuccessfulRepairWithoutCreatingPlayerCache() {
        flags.remove("build");
        flags.put("vehicledestroy", true);
        allowWrites();
        when(permissions.setPlayerFlag(isNull(), eq(member), eq("build"), eq("true"), eq(true), eq(false)))
                .thenAnswer(call -> { flags.put("build", true); return true; });

        assertFalse(sync.verifyPermissions("town", residence, List.of(member), false).success());
        assertTrue(sync.verifyPermissions("town", residence, List.of(member), true).success());
        assertTrue(sync.verifyPermissions("town", residence, List.of(member), false).success());
        verify(residence, never()).isTrusted(any(UUID.class));
    }

    @Test
    void supportsLegacyGroupedFlagSetsAndRejectsEmptyGroup() {
        flags.put("vehicledestroy", true);
        groups.put(padd.groupedFlag, java.util.Set.of("build"));
        assertTrue(sync.verifyPermissions("town", residence, List.of(member), false).success());
        groups.put(padd.groupedFlag, Map.of());
        assertEquals(ResultCode.MEMBER_PADD_PERMISSION_MISMATCH,
                sync.verifyPermissions("town", residence, List.of(member), false).code());
    }

    @Test
    void grantsVehiclePermissionAndRemovesDepartedMemberFlags() {
        allowWrites();
        UUID departed = UUID.randomUUID();
        when(permissions.getPlayerFlags()).thenReturn(Map.of(member, flags,
                departed, Map.of("vehicledestroy", true)));

        assertTrue(sync.verifyPermissions("town", residence, List.of(member), true).success());

        verify(permissions).setPlayerFlag(member, "vehicledestroy", FlagPermissions.FlagState.TRUE);
        verify(permissions).removeAllPlayerFlags(departed);
        verify(permissions, never()).setPlayerFlag(eq(departed), anyString(), any());
        verify(permissions, never()).setFlag(any(), eq("vehicledestroy"), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void reportsVehiclePermissionWriteFailure() {
        allowWrites();
        when(permissions.setPlayerFlag(member, "vehicledestroy", FlagPermissions.FlagState.TRUE))
                .thenReturn(false);
        var result = sync.verifyPermissions("town", residence, List.of(member), true);
        assertEquals(ResultCode.MEMBER_VEHICLE_DESTROY_PERMISSION_WRITE_FAILED, result.code());
        assertEquals(member.toString(), result.parameters().get("member"));
    }

    @Test
    void detectsAllowedOrMissingMonsterSpawnFlagWithoutWriting() {
        when(permissions.has("monsters", true)).thenReturn(true);

        assertEquals(ResultCode.MONSTER_SPAWN_FLAG_MISMATCH,
                sync.verifyPermissions("town", residence, List.of(member), false).code());

        verify(permissions, never()).setFlag(any(), anyString(), any(), anyBoolean(), anyBoolean());
        verify(permissions, never()).setPlayerFlag(any(UUID.class), anyString(), any());
    }

    @Test
    void disablesMonsterSpawningDuringPermissionSync() {
        allowWrites();

        assertTrue(sync.verifyPermissions("town", residence, List.of(member), true).success());

        verify(permissions).setFlag(null, "monsters", FlagPermissions.FlagState.FALSE, true, false);
        verify(permissions, never()).setFlag(any(), eq("animals"), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void reportsMonsterSpawnFlagWriteFailure() {
        allowWrites();
        when(permissions.setFlag(null, "monsters", FlagPermissions.FlagState.FALSE, true, false))
                .thenReturn(false);

        assertEquals(ResultCode.MONSTER_SPAWN_FLAG_WRITE_FAILED,
                sync.verifyPermissions("town", residence, List.of(member), true).code());
        verify(permissions, never()).setPlayerFlag(any(UUID.class), anyString(), any());
    }

    @Test
    void detectsMissingOrDisabledMonsterEntryFlagWithoutWriting() {
        when(permissions.has("nomobs", false)).thenReturn(false);

        assertEquals(ResultCode.MONSTER_ENTRY_FLAG_MISMATCH,
                sync.verifyPermissions("town", residence, List.of(member), false).code());

        verify(permissions, never()).setFlag(any(), anyString(), any(), anyBoolean(), anyBoolean());
        verify(permissions, never()).setPlayerFlag(any(UUID.class), anyString(), any());
    }

    @Test
    void enablesMonsterEntryProtectionDuringPermissionSync() {
        allowWrites();

        assertTrue(sync.verifyPermissions("town", residence, List.of(member), true).success());

        verify(permissions).setFlag(null, "nomobs", FlagPermissions.FlagState.TRUE, true, false);
        verify(permissions).setFlag(null, "monsters", FlagPermissions.FlagState.FALSE, true, false);
    }

    @Test
    void reportsMonsterEntryFlagWriteFailure() {
        allowWrites();
        when(permissions.setFlag(null, "nomobs", FlagPermissions.FlagState.TRUE, true, false))
                .thenReturn(false);

        assertEquals(ResultCode.MONSTER_ENTRY_FLAG_WRITE_FAILED,
                sync.verifyPermissions("town", residence, List.of(member), true).code());
        verify(permissions, never()).setPlayerFlag(any(UUID.class), anyString(), any());
    }

    private void allowWrites() {
        when(permissions.setFlag(null, "nomobs", FlagPermissions.FlagState.TRUE, true, false))
                .thenReturn(true);
        when(permissions.setFlag(any(), anyString(), eq(FlagPermissions.FlagState.FALSE), eq(true), eq(false)))
                .thenReturn(true);
        when(permissions.setPlayerFlag(isNull(), eq(member), anyString(), anyString(), eq(true), eq(false))).thenReturn(true);
        when(permissions.setPlayerFlag(eq(member), anyString(), eq(FlagPermissions.FlagState.TRUE)))
                .thenReturn(true);
    }
}

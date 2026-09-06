package org.allivlisey.tianjitown.paper.action;

import org.allivlisey.tianjitown.core.governance.VoteStatus;
import org.allivlisey.tianjitown.core.governance.VoteType;
import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.buff.BuffRuntime;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.allivlisey.tianjitown.storage.governance.VoteSnapshot;
import org.allivlisey.tianjitown.storage.town.JoinApplicationSnapshot;
import org.allivlisey.tianjitown.storage.town.TownPlayerChange;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TownMembershipBuffTest {
    private final TianjiTownPlugin plugin = mock(TianjiTownPlugin.class);
    private final TownRuntime runtime = mock(TownRuntime.class);
    private final BuffRuntime buffs = mock(BuffRuntime.class);
    private final Player actor = mock(Player.class);
    private final Player removed = mock(Player.class);
    private final UUID townId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();
    private final TownActionSupport support = new TownActionSupport(plugin, runtime);
    private Consumer<Object> committed;

    TownMembershipBuffTest() {
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPlayer(targetId)).thenReturn(removed);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(runtime.databaseAvailable()).thenReturn(true);
        when(runtime.buffs()).thenReturn(buffs);
        when(actor.getUniqueId()).thenReturn(UUID.randomUUID());
        doAnswer(call -> {
            committed = call.getArgument(2);
            return null;
        }).when(runtime).writeAction(any(), any(), any(), any());
    }

    @Test
    void approvedJoinRefreshesApplicantAfterCommit() {
        Player applicant = mock(Player.class);
        when(plugin.getServer().getPlayer(targetId)).thenReturn(applicant);
        new TownMembershipActions(support).approveJoinApplication(actor, UUID.randomUUID(),
                outcome -> assertTrue(outcome.result().success()));
        verifyNoInteractions(buffs);
        committed.accept(approvedJoin());
        verify(buffs).refreshPlayer(applicant);
        verifyNoMoreInteractions(buffs);
    }

    @Test
    void offlineApplicantDoesNotPreventSuccessfulJoinCompletion() {
        when(plugin.getServer().getPlayer(targetId)).thenReturn(null);
        new TownMembershipActions(support).approveJoinApplication(actor, UUID.randomUUID(),
                outcome -> assertTrue(outcome.result().success()));
        assertDoesNotThrow(() -> committed.accept(approvedJoin()));
        verifyNoInteractions(buffs);
    }

    private JoinApplicationSnapshot approvedJoin() {
        Instant now = Instant.now();
        return new JoinApplicationSnapshot(UUID.randomUUID(), townId, "测试镇", targetId,
                JoinApplicationSnapshot.Status.APPROVED, now.plusSeconds(3600),
                actor.getUniqueId(), now, now);
    }

    @Test
    void passingKickVoteRefreshesRemovedPlayerAfterCommit() {
        new TownGovernanceActions(support).castVote(actor, UUID.randomUUID(), true,
                outcome -> assertTrue(outcome.result().success()));
        verifyNoInteractions(buffs);
        committed.accept(vote(VoteType.KICK_MEMBER, VoteStatus.PASSED));
        verify(buffs).refreshPlayer(removed);
        verifyNoMoreInteractions(buffs);
    }

    @Test
    void unfinishedOrRejectedKickVoteDoesNotChangeBuffs() {
        new TownGovernanceActions(support).castVote(actor, UUID.randomUUID(), false,
                outcome -> assertTrue(outcome.result().success()));
        committed.accept(vote(VoteType.KICK_MEMBER, VoteStatus.OPEN));
        committed.accept(vote(VoteType.KICK_MEMBER, VoteStatus.REJECTED));
        verifyNoInteractions(buffs);
    }

    @Test
    void offlineKickTargetDoesNotPreventSuccessfulVoteCompletion() {
        when(plugin.getServer().getPlayer(targetId)).thenReturn(null);
        new TownGovernanceActions(support).castVote(actor, UUID.randomUUID(), true,
                outcome -> assertTrue(outcome.result().success()));
        assertDoesNotThrow(() -> committed.accept(vote(VoteType.KICK_MEMBER, VoteStatus.PASSED)));
        verifyNoInteractions(buffs);
    }

    @Test
    void directKickRefreshesRemovedPlayerAfterCommit() {
        new TownMembershipActions(support).kickMember(actor, townId, targetId,
                outcome -> assertTrue(outcome.result().success()));
        verifyNoInteractions(buffs);
        committed.accept(new TownPlayerChange(townId, targetId, "测试镇"));
        verify(buffs).refreshPlayer(removed);
        verifyNoMoreInteractions(buffs);
    }

    @Test
    void voluntaryDepartureRefreshesDepartingPlayerAfterCommit() {
        new TownMembershipActions(support).leaveTown(actor, townId,
                outcome -> assertTrue(outcome.result().success()));
        verifyNoInteractions(buffs);
        committed.accept(townId);
        verify(buffs).refreshPlayer(actor);
        verifyNoMoreInteractions(buffs);
    }

    private VoteSnapshot vote(VoteType type, VoteStatus status) {
        return new VoteSnapshot(UUID.randomUUID(), townId, type, targetId, null,
                actor.getUniqueId(), status, 5, 3, 3, 0, Instant.now(), true, true);
    }
}

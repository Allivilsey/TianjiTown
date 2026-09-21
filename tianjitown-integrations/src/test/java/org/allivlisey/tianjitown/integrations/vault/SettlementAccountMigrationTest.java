package org.allivlisey.tianjitown.integrations.vault;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SettlementAccountMigrationTest {
    @TempDir Path directory;
    private final UUID sourceId = UUID.randomUUID(), targetId = UUID.randomUUID();
    private final Backend backend = new Backend();
    private final AtomicReference<String> configuration = new AtomicReference<>("tax");

    SettlementAccountMigrationTest() {
        backend.data.put(sourceId, new SettlementAccountMigration.Account(sourceId, "Tax", new BigDecimal("98765.43")));
    }

    @Test void renamesProductionAccountWithoutCreatingAnAccountOrChangingFunds() throws Exception {
        var binding = run();
        assertEquals(sourceId, binding.id());
        assertEquals("Tax", binding.formerName());
        assertEquals("tianjitown-tax", configuration.get());
        assertEquals(1, backend.data.size());
        assertEquals(new BigDecimal("98765.43"), backend.byId(sourceId).balance());
        assertNull(backend.byName("tax"));
        assertEquals(sourceId, backend.byName("tianjitown-tax").id());
        assertTrue(Files.readString(directory.resolve(SettlementAccountMigration.JOURNAL)).contains("COMPLETE"));
        // Later real payments are allowed: original balance is not an ongoing frozen balance.
        backend.data.put(sourceId, new SettlementAccountMigration.Account(sourceId, "tianjitown-tax", BigDecimal.TEN));
        assertEquals(binding, run());
        assertEquals(BigDecimal.TEN, backend.byId(sourceId).balance());
        assertEquals(1, backend.renames);
    }

    @Test void interruptionBeforeProviderWriteResumesOnceWithTheSameUuid() throws Exception {
        backend.crashBeforeRename = true;
        assertThrows(IllegalStateException.class, this::run);
        assertTrue(Files.readString(directory.resolve(SettlementAccountMigration.JOURNAL)).contains("PREPARED"));
        assertEquals("Tax", backend.byId(sourceId).name());
        backend.crashBeforeRename = false;
        assertEquals(sourceId, run().id());
        assertEquals(1, backend.renames);
    }

    @Test void interruptionAfterProviderWriteDoesNotRepeatRenameOrMoveMoney() throws Exception {
        backend.crashAfterRename = true;
        assertThrows(IllegalStateException.class, this::run);
        assertEquals("tax", configuration.get());
        backend.crashAfterRename = false;
        assertEquals(sourceId, run().id());
        assertEquals(1, backend.renames);
        assertEquals(new BigDecimal("98765.43"), backend.byId(sourceId).balance());
    }

    @Test void configurationWriteFailureRecoversFromAlreadyRenamedAccount() throws Exception {
        assertThrows(IllegalStateException.class, () -> migration().run("tax", sourceId, targetId,
                name -> { throw new IllegalStateException("disk full"); }));
        assertEquals(sourceId, run().id());
        assertEquals(1, backend.renames);
    }

    @Test void occupiedTargetStopsBeforeAnyMutationEvenIfItsBalanceIsZero() {
        backend.data.put(targetId, new SettlementAccountMigration.Account(targetId, "tianjitown-tax", BigDecimal.ZERO));
        assertThrows(IllegalStateException.class, this::run);
        assertEquals("Tax", backend.byId(sourceId).name());
        assertEquals(0, backend.renames);
        assertFalse(Files.exists(directory.resolve(SettlementAccountMigration.JOURNAL)));
    }

    @Test void realPlayerAndMismatchedSourceUuidAreRejected() {
        backend.players.add(sourceId);
        assertThrows(IllegalStateException.class, this::run);
        backend.players.clear();
        assertThrows(IllegalStateException.class, () -> migration().run("tax", UUID.randomUUID(), targetId,
                configuration::set));
        assertEquals(0, backend.renames);
    }

    @Test void customOrAlreadyNewConfigurationDoesNotSweepUnownedTaxAccounts() throws Exception {
        configuration.set("another-plugin-bank");
        assertNull(run());
        configuration.set("tianjitown-tax");
        assertNull(run());
        assertEquals("Tax", backend.byId(sourceId).name());
        assertEquals(0, backend.renames);
    }

    @Test void missingLegacyAccountOnlyUpdatesConfiguration() throws Exception {
        backend.data.clear();
        assertNull(run());
        assertEquals("tianjitown-tax", configuration.get());
        assertTrue(backend.data.isEmpty());
        assertEquals(0, backend.renames);
    }

    @Test void databaseReadFailureCannotMasqueradeAsMissingAccount() {
        backend.readFailure = true;
        assertThrows(IllegalStateException.class, this::run);
        assertEquals("tax", configuration.get());
        assertEquals(0, backend.renames);
    }

    @Test void invalidJournalAndChangedBalanceStopRecovery() throws Exception {
        Files.writeString(directory.resolve(SettlementAccountMigration.JOURNAL), "state=COMPLETE");
        assertThrows(IOException.class, this::run);
        Files.delete(directory.resolve(SettlementAccountMigration.JOURNAL));
        backend.crashAfterRename = true;
        assertThrows(IllegalStateException.class, this::run);
        backend.crashAfterRename = false;
        backend.data.put(sourceId, new SettlementAccountMigration.Account(sourceId, "tianjitown-tax", BigDecimal.ONE));
        assertThrows(IllegalStateException.class, this::run);
        assertEquals("tax", configuration.get());
        assertEquals(1, backend.renames);
    }

    @Test void unsupportedEnvironmentDoesNotEvenWriteThePreparedRecord() {
        backend.prepareFailure = true;
        assertThrows(IllegalStateException.class, this::run);
        assertFalse(Files.exists(directory.resolve(SettlementAccountMigration.JOURNAL)));
        assertEquals(0, backend.renames);
    }

    private SettlementAccountMigration migration() { return new SettlementAccountMigration(directory, backend); }
    private SettlementAccountMigration.Binding run() throws IOException {
        return migration().run(configuration.get(), sourceId, targetId, configuration::set);
    }

    private static class Backend implements SettlementAccountMigration.Accounts {
        final Map<UUID, SettlementAccountMigration.Account> data = new HashMap<>();
        final Set<UUID> players = new HashSet<>();
        boolean crashBeforeRename, crashAfterRename, readFailure, prepareFailure;
        int renames;
        public SettlementAccountMigration.Account byName(String name) {
            if (readFailure) throw new IllegalStateException("database unavailable");
            return data.values().stream().filter(a -> a.name().equalsIgnoreCase(name)).findFirst().orElse(null);
        }
        public SettlementAccountMigration.Account byId(UUID id) { return data.get(id); }
        public boolean hasPlayed(UUID id) { return players.contains(id); }
        public void prepare() { if (prepareFailure) throw new IllegalStateException("unsupported provider mode"); }
        public void rename(UUID id, String name) {
            if (crashBeforeRename) throw new IllegalStateException("interrupted before provider mutation");
            var old = data.get(id);
            data.put(id, new SettlementAccountMigration.Account(id, name, old.balance()));
            renames++;
            if (crashAfterRename) throw new IllegalStateException("interrupted after provider mutation");
        }
    }
}

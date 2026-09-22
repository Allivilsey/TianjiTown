package org.allivlisey.tianjitown.integrations.vault;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.allivlisey.tianjitown.integrations.vault.SettlementAccountMigration.Account;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyTaxAccountCleanupTest {
    @TempDir Path directory;
    final LegacyTaxAccountCleanup.Accounts accounts = mock(LegacyTaxAccountCleanup.Accounts.class);
    final UUID id = UUID.randomUUID();
    final Account account = new Account(id, "tax", new BigDecimal("123.45"));

    private void existing(Account value) {
        when(accounts.byName(value.name())).thenReturn(value);
        when(accounts.byId(value.id())).thenReturn(value);
        when(accounts.resolveId(value.name())).thenReturn(value.id());
    }

    private Properties audit() throws Exception {
        Properties value = new Properties();
        try (var stream = Files.newInputStream(directory.resolve(LegacyTaxAccountCleanup.JOURNAL))) {
            value.load(stream);
        }
        return value;
    }

    @Test void deletesConfiguredAccountAndRecordsBalanceBeforeDeletion() throws Exception {
        existing(account);
        doAnswer(call -> {
            assertEquals("PREPARED", audit().getProperty("state"));
            assertEquals("123.45", audit().getProperty("original-balance"));
            when(accounts.byName("tax")).thenReturn(null);
            when(accounts.byId(id)).thenReturn(null);
            return null;
        }).when(accounts).delete(account);
        assertTrue(LegacyTaxAccountCleanup.run(directory, "tax", accounts).startsWith("DELETED"));
        assertEquals("COMPLETE", audit().getProperty("state"));
        // A later account with the same name must never be deleted by another startup.
        existing(account);
        assertEquals("ALREADY_COMPLETE", LegacyTaxAccountCleanup.run(directory, "tax", accounts));
        verify(accounts, times(1)).delete(account);
    }

    @Test void ignoresNewInstallAndCustomAccounts() throws Exception {
        assertEquals("SKIPPED", LegacyTaxAccountCleanup.run(directory, null, accounts));
        assertEquals("SKIPPED", LegacyTaxAccountCleanup.run(directory, "treasury", accounts));
        verifyNoInteractions(accounts);
    }

    @Test void missingAccountIsCompletedWithoutCreatingAnAccount() throws Exception {
        when(accounts.resolveId("tax")).thenReturn(id);
        assertEquals("ABSENT", LegacyTaxAccountCleanup.run(directory, "tax", accounts));
        assertEquals("COMPLETE", audit().getProperty("state"));
        verify(accounts, never()).delete(any());
    }

    @Test void refusesPlayerAccountAndIdentityMismatch() {
        existing(account);
        when(accounts.hasPlayed(id)).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> LegacyTaxAccountCleanup.run(directory, "tax", accounts));
        when(accounts.hasPlayed(id)).thenReturn(false);
        when(accounts.resolveId("tax")).thenReturn(UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> LegacyTaxAccountCleanup.run(directory, "tax", accounts));
        verify(accounts, never()).delete(any());
        assertFalse(Files.exists(directory.resolve(LegacyTaxAccountCleanup.JOURNAL)));
    }

    @Test void resolvesRenamedAccountByOldMigrationUuid() throws Exception {
        Account renamed = new Account(id, "tianjitown-tax", account.balance());
        existing(renamed);
        Files.writeString(directory.resolve(SettlementAccountMigration.JOURNAL),
                "version=1\nformer-name=tax\ntarget-name=tianjitown-tax\nstate=COMPLETE\naccount-uuid=" + id);
        doAnswer(call -> {
            when(accounts.byName(renamed.name())).thenReturn(null);
            when(accounts.byId(id)).thenReturn(null);
            return null;
        }).when(accounts).delete(renamed);
        assertTrue(LegacyTaxAccountCleanup.run(directory, "tianjitown-tax", accounts).startsWith("DELETED"));
        verify(accounts).delete(renamed);
        verify(accounts, never()).resolveId(anyString());
    }

    @Test void failedDeletionIsNeverBlindlyRetried() throws Exception {
        existing(account);
        assertThrows(IllegalStateException.class, () -> LegacyTaxAccountCleanup.run(directory, "tax", accounts));
        assertEquals("PREPARED", audit().getProperty("state"));
        assertThrows(IllegalStateException.class, () -> LegacyTaxAccountCleanup.run(directory, "tax", accounts));
        verify(accounts, times(1)).delete(account);
        when(accounts.byName("tax")).thenReturn(null);
        when(accounts.byId(id)).thenReturn(null);
        assertEquals("COMPLETE", LegacyTaxAccountCleanup.run(directory, "tax", accounts));
        verify(accounts, times(1)).delete(account);
    }

    @Test void cannotDeleteWithoutDurableAudit() throws Exception {
        existing(account);
        Path missingDirectory = directory.resolve("missing");
        assertThrows(java.io.IOException.class,
                () -> LegacyTaxAccountCleanup.run(missingDirectory, "tax", accounts));
        verify(accounts, never()).delete(any());
    }

    @Test void refusesAccountThatWasRenamedWithoutMigrationRecord() {
        when(accounts.resolveId("tax")).thenReturn(id);
        when(accounts.byId(id)).thenReturn(new Account(id, "someone", account.balance()));
        assertThrows(IllegalStateException.class, () -> LegacyTaxAccountCleanup.run(directory, "tax", accounts));
        verify(accounts, never()).delete(any());
    }
}

package org.allivlisey.tianjitown.integrations.vault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import org.allivlisey.tianjitown.integrations.vault.SettlementAccountMigration.Account;

/** Temporary, one-time retirement of the account explicitly selected by old configuration. */
public final class LegacyTaxAccountCleanup {
    public static final String JOURNAL = "legacy-tax-account-cleanup.properties";

    public interface Accounts {
        Account byId(UUID id);
        UUID resolveId(String name);
        boolean hasPlayed(UUID id);
        void prepare();
        void delete(Account account);
    }

    public static boolean applies(String configuredName) {
        return "tax".equalsIgnoreCase(configuredName) || "tianjitown-tax".equalsIgnoreCase(configuredName);
    }

    public static String run(Path directory, String configuredName, Accounts accounts) throws IOException {
        if (!applies(configuredName)) return "SKIPPED";
        Path file = directory.resolve(JOURNAL);
        Properties audit = read(file);
        if ("COMPLETE".equals(audit.getProperty("state"))) return "ALREADY_COMPLETE";
        if (!audit.isEmpty()) {
            require("PREPARED".equals(audit.getProperty("state")), "Invalid cleanup journal state");
            // An interrupted deletion is never replayed against an account that may have been recreated.
            UUID id = UUID.fromString(audit.getProperty("account-uuid"));
            String name = audit.getProperty("account-name");
            require(applies(name), "Invalid cleanup account name");
            require(accounts.byId(id) == null,
                    "Interrupted tax cleanup requires manual verification; account still exists");
            complete(file, audit);
            return "COMPLETE";
        }

        accounts.prepare();
        Properties migration = read(directory.resolve(SettlementAccountMigration.JOURNAL));
        Account account;
        if (!migration.isEmpty()) {
            require("1".equals(migration.getProperty("version"))
                    && "tax".equalsIgnoreCase(migration.getProperty("former-name"))
                    && "tianjitown-tax".equals(migration.getProperty("target-name"))
                    && ("PREPARED".equals(migration.getProperty("state"))
                        || "COMPLETE".equals(migration.getProperty("state"))), "Invalid old migration journal");
            UUID id = UUID.fromString(migration.getProperty("account-uuid"));
            account = accounts.byId(id);
            if (account != null) {
                require(id.equals(account.id()) && applies(account.name()),
                        "Old migration account identity changed externally");
            }
        } else {
            // Match VaultSettlementService: Paper's OfflinePlayer UUID was the identity used
            // for all wallet calls. A similarly named XConomy row can belong to another plugin.
            UUID expected = accounts.resolveId(configuredName);
            account = accounts.byId(expected);
            if (account != null) {
                require(account.id().equals(expected) && configuredName.equalsIgnoreCase(account.name()),
                        "Legacy account identity mismatch");
            }
        }
        if (account == null) {
            audit.setProperty("result", "ABSENT");
            complete(file, audit);
            return "ABSENT";
        }
        require(!accounts.hasPlayed(account.id()), "Legacy tax account belongs to a real player");
        audit.setProperty("state", "PREPARED");
        audit.setProperty("account-uuid", account.id().toString());
        audit.setProperty("account-name", account.name());
        audit.setProperty("original-balance", account.balance().toPlainString());
        write(file, audit);
        accounts.delete(account);
        require(accounts.byId(account.id()) == null,
                "Tax account deletion could not be verified; manual verification required");
        audit.setProperty("result", "DELETED");
        complete(file, audit);
        return "DELETED " + account.name() + " uuid=" + account.id() + " balance=" + account.balance();
    }

    private static Properties read(Path file) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (var input = Files.newInputStream(file)) { properties.load(input); }
        }
        return properties;
    }

    private static void complete(Path file, Properties audit) throws IOException {
        audit.setProperty("state", "COMPLETE");
        write(file, audit);
    }

    private static void write(Path file, Properties audit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        audit.store(bytes, "Legacy tax account retirement audit. Keep to prevent repeated deletion.");
        SettlementAccountMigration.atomicWrite(file, bytes.toByteArray());
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}

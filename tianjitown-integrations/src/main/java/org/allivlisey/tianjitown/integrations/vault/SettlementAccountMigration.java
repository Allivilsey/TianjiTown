package org.allivlisey.tianjitown.integrations.vault;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;

/** A name migration, never a balance transfer. The journal also retains the account's UUID. */
public final class SettlementAccountMigration {
    public static final String TARGET = "tianjitown-tax";
    public static final String JOURNAL = "settlement-account-migration.properties";
    private final Path file;
    private final Accounts accounts;

    public SettlementAccountMigration(Path directory, Accounts accounts) {
        this.file = directory.resolve(JOURNAL);
        this.accounts = Objects.requireNonNull(accounts);
    }

    public interface Accounts {
        Account byName(String name);
        Account byId(UUID id);
        boolean hasPlayed(UUID id);
        void prepare();
        void rename(UUID id, String name);
    }

    public record Account(UUID id, String name, BigDecimal balance) { }
    public record Binding(UUID id, String formerName) { }

    public Binding run(String configuredName, UUID resolvedSource, UUID resolvedTarget,
                       Consumer<String> saveConfiguration) throws IOException {
        boolean legacy = "tax".equalsIgnoreCase(configuredName);
        // Custom accounts are explicitly outside the automatic legacy migration.
        if (!legacy && !TARGET.equals(configuredName)) return null;
        if (!legacy && !Files.exists(file)) return null;
        Files.createDirectories(file.getParent());
        try (FileChannel channel = FileChannel.open(file.resolveSibling(JOURNAL + ".lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("Another account migration is running");
            Journal journal = Files.exists(file) ? read() : null;
            if (journal == null || !journal.complete()) accounts.prepare();
            if (journal == null) {
                Account source = accounts.byName(configuredName);
                if (source == null) {
                    // Do not silently abandon an account reachable by UUID under another name.
                    require(accounts.byId(resolvedSource) == null, "Legacy account identity mismatch");
                    saveConfiguration.accept(TARGET);
                    return null;
                }
                require(source.id().equals(resolvedSource), "Legacy account UUID mismatch");
                require("tax".equalsIgnoreCase(source.name()), "Legacy account name mismatch");
                require(!accounts.hasPlayed(source.id()), "Legacy account belongs to a real player");
                require(source.balance().signum() >= 0, "Legacy account has a negative balance");
                require(accounts.byName(TARGET) == null, "Target account name is already occupied");
                require(accounts.byId(resolvedTarget) == null, "Target account UUID is already occupied");
                journal = new Journal(source.id(), source.name(), source.balance(), false);
                write(journal); // Durable identity before the provider can change a name.
            }

            Account current = accounts.byId(journal.id());
            require(current != null, "Recorded settlement account is missing");
            require(!accounts.hasPlayed(journal.id()), "Recorded settlement account belongs to a real player");
            Account target = accounts.byName(TARGET);
            require(target == null || target.id().equals(journal.id()), "Target account name is already occupied");
            Account targetUuid = accounts.byId(resolvedTarget);
            require(targetUuid == null || targetUuid.id().equals(journal.id()), "Target account UUID is already occupied");

            if (!TARGET.equals(current.name())) {
                require(!journal.complete(), "Completed migration account was renamed externally");
                require(journal.formerName().equals(current.name()), "Recorded account identity changed");
                accounts.rename(journal.id(), TARGET);
                current = accounts.byId(journal.id());
            }
            // A restart after the provider rename reaches here without repeating the rename.
            target = accounts.byName(TARGET);
            require(current != null && current.id().equals(journal.id()) && TARGET.equals(current.name())
                    && target != null && target.id().equals(journal.id()), "Provider rename could not be verified");
            if (!journal.complete()) {
                require(journal.balance().compareTo(current.balance()) == 0,
                        "Balance changed during migration; manual reconciliation required");
            }
            saveConfiguration.accept(TARGET);
            if (!journal.complete()) write(new Journal(journal.id(), journal.formerName(), journal.balance(), true));
            return new Binding(journal.id(), journal.formerName());
        }
    }

    private Journal read() throws IOException {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(file)) { properties.load(input); }
        try {
            require("1".equals(properties.getProperty("version")), "Unsupported migration journal version");
            require(TARGET.equals(properties.getProperty("target-name")), "Invalid migration target");
            String name = properties.getProperty("former-name");
            require("tax".equalsIgnoreCase(name), "Invalid legacy account in migration journal");
            String state = properties.getProperty("state");
            require("PREPARED".equals(state) || "COMPLETE".equals(state), "Invalid migration journal state");
            BigDecimal balance = new BigDecimal(properties.getProperty("original-balance"));
            require(balance.signum() >= 0, "Invalid migration balance");
            return new Journal(UUID.fromString(properties.getProperty("account-uuid")), name, balance,
                    "COMPLETE".equals(state));
        } catch (RuntimeException exception) {
            throw new IOException("Invalid settlement account migration journal: " + file, exception);
        }
    }

    private void write(Journal journal) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("target-name", TARGET);
        properties.setProperty("former-name", journal.formerName());
        properties.setProperty("account-uuid", journal.id().toString());
        properties.setProperty("original-balance", journal.balance().toPlainString());
        properties.setProperty("state", journal.complete() ? "COMPLETE" : "PREPARED");
        var bytes = new java.io.ByteArrayOutputStream();
        properties.store(bytes, "Keep this file: it binds the renamed account to its original UUID.");
        atomicWrite(file, bytes.toByteArray());
    }

    public static void atomicWrite(Path destination, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            // Fail instead of falling back to a non-atomic replacement of the recovery record.
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }

    private record Journal(UUID id, String formerName, BigDecimal balance, boolean complete) { }
}

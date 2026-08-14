package cn.tianji.town.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlineBackupServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsRelativeBackupDirectoryEscapingPluginDataDirectory() {
        Path dataDirectory = temporaryDirectory.resolve("plugins").resolve("TianjiTown");

        assertThrows(IllegalArgumentException.class,
                () -> OnlineBackupService.resolveDirectory(dataDirectory,
                        Path.of("..", "escape-backups")));
        assertEquals(dataDirectory.resolve("backups").toAbsolutePath().normalize(),
                OnlineBackupService.resolveDirectory(dataDirectory, Path.of("backups")));
    }

    @Test
    void preservesExplicitAbsoluteBackupDirectory() {
        Path dataDirectory = temporaryDirectory.resolve("plugins").resolve("TianjiTown");
        Path externalDirectory = temporaryDirectory.resolve("external-backups").toAbsolutePath();

        assertEquals(externalDirectory.normalize(),
                OnlineBackupService.resolveDirectory(dataDirectory, externalDirectory));
    }

    @Test
    void reservesDistinctFilesForBackupsStartedInTheSameSecond() throws Exception {
        Path directory = temporaryDirectory.resolve("backups");
        Files.createDirectories(directory);
        Instant startedAt = Instant.parse("2026-08-13T09:30:15Z");

        OnlineBackupService.BackupPaths first;
        OnlineBackupService.BackupPaths second;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var firstFuture = executor.submit(() -> {
                start.await();
                return OnlineBackupService.reserveBackupPaths(directory, startedAt);
            });
            var secondFuture = executor.submit(() -> {
                start.await();
                return OnlineBackupService.reserveBackupPaths(directory, startedAt);
            });
            start.countDown();
            first = firstFuture.get();
            second = secondFuture.get();
        }

        assertNotEquals(first.databaseFile(), second.databaseFile());
        Files.writeString(first.databaseFile(), "first-backup");
        Files.deleteIfExists(second.databaseFile());
        Files.deleteIfExists(second.configFile());
        Files.deleteIfExists(second.checksumFile());
        Files.deleteIfExists(second.reservationFile());
        assertTrue(Files.isRegularFile(first.databaseFile()));
    }
}

package cn.tianji.town.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void rejectsExplicitAbsoluteBackupDirectory() {
        Path dataDirectory = temporaryDirectory.resolve("plugins").resolve("TianjiTown");
        Path externalDirectory = temporaryDirectory.resolve("external-backups").toAbsolutePath();

        assertThrows(IllegalArgumentException.class,
                () -> OnlineBackupService.resolveDirectory(dataDirectory, externalDirectory));
    }

    @Test
    void rendersBackupDiagnosticsAndUsesReloadedOverrides() throws Exception {
        PluginMessages messages = new PluginMessages(temporaryDirectory.toFile());
        for (String key : List.of(
                "validation.backup.absolute-path",
                "validation.backup.relative-path-escape",
                "validation.backup.symlink-escape",
                "validation.backup.not-directory",
                "validation.backup.real-path-verification-failure",
                "diagnostic.backup.main-thread-required",
                "diagnostic.backup.filename-exhausted",
                "diagnostic.backup.sha256-unavailable")) {
            String rendered = messages.plainText(key, Map.of("path", "backups"));
            assertFalse(rendered.isBlank());
            assertFalse(rendered.contains("缺少消息配置"));
            assertFalse(rendered.contains("{"));
        }

        Path dataDirectory = temporaryDirectory.resolve("plugins").resolve("TianjiTown");
        Path externalDirectory = temporaryDirectory.resolve("external-backups").toAbsolutePath();
        assertEquals("备份目录必须位于插件数据目录内，不能使用绝对路径: "
                        + externalDirectory,
                assertThrows(IllegalArgumentException.class,
                        () -> OnlineBackupService.resolveDirectory(dataDirectory,
                                externalDirectory, messages::plainText)).getMessage());

        org.bukkit.configuration.file.YamlConfiguration override =
                new org.bukkit.configuration.file.YamlConfiguration();
        override.set("validation.backup.absolute-path", "自定义备份路径错误: {path}");
        override.save(temporaryDirectory.resolve("messages.yml").toFile());
        messages.reload();

        assertEquals("自定义备份路径错误: " + externalDirectory,
                assertThrows(IllegalArgumentException.class,
                        () -> OnlineBackupService.resolveDirectory(dataDirectory,
                                externalDirectory, messages::plainText)).getMessage());
    }

    @Test
    void rejectsExistingFileAsBackupDirectory() throws Exception {
        Path dataDirectory = temporaryDirectory.resolve("plugins").resolve("TianjiTown");
        Files.createDirectories(dataDirectory);
        Files.writeString(dataDirectory.resolve("backups"), "不是目录");

        assertThrows(IllegalArgumentException.class,
                () -> OnlineBackupService.resolveDirectory(dataDirectory, Path.of("backups")));
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

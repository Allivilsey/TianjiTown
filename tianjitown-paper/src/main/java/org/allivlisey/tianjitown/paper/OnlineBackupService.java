package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.storage.database.DatabaseGate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

final class OnlineBackupService {
    private static final String PREFIX = "tianjitown-";
    private static final String MAIN_THREAD_REQUIRED =
            "diagnostic.backup.main-thread-required";
    private static final String ABSOLUTE_PATH = "validation.backup.absolute-path";
    private static final String RELATIVE_PATH_ESCAPE =
            "validation.backup.relative-path-escape";
    private static final String SYMLINK_ESCAPE = "validation.backup.symlink-escape";
    private static final String NOT_DIRECTORY = "validation.backup.not-directory";
    private static final String REAL_PATH_VERIFICATION_FAILURE =
            "validation.backup.real-path-verification-failure";
    private static final String FILENAME_EXHAUSTED = "diagnostic.backup.filename-exhausted";
    private static final String SHA256_UNAVAILABLE = "diagnostic.backup.sha256-unavailable";
    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private final TianjiTownPlugin plugin;
    private final DatabaseGate database;
    private final TownBonusSettings.Backup settings;
    private final AtomicReference<Result> lastResult;

    OnlineBackupService(TianjiTownPlugin plugin, DatabaseGate database,
                           TownBonusSettings.Backup settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.database = Objects.requireNonNull(database, "database");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.lastResult = new AtomicReference<>(new Result(false, null,
                plugin.messages().text("chat.backup.not-run"), null));
    }

    Result create() {
        if (plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException(resolveMessage(plugin.messages()::plainText,
                    MAIN_THREAD_REQUIRED, Map.of()));
        }
        Instant startedAt = Instant.now();
        Path databaseFile = null;
        Path configFile = null;
        Path checksumFile = null;
        Path reservationFile = null;
        try {
            Path directory = resolveDirectory();
            Files.createDirectories(directory);
            directory = resolveDirectory();
            BackupPaths paths = reserveBackupPaths(directory, startedAt,
                    plugin.messages()::plainText);
            databaseFile = paths.databaseFile();
            configFile = paths.configFile();
            checksumFile = paths.checksumFile();
            reservationFile = paths.reservationFile();
            database.onlineBackup(databaseFile);
            Files.copy(plugin.getDataFolder().toPath().resolve("config.yml"), configFile,
                    StandardCopyOption.COPY_ATTRIBUTES);
            String checksum = sha256(databaseFile);
            Files.writeString(checksumFile, checksum + "  " + databaseFile.getFileName()
                    + System.lineSeparator());
            Files.deleteIfExists(reservationFile);
            prune(directory);
            Result result = new Result(true, startedAt,
                    plugin.messages().text("chat.backup.success-detail", Map.of(
                            "checksum", checksum)), databaseFile);
            lastResult.set(result);
            return result;
        } catch (IOException | RuntimeException exception) {
            cleanup(databaseFile, configFile, checksumFile, reservationFile);
            Result result = new Result(false, startedAt,
                    plugin.messages().text("chat.backup.failure-detail", Map.of(
                            "detail", safeMessage(exception))), null);
            lastResult.set(result);
            return result;
        }
    }

    Result lastResult() {
        return lastResult.get();
    }

    Path resolveDirectory() {
        return resolveDirectory(plugin.getDataFolder().toPath(), settings.directory(),
                plugin.messages()::plainText);
    }

    static Path resolveDirectory(Path dataDirectory, Path configured) {
        return resolveDirectory(dataDirectory, configured, OnlineBackupService::fallbackMessage);
    }

    static Path resolveDirectory(Path dataDirectory, Path configured,
                                 BiFunction<String, Map<String, ?>, String> messageResolver) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(configured, "configured");
        Objects.requireNonNull(messageResolver, "messageResolver");
        Path normalizedDataDirectory = dataDirectory.toAbsolutePath().normalize();
        if (configured.isAbsolute()) {
            throw new IllegalArgumentException(resolveMessage(messageResolver, ABSOLUTE_PATH,
                    Map.of("path", safeText(configured))));
        }
        Path directory = normalizedDataDirectory.resolve(configured).normalize();
        if (!directory.startsWith(normalizedDataDirectory)) {
            throw new IllegalArgumentException(resolveMessage(messageResolver,
                    RELATIVE_PATH_ESCAPE, Map.of("path", safeText(configured))));
        }
        verifyRealPathContained(normalizedDataDirectory, directory, messageResolver);
        return directory;
    }

    private static void verifyRealPathContained(Path dataDirectory, Path directory,
                                                BiFunction<String, Map<String, ?>, String>
                                                        messageResolver) {
        if (!Files.exists(dataDirectory)) {
            return;
        }
        try {
            Path realDataDirectory = dataDirectory.toRealPath();
            Path existing = directory;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing == null || !existing.toRealPath().startsWith(realDataDirectory)) {
                throw new IllegalArgumentException(resolveMessage(messageResolver, SYMLINK_ESCAPE,
                        Map.of("path", safeText(directory))));
            }
            if (Files.exists(directory) && !Files.isDirectory(directory)) {
                throw new IllegalArgumentException(resolveMessage(messageResolver, NOT_DIRECTORY,
                        Map.of("path", safeText(directory))));
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException(resolveMessage(messageResolver,
                    REAL_PATH_VERIFICATION_FAILURE, Map.of("path", safeText(directory))),
                    exception);
        }
    }

    static BackupPaths reserveBackupPaths(Path directory, Instant startedAt) throws IOException {
        return reserveBackupPaths(directory, startedAt, OnlineBackupService::fallbackMessage);
    }

    static BackupPaths reserveBackupPaths(Path directory, Instant startedAt,
                                          BiFunction<String, Map<String, ?>, String>
                                                  messageResolver) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(messageResolver, "messageResolver");
        String baseStem = PREFIX + STAMP.format(startedAt);
        for (int sequence = 0; sequence < 10_000; sequence++) {
            String stem = sequence == 0 ? baseStem : baseStem + "-" + sequence;
            BackupPaths paths = new BackupPaths(directory.resolve(stem + ".db"),
                    directory.resolve(stem + "-config.yml"),
                    directory.resolve(stem + ".sha256"),
                    directory.resolve(stem + ".pending"));
            try {
                Files.createFile(paths.reservationFile());
            } catch (FileAlreadyExistsException exception) {
                continue;
            }
            if (!Files.exists(paths.databaseFile()) && !Files.exists(paths.configFile())
                    && !Files.exists(paths.checksumFile())) {
                return paths;
            }
            Files.deleteIfExists(paths.reservationFile());
        }
        throw new IOException(resolveMessage(messageResolver, FILENAME_EXHAUSTED, Map.of()));
    }

    private void prune(Path directory) throws IOException {
        List<Path> databases;
        try (var stream = Files.list(directory)) {
            databases = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(PREFIX))
                    .filter(path -> path.getFileName().toString().endsWith(".db"))
                    .sorted(Comparator.comparingLong(OnlineBackupService::lastModified)
                            .reversed()).toList();
        }
        for (Path obsolete : databases.stream().skip(settings.retentionCount()).toList()) {
            String name = obsolete.getFileName().toString();
            String stem = name.substring(0, name.length() - 3);
            Files.deleteIfExists(obsolete);
            Files.deleteIfExists(directory.resolve(stem + "-config.yml"));
            Files.deleteIfExists(directory.resolve(stem + ".sha256"));
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return Long.MIN_VALUE;
        }
    }

    private String sha256(Path path) throws IOException {
        return sha256(path, plugin.messages()::plainText);
    }

    static String sha256(Path path, BiFunction<String, Map<String, ?>, String> messageResolver)
            throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(messageResolver, "messageResolver");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(resolveMessage(messageResolver, SHA256_UNAVAILABLE,
                    Map.of()), exception);
        }
        try (InputStream stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void cleanup(Path... paths) {
        for (Path path : paths) {
            if (path == null) {
                continue;
            }
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // 保留原始失败结果；残留临时文件会由管理员诊断发现。
            }
        }
    }

    private static String safeMessage(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getSimpleName() : value;
    }

    private static String resolveMessage(
            BiFunction<String, Map<String, ?>, String> messageResolver,
            String key, Map<String, ?> placeholders) {
        try {
            String message = messageResolver.apply(key, placeholders);
            if (message != null && !message.isBlank()) {
                return message;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 备份路径校验和技术诊断仍需在消息配置异常时返回稳定键名。
        }
        return fallbackMessage(key, placeholders);
    }

    private static String fallbackMessage(String key, Map<String, ?> placeholders) {
        return ConfigurationValues.fallbackMessage(key, placeholders);
    }

    private static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    record Result(boolean success, Instant completedAt, String detail, Path databaseFile) {
    }

    record BackupPaths(Path databaseFile, Path configFile, Path checksumFile,
                       Path reservationFile) {
    }
}

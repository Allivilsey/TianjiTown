package cn.tianji.town.paper;

import cn.tianji.town.storage.database.DatabaseGate;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

final class PhaseFiveBackupService {
    private static final String PREFIX = "tianjitown-";
    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private final TianjiTownPlugin plugin;
    private final DatabaseGate database;
    private final PhaseFiveSettings.Backup settings;
    private final AtomicReference<Result> lastResult = new AtomicReference<>(
            new Result(false, null, "尚未执行", null));

    PhaseFiveBackupService(TianjiTownPlugin plugin, DatabaseGate database,
                           PhaseFiveSettings.Backup settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.database = Objects.requireNonNull(database, "database");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    Result create() {
        if (plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("SQLite 备份不能在 Paper 主线程执行");
        }
        Instant startedAt = Instant.now();
        Path directory = resolveDirectory();
        String stem = PREFIX + STAMP.format(startedAt);
        Path databaseFile = directory.resolve(stem + ".db");
        Path configFile = directory.resolve(stem + "-config.yml");
        Path checksumFile = directory.resolve(stem + ".sha256");
        try {
            Files.createDirectories(directory);
            database.onlineBackup(databaseFile);
            Files.copy(plugin.getDataFolder().toPath().resolve("config.yml"), configFile,
                    StandardCopyOption.COPY_ATTRIBUTES);
            String checksum = sha256(databaseFile);
            Files.writeString(checksumFile, checksum + "  " + databaseFile.getFileName()
                    + System.lineSeparator());
            prune(directory);
            Result result = new Result(true, startedAt,
                    "SQLite 在线备份与配置快照已完成，SHA-256=" + checksum, databaseFile);
            lastResult.set(result);
            return result;
        } catch (IOException | RuntimeException exception) {
            cleanup(databaseFile, configFile, checksumFile);
            Result result = new Result(false, startedAt,
                    "备份失败: " + safeMessage(exception), databaseFile);
            lastResult.set(result);
            return result;
        }
    }

    Result lastResult() {
        return lastResult.get();
    }

    Path resolveDirectory() {
        Path configured = settings.directory();
        Path directory = configured.isAbsolute() ? configured
                : plugin.getDataFolder().toPath().resolve(configured);
        return directory.toAbsolutePath().normalize();
    }

    private void prune(Path directory) throws IOException {
        List<Path> databases;
        try (var stream = Files.list(directory)) {
            databases = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(PREFIX))
                    .filter(path -> path.getFileName().toString().endsWith(".db"))
                    .sorted(Comparator.comparingLong(PhaseFiveBackupService::lastModified)
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

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
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

    record Result(boolean success, Instant completedAt, String detail, Path databaseFile) {
    }
}

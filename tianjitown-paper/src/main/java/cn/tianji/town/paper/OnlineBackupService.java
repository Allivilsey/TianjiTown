package cn.tianji.town.paper;

import cn.tianji.town.storage.database.DatabaseGate;

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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

final class OnlineBackupService {
    private static final String PREFIX = "tianjitown-";
    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private final TianjiTownPlugin plugin;
    private final DatabaseGate database;
    private final TownBonusSettings.Backup settings;
    private final AtomicReference<Result> lastResult = new AtomicReference<>(
            new Result(false, null, "尚未执行", null));

    OnlineBackupService(TianjiTownPlugin plugin, DatabaseGate database,
                           TownBonusSettings.Backup settings) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.database = Objects.requireNonNull(database, "database");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    Result create() {
        if (plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("SQLite 备份不能在 Paper 主线程执行");
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
            BackupPaths paths = reserveBackupPaths(directory, startedAt);
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
                    "SQLite 在线备份与配置快照已完成，SHA-256=" + checksum, databaseFile);
            lastResult.set(result);
            return result;
        } catch (IOException | RuntimeException exception) {
            cleanup(databaseFile, configFile, checksumFile, reservationFile);
            Result result = new Result(false, startedAt,
                    "备份失败: " + safeMessage(exception), null);
            lastResult.set(result);
            return result;
        }
    }

    Result lastResult() {
        return lastResult.get();
    }

    Path resolveDirectory() {
        return resolveDirectory(plugin.getDataFolder().toPath(), settings.directory());
    }

    static Path resolveDirectory(Path dataDirectory, Path configured) {
        Path normalizedDataDirectory = dataDirectory.toAbsolutePath().normalize();
        if (configured.isAbsolute()) {
            throw new IllegalArgumentException("备份目录必须位于插件数据目录内，不能使用绝对路径: "
                    + configured);
        }
        Path directory = normalizedDataDirectory.resolve(configured).normalize();
        if (!directory.startsWith(normalizedDataDirectory)) {
            throw new IllegalArgumentException("相对备份目录不能超出插件数据目录: " + configured);
        }
        verifyRealPathContained(normalizedDataDirectory, directory);
        return directory;
    }

    private static void verifyRealPathContained(Path dataDirectory, Path directory) {
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
                throw new IllegalArgumentException("备份目录通过符号链接或联接超出插件数据目录: "
                        + directory);
            }
            if (Files.exists(directory) && !Files.isDirectory(directory)) {
                throw new IllegalArgumentException("备份目录指向文件: " + directory);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法验证备份目录真实路径: " + directory,
                    exception);
        }
    }

    static BackupPaths reserveBackupPaths(Path directory, Instant startedAt) throws IOException {
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
        throw new IOException("同一秒内备份任务过多，无法分配唯一文件名");
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

    record Result(boolean success, Instant completedAt, String detail, Path databaseFile) {
    }

    record BackupPaths(Path databaseFile, Path configFile, Path checksumFile,
                       Path reservationFile) {
    }
}

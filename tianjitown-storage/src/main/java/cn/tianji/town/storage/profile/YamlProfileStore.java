package cn.tianji.town.storage.profile;

import cn.tianji.town.core.profile.ProfileChecksum;
import cn.tianji.town.core.profile.ProfileValidator;
import cn.tianji.town.core.profile.TownProfile;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class YamlProfileStore {
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "schema-version", "town-id", "revision", "created-at", "name", "short-name",
            "description", "rules", "public-settings", "checksum");

    private final Yaml yaml;
    private final ProfileValidator validator = new ProfileValidator();

    public YamlProfileStore() {
        LoaderOptions loader = new LoaderOptions();
        loader.setAllowDuplicateKeys(false);
        loader.setMaxAliasesForCollections(0);
        loader.setCodePointLimit(1_000_000);
        DumperOptions dumper = new DumperOptions();
        dumper.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumper.setPrettyFlow(true);
        dumper.setIndent(2);
        yaml = new Yaml(new SafeConstructor(loader), new Representer(dumper), dumper, loader);
    }

    public TownProfile sign(TownProfile profile) {
        return profile.withChecksum(ProfileChecksum.calculate(profile));
    }

    public void writeAtomically(Path target, TownProfile profile) throws IOException {
        TownProfile signed = sign(profile);
        List<String> errors = validator.validate(signed);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(),
                target.getFileName().toString(), ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                yaml.dump(toMap(signed), writer);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public ReadResult readAndValidate(Path source) throws IOException {
        Object loaded;
        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            loaded = yaml.load(reader);
        }
        if (!(loaded instanceof Map<?, ?> raw)) {
            return ReadResult.invalid(List.of("YAML 根节点必须是对象"));
        }
        List<String> errors = new ArrayList<>();
        raw.keySet().stream().map(String::valueOf).filter(key -> !ALLOWED_FIELDS.contains(key))
                .forEach(key -> errors.add("不允许的字段: " + key));
        try {
            TownProfile profile = fromMap(raw);
            errors.addAll(validator.validate(profile));
            return errors.isEmpty() ? ReadResult.valid(profile) : ReadResult.invalid(errors);
        } catch (RuntimeException exception) {
            errors.add("字段格式错误: " + exception.getMessage());
            return ReadResult.invalid(errors);
        }
    }

    private Map<String, Object> toMap(TownProfile profile) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schema-version", profile.schemaVersion());
        map.put("town-id", profile.townId().toString());
        map.put("revision", profile.revision());
        map.put("created-at", profile.createdAt().toString());
        map.put("name", profile.name());
        map.put("short-name", profile.shortName());
        map.put("description", profile.description());
        map.put("rules", profile.rules());
        map.put("public-settings", new java.util.TreeMap<>(profile.publicSettings()));
        map.put("checksum", profile.checksum());
        return map;
    }

    @SuppressWarnings("unchecked")
    private TownProfile fromMap(Map<?, ?> map) {
        List<String> rules = ((List<?>) required(map, "rules")).stream().map(String::valueOf).toList();
        Map<String, Boolean> settings = new LinkedHashMap<>();
        ((Map<?, ?>) required(map, "public-settings")).forEach((key, value) -> {
            if (!(value instanceof Boolean booleanValue)) {
                throw new IllegalArgumentException("public-settings." + key + " 必须是布尔值");
            }
            settings.put(String.valueOf(key), booleanValue);
        });
        return new TownProfile(
                ((Number) required(map, "schema-version")).intValue(),
                UUID.fromString(String.valueOf(required(map, "town-id"))),
                ((Number) required(map, "revision")).longValue(),
                Instant.parse(String.valueOf(required(map, "created-at"))),
                String.valueOf(required(map, "name")),
                String.valueOf(required(map, "short-name")),
                String.valueOf(required(map, "description")),
                rules, settings, String.valueOf(required(map, "checksum")));
    }

    private Object required(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("缺少字段 " + key);
        }
        return value;
    }

    public record ReadResult(TownProfile profile, List<String> errors) {
        public static ReadResult valid(TownProfile profile) {
            return new ReadResult(profile, List.of());
        }

        public static ReadResult invalid(List<String> errors) {
            return new ReadResult(null, List.copyOf(errors));
        }

        public boolean valid() {
            return errors.isEmpty();
        }
    }
}

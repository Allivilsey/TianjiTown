package cn.tianji.town.paper;

import cn.tianji.town.core.application.ApplicationStatus;
import cn.tianji.town.core.town.TownStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

final class TownAdminCompletionEngine {
    private static final int MAX_SUGGESTIONS = 100;
    private static final List<String> ROOTS = List.of(
            "help", "status", "reload", "audit", "handbook", "application", "town",
            "member", "mayor", "land", "maintenance");

    List<String> complete(String[] args, Snapshot snapshot, Dynamic dynamic) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(dynamic, "dynamic");
        if (args.length <= 1) {
            List<String> roots = new ArrayList<>(ROOTS);
            if (dynamic.playerSender()) {
                roots.add("station");
            }
            if (dynamic.phaseZeroAllowed()) {
                roots.add("phase0");
            }
            return filter(roots, current(args));
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "audit" -> args.length == 2
                    ? filter(List.of("10", "20", "50", "100", "200"), args[1]) : List.of();
            case "phase0" -> phaseZero(args, dynamic);
            case "station" -> args.length == 2 && dynamic.playerSender()
                    ? filter(List.of("create"), args[1]) : List.of();
            case "handbook" -> args.length == 2
                    ? filter(dynamic.onlinePlayerNames(), args[1]) : List.of();
            case "maintenance" -> args.length == 2
                    ? filter(List.of("off", "on", "status"), args[1]) : List.of();
            case "application" -> application(args, snapshot);
            case "town" -> town(args, snapshot);
            case "member" -> member(args, snapshot, dynamic);
            case "mayor" -> mayor(args, snapshot, dynamic);
            case "land" -> land(args, snapshot, dynamic);
            default -> List.of();
        };
    }

    private List<String> phaseZero(String[] args, Dynamic dynamic) {
        if (!dynamic.phaseZeroAllowed()) {
            return List.of();
        }
        if (args.length == 2) {
            return filter(List.of("status", "residence-smoke"), args[1]);
        }
        if (!args[1].equalsIgnoreCase("residence-smoke")) {
            return List.of();
        }
        return switch (args.length) {
            case 3 -> filter(dynamic.worldNames(), args[2]);
            case 4 -> dynamic.currentChunkX() == null ? List.of()
                    : filter(List.of(dynamic.currentChunkX().toString()), args[3]);
            case 5 -> dynamic.currentChunkZ() == null ? List.of()
                    : filter(List.of(dynamic.currentChunkZ().toString()), args[4]);
            case 6 -> filter(dynamic.onlinePlayerIds().stream().map(UUID::toString).toList(), args[5]);
            case 7 -> filter(List.of("--confirm-empty-chunk"), args[6]);
            case 8 -> filter(List.of("--confirm-preproduction"), args[7]);
            default -> List.of();
        };
    }

    private List<String> application(String[] args, Snapshot snapshot) {
        if (args.length == 2) {
            return filter(List.of("list", "approve", "reject", "change"), args[1]);
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            return List.of();
        }
        int reasonIndex = indexOf(args, "--reason");
        if (reasonIndex >= 0) {
            return args.length == reasonIndex + 2 ? filter(List.of(switch (action) {
                case "approve" -> "管理员批准申请";
                case "reject" -> "管理员拒绝申请";
                default -> "请补充申请资料";
            }), current(args)) : List.of();
        }
        Predicate<ApplicationCandidate> predicate = action.equals("approve")
                ? candidate -> candidate.status() == ApplicationStatus.SUBMITTED
                || candidate.status() == ApplicationStatus.UNDER_REVIEW
                || candidate.status() == ApplicationStatus.PROVISION_FAILED
                : candidate -> candidate.status() == ApplicationStatus.SUBMITTED
                || candidate.status() == ApplicationStatus.UNDER_REVIEW;
        List<String> names = snapshot.applications().stream().filter(predicate)
                .map(ApplicationCandidate::name).toList();
        return completePhraseOrOptions(args, 2, names, List.of("--reason"));
    }

    private List<String> town(String[] args, Snapshot snapshot) {
        if (args.length == 2) {
            return filter(List.of("view", "delete"), args[1]);
        }
        List<String> names = townNames(snapshot, ignored -> true);
        if (args[1].equalsIgnoreCase("view")) {
            return completePhrase(args, 2, names);
        }
        if (args[1].equalsIgnoreCase("delete")) {
            if (indexOf(args, "--reason") >= 0) {
                return TownCommandParser.contains(args, "--confirm") ? List.of()
                        : filter(List.of("--confirm"), current(args));
            }
            if (TownCommandParser.contains(args, "--confirm")) {
                return filter(List.of("--reason"), current(args));
            }
            return completePhraseOrOptions(args, 2, names, List.of("--reason", "--confirm"));
        }
        return List.of();
    }

    private List<String> member(String[] args, Snapshot snapshot, Dynamic dynamic) {
        if (args.length == 2) {
            return filter(List.of("invite", "add", "remove"), args[1]);
        }
        int playerIndex = indexOf(args, "--player");
        if (playerIndex < 0) {
            return completePhraseOrOptions(args, 2,
                    townNames(snapshot, candidate -> candidate.status() == TownStatus.ACTIVE),
                    List.of("--player"));
        }
        UUID townId = townIdByName(snapshot, join(args, 2, playerIndex));
        int reasonIndex = indexOf(args, "--reason");
        if (args.length == playerIndex + 2) {
            if (args[1].equalsIgnoreCase("remove")) {
                return filter(memberLabels(snapshot, dynamic, townId), current(args));
            }
            Set<UUID> existing = Set.copyOf(snapshot.membersByTown().getOrDefault(townId, List.of()));
            return filter(dynamic.players().stream().filter(PlayerCandidate::online)
                    .filter(candidate -> !existing.contains(candidate.id()))
                    .map(PlayerCandidate::label).toList(), current(args));
        }
        if (reasonIndex < 0) {
            return filter(List.of("--reason"), current(args));
        }
        if (args.length == reasonIndex + 2) {
            return filter(List.of("管理员代办成员操作"), current(args));
        }
        return List.of();
    }

    private List<String> mayor(String[] args, Snapshot snapshot, Dynamic dynamic) {
        if (args.length == 2) {
            return filter(List.of("transfer"), args[1]);
        }
        if (!args[1].equalsIgnoreCase("transfer")) {
            return List.of();
        }
        int playerIndex = indexOf(args, "--player");
        if (playerIndex < 0) {
            return completePhraseOrOptions(args, 2,
                    townNames(snapshot, candidate -> candidate.status() == TownStatus.ACTIVE),
                    List.of("--player"));
        }
        UUID townId = townIdByName(snapshot, join(args, 2, playerIndex));
        int reasonIndex = indexOf(args, "--reason");
        if (args.length == playerIndex + 2) {
            return filter(memberLabels(snapshot, dynamic, townId), current(args));
        }
        if (reasonIndex < 0) {
            return filter(List.of("--reason"), current(args));
        }
        if (args.length == reasonIndex + 2) {
            return filter(List.of("紧急转移镇长"), current(args));
        }
        return List.of();
    }

    private List<String> land(String[] args, Snapshot snapshot, Dynamic dynamic) {
        if (args.length == 2) {
            List<String> actions = new ArrayList<>(List.of("reconcile", "rebuild"));
            if (dynamic.playerSender()) {
                actions.add("preview");
            }
            return filter(actions, args[1]);
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>(townNames(snapshot,
                town -> town.status() != TownStatus.ARCHIVED));
        if (!action.equals("preview")) {
            candidates.add("all");
        }
        List<String> options = switch (action) {
            case "reconcile" -> List.of("--repair");
            case "rebuild" -> List.of("--confirm");
            default -> List.of();
        };
        return completePhraseOrOptions(args, 2, candidates, options);
    }

    private List<String> memberLabels(Snapshot snapshot, Dynamic dynamic, UUID townId) {
        if (townId == null) {
            return List.of();
        }
        return snapshot.membersByTown().getOrDefault(townId, List.of()).stream()
                .map(dynamic::playerLabel).toList();
    }

    private List<String> townNames(Snapshot snapshot, Predicate<TownCandidate> predicate) {
        return snapshot.towns().stream().filter(predicate)
                .map(TownCandidate::name).toList();
    }

    private UUID townIdByName(Snapshot snapshot, String name) {
        String normalized = cn.tianji.town.core.application.ApplicationText.normalizeNameKey(name);
        return snapshot.towns().stream()
                .filter(candidate -> cn.tianji.town.core.application.ApplicationText
                        .normalizeNameKey(candidate.name()).equals(normalized))
                .map(TownCandidate::id).findFirst().orElse(null);
    }

    private List<String> completePhraseOrOptions(String[] args, int start, List<String> phrases,
                                                  List<String> options) {
        List<String> phraseResult = completePhrase(args, start, phrases);
        if (!phraseResult.isEmpty()) {
            return phraseResult;
        }
        String entered = join(args, start, args.length).strip();
        boolean exact = phrases.stream().anyMatch(phrase -> phrase.equalsIgnoreCase(entered));
        return exact ? filter(options, current(args)) : List.of();
    }

    private List<String> completePhrase(String[] args, int start, List<String> phrases) {
        if (args.length <= start || indexOf(args, "--reason") >= 0
                || indexOf(args, "--player") >= 0 || indexOf(args, "--confirm") >= 0
                || indexOf(args, "--repair") >= 0) {
            return List.of();
        }
        String entered = join(args, start, args.length);
        String prior = args.length - 1 <= start ? ""
                : join(args, start, args.length - 1) + " ";
        List<String> suffixes = phrases.stream()
                .filter(phrase -> phrase.toLowerCase(Locale.ROOT)
                        .startsWith(entered.toLowerCase(Locale.ROOT)))
                .filter(phrase -> phrase.length() >= prior.length())
                .map(phrase -> phrase.substring(prior.length())).toList();
        return filter(suffixes, current(args));
    }

    private static int indexOf(String[] args, String value) {
        for (int index = 0; index < args.length; index++) {
            if (value.equalsIgnoreCase(args[index])) {
                return index;
            }
        }
        return -1;
    }

    private static String join(String[] args, int start, int end) {
        if (start >= end) {
            return "";
        }
        return String.join(" ", java.util.Arrays.copyOfRange(args, start, end));
    }

    private List<String> filter(Collection<String> candidates, String prefix) {
        String normalized = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return candidates.stream().filter(Objects::nonNull).filter(candidate -> !candidate.isBlank())
                .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(normalized))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)).stream()
                .sorted(Comparator.comparing(String::toLowerCase))
                .limit(MAX_SUGGESTIONS)
                .toList();
    }

    private static String current(String[] args) {
        return args.length == 0 ? "" : args[args.length - 1];
    }

    record Snapshot(List<ApplicationCandidate> applications, List<TownCandidate> towns,
                    Map<UUID, List<UUID>> membersByTown) {
        Snapshot {
            applications = List.copyOf(applications);
            towns = List.copyOf(towns);
            membersByTown = Map.copyOf(membersByTown);
        }

        static Snapshot empty() {
            return new Snapshot(List.of(), List.of(), Map.of());
        }
    }

    record ApplicationCandidate(UUID id, String name, ApplicationStatus status) {
    }

    record TownCandidate(UUID id, String name, TownStatus status) {
    }

    record PlayerCandidate(UUID id, String name, boolean online) {
        String label() {
            return name == null || name.isBlank() ? id.toString() : name;
        }
    }

    record Dynamic(List<PlayerCandidate> players, List<String> worldNames, Integer currentChunkX,
                   Integer currentChunkZ, boolean playerSender, boolean phaseZeroAllowed) {
        Dynamic {
            players = List.copyOf(players);
            worldNames = List.copyOf(worldNames);
        }

        List<String> onlinePlayerNames() {
            return players.stream().filter(PlayerCandidate::online).map(PlayerCandidate::label).toList();
        }

        List<UUID> onlinePlayerIds() {
            return players.stream().filter(PlayerCandidate::online).map(PlayerCandidate::id).toList();
        }

        String playerLabel(UUID playerId) {
            return players.stream().filter(candidate -> candidate.id().equals(playerId))
                    .findFirst().map(PlayerCandidate::label).orElse(playerId.toString());
        }
    }
}

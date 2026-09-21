package org.allivlisey.tianjitown.paper.command;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.core.town.TownStatus;

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
import java.util.function.BiFunction;
import java.util.function.Predicate;

public final class TownAdminCompletionEngine {
    private static final int MAX_SUGGESTIONS = 100;
    private final BiFunction<String, Map<String, ?>, String> messageResolver;

    public TownAdminCompletionEngine(BiFunction<String, Map<String, ?>, String> messageResolver) {
        this.messageResolver = Objects.requireNonNull(messageResolver, "messageResolver");
    }

    public List<String> complete(String[] args, Snapshot snapshot, Dynamic dynamic) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(dynamic, "dynamic");
        if (args.length == 2 && args[0].equalsIgnoreCase("help")) {
            return filter(TownAdminPermissions.HELP_TOPICS, args[1]);
        }
        if (args.length < 3) {
            return List.of();
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "application" -> application(args, snapshot, dynamic);
            case "town" -> town(args, snapshot);
            case "member" -> member(args, snapshot, dynamic);
            case "mayor" -> mayor(args, snapshot, dynamic);
            case "vote" -> vote(args, snapshot);
            case "land" -> land(args, snapshot, dynamic);
            case "money", "tax", "ledger" -> economyCommands(args, snapshot);
            case "buff" -> buffs(args, snapshot);
            default -> List.of();
        };
    }

    private List<String> application(String[] args, Snapshot snapshot, Dynamic dynamic) {
        if (args.length != 3) return List.of();
        if (args[1].equalsIgnoreCase("delate")) {
            return filter(snapshot.applications().stream()
                    .flatMap(candidate -> java.util.stream.Stream.of(candidate.code(), candidate.id().toString()))
                    .toList(), current(args));
        }
        if (args[1].equalsIgnoreCase("clearcd")) {
            List<String> players = dynamic.players().stream().map(PlayerCandidate::label).toList();
            return filter(players.isEmpty() ? List.of(playerHint()) : players, current(args));
        }
        return List.of();
    }

    private List<String> town(String[] args, Snapshot snapshot) {

        if (args[1].equalsIgnoreCase("list") && args.length == 3) {
            return filter(List.of("active", "provisioning", "archived"), current(args));
        }
        List<String> names = townNames(snapshot, ignored -> true);
        if (args[1].equalsIgnoreCase("view")) {
            return completePhrase(args, 2, names);
        }
        if (args[1].equalsIgnoreCase("delete")) {
            return nameThenHint(args, 2, townNames(snapshot, TownCandidate::reuseBlocked), reasonHint());
        }
        return List.of();
    }

    private List<String> member(String[] args, Snapshot snapshot, Dynamic dynamic) {

        if (!Set.of("add", "remove", "role").contains(args[1].toLowerCase(Locale.ROOT))) {
            return List.of();
        }
        List<String> names = townNames(snapshot,
                candidate -> candidate.status() == TownStatus.ACTIVE);
        NameMatch match = exactNamePrefix(args, 2, names);
        List<String> phraseSuggestions = completePhrase(args, 2, names);
        if (match == null || args.length <= match.end()) {
            return phraseSuggestions;
        }
        int tailLength = args.length - match.end();
        if (tailLength == 1) {
            UUID townId = townIdByName(snapshot, match.name());
            List<String> players;
            if (args[1].equalsIgnoreCase("remove")) {
                players = memberLabels(snapshot, dynamic, townId);
            } else {
                Set<UUID> existing = Set.copyOf(
                        snapshot.membersByTown().getOrDefault(townId, List.of()));
                players = dynamic.players().stream().filter(PlayerCandidate::online)
                        .filter(candidate -> !existing.contains(candidate.id()))
                        .map(PlayerCandidate::label).toList();
            }
            List<String> playerSuggestions = filter(
                    players.isEmpty() ? List.of(playerHint()) : players, current(args));
            return merge(phraseSuggestions, playerSuggestions);
        }
        if (tailLength == 2 && args[1].equalsIgnoreCase("role")) {
            return filter(List.of("MEMBER", "DEPUTY_MAYOR"), current(args));
        }
        return tailLength == 2 ? reasonHint(current(args)) : List.of();
    }

    private List<String> vote(String[] args, Snapshot snapshot) {
        return args[1].equalsIgnoreCase("cancel")
                ? nameThenHint(args, 2, townNames(snapshot, town -> town.status() != TownStatus.ARCHIVED), reasonHint())
                : List.of();
    }

    private List<String> mayor(String[] args, Snapshot snapshot, Dynamic dynamic) {

        if (!args[1].equalsIgnoreCase("transfer")) {
            return List.of();
        }
        List<String> names = townNames(snapshot,
                candidate -> candidate.status() == TownStatus.ACTIVE);
        NameMatch match = exactNamePrefix(args, 2, names);
        List<String> phraseSuggestions = completePhrase(args, 2, names);
        if (match == null || args.length <= match.end()) {
            return phraseSuggestions;
        }
        int tailLength = args.length - match.end();
        if (tailLength == 1) {
            List<String> players = memberLabels(snapshot, dynamic,
                    townIdByName(snapshot, match.name()));
            return merge(phraseSuggestions, filter(
                    players.isEmpty() ? List.of(playerHint()) : players, current(args)));
        }
        return tailLength == 2 ? reasonHint(current(args)) : List.of();
    }

    private List<String> land(String[] args, Snapshot snapshot, Dynamic dynamic) {

        String action = args[1].toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>(townNames(snapshot,
                town -> town.status() != TownStatus.ARCHIVED));
        if (!action.equals("preview")) {
            candidates.add("all");
        }
        List<String> phraseSuggestions = completePhrase(args, 2, candidates);
        if (!action.equals("reconcile")) {
            return phraseSuggestions;
        }
        NameMatch match = exactNamePrefix(args, 2, candidates);
        if (match != null && args.length == match.end() + 1) {
            return merge(phraseSuggestions, filter(List.of("repair"), current(args)));
        }
        return phraseSuggestions;
    }

    private List<String> economyCommands(String[] args, Snapshot snapshot) {
        String root = args[0].toLowerCase(Locale.ROOT);

        if (root.equals("money") && Set.of("reconcile", "resolve", "pending", "subsidy", "tax")
                .contains(args[1].toLowerCase(Locale.ROOT))) {
            return List.of();
        }
        List<String> names = townNames(snapshot, town -> town.status() == TownStatus.ACTIVE);
        NameMatch match = exactNamePrefix(args, 2, names);
        List<String> phraseSuggestions = completePhrase(args, 2, names);
        if (match == null || args.length <= match.end()) {
            return phraseSuggestions;
        }
        int tail = args.length - match.end();
        if ((root.equals("money") && args[1].equalsIgnoreCase("adjust")
                || root.equals("tax")) && tail == 1) {
            return merge(phraseSuggestions, amountHint(current(args)));
        }
        if ((root.equals("money") && args[1].equalsIgnoreCase("adjust")
                || root.equals("tax")) && tail == 2) {
            return reasonHint(current(args));
        }
        return phraseSuggestions;
    }

    private List<String> buffs(String[] args, Snapshot snapshot) {

        String action = args[1].toLowerCase(Locale.ROOT);
        if (!action.equals("list") && !action.equals("set")) {
            return List.of();
        }
        List<String> names = townNames(snapshot, town -> town.status() == TownStatus.ACTIVE);
        NameMatch match = exactNamePrefix(args, 2, names);
        List<String> phrases = completePhrase(args, 2, names);
        if (match == null || args.length <= match.end()) {
            return phrases;
        }
        int tail = args.length - match.end();
        if (action.equals("list")) {
            return phrases;
        }
        return switch (tail) {
            case 1 -> filter(snapshot.buffLevels().keySet(), current(args));
            case 2 -> filter(List.of("1", "2", "3", "4"), current(args));
            case 3 -> filter(java.util.stream.IntStream.rangeClosed(1,
                    snapshot.buffLevels().getOrDefault(args[match.end()].toLowerCase(Locale.ROOT), 0))
                    .mapToObj(Integer::toString).toList(), current(args));
            default -> List.of();
        };
    }

    private List<String> nameThenHint(String[] args, int start, List<String> names, String hint) {
        List<String> phraseSuggestions = completePhrase(args, start, names);
        NameMatch match = exactNamePrefix(args, start, names);
        if (match != null && args.length == match.end() + 1) {
            return merge(phraseSuggestions, hint(current(args), hint));
        }
        return phraseSuggestions;
    }

    private List<String> hint(String current, String hint) {
        return current.isEmpty() ? List.of(hint) : List.of();
    }

    private List<String> reasonHint(String current) {
        return hint(current, reasonHint());
    }

    private List<String> amountHint(String current) {
        return hint(current, resolveHint(TownAdminCompletionHints.AMOUNT_KEY));
    }

    private String reasonHint() {
        return resolveHint(TownAdminCompletionHints.REASON_KEY);
    }

    private String playerHint() {
        return resolveHint(TownAdminCompletionHints.PLAYER_KEY);
    }

    private String resolveHint(String key) {
        return TownAdminCompletionHints.resolve(messageResolver, key);
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
                .map(TownCandidate::code).toList();
    }

    private UUID townIdByName(Snapshot snapshot, String name) {
        String normalized = ApplicationText.normalizeNameKey(name);
        return snapshot.towns().stream()
                .filter(candidate -> ApplicationText.normalizeNameKey(candidate.code())
                        .equals(normalized))
                .map(TownCandidate::id).findFirst().orElse(null);
    }

    private NameMatch exactNamePrefix(String[] args, int start, List<String> names) {
        if (start >= args.length) {
            return null;
        }
        return names.stream().filter(name -> name.equalsIgnoreCase(args[start]))
                .findFirst().map(name -> new NameMatch(name, start + 1)).orElse(null);
    }

    private List<String> completePhrase(String[] args, int start, List<String> phrases) {
        if (args.length <= start) {
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

    private static String join(String[] args, int start, int end) {
        if (start >= end) {
            return "";
        }
        return String.join(" ", java.util.Arrays.copyOfRange(args, start, end));
    }

    @SafeVarargs
    private final List<String> merge(List<String>... groups) {
        List<String> merged = new ArrayList<>();
        for (List<String> group : groups) {
            merged.addAll(group);
        }
        return filter(merged, "");
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

    private record NameMatch(String name, int end) {
    }

    public record Snapshot(List<TownCandidate> towns,
                    Map<UUID, List<UUID>> membersByTown, Map<String, Integer> buffLevels,
                    List<ApplicationCandidate> applications) {
        public Snapshot(List<TownCandidate> towns, Map<UUID, List<UUID>> membersByTown,
                        Map<String, Integer> buffLevels) {
            this(towns, membersByTown, buffLevels, List.of());
        }
        public Snapshot(List<TownCandidate> towns, Map<UUID, List<UUID>> membersByTown) {
            this(towns, membersByTown, Map.of());
        }
        public Snapshot {
            towns = List.copyOf(towns);
            membersByTown = Map.copyOf(membersByTown);
            buffLevels = Map.copyOf(buffLevels);
            applications = List.copyOf(applications);
        }

        static Snapshot empty() {
            return new Snapshot(List.of(), Map.of());
        }
    }

    public record ApplicationCandidate(UUID id, String code, UUID applicantId) {
    }

    public record TownCandidate(UUID id, String code, TownStatus status, boolean reuseBlocked) {
    }

    public record PlayerCandidate(UUID id, String name, boolean online) {
        String label() {
            return name == null || name.isBlank() ? id.toString() : name;
        }
    }

    public record Dynamic(List<PlayerCandidate> players, boolean playerSender) {
        public Dynamic {
            players = List.copyOf(players);
        }

        List<String> onlinePlayerNames() {
            return players.stream().filter(PlayerCandidate::online).map(PlayerCandidate::label).toList();
        }

        String playerLabel(UUID playerId) {
            return players.stream().filter(candidate -> candidate.id().equals(playerId))
                    .findFirst().map(PlayerCandidate::label).orElse(playerId.toString());
        }
    }
}

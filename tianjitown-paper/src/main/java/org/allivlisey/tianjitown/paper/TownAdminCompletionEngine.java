package cn.tianji.town.paper;

import cn.tianji.town.core.application.ApplicationStatus;
import cn.tianji.town.core.application.ApplicationText;
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
import java.util.function.BiFunction;
import java.util.function.Predicate;

final class TownAdminCompletionEngine {
    private static final int MAX_SUGGESTIONS = 100;
    private static final List<String> ROOTS = List.of(
            "help", "status", "reload", "audit", "station", "handbook", "application", "town",
            "member", "mayor", "vote", "land", "money", "tax", "ledger", "expand",
            "buff", "maintenance", "diagnose", "backup");
    private final BiFunction<String, Map<String, ?>, String> messageResolver;

    TownAdminCompletionEngine(BiFunction<String, Map<String, ?>, String> messageResolver) {
        this.messageResolver = Objects.requireNonNull(messageResolver, "messageResolver");
    }

    List<String> complete(String[] args, Snapshot snapshot, Dynamic dynamic) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(dynamic, "dynamic");
        if (args.length <= 1) {
            return filter(ROOTS, current(args));
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> args.length == 2
                    ? filter(helpTopics(), args[1]) : List.of();
            case "audit" -> args.length == 2
                    ? filter(List.of("10", "20", "50", "100", "200"), args[1]) : List.of();
            case "diagnose" -> args.length == 2
                    ? filter(List.of("1", "7", "14", "30", "90", "180"), args[1]) : List.of();
            case "station" -> args.length == 2
                    ? filter(dynamic.playerSender()
                    ? List.of("create", "info", "list", "remove") : List.of("list"), args[1])
                    : List.of();
            case "handbook" -> args.length == 2
                    ? filter(dynamic.onlinePlayerNames(), args[1]) : List.of();
            case "maintenance" -> args.length == 2
                    ? filter(List.of("off", "on", "status"), args[1]) : List.of();
            case "application" -> application(args, snapshot);
            case "town" -> town(args, snapshot);
            case "member" -> member(args, snapshot, dynamic);
            case "mayor" -> mayor(args, snapshot, dynamic);
            case "vote" -> vote(args, snapshot, dynamic);
            case "land" -> land(args, snapshot, dynamic);
            case "money", "tax", "ledger", "expand" -> economyCommands(args, snapshot);
            case "buff" -> buffs(args, snapshot);
            default -> List.of();
        };
    }

    private List<String> helpTopics() {
        List<String> topics = new ArrayList<>(List.of(
                "application", "land", "member", "station", "system", "town"));
        topics.add("vote");
        topics.addAll(List.of("money", "tax", "ledger", "expand"));
        topics.add("buff");
        return topics;
    }

    private List<String> application(String[] args, Snapshot snapshot) {
        if (args.length == 2) {
            return filter(List.of("list", "approve", "reject", "change"), args[1]);
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            return List.of();
        }
        if (!Set.of("approve", "reject", "change").contains(action)) {
            return List.of();
        }
        Predicate<ApplicationCandidate> predicate = action.equals("approve")
                ? candidate -> candidate.status() == ApplicationStatus.SUBMITTED
                || candidate.status() == ApplicationStatus.UNDER_REVIEW
                || candidate.status() == ApplicationStatus.PROVISION_FAILED
                : candidate -> candidate.status() == ApplicationStatus.SUBMITTED
                || candidate.status() == ApplicationStatus.UNDER_REVIEW;
        List<String> names = snapshot.applications().stream().filter(predicate)
                .map(ApplicationCandidate::name).toList();
        return nameThenHint(args, 2, names, reasonHint());
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
            return nameThenHint(args, 2, names, reasonHint());
        }
        return List.of();
    }

    private List<String> member(String[] args, Snapshot snapshot, Dynamic dynamic) {
        if (args.length == 2) {
            return filter(List.of("add", "remove", "role"), args[1]);
        }
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

    private List<String> vote(String[] args, Snapshot snapshot, Dynamic dynamic) {
        if (args.length == 2) {
            return filter(List.of("create-kick", "create-mayor", "settle", "cancel"), args[1]);
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("settle")) {
            return args.length == 3 ? filter(List.of("<voteId>"), args[2]) : List.of();
        }
        if (action.equals("cancel")) {
            return switch (args.length) {
                case 3 -> filter(List.of("<voteId>"), args[2]);
                case 4 -> reasonHint(current(args));
                default -> List.of();
            };
        }
        if (!Set.of("create-kick", "create-mayor").contains(action)) {
            return List.of();
        }
        List<String> names = townNames(snapshot, town -> town.status() == TownStatus.ACTIVE);
        NameMatch match = exactNamePrefix(args, 2, names);
        List<String> phraseSuggestions = completePhrase(args, 2, names);
        if (match == null || args.length <= match.end()) {
            return phraseSuggestions;
        }
        if (args.length - match.end() == 1) {
            List<String> players = memberLabels(snapshot, dynamic,
                    townIdByName(snapshot, match.name()));
            return merge(phraseSuggestions, filter(
                    players.isEmpty() ? List.of(playerHint()) : players, current(args)));
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
        if (args.length == 2) {
            return filter(switch (root) {
                case "money" -> List.of("view", "adjust", "reconcile");
                case "tax" -> List.of("set");
                case "ledger" -> List.of("view");
                case "expand" -> List.of("view", "preview");
                default -> List.of();
            }, args[1]);
        }
        if (root.equals("money") && args[1].equalsIgnoreCase("reconcile")) {
            return List.of();
        }
        List<String> names = townNames(snapshot, town -> town.status() == TownStatus.ACTIVE);
        NameMatch match = exactNamePrefix(args, 2, names);
        List<String> phraseSuggestions = completePhrase(args, 2, names);
        if (match == null || args.length <= match.end()) {
            return phraseSuggestions;
        }
        int tail = args.length - match.end();
        if (root.equals("expand") && args[1].equalsIgnoreCase("preview") && tail == 1) {
            return merge(phraseSuggestions,
                    filter(List.of("north", "east", "south", "west"), current(args)));
        }
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
        if (args.length == 2) {
            return filter(List.of("list", "grant"), args[1]);
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (!action.equals("list") && !action.equals("grant")) {
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
            case 1 -> merge(phrases, hint(current(args), "<buffKey>"));
            case 2 -> reasonHint(current(args));
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
                .map(TownCandidate::name).toList();
    }

    private UUID townIdByName(Snapshot snapshot, String name) {
        String normalized = ApplicationText.normalizeNameKey(name);
        return snapshot.towns().stream()
                .filter(candidate -> ApplicationText.normalizeNameKey(candidate.name())
                        .equals(normalized))
                .map(TownCandidate::id).findFirst().orElse(null);
    }

    private NameMatch exactNamePrefix(String[] args, int start, List<String> names) {
        int limit = args.length - (current(args).isEmpty() ? 1 : 0);
        NameMatch best = null;
        for (String name : names) {
            String normalized = ApplicationText.normalizeNameKey(name);
            for (int end = start + 1; end <= limit; end++) {
                if (ApplicationText.normalizeNameKey(join(args, start, end)).equals(normalized)
                        && (best == null || end > best.end())) {
                    best = new NameMatch(name, end);
                }
            }
        }
        return best;
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

    record Dynamic(List<PlayerCandidate> players, boolean playerSender) {
        Dynamic {
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

package org.allivlisey.tianjitown.paper.command;

import org.allivlisey.tianjitown.paper.TianjiTownPlugin;
import org.allivlisey.tianjitown.paper.runtime.TownRuntime;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import revxrsal.commands.Lamp;
import revxrsal.commands.node.ParameterNode;
import revxrsal.commands.node.ExecutionContext;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.exception.SenderNotPlayerException;
import revxrsal.commands.exception.*;
import revxrsal.commands.exception.context.ErrorContext;
import revxrsal.commands.node.LiteralNode;

import java.util.List;
import java.util.Map;

/** Configures Lamp's context injection, permissions, suggestions and localized errors. */
public final class TownAdminLamp {
    private TownAdminLamp() {}

    public static Lamp.Builder<BukkitCommandActor> configure(
            Lamp.Builder<BukkitCommandActor> builder, TianjiTownPlugin plugin,
            TownAdminTabCompleter completer) {
        return builder
                .commandCondition(TownAdminLamp::requireCompleteInput)
                .dispatcherSettings(settings -> settings.failureHandler((actor, attempts, input) -> {
                    String[] words = input.source().split(" ");
                    attempts.stream().max(java.util.Comparator.comparingInt(attempt -> {
                        int matched = 0;
                        for (var node : attempt.context().command().nodes()) {
                            if (!(node instanceof LiteralNode) || matched >= words.length
                                    || !node.name().equalsIgnoreCase(words[matched])) break;
                            matched++;
                        }
                        return matched;
                    })).ifPresent(attempt -> attempt.handleException());
                }))
                .permissionForAnnotation(AdminAccess.class, access -> new revxrsal.commands.command.CommandPermission<>() {
                    private boolean hasPermission(BukkitCommandActor actor) {
                        return access.value().isEmpty()
                                ? TownAdminPermissions.hasAny(actor.sender()::hasPermission)
                                : TownAdminPermissions.has(actor.sender()::hasPermission, access.value());
                    }

                    @Override
                    public boolean isExecutableBy(BukkitCommandActor actor) {
                        return hasPermission(actor) && (!access.playerOnly() || actor.sender() instanceof Player);
                    }

                    @Override
                    public void throwMissingPermission(ExecutionContext<BukkitCommandActor> context) {
                        if (hasPermission(context.actor()) && access.playerOnly()) {
                            throw new SenderNotPlayerException();
                        }
                        throw new NoPermissionException(context.command());
                    }
                })
                .parameterTypes(types -> types
                        .addContextParameter(TownRuntime.class, (parameter, context) -> {
                            TownRuntime runtime = plugin.townRuntime();
                            if (runtime == null) {
                                throw new RuntimeUnavailable();
                            }
                            return runtime;
                        }))
                .suggestionProviders(providers -> {
                    // Lamp completes command literals. Only data-dependent arguments use the cache.
                    providers.addProvider(String.class, context -> {
                        String source = context.input().source();
                        String[] words = source.split(" ", -1);
                        String[] args = java.util.Arrays.copyOfRange(words, 1, words.length);
                        List<String> suggestions = completer.complete(context.actor().sender(), args);
                        var greedy = context.command().parameters().values().stream()
                                .filter(parameter -> parameter.type() == String.class
                                        && parameter.parameterType().isGreedy()).findFirst();
                        if (greedy.isEmpty()) {
                            return suggestions;
                        }
                        var input = revxrsal.commands.stream.StringStream.createMutable(source);
                        for (var node : context.command().nodes()) {
                            input.skipWhitespace();
                            if (node == greedy.get() || !input.hasRemaining()) break;
                            input.readString();
                        }
                        String tail = input.peekRemaining();
                        String prefix = tail.substring(0, tail.lastIndexOf(' ') + 1);
                        return suggestions.stream().map(suggestion -> prefix + suggestion).toList();
                    });
                })
                .exceptionHandler((error, context) -> handleException(plugin, error, context));
    }

    /** Lamp permits prefix matches; reject leftover text before any business action starts. */
    private static void requireCompleteInput(ExecutionContext<BukkitCommandActor> context) {
        var input = context.input().toMutableCopy();
        for (var node : context.command().nodes()) {
            input.skipWhitespace();
            if (!input.hasRemaining()) break;
            if (node instanceof ParameterNode<?, ?> parameter && parameter.parameterType().isGreedy()
                    && input.peek() != '"') {
                input.consumeRemaining();
            } else {
                input.readString();
            }
        }
        input.skipWhitespace();
        if (input.hasRemaining()) {
            throw new UnknownParameterException(input.consumeRemaining(), false);
        }
    }

    private static void handleException(TianjiTownPlugin plugin, Throwable error,
                                        ErrorContext<BukkitCommandActor> context) {
        Throwable cause = error instanceof CommandInvocationException invocation ? invocation.cause() : error;
        CommandSender sender = context.actor().sender();
        if (cause instanceof NoPermissionException || !TownAdminPermissions.hasAny(sender::hasPermission)) {
            plugin.messages().send(sender, "chat.admin.no-permission");
            return;
        }
        if (cause instanceof RuntimeUnavailable) {
            plugin.messages().send(sender, "chat.admin.runtime-not-ready");
            return;
        }
        if (cause instanceof SenderNotPlayerException) {
            plugin.messages().send(sender, "chat.admin.lamp-player-only");
            return;
        }
        String detail;
        if (cause instanceof TownCommandParser.ParseException parse) {
            detail = plugin.messages().text(parse.messageKey(), parse.placeholders());
        } else if (cause instanceof InvalidUUIDException) {
            detail = plugin.messages().text("chat.admin.lamp-invalid-uuid");
        } else if (cause instanceof InvalidNumberException || cause instanceof NumberNotInRangeException) {
            detail = plugin.messages().text("chat.admin.lamp-invalid-number");
        } else if (cause instanceof IllegalArgumentException) {
            detail = cause.getMessage();
        } else if (cause instanceof UnknownCommandException unknown) {
            plugin.messages().send(sender, "chat.admin.unknown-command", Map.of("command", unknown.input()));
            return;
        } else if (context.hasExecutionContext()) {
            detail = plugin.messages().text("chat.admin.lamp-usage",
                    Map.of("usage", context.context().command().usage()));
        } else {
            detail = plugin.messages().text("chat.admin.lamp-unknown");
        }
        plugin.messages().send(sender, "chat.admin.argument-error",
                Map.of("detail", detail == null ? cause.getClass().getSimpleName() : detail));
        if (error instanceof CommandInvocationException && !(cause instanceof IllegalArgumentException)
                && !(cause instanceof RuntimeUnavailable)) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Administrator command failed", cause);
        }
    }

    private static final class RuntimeUnavailable extends RuntimeException {}
}

package org.allivlisey.tianjitown.paper;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class CommandConfirmationManager {
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);
    private final long ttlMillis;
    private final LongSupplier clock;
    private final Supplier<String> tokenSupplier;
    private final Map<String, Pending> pendingByToken = new HashMap<>();
    private final Map<String, String> tokenByOwner = new HashMap<>();

    CommandConfirmationManager() {
        this(DEFAULT_TTL, System::currentTimeMillis,
                () -> UUID.randomUUID().toString().replace("-", ""));
    }

    CommandConfirmationManager(Duration ttl, LongSupplier clock, Supplier<String> tokenSupplier) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("确认有效期必须大于 0");
        }
        this.ttlMillis = ttl.toMillis();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
    }

    synchronized Confirmation request(String owner, String description, Runnable action) {
        String ownerKey = requireText(owner, "owner");
        String detail = requireText(description, "description");
        String previousToken = tokenByOwner.remove(ownerKey);
        if (previousToken != null) {
            pendingByToken.remove(previousToken);
        }

        String token = nextToken();
        long expiresAt = Math.addExact(clock.getAsLong(), ttlMillis);
        Pending pending = new Pending(ownerKey, detail, expiresAt,
                Objects.requireNonNull(action, "action"));
        pendingByToken.put(token, pending);
        tokenByOwner.put(ownerKey, token);
        return new Confirmation(token, detail, expiresAt);
    }

    synchronized Result consume(String owner, String token) {
        String ownerKey = requireText(owner, "owner");
        String tokenKey = normalizeToken(token);
        Pending pending = pendingByToken.get(tokenKey);
        if (pending == null) {
            return new Result(Status.NOT_FOUND, null, null);
        }
        if (clock.getAsLong() >= pending.expiresAt()) {
            remove(tokenKey, pending);
            return new Result(Status.EXPIRED, pending.description(), null);
        }
        if (!pending.owner().equals(ownerKey)) {
            return new Result(Status.NOT_OWNER, pending.description(), null);
        }
        remove(tokenKey, pending);
        return new Result(Status.CONFIRMED, pending.description(), pending.action());
    }

    synchronized Result cancel(String owner, String token) {
        String ownerKey = requireText(owner, "owner");
        String tokenKey = normalizeToken(token);
        Pending pending = pendingByToken.get(tokenKey);
        if (pending == null) {
            return new Result(Status.NOT_FOUND, null, null);
        }
        if (clock.getAsLong() >= pending.expiresAt()) {
            remove(tokenKey, pending);
            return new Result(Status.EXPIRED, pending.description(), null);
        }
        if (!pending.owner().equals(ownerKey)) {
            return new Result(Status.NOT_OWNER, pending.description(), null);
        }
        remove(tokenKey, pending);
        return new Result(Status.CANCELLED, pending.description(), null);
    }

    private String nextToken() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String token = normalizeToken(tokenSupplier.get());
            if (!pendingByToken.containsKey(token)) {
                return token;
            }
        }
        throw new IllegalStateException("无法生成唯一确认令牌");
    }

    private void remove(String token, Pending pending) {
        pendingByToken.remove(token);
        tokenByOwner.remove(pending.owner(), token);
    }

    private static String normalizeToken(String token) {
        return requireText(token, "token").toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String field) {
        String text = Objects.requireNonNull(value, field).strip();
        if (text.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return text;
    }

    enum Status {
        CONFIRMED,
        CANCELLED,
        NOT_FOUND,
        NOT_OWNER,
        EXPIRED
    }

    record Confirmation(String token, String description, long expiresAt) {
    }

    record Result(Status status, String description, Runnable action) {
    }

    private record Pending(String owner, String description, long expiresAt, Runnable action) {
    }
}

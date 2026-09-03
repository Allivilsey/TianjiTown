package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.storage.town.ApplicationSnapshot;

import java.util.Map;
import java.util.Objects;

record ProvisionResult(Status status, ApplicationSnapshot application, MessageRef detail,
                       MessageRef recoveryAction) {
    private static final String SUCCESS_DETAIL = "dialog.provision.success-detail";
    private static final String BUSY_RECOVERY_ACTION =
            "dialog.provision.busy-recovery-action";
    private static final String TIMEOUT_DETAIL = "dialog.provision.timeout-detail";
    private static final String TIMEOUT_RECOVERY_ACTION =
            "dialog.provision.timeout-recovery-action";

    enum Status {
        SUCCESS,
        FAILED,
        BUSY,
        TIMEOUT
    }

    static ProvisionResult success(ApplicationSnapshot application) {
        return new ProvisionResult(Status.SUCCESS, application,
                MessageRef.configured(SUCCESS_DETAIL), MessageRef.literal(""));
    }

    static ProvisionResult failure(ApplicationSnapshot application, String detail,
                                   String recoveryAction) {
        return new ProvisionResult(Status.FAILED, application,
                MessageRef.literal(detail), MessageRef.literal(recoveryAction));
    }

    static ProvisionResult failure(ApplicationSnapshot application, MessageRef detail,
                                   MessageRef recoveryAction) {
        return new ProvisionResult(Status.FAILED, application,
                Objects.requireNonNull(detail, "detail"),
                Objects.requireNonNull(recoveryAction, "recoveryAction"));
    }

    static ProvisionResult busy(String detail) {
        return new ProvisionResult(Status.BUSY, null, MessageRef.literal(detail),
                MessageRef.configured(BUSY_RECOVERY_ACTION));
    }

    static ProvisionResult busy(MessageRef detail) {
        return new ProvisionResult(Status.BUSY, null,
                Objects.requireNonNull(detail, "detail"),
                MessageRef.configured(BUSY_RECOVERY_ACTION));
    }

    static ProvisionResult timeout(ApplicationSnapshot application) {
        return new ProvisionResult(Status.TIMEOUT, application,
                MessageRef.configured(TIMEOUT_DETAIL),
                MessageRef.configured(TIMEOUT_RECOVERY_ACTION));
    }

    String detail(PluginMessages messages) {
        return detail.resolve(messages);
    }

    String recoveryAction(PluginMessages messages) {
        return recoveryAction.resolve(messages);
    }

    record MessageRef(String messageKey, Map<String, String> placeholders, String literal) {
        MessageRef {
            if ((messageKey == null) == (literal == null)) {
                throw new IllegalArgumentException("exactly one message source is required");
            }
            placeholders = Map.copyOf(Objects.requireNonNull(placeholders, "placeholders"));
            if (messageKey == null && !placeholders.isEmpty()) {
                throw new IllegalArgumentException("literal messages cannot have placeholders");
            }
        }

        static MessageRef configured(String messageKey) {
            return configured(messageKey, Map.of());
        }

        static MessageRef configured(String messageKey, Map<String, ?> placeholders) {
            return new MessageRef(Objects.requireNonNull(messageKey, "messageKey"),
                    stringPlaceholders(placeholders), null);
        }

        static MessageRef literal(String literal) {
            return new MessageRef(null, Map.of(), Objects.requireNonNull(literal, "literal"));
        }

        String resolve(PluginMessages messages) {
            Objects.requireNonNull(messages, "messages");
            return messageKey == null ? literal : messages.rawText(messageKey, placeholders);
        }
    }

    private static Map<String, String> stringPlaceholders(Map<String, ?> placeholders) {
        Objects.requireNonNull(placeholders, "placeholders");
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        placeholders.forEach((key, value) -> values.put(
                Objects.requireNonNull(key, "placeholder key"),
                Objects.requireNonNull(value, "placeholder value").toString()));
        return Map.copyOf(values);
    }
}

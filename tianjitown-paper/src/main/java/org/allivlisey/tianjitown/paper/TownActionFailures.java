package org.allivlisey.tianjitown.paper;

import org.allivlisey.tianjitown.core.application.ApplicationText;
import org.allivlisey.tianjitown.storage.town.TownRepository;
import org.allivlisey.tianjitown.storage.governance.GovernanceRepository;
import org.allivlisey.tianjitown.storage.economy.EconomyRepository;
import org.allivlisey.tianjitown.storage.commerce.CommerceRepository;
import org.allivlisey.tianjitown.storage.bonus.TownBonusRepository;

import java.util.Locale;
import java.util.Map;

final class TownActionFailures {
    private TownActionFailures() {
    }

    static TownActionResult from(String action, RuntimeException exception) {
        String detail = safeMessage(exception);
        if (exception instanceof TownRepository.MemberConflictException memberConflict) {
            TownRepository.MemberConflict conflict = memberConflict.conflict();
            return TownActionResult.failure(action, "MEMBER_CONFLICT", Map.of(
                    "detail", detail,
                    "player_id", conflict.playerId(),
                    "conflict_type", conflict.conflictType(),
                    "town_id", conflict.townId(),
                    "town_name", conflict.townName()));
        }
        return TownActionResult.failure(action, reason(exception, detail),
                Map.of("detail", detail));
    }

    static TownActionResult from(String action, RuntimeException exception,
                                 PluginMessages messages) {
        if (exception instanceof ApplicationText.ValidationException validation) {
            return TownActionResult.failure(action, "VALIDATION_FAILED", Map.of(
                    "detail", ApplicationTextMessages.join(messages, validation.issues())));
        }
        return from(action, exception);
    }

    static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }

    private static String reason(RuntimeException exception, String detail) {
        if (exception instanceof TownRepository.StorageUnavailableException
                || exception instanceof GovernanceRepository.StorageUnavailableException
                || exception instanceof EconomyRepository.StorageUnavailableException
                || exception instanceof CommerceRepository.StorageUnavailableException
                || exception instanceof TownBonusRepository.StorageUnavailableException) {
            return "STORAGE_UNAVAILABLE";
        }
        String type = exception.getClass().getSimpleName().toUpperCase(Locale.ROOT);
        if (detail.contains("余额不足") || detail.contains("资金不足")
                || detail.contains("insufficient")) {
            return "INSUFFICIENT_BALANCE";
        }
        if (detail.contains("不存在") || detail.contains("找不到")
                || detail.contains("missing")) {
            return "NOT_FOUND";
        }
        if (detail.contains("只有") || detail.contains("不属于") || detail.contains("无权")
                || detail.contains("权限")) {
            return "FORBIDDEN";
        }
        if (detail.contains("已达到") || detail.contains("已存在") || detail.contains("状态")
                || detail.contains("不能") || detail.contains("不允许") || detail.contains("过期")) {
            return "INVALID_STATE";
        }
        if (type.contains("CONFLICT")) {
            return "CONFLICT";
        }
        return exception instanceof IllegalArgumentException
                ? "VALIDATION_FAILED" : "INTERNAL_ERROR";
    }
}

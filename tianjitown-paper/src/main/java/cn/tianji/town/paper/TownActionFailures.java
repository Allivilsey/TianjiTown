package cn.tianji.town.paper;

import cn.tianji.town.storage.town.TownRepository;
import cn.tianji.town.storage.governance.GovernanceRepository;
import cn.tianji.town.storage.economy.EconomyRepository;
import cn.tianji.town.storage.commerce.CommerceRepository;
import cn.tianji.town.storage.bonus.TownBonusRepository;

import java.util.Locale;
import java.util.Map;

final class TownActionFailures {
    private TownActionFailures() {
    }

    static TownActionResult from(String action, RuntimeException exception) {
        String detail = safeMessage(exception);
        return TownActionResult.failure(action, reason(exception, detail),
                Map.of("detail", detail));
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

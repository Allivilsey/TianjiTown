package org.allivlisey.tianjitown.paper.runtime;

import java.math.BigDecimal;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Text normalization used by runtime error messages. */
final class RuntimeText {
    private RuntimeText() {}

    static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    static String safeText(Object value) {
        return String.valueOf(value).replace('&', '＆').replace('§', '�');
    }

    static String percent(int basisPoints) {
        return java.math.BigDecimal.valueOf(basisPoints, 2).stripTrailingZeros().toPlainString()
                + "%";
    }

    static UUID actorId(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }
}

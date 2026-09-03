package org.allivlisey.tianjitown.integrations;

import org.bukkit.event.Event;
import org.bukkit.plugin.EventExecutor;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 将可选依赖的事件回调限制在适配器边界内，避免接口漂移影响 Paper 事件循环。
 */
public final class ThirdPartyEventExecutor {
    private ThirdPartyEventExecutor() {
    }

    public static EventExecutor filtered(Class<? extends Event> expectedType,
                                         Consumer<Event> consumer,
                                         Consumer<Throwable> failureHandler) {
        Objects.requireNonNull(expectedType, "expectedType");
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(failureHandler, "failureHandler");
        return (listener, event) -> {
            if (!expectedType.isInstance(event)) {
                return;
            }
            try {
                consumer.accept(event);
            } catch (RuntimeException | LinkageError exception) {
                try {
                    failureHandler.accept(exception);
                } catch (RuntimeException | LinkageError ignored) {
                    // 故障记录器自身失效时仍不能把依赖异常重新抛回 Paper。
                }
            }
        };
    }
}

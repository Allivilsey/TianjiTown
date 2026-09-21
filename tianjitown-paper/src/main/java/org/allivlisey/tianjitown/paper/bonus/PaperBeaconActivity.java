package org.allivlisey.tianjitown.paper.bonus;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Paper 26.2 adapter for the native beam state, which Bukkit's Beacon API does not expose.
 * Read the live block entity: a BlockState snapshot does not preserve the calculated beam.
 * Public Mojang-mapped methods only; no field access, world mutation or chunk loading.
 */
final class PaperBeaconActivity {
    private static final ClassValue<Method> BLOCK_ENTITY = method("getBlockEntity");
    private static final ClassValue<Method> BEAM_SECTIONS = method("getBeamSections");

    private PaperBeaconActivity() {}

    static boolean hasBeam(Object beaconState) {
        try {
            Object entity = BLOCK_ENTITY.get(beaconState.getClass()).invoke(beaconState);
            return !((List<?>) BEAM_SECTIONS.get(entity.getClass()).invoke(entity)).isEmpty();
        } catch (ReflectiveOperationException | ClassCastException exception) {
            // Fail closed; the runtime logs the failure and retries on later refreshes.
            throw new IllegalStateException("Paper beacon beam state is unavailable", exception);
        }
    }

    private static ClassValue<Method> method(String name) {
        return new ClassValue<>() {
            @Override
            protected Method computeValue(Class<?> type) {
                try {
                    return type.getMethod(name);
                } catch (NoSuchMethodException exception) {
                    throw new IllegalStateException("Paper beacon method is unavailable: " + name, exception);
                }
            }
        };
    }
}

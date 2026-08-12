package cn.tianji.town.integrations.quickshop;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class QuickShopHistoryProbeTest {
    @Test
    void callsStableApiWithoutResolvingOptionalConcreteMethodTypes() throws Exception {
        URL classes = QuickShopHistoryProbeTest.class.getProtectionDomain().getCodeSource()
                .getLocation();
        String implementationName = LinkageFixtureImplementation.class.getName();
        String optionalTypeName = LinkageFixtureOptionalType.class.getName();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes},
                QuickShopHistoryProbeTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                if (name.equals(optionalTypeName)) {
                    throw new ClassNotFoundException(name);
                }
                if (!name.equals(implementationName)) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
        }) {
            Object implementation = loader.loadClass(implementationName)
                    .getConstructor().newInstance();

            assertThrows(NoClassDefFoundError.class,
                    () -> implementation.getClass().getMethod("value"));
            assertEquals("available", QuickShopHistoryProbe.callApi(implementation,
                    LinkageFixtureApi.class, "value"));
        }
    }

    public interface LinkageFixtureApi {
        String value();
    }

    public static class LinkageFixtureImplementation implements LinkageFixtureApi {
        public LinkageFixtureImplementation() {
        }

        @Override
        public String value() {
            return "available";
        }

        public LinkageFixtureOptionalType optionalValue() {
            return null;
        }
    }

    public static class LinkageFixtureOptionalType {
    }
}

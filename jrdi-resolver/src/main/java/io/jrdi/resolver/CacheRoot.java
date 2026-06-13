package io.jrdi.resolver;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Picks a sane default cache location ({@code $HOME/.jrdi/cache}) with environment
 * variable override {@code JRDI_CACHE}.
 */
public final class CacheRoot {

    public static final String ENV_KEY = "JRDI_CACHE";

    private CacheRoot() {
    }

    public static Path resolve() {
        String env = System.getenv(ENV_KEY);
        if (env != null && !env.isBlank()) {
            return Paths.get(env);
        }
        return Paths.get(System.getProperty("user.home"), ".jrdi", "cache");
    }
}

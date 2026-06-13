package io.jrdi.resolver;

import org.apache.maven.settings.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Holder for a configured {@link Settings} + cached {@link Cache} + the derived
 * {@link RemotePlan}. Sessions are cached per local-repo path so multiple fetches
 * within one indexing run share work.
 */
public final class ResolverSession {

    private static final Logger LOG = LoggerFactory.getLogger(ResolverSession.class);
    private static final ConcurrentHashMap<String, ResolverSession> CACHE = new ConcurrentHashMap<>();

    private final Settings settings;
    private final Cache cache;
    private final RemotePlan remotePlan;

    private ResolverSession(Settings settings, Cache cache) {
        this.settings = settings;
        this.cache = cache;
        this.remotePlan = new RemotePlan(settings);
    }

    public static ResolverSession of(Settings settings) {
        String key = settings.getLocalRepository();
        return CACHE.computeIfAbsent(key, k -> {
            LOG.info("creating new ResolverSession for local-repo={}", key);
            Cache cache = new Cache(CacheRoot.resolve());
            return new ResolverSession(settings, cache);
        });
    }

    public static ResolverSession of(Settings settings, Cache cache) {
        return new ResolverSession(settings, cache);
    }

    public Settings settings() {
        return settings;
    }

    public Cache cache() {
        return cache;
    }

    public RemotePlan remotePlan() {
        return remotePlan;
    }

    public ArtifactFetcher fetcher() {
        return new ArtifactFetcher(settings, cache, remotePlan);
    }

    public static void clearCache() {
        CACHE.clear();
    }
}

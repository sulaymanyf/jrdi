package io.jrdi.resolver;

import io.jrdi.core.coord.Gav;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Output of {@link ArtifactFetcher#fetch(Gav, OriginRepo)}: the cached jar and (optionally)
 * sources.jar paths plus the jar's sha256 fingerprint.
 *
 * <p>The files live under {@code CacheRoot} (default {@code ~/.jrdi/cache}) organized by sha256,
 * so two {@code Gav} coordinates that resolve to identical bytes share a single on-disk copy.
 */
public record FetchResult(
        Gav gav,
        OriginRepo origin,
        Path jarPath,
        Optional<Path> sourcesPath,
        String jarSha256
) {
    public boolean hasSources() {
        return sourcesPath.isPresent();
    }
}

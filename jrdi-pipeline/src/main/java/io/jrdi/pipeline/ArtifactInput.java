package io.jrdi.pipeline;

import io.jrdi.core.coord.Gav;

import java.nio.file.Path;
import java.util.Optional;

/**
 * A unit of indexing work: one GAV's jar at one path. Sources path is optional.
 * Returned by {@link io.jrdi.resolver.ArtifactFetcher}.
 */
public record ArtifactInput(Gav gav, Path jarPath, Optional<Path> sourcesPath) {
    public boolean hasSources() {
        return sourcesPath.isPresent();
    }
}

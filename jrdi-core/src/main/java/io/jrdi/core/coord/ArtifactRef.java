package io.jrdi.core.coord;

import java.util.Objects;
import java.util.Optional;

/**
 * Identifies a unique code artifact across repositories, with a stable fingerprint
 * (sha256 of the jar bytes). The {@link #originRepo} disambiguates when the same GAV appears
 * in multiple repos; the {@link #repoPath} points to the physical jar in that repo.
 */
public final class ArtifactRef {

    private final Gav gav;
    private final String originRepo;
    private final String repoPath;
    private final String sha256;
    private final boolean hasSources;

    public ArtifactRef(Gav gav, String originRepo, String repoPath, String sha256, boolean hasSources) {
        this.gav = Objects.requireNonNull(gav);
        this.originRepo = Objects.requireNonNull(originRepo);
        this.repoPath = Objects.requireNonNull(repoPath);
        this.sha256 = sha256;
        this.hasSources = hasSources;
    }

    public Gav gav() {
        return gav;
    }

    public String originRepo() {
        return originRepo;
    }

    public String repoPath() {
        return repoPath;
    }

    public Optional<String> sha256() {
        return Optional.ofNullable(sha256);
    }

    public boolean hasSources() {
        return hasSources;
    }
}

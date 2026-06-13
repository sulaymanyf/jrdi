/*
 * Copyright 2026 sulaymanyf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */package io.jrdi.core.coord;

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

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
 */package io.jrdi.resolver;

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

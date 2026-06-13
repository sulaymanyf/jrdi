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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Resolves a {@link Gav} into a cached jar and (optionally) sources.jar. Two strategies:
 *
 * <ol>
 *   <li>Local lookup in {@code ${localRepository}/{groupPath}/{artifact}/{version}/{artifact}-{version}.jar}.
 *       Cheap, no network. Sources jar lookup appends {@code -sources}.</li>
 *   <li>Remote fetch from the configured mirror/repo URL using the same layout
 *       (with {@code -sources} for sources). Streams to a temp file then installs to cache.</li>
 * </ol>
 *
 * <p>This implementation deliberately avoids {@code maven-resolver-impl} for two reasons:
 * (a) the 1.9.x service locator refactor pulls in 30+ transitive jars for things
 * (POMs, version ranges, deps) that jrdi does not need; (b) it makes the test surface
 * tractable — every fetch is just an HTTP GET, no classpath shadowing.
 */
public final class ArtifactFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(ArtifactFetcher.class);

    private final org.apache.maven.settings.Settings settings;
    private final Cache cache;
    private final RemotePlan remotePlan;

    public ArtifactFetcher(org.apache.maven.settings.Settings settings, Cache cache) {
        this(settings, cache, new RemotePlan(settings));
    }

    public ArtifactFetcher(org.apache.maven.settings.Settings settings, Cache cache, RemotePlan remotePlan) {
        this.settings = settings;
        this.cache = cache;
        this.remotePlan = remotePlan;
    }

    public FetchResult fetch(Gav gav) {
        Path localJar = resolveLocalJar(gav);
        if (localJar != null && Files.isRegularFile(localJar)) {
            return installFromLocal(gav, localJar);
        }
        // Remote
        Optional<Path> remote = remotePlan.downloadJar(gav, "jar");
        if (remote.isEmpty()) {
            throw new ResolverException("artifact not found locally or remotely: " + gav);
        }
        Path cached = cache.installJar(remote.get());
        String sha = Cache.sha256(cached);
        Optional<Path> sources = remotePlan.downloadJar(gav, "sources");
        Optional<Path> cachedSources = sources.map(p -> cache.installSources(p, sha));
        return new FetchResult(gav, OriginRepo.LOCAL, cached, cachedSources, sha);
    }

    private FetchResult installFromLocal(Gav gav, Path localJar) {
        Path cached = cache.installJar(localJar);
        String sha = Cache.sha256(cached);
        Path localSources = resolveLocalSources(gav);
        Optional<Path> cachedSources = Files.isRegularFile(localSources)
                ? Optional.of(cache.installSources(localSources, sha))
                : Optional.empty();
        return new FetchResult(gav, OriginRepo.LOCAL, cached, cachedSources, sha);
    }

    private Path resolveLocalJar(Gav gav) {
        return localArtifactPath(settings.getLocalRepository(), gav, "jar");
    }

    private Path resolveLocalSources(Gav gav) {
        return localArtifactPath(settings.getLocalRepository(), gav, "sources");
    }

    static Path localArtifactPath(String localRepo, Gav gav, String classifier) {
        if (localRepo == null || localRepo.isBlank()) return null;
        String rel = gav.group().replace('.', '/') + "/" + gav.artifact() + "/" + gav.version() + "/";
        String name = classifier.equals("jar")
                ? gav.artifact() + "-" + gav.version() + ".jar"
                : gav.artifact() + "-" + gav.version() + "-" + classifier + ".jar";
        return Paths.get(localRepo, rel, name);
    }
}

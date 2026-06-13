package io.jrdi.resolver;

import io.jrdi.core.coord.Gav;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactFetcherLocalTest {

    @Test
    void local_repo_fallback_finds_jar(@TempDir Path tmp) throws IOException {
        // Build a fake local repo with org.acme:lib:1.0.0
        Path repo = tmp.resolve("local-repo");
        Path artifactDir = repo.resolve("org/acme/lib/1.0.0");
        Files.createDirectories(artifactDir);
        Path jar = artifactDir.resolve("lib-1.0.0.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("org/acme/Lib.class"));
            z.write(new byte[]{1, 2, 3}, 0, 3);
            z.closeEntry();
        }
        Path sources = artifactDir.resolve("lib-1.0.0-sources.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(sources))) {
            z.putNextEntry(new ZipEntry("org/acme/Lib.java"));
            z.write("package org.acme; class Lib {}".getBytes());
            z.closeEntry();
        }

        // Build minimal settings pointing at our fake repo
        org.apache.maven.settings.Settings settings = new org.apache.maven.settings.Settings();
        settings.setLocalRepository(repo.toString());
        // Don't add any remote — we want pure local fallback.

        ResolverSession session = ResolverSession.of(settings, new Cache(tmp.resolve("cache")));
        ArtifactFetcher fetcher = session.fetcher();

        Gav gav = Gav.of("org.acme", "lib", "1.0.0");
        FetchResult result = fetcher.fetch(gav);
        assertThat(result.jarPath()).exists();
        assertThat(result.jarSha256()).isNotBlank();
        assertThat(result.hasSources()).isTrue();
        assertThat(result.sourcesPath().orElseThrow()).exists();
    }

    @Test
    void missing_artifact_throws(@TempDir Path tmp) {
        org.apache.maven.settings.Settings settings = new org.apache.maven.settings.Settings();
        settings.setLocalRepository(tmp.toString());
        ResolverSession session = ResolverSession.of(settings, new Cache(tmp.resolve("cache")));
        ArtifactFetcher fetcher = session.fetcher();
        assertThatThrownBy(() -> fetcher.fetch(Gav.of("nope", "missing", "1.0.0")))
                .isInstanceOf(ResolverException.class);
    }
}

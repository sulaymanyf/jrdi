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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class CacheTest {

    @Test
    void install_then_reuse_same_sha(@TempDir Path tmp) throws IOException {
        Cache cache = new Cache(tmp);
        // create a tiny jar-like file
        Path src = tmp.resolve("src.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(src))) {
            z.putNextEntry(new ZipEntry("a.txt"));
            z.write("hello".getBytes());
            z.closeEntry();
        }
        Path installed = cache.installJar(src);
        String sha = Cache.sha256(installed);
        assertThat(cache.hasJar(sha)).isTrue();
        // Re-install same content should be idempotent
        Path second = cache.installJar(src);
        assertThat(second).isEqualTo(installed);
    }

    @Test
    void sha256_matches_known_input(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("t.txt");
        Files.writeString(f, "abc");
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertThat(Cache.sha256(f)).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void install_sources_dedup(@TempDir Path tmp) throws IOException {
        Cache cache = new Cache(tmp);
        Path jar = tmp.resolve("artifact.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("x.class"));
            z.write(new byte[]{1, 2, 3}, 0, 3);
            z.closeEntry();
        }
        Path cached = cache.installJar(jar);
        String sha = Cache.sha256(cached);

        Path sources = tmp.resolve("src.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(sources))) {
            z.putNextEntry(new ZipEntry("x.java"));
            z.write("class X {}".getBytes());
            z.closeEntry();
        }
        Path installedSources = cache.installSources(sources, sha);
        assertThat(cache.hasSources(sha)).isTrue();
        assertThat(installedSources).isEqualTo(cache.sourcesPath(sha));
    }
}

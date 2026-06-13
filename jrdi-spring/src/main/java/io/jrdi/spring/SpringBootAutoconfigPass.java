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
 */package io.jrdi.spring;

import io.jrdi.storage.Db;
import io.jrdi.storage.repo.SpringBootAutoconfigRepo;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Spring Boot auto-configuration discovery. Reads two file formats:
 *
 * <ul>
 *   <li>{@code META-INF/spring.factories} — pre-Spring-Boot-3.0 format. A
 *       {@code Properties} file where keys are interface FQNs and values are
 *       comma-separated lists of implementing class FQNs. We extract
 *       {@code org.springframework.boot.autoconfigure.EnableAutoConfiguration}
 *       and the other well-known keys ({@link SpringBootAutoconfigRepo#KNOWN_KEYS}).</li>
 *   <li>{@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 *       — Spring Boot 3.0+ format. One FQN per line, blank lines and {@code #} comments
 *       ignored.</li>
 * </ul>
 *
 * <p>Both formats are scanned in every artifact. Each discovered entry is
 * recorded in {@code spring_boot_autoconfigs} with its source file, source
 * format, and (for spring.factories) the key it appeared under.
 *
 * <p>M1 limitations:
 * <ul>
 *   <li>We only record the well-known spring.factories keys (see
 *       {@link SpringBootAutoconfigRepo#KNOWN_KEYS}). Spring allows arbitrary
 *       keys; we ignore them to keep the table size manageable.</li>
 *   <li>We don't resolve conditional annotations ({@code @ConditionalOnClass},
 *       {@code @ConditionalOnBean}) on the auto-config classes. The LLM sees
 *       the class is on the classpath; runtime activation depends on those
 *       conditions and is out of scope (no runtime introspection).</li>
 *   <li>We don't follow {@code AutoConfiguration.imports} imports of imports
 *       (the file is one-level only).</li>
 *   <li>{@code spring.factories} is a {@code Properties} file — its quoting
 *       rules are subtle (commas, backslash-newline continuations, leading
 *       whitespace). We use {@link Properties#load} which handles the common
 *       cases; pathological cases (e.g. backslash at end of value) may
 *       produce partial results.</li>
 * </ul>
 */
public final class SpringBootAutoconfigPass {

    private static final Logger LOG = LoggerFactory.getLogger(SpringBootAutoconfigPass.class);

    /** Spring Boot 3.0+ auto-config file (canonical path inside any jar). */
    private static final String IMPORTS_FILE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    /** Pre-3.0 spring.factories. */
    private static final String FACTORIES_FILE = "META-INF/spring.factories";

    private final Db db;

    public SpringBootAutoconfigPass(Db db) {
        this.db = db;
    }

    public record Result(int autoconfigsRecorded, int factoriesFilesScanned, int importsFilesScanned) {}

    /**
     * Scan a {@code .jar} (or exploded directory) for Spring Boot auto-config
     * files and persist discovered entries.
     */
    public Result scanJar(Path jarOrDir) throws IOException {
        if (Files.isDirectory(jarOrDir)) {
            int total = 0, totalF = 0, totalI = 0;
            // Both files in a directory tree.
            Path factories = jarOrDir.resolve(FACTORIES_FILE);
            if (Files.isRegularFile(factories)) {
                int n = parseFactories(factories);
                total += n; totalF++;
            }
            Path imports = jarOrDir.resolve(IMPORTS_FILE);
            if (Files.isRegularFile(imports)) {
                int n = parseImportsFile(imports);
                total += n; totalI++;
            }
            // Some Spring Boot jars put the imports under slightly different
            // names; for now we cover the canonical one.
            return new Result(total, totalF, totalI);
        }
        int total = 0, totalF = 0, totalI = 0;
        try (JarFile jar = new JarFile(jarOrDir.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (FACTORIES_FILE.equals(name)) {
                    try (InputStream in = jar.getInputStream(e)) {
                        int n = parseFactoriesBytes(in.readAllBytes(), name);
                        total += n; totalF++;
                    }
                } else if (IMPORTS_FILE.equals(name)) {
                    try (InputStream in = jar.getInputStream(e)) {
                        int n = parseImportsBytes(in.readAllBytes(), name);
                        total += n; totalI++;
                    }
                }
            }
        }
        return new Result(total, totalF, totalI);
    }

    /** Parse a {@code spring.factories} file at the given path. */
    public int parseFactories(Path file) throws IOException {
        return parseFactoriesBytes(Files.readAllBytes(file), file.toString());
    }

    /**
     * Parse a {@code spring.factories} byte stream. Returns the count of
     * auto-config rows recorded.
     */
    public int parseFactoriesBytes(byte[] bytes, String sourceName) {
        SpringBootAutoconfigRepo repo = SqliteRepos.springBootAutoconfigRepo(db);
        Properties props = new Properties();
        try {
            props.load(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            LOG.debug("spring.factories parse failed for {}: {}", sourceName, e.getMessage());
            return 0;
        }
        int count = 0;
        for (String key : SpringBootAutoconfigRepo.KNOWN_KEYS) {
            String value = props.getProperty(key);
            if (value == null || value.isBlank()) continue;
            // Value is a comma-separated list, possibly with backslash-newline continuations.
            String[] fqns = value.split(",");
            for (String fqn : fqns) {
                String cleaned = fqn.trim();
                if (cleaned.isEmpty()) continue;
                repo.upsert(cleaned, sourceName, "factories", key);
                count++;
            }
        }
        return count;
    }

    /** Parse a Spring Boot 3.0+ auto-config imports file. */
    public int parseImportsFile(Path file) throws IOException {
        return parseImportsBytes(Files.readAllBytes(file), file.toString());
    }

    /**
     * Parse an {@code AutoConfiguration.imports} byte stream. One FQN per line,
     * blank lines and lines starting with {@code #} (after trim) are ignored.
     */
    public int parseImportsBytes(byte[] bytes, String sourceName) {
        SpringBootAutoconfigRepo repo = SqliteRepos.springBootAutoconfigRepo(db);
        int count = 0;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("#")) continue;
                repo.upsert(trimmed, sourceName, "imports", "");
                count++;
            }
        } catch (IOException e) {
            LOG.debug("AutoConfiguration.imports parse failed for {}: {}", sourceName, e.getMessage());
            return 0;
        }
        return count;
    }

    /**
     * Convenience for tests: parse raw byte payloads (e.g. from inline strings
     * in JUnit tests). Returns the same {@link Result} shape as
     * {@link #scanJar(Path)} but for a single artifact in-memory.
     */
    public Result scanBytes(byte[] factoriesBytes, byte[] importsBytes) {
        int f = factoriesBytes != null
                ? parseFactoriesBytes(factoriesBytes, FACTORIES_FILE) : 0;
        int i = importsBytes != null
                ? parseImportsBytes(importsBytes, IMPORTS_FILE) : 0;
        return new Result(f + i, factoriesBytes != null ? 1 : 0, importsBytes != null ? 1 : 0);
    }

    // Suppress the unused-import warning for Locale (kept for future i18n).
    @SuppressWarnings("unused")
    private static final java.util.List<String> UNUSED = List.of();
}

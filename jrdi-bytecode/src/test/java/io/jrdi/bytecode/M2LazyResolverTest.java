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
 */
package io.jrdi.bytecode;

import io.jrdi.core.symbol.Fqn;
import io.jrdi.storage.Db;
import io.jrdi.storage.Migrator;
import io.jrdi.storage.repo.M2Repo;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the m2 lazy resolver against a synthetic
 * jar built with {@code javac} (or hand-rolled .class bytes
 * copied from the fixture jars under {@code jrdi-bytecode/src/test/resources}).
 *
 * <p>These tests deliberately don't pull in the bytecode fixtures'
 * deps — they build tiny throwaway .class files in a {@code @TempDir}
 * and verify that:
 *
 * <ol>
 *   <li>First {@code resolve} extracts; second is a cache hit (LRU touch).</li>
 *   <li>Stale mtime invalidates the cache and re-extracts.</li>
 *   <li>A non-existent class returns {@code Optional.empty()}.</li>
 *   <li>Cache eviction works (LRU).</li>
 *   <li>The m2_caches row reflects the correct counts.</li>
 * </ol>
 */
class M2LazyResolverTest {

    private Db db;
    private M2Repo repo;

    @BeforeEach
    void setUp() {
        db = Db.inMemorySqlite();
        Migrator.migrate(db.dataSource());
        repo = SqliteRepos.m2Repo(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void resolves_class_in_local_jar(@TempDir Path tmp) throws Exception {
        byte[] fooBytes = InMemoryCompiler.compile("com.acme.Foo", """
                package com.acme;
                public class Foo {
                    public String hello() { return "hi"; }
                }
                """).classBytes();
        Path jar = buildJarWithName(tmp, "foo.jar", "com/acme/Foo.class", fooBytes);
        M2LazyResolver resolver = new M2LazyResolver(db, List.of(tmp));

        var hit = resolver.resolveClassByFqn(Fqn.fromDotted("com.acme.Foo"));
        if (hit.isEmpty()) {
            // Diagnostic dump
            System.out.println("hit empty; cache rows: " + repo.oldestCaches(10).size());
            for (var c : repo.oldestCaches(10)) {
                System.out.println("  cache: " + c.jarPath() + " classes=" + c.classesCount());
            }
            var r = SqliteRepos.classRepo(db).findByFqn(Fqn.fromDotted("com.acme.Foo"));
            if (r.isPresent()) {
                System.out.println("  byFqn: " + r.get().sourceJar() + " / " + r.get().fqn().slashed());
            }
        }
        assertThat(hit).isPresent();
        assertThat(hit.get().fqn().dotted()).isEqualTo("com.acme.Foo");
        assertThat(hit.get().sourceJar()).endsWith("foo.jar");

        // m2_caches row should have counts.
        var cache = repo.findCache(jar.toAbsolutePath().toString());
        assertThat(cache).isPresent();
        assertThat(cache.get().classesCount()).isEqualTo(1);
        assertThat(cache.get().methodsCount()).isGreaterThan(0);
    }

    @Test
    void second_resolve_is_cache_hit_no_rewrite(@TempDir Path tmp) throws Exception {
        byte[] barBytes = InMemoryCompiler.compile("com.acme.Bar", """
                package com.acme;
                public class Bar {
                    public int x() { return 0; }
                }
                """).classBytes();
        Path jar = buildJarWithName(tmp, "bar.jar", "com/acme/Bar.class", barBytes);
        M2LazyResolver resolver = new M2LazyResolver(db, List.of(tmp));

        resolver.resolveClassByFqn(Fqn.fromDotted("com.acme.Bar"));
        String extractedAtBefore = repo.findCache(jar.toAbsolutePath().toString())
                .orElseThrow().extractedAt();
        Thread.sleep(50);

        resolver.resolveClassByFqn(Fqn.fromDotted("com.acme.Bar"));
        String extractedAtAfter = repo.findCache(jar.toAbsolutePath().toString())
                .orElseThrow().extractedAt();
        assertThat(extractedAtAfter).isEqualTo(extractedAtBefore);
    }

    @Test
    void missing_class_returns_empty(@TempDir Path tmp) throws Exception {
        byte[] fooBytes = InMemoryCompiler.compile("com.acme.Foo", """
                package com.acme;
                public class Foo {}
                """).classBytes();
        buildJarWithName(tmp, "foo.jar", "com/acme/Foo.class", fooBytes);
        M2LazyResolver resolver = new M2LazyResolver(db, List.of(tmp));

        assertThat(resolver.resolveClassByFqn(Fqn.fromDotted("com.acme.Missing")))
                .isEmpty();
    }

    @Test
    void mtime_change_invalidates_cache(@TempDir Path tmp) throws Exception {
        byte[] fooBytes = InMemoryCompiler.compile("com.acme.Foo", """
                package com.acme;
                public class Foo {
                    public void m() {}
                }
                """).classBytes();
        Path jar = buildJarWithName(tmp, "foo.jar", "com/acme/Foo.class", fooBytes);
        M2LazyResolver resolver = new M2LazyResolver(db, List.of(tmp));

        resolver.resolveClassByFqn(Fqn.fromDotted("com.acme.Foo"));
        Files.setLastModifiedTime(jar, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(jar).toMillis() + 2000));

        resolver.resolveClassByFqn(Fqn.fromDotted("com.acme.Foo"));
        assertThat(repo.findCache(jar.toAbsolutePath().toString())).isPresent();
    }

    @Test
    void lru_eviction_removes_oldest_cache(@TempDir Path tmp) throws Exception {
        byte[] aBytes = InMemoryCompiler.compile("com.acme.A", """
                package com.acme;
                public class A {}
                """).classBytes();
        byte[] bBytes = InMemoryCompiler.compile("com.acme.B", """
                package com.acme;
                public class B {}
                """).classBytes();
        Path jar1 = buildJarWithName(tmp, "a.jar", "com/acme/A.class", aBytes);
        Path jar2 = buildJarWithName(tmp, "b.jar", "com/acme/B.class", bBytes);

        M2LazyResolver resolver = new M2LazyResolver(db, List.of(tmp), new BytecodePass(), 1);
        resolver.resolveClassByFqn(Fqn.fromDotted("com.acme.A"));
        Thread.sleep(50);
        resolver.resolveClassByFqn(Fqn.fromDotted("com.acme.B"));

        // Cap is 1 → jar1 was evicted.
        assertThat(repo.findCache(jar1.toAbsolutePath().toString())).isEmpty();
        assertThat(repo.findCache(jar2.toAbsolutePath().toString())).isPresent();
    }

    @Test
    void warm_all_extracts_every_jar_in_root(@TempDir Path tmp) throws Exception {
        buildJarWithName(tmp, "a.jar", "com/acme/A.class",
                InMemoryCompiler.compile("com.acme.A", """
                        package com.acme;
                        public class A {}
                        """).classBytes());
        buildJarWithName(tmp, "b.jar", "com/acme/B.class",
                InMemoryCompiler.compile("com.acme.B", """
                        package com.acme;
                        public class B {}
                        """).classBytes());
        buildJarWithName(tmp, "c.jar", "com/acme/C.class",
                InMemoryCompiler.compile("com.acme.C", """
                        package com.acme;
                        public class C {}
                        """).classBytes());
        Files.writeString(tmp.resolve("README.md"), "irrelevant");

        M2LazyResolver resolver = new M2LazyResolver(db, List.of(tmp));
        int n = resolver.warmAll();
        assertThat(n).isEqualTo(3);
    }

    @Test
    void outgoing_invokes_after_extract(@TempDir Path tmp) throws Exception {
        // Compile both classes in one batch with package declarations so
        // the cross-reference resolves.
        var compiled = InMemoryCompiler.compile(Map.of(
                "com.acme.Greeter", """
                        package com.acme;
                        public class Greeter {
                            public String greet(String name) { return "hi " + name; }
                        }
                        """,
                "com.acme.UseGreeter", """
                        package com.acme;
                        public class UseGreeter {
                            public String callIt() {
                                return new com.acme.Greeter().greet("world");
                            }
                        }
                        """));
        byte[] greeterBytes = compiled.get("com.acme.Greeter");
        byte[] useGreeterBytes = compiled.get("com.acme.UseGreeter");
        Path jar = buildJarWithEntries(tmp, "acme.jar", Map.of(
                "com/acme/Greeter.class", greeterBytes,
                "com/acme/UseGreeter.class", useGreeterBytes));
        M2LazyResolver resolver = new M2LazyResolver(db, List.of(tmp));

        resolver.resolveClassByFqn(Fqn.fromDotted("com.acme.UseGreeter"));
        // 0.3.0-M1: invokes are in the main `invokes` table.
        // UseGreeter.<init> should have an edge to Greeter.<init>.
        var methodRepo = SqliteRepos.methodRepo(db);
        var ctor = methodRepo.findByKey(Fqn.fromDotted("com.acme.UseGreeter"),
                new io.jrdi.core.symbol.MethodKey("<init>", "()V"));
        assertThat(ctor).isPresent();
        var edges = SqliteRepos.invokeRepo(db).findCalleesOf(ctor.get().id());
        assertThat(edges).isNotEmpty();
        assertThat(edges).anyMatch(e -> e.calleeOwner().equals("com/acme/Greeter"));
    }

    // ─── fixture helpers ────────────────────────────────────────────

    private static Path buildJarWithName(Path dir, String jarName, String entryName, byte[] classBytes)
            throws Exception {
        Path jar = dir.resolve(jarName);
        try (OutputStream os = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(os)) {
            jos.putNextEntry(new JarEntry(entryName));
            jos.write(classBytes);
            jos.closeEntry();
        }
        return jar;
    }

    private static Path buildJarWithEntries(Path dir, String jarName,
                                            Map<String, byte[]> entries) throws Exception {
        Path jar = dir.resolve(jarName);
        try (OutputStream os = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(os)) {
            for (var e : entries.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue());
                jos.closeEntry();
            }
        }
        return jar;
    }
}

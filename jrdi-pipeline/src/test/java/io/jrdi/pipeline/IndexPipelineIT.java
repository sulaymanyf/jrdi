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
 */package io.jrdi.pipeline;

import io.jrdi.core.coord.Gav;
import io.jrdi.core.symbol.Fqn;
import io.jrdi.core.symbol.MethodKey;
import io.jrdi.storage.Db;
import io.jrdi.storage.Migrator;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: end-to-end through pipeline. We compile a tiny Java class, package it into
 * a jar (and a sources.jar), then run {@link IndexPipeline} against a real Sqlite DB and
 * verify the rows are queryable.
 */
class IndexPipelineIT {

    private Db db;
    private Path repoRoot;
    private Path fakeM2;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws IOException {
        db = Db.inMemorySqlite();
        Migrator.migrate(db.dataSource());

        repoRoot = tmp.resolve("repo");
        fakeM2 = tmp.resolve("m2");
        Files.createDirectories(repoRoot);
        Files.createDirectories(fakeM2);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void end_to_end_index_then_query() throws IOException {
        // Compile both classes together so UseGreeter can reference Greeter.
        Map<String, String> sources = new HashMap<>();
        sources.put("com.acme.demo.Greeter", """
                package com.acme.demo;
                public class Greeter {
                    public String greet(String name) {
                        return "hello " + name;
                    }
                }
                """);
        sources.put("com.acme.demo.UseGreeter", """
                package com.acme.demo;
                public class UseGreeter {
                    public String run() {
                        Greeter g = new Greeter();
                        return g.greet("world");
                    }
                }
                """);
        Map<String, byte[]> compiled = compileAll(sources);
        byte[] greetBytes = compiled.get("com.acme.demo.Greeter");
        byte[] useBytes = compiled.get("com.acme.demo.UseGreeter");

        // Build a jar
        Path jar = fakeM2.resolve("com/acme/demo/1.0.0/demo-1.0.0.jar");
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            write(z, "com/acme/demo/Greeter.class", greetBytes);
            write(z, "com/acme/demo/UseGreeter.class", useBytes);
        }
        // Build a sources.jar
        Path sourcesJar = fakeM2.resolve("com/acme/demo/1.0.0/demo-1.0.0-sources.jar");
        Files.createDirectories(sourcesJar.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(sourcesJar))) {
            write(z, "com/acme/demo/Greeter.java", """
                    package com.acme.demo;
                    public class Greeter {
                        public String greet(String name) {
                            return "hello " + name;
                        }
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            write(z, "com/acme/demo/UseGreeter.java", """
                    package com.acme.demo;
                    public class UseGreeter {
                        public String run() {
                            Greeter g = new Greeter();
                            return g.greet("world");
                        }
                    }
                    """.getBytes(StandardCharsets.UTF_8));
        }

        // Run pipeline. Use MavenSettingsParser to point at our fake m2 via system env.
        Gav gav = Gav.of("com.acme.demo", "demo", "1.0.0");
        IndexPipeline pipeline = new IndexPipeline(db, io.jrdi.resolver.MavenSettingsParser.load());
        // We bypass the resolver and call indexRepo directly with the local jar
        ArtifactInput input = new ArtifactInput(gav, jar, java.util.Optional.of(sourcesJar));
        IndexReport report = pipeline.indexRepo("test-repo", repoRoot.toString(), List.of(input));

        // Verify counts
        assertThat(report.classesIndexed()).isEqualTo(2);
        assertThat(report.methodsIndexed()).isGreaterThanOrEqualTo(4); // 2 ctor + 2 greet/run
        assertThat(report.invokesIndexed()).isGreaterThanOrEqualTo(4);
        assertThat(report.lambdasIndexed()).isZero();

        // Verify the rows are queryable
        var classRepo = SqliteRepos.classRepo(db);
        assertThat(classRepo.findByFqn(Fqn.fromDotted("com.acme.demo.Greeter"))).isPresent();

        var methodRepo = SqliteRepos.methodRepo(db);
        var greet = methodRepo.findByKey(Fqn.fromDotted("com.acme.demo.Greeter"),
                new MethodKey("greet", "(Ljava/lang/String;)Ljava/lang/String;"));
        assertThat(greet).isPresent();
        // source-derived line numbers (greet is at line 3-5 in the source above)
        assertThat(greet.get().startLine()).isEqualTo(3);
        assertThat(greet.get().endLine()).isEqualTo(5);
        assertThat(greet.get().virtual()).isFalse();

        // Verify the UseGreeter.run → Greeter.greet invoke is recorded
        var invokeRepo = SqliteRepos.invokeRepo(db);
        var callers = invokeRepo.findCallersOf(
                "com/acme/demo/Greeter", "greet", "(Ljava/lang/String;)Ljava/lang/String;");
        assertThat(callers).isNotEmpty();
    }

    @Test
    void cfr_fallback_when_no_sources() throws IOException {
        byte[] helloBytes = compile("com.acme.nosrc.Hello", """
                package com.acme.nosrc;
                public class Hello {
                    public int answer() { return 42; }
                }
                """);
        Path jar = fakeM2.resolve("com/acme/nosrc/1.0.0/nosrc-1.0.0.jar");
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            write(z, "com/acme/nosrc/Hello.class", helloBytes);
        }
        // No sources.jar — pipeline should fall back to CFR
        Gav gav = Gav.of("com.acme.nosrc", "nosrc", "1.0.0");
        IndexPipeline pipeline = new IndexPipeline(db, io.jrdi.resolver.MavenSettingsParser.load());
        ArtifactInput input = new ArtifactInput(gav, jar, java.util.Optional.empty());
        IndexReport report = pipeline.indexRepo("test-repo-cfr", repoRoot.toString(), List.of(input));

        assertThat(report.classesIndexed()).isEqualTo(1);
        var methodRepo = SqliteRepos.methodRepo(db);
        var m = methodRepo.findByKey(Fqn.fromDotted("com.acme.nosrc.Hello"),
                new MethodKey("answer", "()I"));
        assertThat(m).isPresent();
        // CFR-derived line numbers are flagged virtual
        assertThat(m.get().virtual()).isTrue();
    }

    private static void write(ZipOutputStream z, String name, byte[] bytes) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(bytes, 0, bytes.length);
        z.closeEntry();
    }

    private static byte[] compile(String fqn, String source) throws IOException {
        Map<String, String> single = new HashMap<>();
        single.put(fqn, source);
        return compileAll(single).get(fqn);
    }

    private static Map<String, byte[]> compileAll(Map<String, String> sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Map<String, ByteArrayOutputStream> outputs = new HashMap<>();
            List<JavaFileObject> units = new ArrayList<>();
            for (Map.Entry<String, String> e : sources.entrySet()) {
                units.add(new InMemSource(e.getKey(), e.getValue()));
            }
            CapturingFileManager cfm = new CapturingFileManager(fm, outputs);
            List<String> errors = new ArrayList<>();
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, cfm, d -> errors.add(d.toString()),
                    List.of("-g", "-Xlint:none"),
                    null, units);
            Boolean ok = task.call();
            if (ok == null || !ok) {
                throw new IllegalStateException("compilation failed: " + errors);
            }
            // The className reported by JavaParser matches the FQN for top-level classes.
            // For our trivial cases the FQN is the only output stream.
            Map<String, byte[]> result = new HashMap<>();
            for (String fqn : sources.keySet()) {
                ByteArrayOutputStream baos = outputs.get(fqn);
                if (baos != null && baos.size() > 0) {
                    result.put(fqn, baos.toByteArray());
                } else {
                    // Fallback: take any non-empty output and pair it with this fqn.
                    for (Map.Entry<String, ByteArrayOutputStream> e : outputs.entrySet()) {
                        if (e.getValue().size() > 0) {
                            result.put(fqn, e.getValue().toByteArray());
                            break;
                        }
                    }
                }
            }
            return result;
        }
    }

    private static final class InMemSource extends SimpleJavaFileObject {
        private final String source;

        InMemSource(String fqn, String source) {
            super(URI.create("string:///" + fqn.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static final class CapturingFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, ByteArrayOutputStream> outputs;

        CapturingFileManager(StandardJavaFileManager delegate, Map<String, ByteArrayOutputStream> outputs) {
            super(delegate);
            this.outputs = outputs;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                    JavaFileObject.Kind kind, FileObject sibling) {
            ByteArrayOutputStream baos = outputs.computeIfAbsent(className, k -> new ByteArrayOutputStream());
            return new SimpleJavaFileObject(URI.create("bytes:///" + className.replace('.', '/') + ".class"),
                    JavaFileObject.Kind.CLASS) {
                @Override
                public OutputStream openOutputStream() {
                    baos.reset();
                    return baos;
                }
            };
        }
    }
}

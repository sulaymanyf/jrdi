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
import io.jrdi.storage.Db;
import io.jrdi.storage.Migrator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.ByteArrayOutputStream;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3.6: incremental indexing. Re-indexing an unchanged jar should skip all
 * per-class work. Re-indexing after a class has been modified should re-process
 * only that class.
 */
class IncrementalIndexingIT {

    @Test
    void unchanged_jar_is_fully_skipped_on_reindex(@TempDir Path tmp) throws Exception {
        Path jar = buildJar(tmp, "demo-1.0.0", Map.of(
                "com.acme.Greeter", "package com.acme;\n" +
                        "public class Greeter { public String hi() { return \"hi\"; } }\n",
                "com.acme.Farewell", "package com.acme;\n" +
                        "public class Farewell { public String bye() { return \"bye\"; } }\n"));
        try (Db db = Db.inMemorySqlite()) {
            Migrator.migrate(db.dataSource());
            IndexPipeline pipeline = new IndexPipeline(db, io.jrdi.resolver.MavenSettingsParser.load());
            ArtifactInput input = new ArtifactInput(
                    new Gav("com.acme", "demo", "1.0.0"), jar, java.util.Optional.empty());

            IndexReport first = pipeline.indexRepo("it", tmp.toString(), List.of(input));
            assertThat(first.classesIndexed()).isEqualTo(2);
            assertThat(first.classesSkipped()).isZero();

            IndexReport second = pipeline.indexRepo("it", tmp.toString(), List.of(input));
            // Second pass: everything is skipped, no new classes/methods/indexed.
            assertThat(second.classesSkipped()).isEqualTo(2);
            assertThat(second.classesIndexed()).isZero();
            assertThat(second.methodsIndexed()).isZero();
            assertThat(second.elapsed().toMillis()).isLessThan(first.elapsed().toMillis() + 50);
        }
    }

    @Test
    void only_changed_classes_are_re_processed(@TempDir Path tmp) throws Exception {
        // First build: Greeter + Farewell.
        Path jar1 = buildJar(tmp, "demo-1.0.0", Map.of(
                "com.acme.Greeter", "package com.acme;\n" +
                        "public class Greeter { public String hi() { return \"hi\"; } }\n",
                "com.acme.Farewell", "package com.acme;\n" +
                        "public class Farewell { public String bye() { return \"bye\"; } }\n"));
        try (Db db = Db.inMemorySqlite()) {
            Migrator.migrate(db.dataSource());
            IndexPipeline pipeline = new IndexPipeline(db, io.jrdi.resolver.MavenSettingsParser.load());
            ArtifactInput input1 = new ArtifactInput(
                    new Gav("com.acme", "demo", "1.0.0"), jar1, java.util.Optional.empty());
            IndexReport first = pipeline.indexRepo("it", tmp.toString(), List.of(input1));
            assertThat(first.classesIndexed()).isEqualTo(2);

            // Second build: same jar, only Farewell's bytecode changed.
            Path jar2 = buildJar(tmp, "demo-1.0.0", Map.of(
                    "com.acme.Greeter", "package com.acme;\n" +
                            "public class Greeter { public String hi() { return \"hi\"; } }\n",
                    "com.acme.Farewell", "package com.acme;\n" +
                            "public class Farewell { public String bye() { return \"farewell\"; } }\n"));
            ArtifactInput input2 = new ArtifactInput(
                    new Gav("com.acme", "demo", "1.0.0"), jar2, java.util.Optional.empty());
            IndexReport second = pipeline.indexRepo("it", tmp.toString(), List.of(input2));
            // Greeter is unchanged → skipped. Farewell changed → re-processed.
            assertThat(second.classesSkipped()).isEqualTo(1);
            assertThat(second.classesIndexed()).isEqualTo(1);
        }
    }

    private static Path buildJar(Path dir, String name, Map<String, String> sources) throws Exception {
        Map<String, byte[]> compiled = compileAll(sources);
        Path jar = dir.resolve(name + ".jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var e : compiled.entrySet()) {
                writeEntry(z, e.getKey().replace('.', '/') + ".class", e.getValue());
            }
        }
        return jar;
    }

    private static void writeEntry(ZipOutputStream z, String name, byte[] bytes) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(bytes);
        z.closeEntry();
    }

    private static Map<String, byte[]> compileAll(Map<String, String> sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Map<String, ByteArrayOutputStream> outputs = new HashMap<>();
            List<JavaFileObject> units = new ArrayList<>();
            for (var e : sources.entrySet()) units.add(new InMemSource(e.getKey(), e.getValue()));
            CapturingFileManager cfm = new CapturingFileManager(fm, outputs);
            List<String> errors = new ArrayList<>();
            Boolean ok = compiler.getTask(null, cfm, d -> errors.add(d.toString()),
                    List.of("-g", "-Xlint:none"), null, units).call();
            if (ok == null || !ok) throw new IllegalStateException("compile failed: " + errors);
            Map<String, byte[]> r = new HashMap<>();
            for (String fqn : sources.keySet()) {
                ByteArrayOutputStream baos = outputs.get(fqn);
                if (baos != null && baos.size() > 0) r.put(fqn, baos.toByteArray());
            }
            return r;
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

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
 */package io.jrdi.bytecode;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test helper: compile a snippet of Java source in memory and return the resulting .class bytes.
 * Used by every U3 test to avoid shipping a separate fixture jar.
 */
final class InMemoryCompiler {

    public record Compiled(String fqn, byte[] classBytes) {}

    public static Compiled compile(String fqn, String source) throws IOException {
        Map<String, byte[]> out = compile(Map.of(fqn, source));
        byte[] b = out.get(fqn);
        if (b == null) throw new IllegalStateException("compilation produced no output for " + fqn);
        return new Compiled(fqn, b);
    }

    public static Map<String, byte[]> compile(Map<String, String> sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no system Java compiler (need JDK not JRE)");
        }
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Map<String, ByteArrayOutputStream> outputs = new HashMap<>();
            List<JavaFileObject> units = sources.entrySet().stream()
                    .map(e -> (JavaFileObject) new InMemSource(e.getKey(), e.getValue(), outputs))
                    .toList();
            CapturingFileManager cfm = new CapturingFileManager(fm, outputs);
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, cfm, null,
                    List.of("-g", "-Xlint:none", "-parameters"),
                    null,
                    units);
            boolean ok = task.call();
            if (!ok) {
                throw new IllegalStateException("compilation failed");
            }
            Map<String, byte[]> result = new HashMap<>();
            for (Map.Entry<String, ByteArrayOutputStream> e : outputs.entrySet()) {
                result.put(e.getKey(), e.getValue().toByteArray());
            }
            return result;
        }
    }

    private static final class InMemSource extends SimpleJavaFileObject {
        private final String source;
        private final Map<String, ByteArrayOutputStream> outputs;

        InMemSource(String fqn, String source, Map<String, ByteArrayOutputStream> outputs) {
            super(URI.create("string:///" + fqn.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.source = source;
            this.outputs = outputs;
            outputs.put(fqn, new ByteArrayOutputStream());
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }

        @Override
        public OutputStream openOutputStream() {
            return outputs.values().iterator().next();
        }
    }

    /** Routes {@code getJavaFileForOutput(..., "*.class", ...)} into our byte streams. */
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

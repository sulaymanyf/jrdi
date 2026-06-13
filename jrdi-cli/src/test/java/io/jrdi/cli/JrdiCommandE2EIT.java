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
 */package io.jrdi.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.FileObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class JrdiCommandE2EIT {

    @Test
    void full_flow_index_then_query_then_doctor_then_rebuild(@TempDir Path tmp) throws Exception {
        // Compile a small class
        Map<String, byte[]> compiled = compileAll(Map.of("com.acme.demo.Greeter", """
                package com.acme.demo;
                public class Greeter {
                    public String greet() { return "hi"; }
                }
                """));
        // Build a jar
        Path jar = tmp.resolve("demo-1.0.0.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var e : compiled.entrySet()) {
                writeEntry(z, e.getKey().replace('.', '/') + ".class", e.getValue());
            }
        }
        // Run index
        Path db = tmp.resolve("jrdi.db");
        CliWiring.runIndex(jar.toString(), "sqlite:" + db, "test", false);
        // Verify doctor sees the data
        // Capture stdout via a real run
        PrintStream origOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            CliWiring.runDoctor("sqlite:" + db);
        } finally {
            System.setOut(origOut);
        }
        String out = captured.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("classes:        1");
        assertThat(out).contains("methods:        2");

        // Class query
        captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            CliWiring.runQuery("com.acme.demo.Greeter", "sqlite:" + db, null, null, 8, false);
        } finally {
            System.setOut(origOut);
        }
        out = captured.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("class:    com/acme/demo/Greeter");

        // Method query
        captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            CliWiring.runQuery("com.acme.demo.Greeter#greet()Ljava/lang/String;",
                    "sqlite:" + db, null, null, 8, false);
        } finally {
            System.setOut(origOut);
        }
        out = captured.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("=== callers of");

        // Rebuild: wipe + re-migrate, expect classes=0 after.
        CliWiring.runRebuild("sqlite:" + db);
        captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            CliWiring.runDoctor("sqlite:" + db);
        } finally {
            System.setOut(origOut);
        }
        out = captured.toString(StandardCharsets.UTF_8);
        assertThat(out).contains("classes:        0");
    }

    @Test
    void serve_stdio_serves_index_status(@TempDir Path tmp) throws Exception {
        // Compile + jar + index so the DB is non-empty.
        Map<String, byte[]> compiled = compileAll(Map.of("com.acme.demo.Greeter", """
                package com.acme.demo;
                public class Greeter { public String greet() { return "hi"; } }
                """));
        Path jar = tmp.resolve("demo-1.0.0.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var e : compiled.entrySet()) {
                writeEntry(z, e.getKey().replace('.', '/') + ".class", e.getValue());
            }
        }
        Path db = tmp.resolve("jrdi.db");
        CliWiring.runIndex(jar.toString(), "sqlite:" + db, "test", false);

        // Build an MCP initialize + tools/call sequence and feed it to serveStdio.
        String init = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"client":"test"}}
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"index_status","arguments":{}}}
                """;
        java.io.ByteArrayInputStream input = new java.io.ByteArrayInputStream(
                init.getBytes(StandardCharsets.UTF_8));
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.io.PrintStream out = new java.io.PrintStream(output, true, StandardCharsets.UTF_8);

        // serveStdio blocks until EOF; run on a separate thread.
        Thread t = new Thread(() -> {
            try {
                var service = io.jrdi.mcp.JrdiMcpService.openSqlite("jdbc:sqlite:" + db);
                var server = new io.jrdi.mcp.JrdiMcpServer(service);
                server.serveStdio(input, out);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        t.start();
        t.join(10_000);
        if (t.isAlive()) {
            t.interrupt();
            throw new AssertionError("serveStdio hung");
        }

        String response = output.toString(StandardCharsets.UTF_8);
        // initialize response (id=1)
        assertThat(response).contains("\"id\":1");
        // tools/call response (id=2) for index_status, classes count >= 1
        assertThat(response).contains("\"id\":2");
        assertThat(response).contains("classes");
    }

    @Test
    void stats_command_human_and_json(@TempDir Path tmp) throws Exception {
        // Compile + jar + index so the DB has some classes, methods, etc.
        Map<String, byte[]> compiled = compileAll(Map.of(
                "com.acme.demo.Greeter", """
                        package com.acme.demo;
                        public class Greeter {
                            public String greet() { return "hi"; }
                            public String greet2() { return "hi2"; }
                        }
                        """,
                "com.acme.demo.User", """
                        package com.acme.demo;
                        public class User {
                            public String name() { return "alice"; }
                        }
                        """));
        Path jar = tmp.resolve("demo-1.0.0.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var e : compiled.entrySet()) {
                writeEntry(z, e.getKey().replace('.', '/') + ".class", e.getValue());
            }
        }
        Path db = tmp.resolve("jrdi.db");
        CliWiring.runIndex(jar.toString(), "sqlite:" + db, "stats-test", false);

        // Human-readable output
        PrintStream origOut = System.out;
        ByteArrayOutputStream humanBuf = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(humanBuf, true, StandardCharsets.UTF_8));
            CliWiring.runStats("sqlite:" + db, false);
        } finally {
            System.setOut(origOut);
        }
        String human = humanBuf.toString(StandardCharsets.UTF_8);
        assertThat(human).contains("=== jrdi stats ===");
        assertThat(human).contains("dialect:         sqlite");
        assertThat(human).contains("schema version:  V");  // some V applied
        assertThat(human).contains("last indexed:    ");  // not "(never)"
        // Core counts
        assertThat(human).contains("classes");
        assertThat(human).contains("methods");
        assertThat(human).contains("invokes");
        // P2 framework table names appear in the human view
        assertThat(human).contains("spring_beans");
        assertThat(human).contains("dubbo_services");
        assertThat(human).contains("dubbo_references");
        assertThat(human).contains("dubbo_method_configs");
        assertThat(human).contains("mybatis_statements");
        assertThat(human).contains("mybatis_result_maps");
        // V5: Spring Boot autoconfig (always 0 in this test, but the row must be present)
        assertThat(human).contains("spring_boot_autoconfigs");
        // V6: auto-config conditions row
        assertThat(human).contains("spring_autoconfig_conditions");

        // JSON output
        ByteArrayOutputStream jsonBuf = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(jsonBuf, true, StandardCharsets.UTF_8));
            CliWiring.runStats("sqlite:" + db, true);
        } finally {
            System.setOut(origOut);
        }
        String json = jsonBuf.toString(StandardCharsets.UTF_8).strip();
        assertThat(json).startsWith("{");
        assertThat(json).endsWith("}");
        assertThat(json).contains("\"dialect\":\"sqlite\"");
        assertThat(json).contains("\"schemaVersion\":\"V");
        assertThat(json).contains("\"classes\":");
        assertThat(json).contains("\"methods\":");
        assertThat(json).contains("\"dubboServices\":");
        assertThat(json).contains("\"mybatisStatements\":");
        // V5 autoconfig fields
        assertThat(json).contains("\"springBootAutoconfigs\":");
        assertThat(json).contains("\"springBootAutoconfigsFactories\":");
        assertThat(json).contains("\"springBootAutoconfigsImports\":");
        // V6 condition fields (always 0 in this test — we don't index Spring Boot jars)
        assertThat(json).contains("\"springAutoconfigConditions\":");
    }

    private static void writeEntry(ZipOutputStream z, String name, byte[] bytes) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(bytes, 0, bytes.length);
        z.closeEntry();
    }

    private static Map<String, byte[]> compileAll(Map<String, String> sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Map<String, ByteArrayOutputStream> outputs = new HashMap<>();
            List<JavaFileObject> units = new java.util.ArrayList<>();
            for (Map.Entry<String, String> e : sources.entrySet()) {
                units.add(new InMemSource(e.getKey(), e.getValue()));
            }
            CapturingFileManager cfm = new CapturingFileManager(fm, outputs);
            List<String> errors = new java.util.ArrayList<>();
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, cfm, d -> errors.add(d.toString()),
                    List.of("-g", "-Xlint:none"), null, units);
            Boolean ok = task.call();
            if (ok == null || !ok) {
                throw new IllegalStateException("compilation failed: " + errors);
            }
            Map<String, byte[]> result = new HashMap<>();
            for (String fqn : sources.keySet()) {
                ByteArrayOutputStream baos = outputs.get(fqn);
                if (baos != null && baos.size() > 0) result.put(fqn, baos.toByteArray());
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

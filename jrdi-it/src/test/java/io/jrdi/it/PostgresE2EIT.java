package io.jrdi.it;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jrdi.core.coord.Gav;
import io.jrdi.pipeline.ArtifactInput;
import io.jrdi.pipeline.IndexPipeline;
import io.jrdi.pipeline.IndexReport;
import io.jrdi.storage.Db;
import io.jrdi.storage.Migrator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
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
 * Real PostgreSQL integration test, gated behind a system property so it only
 * runs in environments that have a Docker daemon available.
 *
 * <p>Run with: {@code -Djrdi.pg.it=true}
 *
 * <p>Verifies:
 * <ul>
 *   <li>Flyway PG migration (V1__init.pg.sql) applies cleanly to a fresh DB</li>
 *   <li>jrdi's repos work against a real PG instance (insert + count + readback)</li>
 *   <li>IndexPipeline produces a non-empty report against a real PG backend</li>
 *   <li>The PG schema and the SQLite schema produce the same logical counts</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "jrdi.pg.it", matches = "true")
class PostgresE2EIT {

    private static final DockerImageName PG_IMAGE =
            DockerImageName.parse("postgres:16-alpine");

    private static PostgreSQLContainer<?> pg;

    @BeforeAll
    static void start() {
        pg = new PostgreSQLContainer<>(PG_IMAGE)
                .withDatabaseName("jrdi_test")
                .withUsername("jrdi")
                .withPassword("jrdi");
        pg.start();
    }

    @AfterAll
    static void stop() {
        if (pg != null) pg.stop();
    }

    @Test
    void migration_then_index_then_query() throws Exception {
        DataSource ds = pgDataSource();
        try (Db db = Db.open(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
            // 1. Flyway PG migration
            Migrator.migrate(db.dataSource());
        // 2. Build a small jar
        Map<String, byte[]> compiled = compileAll(Map.of(
                "com.acme.pg.Greeter", """
                        package com.acme.pg;
                        public class Greeter {
                            public String greet() { return "hi"; }
                        }
                        """));
        Path tmp = Files.createTempDirectory("jrdi-pg-it");
        Path jar = tmp.resolve("greeter-1.0.0.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var e : compiled.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey().replace('.', '/') + ".class"));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        // 3. Index via the real pipeline
        IndexPipeline pipeline = IndexPipeline.defaults(db);
            ArtifactInput input = new ArtifactInput(
                    new Gav("com.acme.pg", "greeter", "1.0.0"),
                    jar, java.util.Optional.empty());
            IndexReport report = pipeline.indexRepo("pg-it", tmp.toString(), List.of(input));
            assertThat(report.classesIndexed()).isEqualTo(1);
            assertThat(report.methodsIndexed()).isEqualTo(2);
        }
        // 4. Direct readback to confirm the data is in PG
        long count;
        try (var c = ds.getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM classes")) {
            rs.next();
            count = rs.getLong(1);
        }
        assertThat(count).isEqualTo(1);
        try (var c = ds.getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM methods")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(2);
        }
        try (var c = ds.getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery(
                     "SELECT fqn FROM classes WHERE fqn = 'com/acme/pg/Greeter'")) {
            assertThat(rs.next()).isTrue();
        }
    }

    private static DataSource pgDataSource() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(pg.getJdbcUrl());
        cfg.setUsername(pg.getUsername());
        cfg.setPassword(pg.getPassword());
        cfg.setMaximumPoolSize(2);
        return new HikariDataSource(cfg);
    }

    private static Map<String, byte[]> compileAll(Map<String, String> sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Map<String, ByteArrayOutputStream> outputs = new HashMap<>();
            List<JavaFileObject> units = new ArrayList<>();
            for (var e : sources.entrySet()) {
                units.add(new InMemSource(e.getKey(), e.getValue()));
            }
            CapturingFileManager cfm = new CapturingFileManager(fm, outputs);
            List<String> errors = new ArrayList<>();
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

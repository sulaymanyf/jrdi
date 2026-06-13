package io.jrdi.spring;

import io.jrdi.storage.Db;
import io.jrdi.storage.Migrator;
import io.jrdi.storage.repo.ArtifactRepo;
import io.jrdi.storage.repo.ClassRepo;
import io.jrdi.storage.repo.FileRepo;
import io.jrdi.storage.repo.SpringAutoconfigConditionRepo;
import io.jrdi.storage.repo.SpringBootAutoconfigRepo;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

// Test-only stub annotations live in their canonical Spring Boot packages
// (see src/test/java/org/springframework/boot/autoconfigure/condition/). They
// exist so the test compiles without depending on Spring Boot (which would
// bloat the test classpath by 50+ MB). The bytecode descriptors match the
// real Spring annotations, so SpringConditionalPass picks them up unchanged.

class SpringConditionalPassTest {

    private Db db;

    @BeforeEach
    void setUp() {
        db = Db.inMemorySqlite();
        Migrator.migrate(db.dataSource());
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void extracts_conditional_on_class_with_single_class(@TempDir Path tmp) throws Exception {
        // Compile a small class with @ConditionalOnClass(X.class) and index it.
        String source = """
                package com.acme.autoconfig;
                import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
                @ConditionalOnClass(javax.sql.DataSource.class)
                public class DataSourceAutoConfig {
                    public DataSourceAutoConfig() {}
                }
                """;
        indexSingleClass(db, "com.acme.autoconfig.DataSourceAutoConfig", source, tmp);

        SpringConditionalPass pass = new SpringConditionalPass(db);
        var r = pass.scanAll();
        assertThat(r.autoconfigsScanned()).isEqualTo(1);
        assertThat(r.conditionsRecorded()).isEqualTo(1);

        var allConds = SqliteRepos.springAutoconfigConditionRepo(db).findAll();
        assertThat(allConds).hasSize(1);
        assertThat(allConds.get(0).conditionType()).isEqualTo("on_class");
        assertThat(allConds.get(0).requiredClass()).isEqualTo("javax.sql.DataSource");
        assertThat(allConds.get(0).appliedTo()).isEqualTo("class");
    }

    @Test
    void extracts_conditional_on_class_with_array_of_classes(@TempDir Path tmp) throws Exception {
        // @ConditionalOnClass({X.class, Y.class}) — multi-element array.
        String source = """
                package com.acme.autoconfig;
                import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
                @ConditionalOnClass({javax.sql.DataSource.class, java.sql.Connection.class})
                public class DualConditionConfig {
                    public DualConditionConfig() {}
                }
                """;
        indexSingleClass(db, "com.acme.autoconfig.DualConditionConfig", source, tmp);

        new SpringConditionalPass(db).scanAll();
        var conds = SqliteRepos.springAutoconfigConditionRepo(db).findAll();
        // 2 conditions: one per class in the array.
        assertThat(conds).hasSize(2);
        assertThat(conds).extracting(SpringAutoconfigConditionRepo.Record::conditionType)
                .containsOnly("on_class");
        assertThat(conds).extracting(SpringAutoconfigConditionRepo.Record::requiredClass)
                .containsExactlyInAnyOrder(
                        "javax.sql.DataSource",
                        "java.sql.Connection");
    }

    @Test
    void extracts_conditional_on_missing_class(@TempDir Path tmp) throws Exception {
        String source = """
                package com.acme.autoconfig;
                import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
                @ConditionalOnMissingClass("com.acme.SomeClassThatDoesNotExist")
                public class NegativeConditionConfig {
                    public NegativeConditionConfig() {}
                }
                """;
        indexSingleClass(db, "com.acme.autoconfig.NegativeConditionConfig", source, tmp);
        new SpringConditionalPass(db).scanAll();

        var conds = SqliteRepos.springAutoconfigConditionRepo(db).findAll();
        assertThat(conds).hasSize(1);
        assertThat(conds.get(0).conditionType()).isEqualTo("on_missing_class");
        assertThat(conds.get(0).requiredClass()).isEqualTo("com.acme.SomeClassThatDoesNotExist");
    }

    @Test
    void extracts_conditional_on_property(@TempDir Path tmp) throws Exception {
        String source = """
                package com.acme.autoconfig;
                import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                @ConditionalOnProperty(prefix = "spring.datasource", name = "url")
                public class PropertyConditionConfig {
                    public PropertyConditionConfig() {}
                }
                """;
        indexSingleClass(db, "com.acme.autoconfig.PropertyConditionConfig", source, tmp);
        new SpringConditionalPass(db).scanAll();

        var conds = SqliteRepos.springAutoconfigConditionRepo(db).findAll();
        // @ConditionalOnProperty emits the "name" (and prefix) as String values.
        assertThat(conds).isNotEmpty();
        assertThat(conds.get(0).conditionType()).isEqualTo("on_property");
        // At least the "name" attribute should be captured.
        assertThat(conds).anyMatch(c -> c.requiredProperty().equals("url"));
    }

    @Test
    void no_conditions_recorded_for_class_without_conditionals(@TempDir Path tmp) throws Exception {
        String source = """
                package com.acme.autoconfig;
                public class PlainConfig {
                    public PlainConfig() {}
                }
                """;
        indexSingleClass(db, "com.acme.autoconfig.PlainConfig", source, tmp);
        var r = new SpringConditionalPass(db).scanAll();
        assertThat(r.autoconfigsScanned()).isEqualTo(1);
        assertThat(r.conditionsRecorded()).isZero();
    }

    @Test
    void missing_class_bytes_are_skipped_silently(@TempDir Path tmp) {
        // Seed an autoconfig record that points at a class we never indexed.
        var acRepo = SqliteRepos.springBootAutoconfigRepo(db);
        acRepo.upsert("com.acme.NonExistent", "META-INF/spring.factories", "factories",
                "org.springframework.boot.autoconfigure.EnableAutoConfiguration");

        // scanAll should not throw, just skip the missing class.
        var r = new SpringConditionalPass(db).scanAll();
        assertThat(r.autoconfigsScanned()).isEqualTo(1);
        assertThat(r.conditionsRecorded()).isZero();
    }

    @Test
    void extract_conditions_from_raw_bytes() throws Exception {
        // Direct test of the ASM extraction on hand-built bytes. This isolates
        // the bytecode walker from the DB lookup.
        String source = """
                package com.acme.autoconfig;
                import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
                import javax.sql.DataSource;
                @ConditionalOnClass(DataSource.class)
                public class RawDebug {
                    public RawDebug() {}
                }
                """;
        Map<String, String> sources = new HashMap<>();
        sources.put("com.acme.autoconfig.RawDebug", source);
        Map<String, byte[]> compiled = compileAll(sources);
        byte[] bytes = compiled.get("com.acme.autoconfig.RawDebug");
        assertThat(bytes).isNotNull();
        var conds = new SpringConditionalPass(db).extractConditions(bytes);
        assertThat(conds).hasSize(1);
        assertThat(conds.get(0).type()).isEqualTo("on_class");
        assertThat(conds.get(0).requiredClass()).isEqualTo("javax.sql.DataSource");
    }

    /**
     * Compile a single .java source, package it into a jar, and index it
     * through the storage layer. After this method returns, the
     * {@code spring_boot_autoconfigs} table contains a record for the
     * class so the {@link SpringConditionalPass} can pick it up.
     */
    private static void indexSingleClass(Db db, String fqn, String source, Path tmp) throws Exception {
        // 1. Compile the .java source
        Map<String, String> sources = new HashMap<>();
        sources.put(fqn, source);
        Map<String, byte[]> compiled = compileAll(sources);
        byte[] classBytes = compiled.get(fqn);
        if (classBytes == null) {
            throw new IllegalStateException("compilation failed for " + fqn);
        }

        // 2. Build a jar containing the .class
        Path jar = tmp.resolve("autoconfig-test.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            String entry = fqn.replace('.', '/') + ".class";
            z.putNextEntry(new ZipEntry(entry));
            z.write(classBytes, 0, classBytes.length);
            z.closeEntry();
        }

        // 3. Insert directly into the storage layer (we don't run the full
        // pipeline; we just need the class indexed so SpringConditionalPass
        // can find the .class bytes).
        // Insert a fake repo + artifact + file + class.
        ArtifactRepo artifactRepo = SqliteRepos.artifactRepo(db);
        FileRepo fileRepo = SqliteRepos.fileRepo(db);
        ClassRepo classRepo = SqliteRepos.classRepo(db);
        SpringBootAutoconfigRepo acRepo = SqliteRepos.springBootAutoconfigRepo(db);

        long repoId = SqliteRepos.repoRepo(db).upsert("test-repo", tmp.toString(),
                null, java.time.Instant.now().toString());
        long artifactId = artifactRepo.upsert(repoId,
                io.jrdi.core.coord.Gav.of("com.acme", "autoconfig-test", "1.0.0"),
                null, false, jar.toString(), 100);
        String relPath = "classes/" + fqn.replace('.', '/') + ".class";
        long fileId = fileRepo.upsert(artifactId, relPath, "class", 0L, null);
        classRepo.upsert(io.jrdi.core.symbol.Fqn.fromDotted(fqn),
                0x0001, null, fileId, null, "jar", java.util.List.of());

        // 4. Seed the autoconfig record (V5) so the conditional pass picks it up.
        acRepo.upsert(fqn, "META-INF/spring.factories", "factories",
                "org.springframework.boot.autoconfigure.EnableAutoConfiguration");
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

    private static final class CapturingFileManager extends javax.tools.ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, ByteArrayOutputStream> outputs;
        CapturingFileManager(StandardJavaFileManager delegate, Map<String, ByteArrayOutputStream> outputs) {
            super(delegate);
            this.outputs = outputs;
        }
        @Override
        public JavaFileObject getJavaFileForOutput(javax.tools.JavaFileManager.Location location, String className,
                                                    JavaFileObject.Kind kind,
                                                    javax.tools.FileObject sibling) {
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

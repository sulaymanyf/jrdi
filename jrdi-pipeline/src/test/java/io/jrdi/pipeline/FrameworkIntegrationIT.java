package io.jrdi.pipeline;

import io.jrdi.core.coord.Gav;
import io.jrdi.storage.Db;
import io.jrdi.storage.Migrator;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
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
 * Verifies that the P2 framework analyzers (Spring / Dubbo / MyBatis) actually run
 * inside the pipeline when a class is indexed from a real jar with a real sources.jar.
 *
 * <p>The class in this test is hand-written and contains Spring / Dubbo / MyBatis
 * annotations. The pipeline should:
 * <ol>
 *   <li>Compile the .java source</li>
 *   <li>Package it into a .jar</li>
 *   <li>Build a sources.jar</li>
 *   <li>Index both</li>
 *   <li>Record the framework facts (1 bean, 1 @Autowired site, 1 Dubbo service, 1 MyBatis statement)</li>
 * </ol>
 */
class FrameworkIntegrationIT {

    @Test
    void pipeline_records_framework_facts_from_sources(@TempDir Path tmp) throws Exception {
        // .java source containing Spring, Dubbo, and MyBatis annotations.
        // Note: we don't actually compile this source — the framework passes use JavaParser
        // to read it, not the JDK compiler. So we just write a trivial class to the jar
        // (the bytecode pass needs SOMETHING to scan) and ship the rich source alongside.
        String src = """
                package com.acme.demo;
                import com.acme.api.OrderApi;
                import org.springframework.stereotype.Service;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.apache.dubbo.config.annotation.DubboService;
                import org.apache.ibatis.annotations.Select;

                @Service
                @DubboService
                public class OrderService implements com.acme.api.OrderApi {
                    @Autowired
                    private OrderRepo repo;

                    public String ping() { return repo.findAll(); }

                    @Select("select * from orders where id = #{id}")
                    public String findById() { return ""; }
                }
                """;
        // Trivial bytecode for the class so the bytecode pass has something to index.
        // (The framework passes use JavaParser on the source; the bytecode pass doesn't
        // need the annotation classes to be present.)
        Map<String, byte[]> compiled = compileAll(Map.of(
                "com.acme.api.OrderApi",
                "package com.acme.api;\npublic interface OrderApi { String ping(); }\n",
                "com.acme.demo.OrderService",
                "package com.acme.demo;\n" +
                "import com.acme.api.OrderApi;\n" +
                "public class OrderService implements OrderApi { public String ping() { return \"\"; } public String findById() { return \"\"; } }\n"));

        // Build the main jar
        Path jar = tmp.resolve("ordersvc-1.0.0.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var e : compiled.entrySet()) {
                writeEntry(z, e.getKey().replace('.', '/') + ".class", e.getValue());
            }
        }
        // Build a sibling sources.jar (this is what the framework passes read)
        Path sourcesJar = tmp.resolve("ordersvc-1.0.0-sources.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(sourcesJar))) {
            writeEntry(z, "com/acme/demo/OrderService.java", src.getBytes(StandardCharsets.UTF_8));
        }

        // Index
        try (Db db = Db.inMemorySqlite()) {
            Migrator.migrate(db.dataSource());
            IndexPipeline pipeline = new IndexPipeline(db, io.jrdi.resolver.MavenSettingsParser.load());
            ArtifactInput input = new ArtifactInput(
                    new Gav("com.acme.demo", "ordersvc", "1.0.0"),
                    jar, java.util.Optional.of(sourcesJar));
            IndexReport report = pipeline.indexRepo("it", tmp.toString(), List.of(input));

            // The framework passes should have recorded 1 of each
            assertThat(report.springBeansRecorded()).isEqualTo(1);
            assertThat(report.springInjectsRecorded()).isEqualTo(1);
            assertThat(report.dubboServicesRecorded()).isEqualTo(1);
            assertThat(report.dubboReferencesRecorded()).isEqualTo(0);
            assertThat(report.mybatisStatementsRecorded()).isEqualTo(1);

            // Cross-check via the repos
            var springBeanRepo = SqliteRepos.springBeanRepo(db);
            var springInjectRepo = SqliteRepos.springInjectRepo(db);
            var dubboRepo = SqliteRepos.dubboServiceRepo(db);
            var mybatisRepo = SqliteRepos.mybatisStatementRepo(db);

            var beans = springBeanRepo.findByType(io.jrdi.core.symbol.Fqn.fromDotted("com.acme.demo.OrderService"));
            assertThat(beans).hasSize(1);
            assertThat(beans.get(0).source()).isEqualTo("annotation");

            var classRepo = SqliteRepos.classRepo(db);
            var klass = classRepo.findByFqn(io.jrdi.core.symbol.Fqn.fromDotted("com.acme.demo.OrderService"));
            assertThat(klass).isPresent();
            var injects = springInjectRepo.findByClass(klass.get().id());
            assertThat(injects).hasSize(1);
            assertThat(injects.get(0).targetField()).isEqualTo("repo");

            // The @DubboService is on the class, so the "interface" is whatever the class
            // implements (OrderApi in this test).
            var services = dubboRepo.findByInterface(io.jrdi.core.symbol.Fqn.fromDotted("com.acme.api.OrderApi"));
            assertThat(services).hasSize(1);

            var statements = mybatisRepo.findByNamespace("com.acme.demo.OrderService");
            assertThat(statements).hasSize(1);
            assertThat(statements.get(0).statementId()).isEqualTo("findById");
        }
    }

    @Test
    void pipeline_works_on_plain_java_without_framework_annotations(@TempDir Path tmp) throws Exception {
        // Sanity: framework passes must be no-ops on a class with no annotations.
        String src = """
                package com.acme.plain;
                public class Greeter {
                    public String greet() { return "hi"; }
                }
                """;
        Map<String, byte[]> compiled = compileAll(Map.of("com.acme.plain.Greeter", src));
        Path jar = tmp.resolve("plain-1.0.0.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (var e : compiled.entrySet()) {
                writeEntry(z, e.getKey().replace('.', '/') + ".class", e.getValue());
            }
        }
        Path sourcesJar = tmp.resolve("plain-1.0.0-sources.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(sourcesJar))) {
            writeEntry(z, "com/acme/plain/Greeter.java", src.getBytes(StandardCharsets.UTF_8));
        }
        try (Db db = Db.inMemorySqlite()) {
            Migrator.migrate(db.dataSource());
            IndexPipeline pipeline = new IndexPipeline(db, io.jrdi.resolver.MavenSettingsParser.load());
            ArtifactInput input = new ArtifactInput(
                    new Gav("com.acme.plain", "plain", "1.0.0"),
                    jar, java.util.Optional.of(sourcesJar));
            IndexReport report = pipeline.indexRepo("it", tmp.toString(), List.of(input));
            assertThat(report.springBeansRecorded()).isZero();
            assertThat(report.springInjectsRecorded()).isZero();
            assertThat(report.dubboServicesRecorded()).isZero();
            assertThat(report.dubboReferencesRecorded()).isZero();
            assertThat(report.mybatisStatementsRecorded()).isZero();
        }
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

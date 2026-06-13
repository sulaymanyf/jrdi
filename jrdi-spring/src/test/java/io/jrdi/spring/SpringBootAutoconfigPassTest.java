package io.jrdi.spring;

import io.jrdi.storage.Db;
import io.jrdi.storage.Migrator;
import io.jrdi.storage.repo.SpringBootAutoconfigRepo;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class SpringBootAutoconfigPassTest {

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
    void parses_legacy_spring_factories_with_known_keys(@TempDir Path tmp) throws IOException {
        String properties = """
                # Legacy pre-3.0 format
                org.springframework.boot.autoconfigure.EnableAutoConfiguration=\\
                com.acme.MyAutoConfig,\\
                com.acme.OtherAutoConfig
                org.springframework.context.ApplicationListener=\
                com.acme.MyListener
                """;
        Path file = tmp.resolve("spring.factories");
        Files.writeString(file, properties, StandardCharsets.UTF_8);

        int n = new SpringBootAutoconfigPass(db).parseFactories(file);
        assertThat(n).isEqualTo(3);

        SpringBootAutoconfigRepo repo = SqliteRepos.springBootAutoconfigRepo(db);
        var enableAc = repo.findByKey("org.springframework.boot.autoconfigure.EnableAutoConfiguration");
        assertThat(enableAc).hasSize(2);
        assertThat(enableAc).extracting(SpringBootAutoconfigRepo.Record::classFqn)
                .containsExactlyInAnyOrder("com.acme.MyAutoConfig", "com.acme.OtherAutoConfig");
        assertThat(enableAc.get(0).sourceFormat()).isEqualTo("factories");

        var listeners = repo.findByKey("org.springframework.context.ApplicationListener");
        assertThat(listeners).hasSize(1);
        assertThat(listeners.get(0).classFqn()).isEqualTo("com.acme.MyListener");
    }

    @Test
    void parses_modern_autoconfig_imports(@TempDir Path tmp) throws IOException {
        String imports = """
                # Spring Boot 3.0+ format
                com.acme.ModernAutoConfig
                com.acme.OtherModernAutoConfig
                # This is a comment line
                com.acme.ThirdModernAutoConfig
                """;
        Path file = tmp.resolve("AutoConfiguration.imports");
        Files.writeString(file, imports, StandardCharsets.UTF_8);

        int n = new SpringBootAutoconfigPass(db).parseImportsFile(file);
        assertThat(n).isEqualTo(3);

        var all = SqliteRepos.springBootAutoconfigRepo(db).findByFormat("imports");
        assertThat(all).hasSize(3);
        assertThat(all).extracting(SpringBootAutoconfigRepo.Record::classFqn)
                .containsExactlyInAnyOrder(
                        "com.acme.ModernAutoConfig",
                        "com.acme.OtherModernAutoConfig",
                        "com.acme.ThirdModernAutoConfig");
        // .imports files have no key concept
        assertThat(all.get(0).keyInFactories()).isEmpty();
        assertThat(all.get(0).sourceFormat()).isEqualTo("imports");
    }

    @Test
    void reads_both_formats_from_a_jar(@TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("acme-starter-1.0.0.jar");
        String factories = """
                org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
                com.acme.JarAutoConfig
                """;
        String imports = """
                com.acme.JarImportsConfig
                """;
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("META-INF/spring.factories"));
            z.write(factories.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"));
            z.write(imports.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        var r = new SpringBootAutoconfigPass(db).scanJar(jar);
        assertThat(r.autoconfigsRecorded()).isEqualTo(2);
        assertThat(r.factoriesFilesScanned()).isEqualTo(1);
        assertThat(r.importsFilesScanned()).isEqualTo(1);

        var repo = SqliteRepos.springBootAutoconfigRepo(db);
        assertThat(repo.findAll()).hasSize(2);
    }

    @Test
    void unknown_keys_in_spring_factories_are_ignored(@TempDir Path tmp) throws IOException {
        // Only KNOWN_KEYS are recorded. Custom keys are dropped.
        String properties = """
                org.springframework.boot.autoconfigure.EnableAutoConfiguration=com.acme.KnownConfig
                com.acme.some.random.Key=com.acme.UnknownConfig
                """;
        Path file = tmp.resolve("spring.factories");
        Files.writeString(file, properties, StandardCharsets.UTF_8);

        int n = new SpringBootAutoconfigPass(db).parseFactories(file);
        assertThat(n).isEqualTo(1);  // only the EnableAutoConfiguration entry survives

        var hits = SqliteRepos.springBootAutoconfigRepo(db).findByClass("com.acme.KnownConfig");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).keyInFactories())
                .isEqualTo("org.springframework.boot.autoconfigure.EnableAutoConfiguration");

        // The random key's value is dropped.
        assertThat(SqliteRepos.springBootAutoconfigRepo(db).findByClass("com.acme.UnknownConfig"))
                .isEmpty();
    }

    @Test
    void blank_and_comment_lines_in_imports_are_ignored(@TempDir Path tmp) throws IOException {
        String imports = """

                # leading comment

                com.acme.RealConfig
                """;
        Path file = tmp.resolve("AutoConfiguration.imports");
        Files.writeString(file, imports, StandardCharsets.UTF_8);
        int n = new SpringBootAutoconfigPass(db).parseImportsFile(file);
        assertThat(n).isEqualTo(1);
        assertThat(SqliteRepos.springBootAutoconfigRepo(db).findByClass("com.acme.RealConfig")).hasSize(1);
    }

    @Test
    void scan_is_idempotent(@TempDir Path tmp) throws IOException {
        String properties = """
                org.springframework.boot.autoconfigure.EnableAutoConfiguration=com.acme.X
                """;
        Path file = tmp.resolve("spring.factories");
        Files.writeString(file, properties, StandardCharsets.UTF_8);
        var pass = new SpringBootAutoconfigPass(db);
        pass.parseFactories(file);
        pass.parseFactories(file);
        pass.parseFactories(file);
        // V5 ON CONFLICT DO NOTHING: only one row survives.
        assertThat(SqliteRepos.springBootAutoconfigRepo(db).findAll()).hasSize(1);
    }

    @Test
    void find_by_class_returns_source_metadata(@TempDir Path tmp) throws IOException {
        String properties = """
                org.springframework.boot.autoconfigure.EnableAutoConfiguration=com.acme.MultiKey
                org.springframework.context.ApplicationListener=com.acme.MultiKey
                """;
        Path file = tmp.resolve("spring.factories");
        Files.writeString(file, properties, StandardCharsets.UTF_8);
        new SpringBootAutoconfigPass(db).parseFactories(file);

        // com.acme.MultiKey appears under TWO different keys.
        List<SpringBootAutoconfigRepo.Record> hits =
                SqliteRepos.springBootAutoconfigRepo(db).findByClass("com.acme.MultiKey");
        assertThat(hits).hasSize(2);
        assertThat(hits).extracting(SpringBootAutoconfigRepo.Record::keyInFactories)
                .containsExactlyInAnyOrder(
                        "org.springframework.boot.autoconfigure.EnableAutoConfiguration",
                        "org.springframework.context.ApplicationListener");
    }
}

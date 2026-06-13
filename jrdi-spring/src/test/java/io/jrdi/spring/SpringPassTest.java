package io.jrdi.spring;

import io.jrdi.core.symbol.Fqn;
import io.jrdi.storage.Db;
import io.jrdi.storage.Migrator;
import io.jrdi.storage.repo.SpringBeanRepo;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpringPassTest {

    private Db db;

    @BeforeEach
    void setUp() {
        db = Db.open("jdbc:sqlite:file::memory:?cache=shared");
        Migrator.migrate(db.dataSource());
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void component_annotation_records_bean() {
        String src = """
                package com.acme;
                import org.springframework.stereotype.Service;
                @Service
                public class Greeter {
                    public String greet() { return "hi"; }
                }
                """;
        new SpringPass(db).scan(src, Fqn.fromDotted("com.acme.Greeter"));
        SpringBeanRepo repo = SqliteRepos.springBeanRepo(db);
        var hits = repo.findByType(Fqn.fromDotted("com.acme.Greeter"));
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).name()).isEqualTo("greeter");  // default lower-cased
        assertThat(hits.get(0).source()).isEqualTo("annotation");
    }

    @Test
    void explicit_value_overrides_default_bean_name() {
        String src = """
                package com.acme;
                import org.springframework.stereotype.Component;
                @Component("customName")
                public class Foo {}
                """;
        new SpringPass(db).scan(src, Fqn.fromDotted("com.acme.Foo"));
        var hits = SqliteRepos.springBeanRepo(db).findByType(Fqn.fromDotted("com.acme.Foo"));
        assertThat(hits.get(0).name()).isEqualTo("customName");
    }

    @Test
    void bean_method_under_configuration_class() {
        String src = """
                package com.acme;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                @Configuration
                public class AppConfig {
                    @Bean
                    public String hello() { return "hi"; }
                }
                """;
        new SpringPass(db).scan(src, Fqn.fromDotted("com.acme.AppConfig"));
        // The bean is named after the method, typed by the return.
        var hits = SqliteRepos.springBeanRepo(db).findByName("hello");
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).source()).isEqualTo("config");
    }

    @Test
    void autowired_field_records_inject_site() {
        // First, define the candidate bean
        new SpringPass(db).scan("""
                package com.acme;
                import org.springframework.stereotype.Service;
                @Service
                public class Repo {}
                """, Fqn.fromDotted("com.acme.Repo"));

        // Then, a class that injects it
        new SpringPass(db).scan("""
                package com.acme;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.stereotype.Service;
                @Service
                public class App {
                    @Autowired
                    private Repo repo;
                }
                """, Fqn.fromDotted("com.acme.App"));

        // The bytecode pass wasn't run, so the classes table has no rows.
        // The inject row is recorded with classId=-1 (signaling "no class row yet");
        // P2.5 will backfill from the bytecode indexer.
        // For now, verify the inject row is stored by querying directly.
                try (var c = db.getConnection();
                     var st = c.createStatement();
                     var rs = st.executeQuery("SELECT target_field, confidence, by_value FROM spring_injects")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("target_field")).isEqualTo("repo");
                // Resolution: candidate "Repo" type matches the @Service-annotated bean by name
                assertThat(rs.getString("by_value")).isEqualTo("TYPE");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
    }

    /**
     * P2.5: when the field type is an interface, candidate resolution should
     * find beans whose class implements the interface — not just exact type matches.
     */
    @Test
    void interface_typed_autowired_resolves_to_implementing_bean() {
        // 1. Seed the interface and its implementing class in the classes table.
        //    The SpringPass does not index classes itself — the pipeline does —
        //    so we pre-populate them to simulate a real index.
        var classRepo = io.jrdi.storage.repo.sqlite.SqliteRepos.classRepo(db);
        var interfaceId = classRepo.upsert(
                io.jrdi.core.symbol.Fqn.fromDotted("com.acme.api.OrderApi"),
                0x0600, // ACC_INTERFACE | ACC_ABSTRACT
                null, null, null, "jar",
                java.util.List.of());
        var implId = classRepo.upsert(
                io.jrdi.core.symbol.Fqn.fromDotted("com.acme.provider.OrderProviderImpl"),
                0x0001, // ACC_PUBLIC
                io.jrdi.core.symbol.Fqn.fromDotted("java.lang.Object"),
                null, null, "jar",
                java.util.List.of(io.jrdi.core.symbol.Fqn.fromDotted("com.acme.api.OrderApi")));

        // 2. Record a bean for the impl (so it shows up in findByName).
        var beanRepo = io.jrdi.storage.repo.sqlite.SqliteRepos.springBeanRepo(db);
        beanRepo.upsert("orderProvider",
                io.jrdi.core.symbol.Fqn.fromDotted("com.acme.provider.OrderProviderImpl"),
                "annotation", implId, null, "singleton", false);

        // 3. Index a class that @Autowired-s the interface.
        new SpringPass(db).scan("""
                package com.acme.consumer;
                import com.acme.api.OrderApi;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.stereotype.Service;
                @Service
                public class OrderConsumer {
                    @Autowired
                    private OrderApi orderApi;
                }
                """, io.jrdi.core.symbol.Fqn.fromDotted("com.acme.consumer.OrderConsumer"));

        // 4. Verify the inject row found a candidate via interface matching.
        try (var c = db.getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery(
                     "SELECT target_field, confidence, by_value, candidate_bean_ids "
                             + "FROM spring_injects WHERE target_field='orderApi'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("target_field")).isEqualTo("orderApi");
            assertThat(rs.getString("by_value")).isEqualTo("TYPE");
            // Interface match is best-effort → PROBABLE
            assertThat(rs.getString("confidence")).isEqualTo("PROBABLE");
            // The candidate list should include the impl bean
            String candidates = rs.getString("candidate_bean_ids");
            long implBeanId = beanRepo.findByName("orderProvider").get(0).id();
            assertThat(candidates).contains(String.valueOf(implBeanId));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

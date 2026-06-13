package io.jrdi.storage;

import io.jrdi.core.coord.Gav;
import io.jrdi.core.symbol.Fqn;
import io.jrdi.core.symbol.MethodKey;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteRoundtripIT {

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
    void migrate_then_insert_then_query_round_trip() {
        var repos = SqliteRepos.repoRepo(db);
        var artifacts = SqliteRepos.artifactRepo(db);
        var classes = SqliteRepos.classRepo(db);
        var methods = SqliteRepos.methodRepo(db);
        var fields = SqliteRepos.fieldRepo(db);
        var invokes = SqliteRepos.invokeRepo(db);
        var lambdas = SqliteRepos.lambdaRepo(db);
        var issues = SqliteRepos.issueRepo(db);

        long repoId = repos.upsert("petclinic", "/abs/path", "abc123", Instant.now().toString());
        assertThat(repoId).isPositive();

        Gav gav = Gav.of("org.springframework.samples", "petclinic", "3.0.0");
        long artId = artifacts.upsert(repoId, gav, "deadbeef", true, "/abs/path/lib.jar", 100);
        assertThat(artId).isPositive();

        Fqn owner = Fqn.fromDotted("com.acme.Owner");
        long classId = classes.upsert(owner, 0x0001, Fqn.OBJECT, null, "<T:Ljava/lang/Object;>(TT;)V", "jar");
        long ifaceClassId = classes.upsert(Fqn.fromDotted("com.acme.OwnerRepository"), 0x0401, Fqn.OBJECT,
                null, "Lcom/acme/OwnerRepository<Lcom/acme/Owner;>;", "jar");

        MethodKey key = new MethodKey("save", "(Lcom/acme/Owner;)V");
        long methodId = methods.upsert(classId, key.name(), key.descriptor(), null, 42, 80, false);
        assertThat(methods.findByKey(owner, key)).isPresent()
                .hasValueSatisfying(rec -> {
                    assertThat(rec.startLine()).isEqualTo(42);
                    assertThat(rec.endLine()).isEqualTo(80);
                    assertThat(rec.virtual()).isFalse();
                });

        fields.upsert(classId, "firstName", "Ljava/lang/String;", null, 12);

        invokes.insertAll(List.of(
                new io.jrdi.storage.repo.InvokeRepo.Edge(
                        methodId,
                        "com/acme/OwnerRepository",
                        "save",
                        "(Lcom/acme/Owner;)Lcom/acme/Owner;",
                        io.jrdi.storage.repo.InvokeRepo.Kind.INTERFACE,
                        55,
                        io.jrdi.storage.repo.InvokeRepo.Confidence.CERTAIN),
                new io.jrdi.storage.repo.InvokeRepo.Edge(
                        methodId,
                        "com/acme/UNKNOWN",
                        "lookup",
                        "(Ljava/lang/String;)V",
                        io.jrdi.storage.repo.InvokeRepo.Kind.REFLECT,
                        60,
                        io.jrdi.storage.repo.InvokeRepo.Confidence.UNCERTAIN)
        ));

        List<io.jrdi.storage.repo.InvokeRepo.Edge> callers =
                invokes.findCallersOf("com/acme/OwnerRepository", "save", "(Lcom/acme/Owner;)Lcom/acme/Owner;");
        assertThat(callers).hasSize(1);
        assertThat(callers.get(0).confidence()).isEqualTo(io.jrdi.storage.repo.InvokeRepo.Confidence.CERTAIN);

        lambdas.upsert(methodId, null, "java/lang/invoke/LambdaMetafactory", 70);
        issues.record("missing_source", "org/mozilla/classfile/ByteCode", "no sources.jar",
                io.jrdi.storage.repo.IssueRepo.Severity.WARN, Instant.now().toString());

        assertThat(classes.findByFqn(owner)).isPresent();
        assertThat(artifacts.findByGav(repoId, gav)).isPresent()
                .hasValueSatisfying(rec -> {
                    assertThat(rec.sha256()).isEqualTo("deadbeef");
                    assertThat(rec.hasSources()).isTrue();
                    assertThat(rec.score()).isEqualTo(100);
                });
        assertThat(artifacts.findByGavGroupArtifact("org.springframework.samples", "petclinic"))
                .isPresent()
                .hasValueSatisfying(rec -> assertThat(rec.gav().version()).isEqualTo("3.0.0"));
        assertThat(issues.findByKind("missing_source")).hasSize(1);

        // second upsert should not create duplicate artifacts
        long artId2 = artifacts.upsert(repoId, gav, "deadbeef2", true, "/abs/path/lib.jar", 100);
        assertThat(artId2).isEqualTo(artId);
        assertThat(artifacts.findByGav(repoId, gav).orElseThrow().sha256()).isEqualTo("deadbeef2");

        // shadow-aware: re-insert with higher score in same repo is a no-op (UNIQUE on repo_id+gav)
        // but the version-pick across repos will be handled in P2.
    }

    @Test
    void id_query_for_iface_returns_inserted_record() {
        var repos = SqliteRepos.repoRepo(db);
        var classes = SqliteRepos.classRepo(db);
        long repoId = repos.upsert("repo", "/p", null, Instant.now().toString());
        long id = classes.upsert(Fqn.fromDotted("com.acme.I"), 0x0601, null, null, null, "jar");
        assertThat(id).isPositive();
        assertThat(classes.findByFqn(Fqn.fromDotted("com.acme.I"))).isPresent();
    }
}

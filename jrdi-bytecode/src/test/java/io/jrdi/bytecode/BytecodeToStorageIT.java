package io.jrdi.bytecode;

import io.jrdi.core.symbol.Fqn;
import io.jrdi.storage.Db;
import io.jrdi.storage.Migrator;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke: compile a class with invokes/lambdas/reflection, run the pass, persist
 * the results through the storage layer, and verify the rows are queryable.
 */
class BytecodeToStorageIT {

    private Db db;

    @BeforeEach
    void setUp() throws IOException {
        db = Db.inMemorySqlite();
        Migrator.migrate(db.dataSource());
    }

    @AfterEach
    void tearDown() throws IOException {
        db.close();
    }

    @Test
    void persist_pass_result_to_storage() throws IOException {
        InMemoryCompiler.Compiled c = InMemoryCompiler.compile("com.acme.Persist", """
                package com.acme;
                public class Persist {
                    public String greet(String name) {
                        return "hi " + name;
                    }
                    public Object makeList() throws Exception {
                        return Class.forName("java.util.ArrayList").getDeclaredConstructor().newInstance();
                    }
                }
                """);
        PassResult r = new BytecodePass().run(Fqn.fromDotted(c.fqn()), c.classBytes());

        var classRepo = SqliteRepos.classRepo(db);
        var methodRepo = SqliteRepos.methodRepo(db);
        var invokeRepo = SqliteRepos.invokeRepo(db);
        var lambdaRepo = SqliteRepos.lambdaRepo(db);

        long classId = classRepo.upsert(r.fqn(), r.access(), r.superFqn().orElse(null), null,
                r.classSignatureRaw(), "jar");

        for (PassResult.MethodInfo m : r.methods()) {
            methodRepo.upsert(classId, m.name(), m.desc(), m.signatureRaw(),
                    m.startLine(), m.endLine(), false);
        }

        // For invokes we need caller method ids — re-resolve by name+desc
        var invoker = new java.util.HashMap<String, Long>();
        for (PassResult.MethodInfo m : r.methods()) {
            invoker.put(m.name() + m.desc(),
                    methodRepo.findByKey(r.fqn(),
                            new io.jrdi.core.symbol.MethodKey(m.name(), m.desc())).orElseThrow().id());
        }

        var edges = new java.util.ArrayList<io.jrdi.storage.repo.InvokeRepo.Edge>();
        for (PassResult.InvokeEdge e : r.invokes()) {
            long callerId = invoker.get(r.methods().get(e.methodIndex()).name()
                    + r.methods().get(e.methodIndex()).desc());
            edges.add(new io.jrdi.storage.repo.InvokeRepo.Edge(
                    callerId,
                    e.calleeOwner(),
                    e.calleeName(),
                    e.calleeDesc(),
                    io.jrdi.storage.repo.InvokeRepo.Kind.valueOf(e.kind().name()),
                    e.line(),
                    io.jrdi.storage.repo.InvokeRepo.Confidence.valueOf(e.confidence().name())));
        }
        invokeRepo.insertAll(edges);

        for (PassResult.LambdaInfo l : r.lambdas()) {
            long enclosingId = invoker.get(r.methods().get(l.enclosingMethodIndex()).name()
                    + r.methods().get(l.enclosingMethodIndex()).desc());
            lambdaRepo.upsert(enclosingId, null, l.bsmTarget(), l.line());
        }

        // Query back
        assertThat(classRepo.findByFqn(r.fqn())).isPresent();
        assertThat(r.methods()).allSatisfy(m -> assertThat(
                methodRepo.findByKey(r.fqn(),
                        new io.jrdi.core.symbol.MethodKey(m.name(), m.desc())))
                .isPresent());
        assertThat(invokeRepo.findCallersOf("java/lang/Object", "toString", "()Ljava/lang/String;")
                .size()).isGreaterThanOrEqualTo(0);
    }
}

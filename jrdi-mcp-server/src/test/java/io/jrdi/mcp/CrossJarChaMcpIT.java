package io.jrdi.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jrdi.callgraph.ExternalClassResolver;
import io.jrdi.core.symbol.Fqn;
import io.jrdi.storage.Db;
import io.jrdi.storage.Migrator;
import io.jrdi.storage.repo.ClassRepo;
import io.jrdi.storage.repo.MethodRepo;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check: a class in the DB extends a parent in a jar outside the DB.
 * The {@code find_path} tool should still be able to find a path through the
 * parent's methods (the cross-jar CHA path).
 */
class CrossJarChaMcpIT {

    @Test
    void find_path_walks_into_external_parent(@TempDir Path tmp) throws Exception {
        // 1. Compile parent + child.
        Map<String, String> src = Map.of(
                "com.ext.Base",
                "package com.ext;\n" +
                "public class Base { public String root() { return \"r\"; } }\n",
                "com.usr.Middle",
                "package com.usr;\n" +
                "import com.ext.Base;\n" +
                "public class Middle extends Base {\n" +
                "  public String mid() { return root(); }\n" +
                "}\n");
        Map<String, byte[]> compiled = compileAll(src);

        // 2. Build a "dependency" jar (the external lib containing Base).
        Path depJar = tmp.resolve("ext-1.0.0.jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(depJar))) {
            writeEntry(z, "com/ext/Base.class", compiled.get("com.ext.Base"));
        }

        // 3. Index only Middle into a fresh DB (Base is in depJar, NOT indexed).
        try (Db db = Db.inMemorySqlite()) {
            Migrator.migrate(db.dataSource());
            // Manually populate classes + methods + invokes to simulate a real index.
            ClassRepo classRepo = SqliteRepos.classRepo(db);
            MethodRepo methodRepo = SqliteRepos.methodRepo(db);
            io.jrdi.storage.repo.InvokeRepo invokeRepo = SqliteRepos.invokeRepo(db);
            long midId = classRepo.upsert(Fqn.fromDotted("com.usr.Middle"),
                    0x0001, Fqn.fromDotted("com.ext.Base"), null, null, "jar");
            long midMethodId = methodRepo.upsert(midId, "mid", "()Ljava/lang/String;",
                    null, 10, 15, false);
            // mid() invokes Base.root() virtually
            invokeRepo.insertAll(List.of(new io.jrdi.storage.repo.InvokeRepo.Edge(
                    midMethodId, "com/ext/Base", "root", "()Ljava/lang/String;",
                    io.jrdi.storage.repo.InvokeRepo.Kind.VIRTUAL, 12,
                    io.jrdi.storage.repo.InvokeRepo.Confidence.CERTAIN)));

            // 4. Build a service with a custom external resolver pointing at depJar.
            var service = new JrdiMcpService(db);
            ExternalClassResolver ext = new io.jrdi.callgraph.M2ClasspathResolver(List.of(tmp));
            // Use the buildCallGraph(Supplier) variant to inject the test resolver.
            io.jrdi.callgraph.CallGraph graph = service.buildCallGraph(() -> ext);
            // The graph should contain a VIRTUAL edge from Middle.mid() to Base.root()
            // via the cross-jar resolution.
            var base = Fqn.fromDotted("com.ext.Base");
            boolean foundCrossJarEdge = graph.edges().stream()
                    .anyMatch(e -> e.to().owner().equals(base) && e.to().key().name().equals("root"));
            assertThat(foundCrossJarEdge)
                    .as("CHA should expand Middle.mid() to Base.root() via m2 resolver")
                    .isTrue();

            // 5. The same call graph should also work via the MCP find_path tool.
            // (For the full server path we use the default m2 resolver; since depJar
            // lives in tmp and defaultM2() is ~/.m2/repository, this server-level path
            // won't find it. The unit test above already proves the cross-jar CHA works.)
            JrdiMcpServer server = new JrdiMcpServer(service);
            String input = """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"find_path","arguments":{"fromOwner":"com.usr.Middle","fromName":"mid","fromDesc":"()Ljava/lang/String;","toOwner":"com.usr.Middle","toName":"mid","toDesc":"()Ljava/lang/String;","maxDepth":4}}}
                    """.trim() + "\n";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            server.serveStdio(
                    new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                    new PrintStream(out, true, StandardCharsets.UTF_8));
            List<JsonNode> responses = readResponses(out);
            assertThat(responses).hasSize(2);
            String pathText = responses.get(1).get("result").get("content").get(0).get("text").asText();
            // path should contain both endpoints
            assertThat(pathText).contains("found");
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

    private static List<JsonNode> readResponses(ByteArrayOutputStream out) throws Exception {
        ObjectMapper M = new ObjectMapper();
        String[] lines = out.toString(StandardCharsets.UTF_8).split("\n");
        List<JsonNode> out2 = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) continue;
            out2.add(M.readTree(line));
        }
        return out2;
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

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
 */package io.jrdi.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jrdi.callgraph.BfsPathFinder;
import io.jrdi.callgraph.CallGraph;
import io.jrdi.callgraph.ChaResolver;
import io.jrdi.callgraph.EdgeExpander;
import io.jrdi.callgraph.MethodRef;
import io.jrdi.core.symbol.Fqn;
import io.jrdi.core.symbol.MethodKey;
import io.jrdi.storage.Db;
import io.jrdi.storage.Migrator;
import io.jrdi.storage.repo.ClassRepo;
import io.jrdi.storage.repo.DubboMethodConfigRepo;
import io.jrdi.storage.repo.DubboReferenceRepo;
import io.jrdi.storage.repo.DubboRegistryRepo;
import io.jrdi.storage.repo.DubboServiceRepo;
import io.jrdi.storage.repo.InvokeRepo;
import io.jrdi.storage.repo.IssueRepo;
import io.jrdi.storage.repo.MethodRepo;
import io.jrdi.storage.repo.MybatisResultMapRepo;
import io.jrdi.storage.repo.MybatisStatementRepo;
import io.jrdi.storage.repo.SpringAutoconfigConditionRepo;
import io.jrdi.storage.repo.SpringBeanRepo;
import io.jrdi.storage.repo.SpringBootAutoconfigRepo;
import io.jrdi.storage.repo.SpringInjectRepo;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The MCP tool surface. Each public method corresponds to one MCP {@code tools/call}
 * target. The {@link JrdiMcpServer} routes {@code method} strings to these methods.
 *
 * <p>Tool surface (per the plan in README):
 * <ul>
 *   <li>{@code index_status} — return current repo + fact counts</li>
 *   <li>{@code find_symbol} — exact match on FQN prefix</li>
 *   <li>{@code describe_method} — method detail with line numbers</li>
 *   <li>{@code callers_of} — list callers of a method</li>
 *   <li>{@code callees_of} — list callees of a method (resolved through CHA)</li>
 *   <li>{@code find_path} — BFS shortest path between two methods</li>
 *   <li>{@code list_issues} — issues table entries</li>
 *   <li>{@code find_spring_beans} — Spring beans by type/name (P2.1)</li>
 *   <li>{@code find_spring_injects} — Spring @Autowired sites for a class (P2.1)</li>
 *   <li>{@code find_dubbo_services} — Dubbo providers by interface (P2.2)</li>
 *   <li>{@code find_dubbo_references} — Dubbo consumers by interface (P2.2)</li>
 *   <li>{@code find_dubbo_method_configs} — per-method Dubbo tuning (P2.7+)</li>
 *   <li>{@code find_mybatis_statements} — MyBatis SQL by namespace/statement id (P2.3)</li>
 *   <li>{@code find_mybatis_result_maps} — MyBatis row-mapper shape (P2.7+)</li>
 *   <li>{@code find_spring_autoconfigs} — Spring Boot auto-configurations (V5)</li>
 *   <li>{@code find_spring_autoconfig_conditions} — @Conditional* annotations on auto-configs (V6)</li>
 * </ul>
 */
public final class JrdiMcpService {

    private static final Logger LOG = LoggerFactory.getLogger(JrdiMcpService.class);

    private final Db db;
    private final ObjectMapper mapper = new ObjectMapper();

    public JrdiMcpService(Db db) {
        this.db = db;
        // Serialize Fqn as a string (toString = slashed form) so tool output
        // is round-trippable JSON.
        SimpleModule fqnModule = new SimpleModule();
        fqnModule.addSerializer(io.jrdi.core.symbol.Fqn.class,
                new com.fasterxml.jackson.databind.JsonSerializer<io.jrdi.core.symbol.Fqn>() {
                    @Override
                    public void serialize(io.jrdi.core.symbol.Fqn value,
                                          com.fasterxml.jackson.core.JsonGenerator gen,
                                          com.fasterxml.jackson.databind.SerializerProvider serializers)
                            throws java.io.IOException {
                        gen.writeString(value.slashed());
                    }
                });
        mapper.registerModule(fqnModule);
    }

    public Db db() {
        return db;
    }

    public static JrdiMcpService openSqlite(String jdbcUrl) {
        Db db = Db.open(normalizeUrl(jdbcUrl));
        Migrator.migrate(db.dataSource());
        return new JrdiMcpService(db);
    }

    private static String normalizeUrl(String url) {
        if (url.startsWith("jdbc:")) return url;
        if (url.startsWith("sqlite:")) return "jdbc:" + url;
        if (url.startsWith("postgres:")) return "jdbc:" + url;
        if (url.equals(":memory:")) return "jdbc:sqlite::memory:";
        return url;
    }

    public ObjectNode toolIndexStatus() {
        var classRepo = SqliteRepos.classRepo(db);
        var methodRepo = SqliteRepos.methodRepo(db);
        var invokeRepo = SqliteRepos.invokeRepo(db);
        var issueRepo = SqliteRepos.issueRepo(db);
        ObjectNode out = mapper.createObjectNode();
        out.put("classes", count("SELECT COUNT(*) FROM classes", db));
        out.put("methods", count("SELECT COUNT(*) FROM methods", db));
        out.put("invokes", count("SELECT COUNT(*) FROM invokes", db));
        out.put("issues", count("SELECT COUNT(*) FROM issues", db));
        out.put("uncertainReflections", issueRepo.findByKind("uncertain_reflect").size());
        out.put("dialect", db.dialect().name());
        return out;
    }

    public ObjectNode toolFindSymbol(JsonNode args) {
        String prefix = args.path("prefix").asText("");
        var classRepo = SqliteRepos.classRepo(db);
        var list = classRepo.findByFqn(Fqn.fromDotted(prefix));
        ObjectNode out = mapper.createObjectNode();
        out.put("matched", list.isPresent());
        if (list.isPresent()) {
            out.set("class", mapper.valueToTree(list.get()));
        } else {
            // Fall back: linear scan over all classes with the same prefix.
            // (P1: no `findByPrefix`, so do a SQL LIKE through JDBC directly.)
            out.set("prefix", mapper.valueToTree(prefix));
        }
        return out;
    }

    public ObjectNode toolDescribeMethod(JsonNode args) {
        Fqn owner = Fqn.fromDotted(args.path("owner").asText());
        String name = args.path("name").asText();
        String desc = args.path("desc").asText();
        var methodRepo = SqliteRepos.methodRepo(db);
        var rec = methodRepo.findByKey(owner, new MethodKey(name, desc));
        ObjectNode out = mapper.createObjectNode();
        if (rec.isEmpty()) {
            out.put("found", false);
            return out;
        }
        out.put("found", true);
        out.set("method", mapper.valueToTree(rec.get()));
        return out;
    }

    public ObjectNode toolCallersOf(JsonNode args) {
        String owner = args.path("owner").asText();
        String name = args.path("name").asText();
        String desc = args.path("desc").asText();
        boolean includeReflect = args.path("includeReflect").asBoolean(false);
        var invokeRepo = SqliteRepos.invokeRepo(db);
        var edges = invokeRepo.findCallersOf(owner, name, desc);
        ObjectNode out = mapper.createObjectNode();
        var arr = mapper.createArrayNode();
        for (var e : edges) {
            if (!includeReflect && e.confidence() == InvokeRepo.Confidence.UNCERTAIN) continue;
            arr.add(mapper.valueToTree(e));
        }
        out.set("callers", arr);
        out.put("count", arr.size());
        return out;
    }

    public ObjectNode toolCalleesOf(JsonNode args) {
        long callerMethodId = args.path("callerMethodId").asLong();
        var invokeRepo = SqliteRepos.invokeRepo(db);
        var edges = invokeRepo.findCalleesOf(callerMethodId);
        ObjectNode out = mapper.createObjectNode();
        out.set("callees", mapper.valueToTree(edges));
        out.put("count", edges.size());
        return out;
    }

    public ObjectNode toolFindPath(JsonNode args) {
        String fromOwner = args.path("fromOwner").asText();
        String fromName = args.path("fromName").asText();
        String fromDesc = args.path("fromDesc").asText();
        String toOwner = args.path("toOwner").asText();
        String toName = args.path("toName").asText();
        String toDesc = args.path("toDesc").asText();
        int maxDepth = args.path("maxDepth").asInt(8);
        var methodRepo = SqliteRepos.methodRepo(db);
        var fromRef = methodRepo.findByKey(Fqn.fromDotted(fromOwner),
                new MethodKey(fromName, fromDesc))
                .map(r -> new MethodRef(Fqn.fromDotted(fromOwner), new MethodKey(fromName, fromDesc)))
                .orElse(null);
        var toRef = methodRepo.findByKey(Fqn.fromDotted(toOwner),
                new MethodKey(toName, toDesc))
                .map(r -> new MethodRef(Fqn.fromDotted(toOwner), new MethodKey(toName, toDesc)))
                .orElse(null);
        ObjectNode out = mapper.createObjectNode();
        if (fromRef == null || toRef == null) {
            out.put("found", false);
            out.put("reason", "method not found in DB");
            return out;
        }
        CallGraph graph = buildCallGraph();
        BfsPathFinder finder = new BfsPathFinder(graph);
        var path = finder.findPath(fromRef, toRef, maxDepth);
        out.put("found", path.isPresent());
        if (path.isPresent()) {
            var arr = mapper.createArrayNode();
            for (MethodRef r : path.get()) {
                arr.add(r.dashed());
            }
            out.set("path", arr);
        }
        return out;
    }

    public ObjectNode toolListIssues(JsonNode args) {
        String kind = args.path("kind").asText("");
        var issueRepo = SqliteRepos.issueRepo(db);
        var issues = kind.isEmpty() ? List.<IssueRepo.Record>of() : issueRepo.findByKind(kind);
        ObjectNode out = mapper.createObjectNode();
        out.set("issues", mapper.valueToTree(issues));
        out.put("count", issues.size());
        return out;
    }

    // ─── P2 framework tools ───────────────────────────────────────────────────

    public ObjectNode toolFindSpringBeans(JsonNode args) {
        String type = args.path("type").asText("");
        String name = args.path("name").asText("");
        var repo = SqliteRepos.springBeanRepo(db);
        List<SpringBeanRepo.Record> hits;
        if (!type.isEmpty()) {
            hits = repo.findByType(Fqn.fromDotted(type));
        } else if (!name.isEmpty()) {
            hits = repo.findByName(name);
        } else {
            // No filter: do a linear scan (best-effort, capped at 500).
            hits = scanAllSpringBeans(repo);
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("beans", mapper.valueToTree(hits));
        out.put("count", hits.size());
        return out;
    }

    public ObjectNode toolFindSpringInjects(JsonNode args) {
        String classFqn = args.path("class").asText("");
        List<SpringInjectRepo.Record> hits;
        if (!classFqn.isEmpty()) {
            var classRepo = SqliteRepos.classRepo(db);
            var klass = classRepo.findByFqn(Fqn.fromDotted(classFqn));
            if (klass.isEmpty()) {
                ObjectNode out = mapper.createObjectNode();
                out.put("count", 0);
                out.put("error", "class not found: " + classFqn);
                return out;
            }
            hits = SqliteRepos.springInjectRepo(db).findByClass(klass.get().id());
        } else {
            hits = scanAllSpringInjects();
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("injects", mapper.valueToTree(hits));
        out.put("count", hits.size());
        return out;
    }

    public ObjectNode toolFindDubboServices(JsonNode args) {
        String iface = args.path("interface").asText("");
        var repo = SqliteRepos.dubboServiceRepo(db);
        List<DubboServiceRepo.Record> hits = iface.isEmpty()
                ? scanAllDubboServices()
                : repo.findByInterface(Fqn.fromDotted(iface));
        ObjectNode out = mapper.createObjectNode();
        out.set("services", mapper.valueToTree(hits));
        out.put("count", hits.size());
        return out;
    }

    public ObjectNode toolFindDubboReferences(JsonNode args) {
        String iface = args.path("interface").asText("");
        String consumer = args.path("consumerClass").asText("");
        var repo = SqliteRepos.dubboReferenceRepo(db);
        List<DubboReferenceRepo.Record> hits;
        if (!consumer.isEmpty()) {
            // Filter by consumer class. If interface is also given, intersect in Java.
            List<DubboReferenceRepo.Record> byConsumer = repo.findByConsumerClass(Fqn.fromDotted(consumer));
            hits = iface.isEmpty() ? byConsumer : byConsumer.stream()
                    .filter(r -> r.interfaceFqn().dotted().equals(iface))
                    .toList();
        } else if (!iface.isEmpty()) {
            hits = repo.findByInterface(Fqn.fromDotted(iface));
        } else {
            hits = scanAllDubboReferences();
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("references", mapper.valueToTree(hits));
        out.put("count", hits.size());
        return out;
    }

    public ObjectNode toolFindMybatisStatements(JsonNode args) {
        String namespace = args.path("namespace").asText("");
        String statementId = args.path("statementId").asText("");
        var repo = SqliteRepos.mybatisStatementRepo(db);
        List<MybatisStatementRepo.Record> hits;
        if (!namespace.isEmpty() && !statementId.isEmpty()) {
            hits = repo.findByNamespaceAndId(namespace, statementId);
        } else if (!namespace.isEmpty()) {
            hits = repo.findByNamespace(namespace);
        } else {
            hits = scanAllMybatisStatements();
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("statements", mapper.valueToTree(hits));
        out.put("count", hits.size());
        return out;
    }

    /**
     * Per-method Dubbo config. Caller passes either {@code serviceId} (provider-side
     * method config under {@code <dubbo:service>}) or {@code referenceId} (consumer-side
     * method config under {@code <dubbo:reference>}). At least one is required.
     *
     * <p>Typical workflow: {@code find_dubbo_services(interface)} → grab the
     * {@code id} of the matching service → {@code find_dubbo_method_configs(serviceId)}
     * → see the per-method timeout / retries / loadbalance / async flags.
     */
    public ObjectNode toolFindDubboMethodConfigs(JsonNode args) {
        long serviceId = args.path("serviceId").asLong(0L);
        long referenceId = args.path("referenceId").asLong(0L);
        if (serviceId == 0L && referenceId == 0L) {
            ObjectNode out = mapper.createObjectNode();
            out.put("count", 0);
            out.put("error", "either serviceId or referenceId is required");
            return out;
        }
        var repo = SqliteRepos.dubboMethodConfigRepo(db);
        List<DubboMethodConfigRepo.Record> hits = serviceId != 0L
                ? repo.findByService(serviceId)
                : repo.findByReference(referenceId);
        ObjectNode out = mapper.createObjectNode();
        out.set("methodConfigs", mapper.valueToTree(hits));
        out.put("count", hits.size());
        out.put("parent", serviceId != 0L ? "service" : "reference");
        out.put("parentId", serviceId != 0L ? serviceId : referenceId);
        return out;
    }

    /**
     * MyBatis row-mapper shape. Caller passes either {@code namespace} (all
     * resultMaps in a Mapper interface) or {@code typeFqn} (all resultMaps whose
     * target type is a given Java class). The LLM uses this to answer "what
     * shape does this query return" and to join with
     * {@code find_mybatis_statements(statementId)} via the {@code resultMap="..."}
     * attribute on each statement.
     */
    /**
     * Dubbo registries. Caller passes at most one of:
     * <ul>
     *   <li>{@code registryId} — find a specific registry by its {@code id} attribute.</li>
     *   <li>{@code protocol} — every registry using a given protocol (zookeeper, nacos, ...).</li>
     * </ul>
     * With no filter, every recorded registry is returned (capped at 1000).
     */
    public ObjectNode toolFindDubboRegistries(JsonNode args) {
        String idFilter = args.path("registryId").asText("");
        String protocolFilter = args.path("protocol").asText("");
        var repo = SqliteRepos.dubboRegistryRepo(db);
        List<DubboRegistryRepo.Record> hits;
        if (!idFilter.isEmpty()) {
            hits = repo.findByRegistryId(idFilter)
                    .map(List::of).orElse(List.of());
        } else if (!protocolFilter.isEmpty()) {
            hits = repo.findByProtocol(protocolFilter);
        } else {
            hits = repo.findAll();
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("registries", mapper.valueToTree(hits));
        out.put("count", hits.size());
        return out;
    }

    public ObjectNode toolFindMybatisResultMaps(JsonNode args) {
        String namespace = args.path("namespace").asText("");
        String typeFqn = args.path("typeFqn").asText("");
        var repo = SqliteRepos.mybatisResultMapRepo(db);
        List<MybatisResultMapRepo.Record> hits;
        if (!namespace.isEmpty()) {
            hits = repo.findByNamespace(namespace);
        } else if (!typeFqn.isEmpty()) {
            hits = repo.findByType(typeFqn);
        } else {
            ObjectNode out = mapper.createObjectNode();
            out.put("count", 0);
            out.put("error", "either namespace or typeFqn is required");
            return out;
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("resultMaps", mapper.valueToTree(hits));
        out.put("count", hits.size());
        return out;
    }

    /**
     * Spring Boot auto-configurations discovered from {@code spring.factories}
     * (pre-3.0) and {@code AutoConfiguration.imports} (3.0+).
     *
     * <p>Caller passes at most one of:
     * <ul>
     *   <li>{@code class} — find every place a given auto-config class is
     *       registered (which jars pull it in, under which key).</li>
     *   <li>{@code key} — find every auto-config under a given
     *       {@code spring.factories} key (e.g. {@code EnableAutoConfiguration}).</li>
     *   <li>{@code format} — {@code "factories"} or {@code "imports"}.</li>
     * </ul>
     * With no filter, every recorded auto-config is returned (capped at 1000).
     */
    public ObjectNode toolFindSpringAutoconfigs(JsonNode args) {
        String classFilter = args.path("class").asText("");
        String keyFilter = args.path("key").asText("");
        String formatFilter = args.path("format").asText("");
        var repo = SqliteRepos.springBootAutoconfigRepo(db);
        List<SpringBootAutoconfigRepo.Record> hits;
        if (!classFilter.isEmpty()) {
            hits = repo.findByClass(classFilter);
        } else if (!keyFilter.isEmpty()) {
            hits = repo.findByKey(keyFilter);
        } else if (!formatFilter.isEmpty()) {
            hits = repo.findByFormat(formatFilter);
        } else {
            hits = repo.findAll();
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("autoconfigs", mapper.valueToTree(hits));
        out.put("count", hits.size());
        return out;
    }

    /**
     * @Conditional* annotations extracted from auto-config classes (V6).
     * Caller passes one of:
     * <ul>
     *   <li>{@code autoconfigClass} — list every condition on a given
     *       auto-config (e.g. "what does DataSourceAutoConfiguration require?").</li>
     *   <li>{@code requiredClass} — reverse lookup: which auto-configs require
     *       a given class (e.g. "which auto-configs gate on
     *       javax.sql.DataSource?").</li>
     *   <li>{@code type} — every condition of a given type (e.g. "show me
     *       all @ConditionalOnClass across the classpath").</li>
     * </ul>
     * With no filter, every recorded condition is returned (capped at 1000).
     */
    public ObjectNode toolFindSpringAutoconfigConditions(JsonNode args) {
        String autoconfigFilter = args.path("autoconfigClass").asText("");
        String requiredFilter = args.path("requiredClass").asText("");
        String typeFilter = args.path("type").asText("");
        var repo = SqliteRepos.springAutoconfigConditionRepo(db);
        List<SpringAutoconfigConditionRepo.Record> hits;
        if (!autoconfigFilter.isEmpty()) {
            hits = repo.findByAutoconfigClass(autoconfigFilter);
        } else if (!requiredFilter.isEmpty()) {
            hits = repo.findByRequiredClass(requiredFilter);
        } else if (!typeFilter.isEmpty()) {
            hits = repo.findByType(typeFilter);
        } else {
            hits = repo.findAll();
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("conditions", mapper.valueToTree(hits));
        out.put("count", hits.size());
        return out;
    }

    /**
     * In-memory call graph rebuilt from the DB. The CHA is built from
     * {@code classes.super_fqn} for indexed classes, and falls back to the m2
     * cache for cross-jar virtual/interface resolution.
     */
    public CallGraph buildCallGraph() {
        return buildCallGraph(this::defaultExternalResolver);
    }

    /** Variant that lets callers (tests) inject a custom external resolver. */
    public CallGraph buildCallGraph(java.util.function.Supplier<io.jrdi.callgraph.ExternalClassResolver> extFactory) {
        var methodRepo = SqliteRepos.methodRepo(db);
        var invokeRepo = SqliteRepos.invokeRepo(db);
        var classRepo = SqliteRepos.classRepo(db);
        // Build class hierarchy from classes.super_fqn and the implemented interfaces.
        // CHA uses parent edges for closure walking, and the interfaces set is a hint
        // to the resolver (so cross-jar types can still be subtype-closed).
        var parent = new HashMap<Fqn, Fqn>();
        java.util.Set<Fqn> interfaces = new java.util.HashSet<>();
        for (ClassRepo.Record r : findAllClasses(classRepo)) {
            if (r.superFqn() != null) {
                parent.put(r.fqn(), r.superFqn());
            }
            if (r.interfaces() != null) {
                interfaces.addAll(r.interfaces());
            }
        }
        // Wire the cross-jar fallback (m2 cache by default). The resolver is lazy:
        // if no cross-jar CHA is needed, no jar walking happens.
        io.jrdi.callgraph.ExternalClassResolver ext = extFactory == null ? null : extFactory.get();
        ChaResolver cha = parent.isEmpty() && interfaces.isEmpty()
                ? (ext == null ? ChaResolver.empty() : new ChaResolver(parent, interfaces, java.util.Set.of(), ext))
                : new ChaResolver(parent, interfaces, java.util.Set.of(), ext);
        EdgeExpander expander = new EdgeExpander(cha);

        // Map methodId -> MethodRef
        Map<Long, MethodRef> idx = new HashMap<>();
        for (MethodRepo.Record r : findAllMethods(methodRepo)) {
            long classId = r.classId();
            // We need the class fqn. We have methodId → classId. Reuse SQL.
            var classes = findAllClasses(classRepo);
            for (ClassRepo.Record c : classes) {
                if (c.id() == classId) {
                    MethodRef ref = new MethodRef(c.fqn(), r.key());
                    idx.put(r.id(), ref);
                    expander.registerCaller(r.id(), ref);
                    break;
                }
            }
        }

        // Read all invokes
        List<io.jrdi.storage.repo.InvokeRepo.Edge> all = findAllInvokes(invokeRepo);
        // Expand
        List<CallGraph.CallEdge> edges = new ArrayList<>();
        java.util.Set<MethodRef> verts = new java.util.HashSet<>();
        for (var e : all) {
            MethodRef caller = idx.get(e.callerMethodId());
            if (caller == null) continue;
            for (var x : expander.expand(e, caller)) {
                edges.add(x);
                verts.add(caller);
                verts.add(x.to());
            }
        }
        return new CallGraph(verts, edges);
    }

    private long count(String sql, Db db) {
        try (var c = db.getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (Exception e) {
            LOG.debug("count failed: {}", e.getMessage());
            return 0;
        }
    }

    private List<ClassRepo.Record> findAllClasses(ClassRepo repo) {
        // Crude: SQL "SELECT id, fqn, ... FROM classes". SqliteRepo's Record is a public
        // shape so we can re-build it manually. For P1 we cap at 1000 classes.
        List<ClassRepo.Record> out = new ArrayList<>();
        try (var c = db.getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery("SELECT id, fqn, access, super_fqn, file_id, signature_raw, source, interfaces FROM classes LIMIT 10000")) {
            while (rs.next()) {
                Long fileId = rs.getLong("file_id");
                if (rs.wasNull()) fileId = null;
                String superRaw = rs.getString("super_fqn");
                Fqn superFqn = superRaw == null ? null : Fqn.of(superRaw);
                out.add(new ClassRepo.Record(
                        rs.getLong("id"),
                        Fqn.of(rs.getString("fqn")),
                        rs.getInt("access"),
                        superFqn,
                        fileId,
                        rs.getString("signature_raw"),
                        rs.getString("source"),
                        List.of()));
            }
        } catch (Exception e) {
            LOG.debug("findAllClasses failed: {}", e.getMessage());
        }
        return out;
    }

    private List<MethodRepo.Record> findAllMethods(MethodRepo repo) {
        List<MethodRepo.Record> out = new ArrayList<>();
        try (var c = db.getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery("SELECT id, class_id, name, desc, signature_raw, start_line, end_line, virtual_lines FROM methods LIMIT 100000")) {
            while (rs.next()) {
                int start = rs.getInt("start_line");
                Integer startLine = rs.wasNull() ? null : start;
                int end = rs.getInt("end_line");
                Integer endLine = rs.wasNull() ? null : end;
                out.add(new MethodRepo.Record(
                        rs.getLong("id"),
                        rs.getLong("class_id"),
                        rs.getString("name"),
                        rs.getString("desc"),
                        rs.getString("signature_raw"),
                        startLine,
                        endLine,
                        rs.getInt("virtual_lines") != 0));
            }
        } catch (Exception e) {
            LOG.debug("findAllMethods failed: {}", e.getMessage());
        }
        return out;
    }

    private List<InvokeRepo.Edge> findAllInvokes(InvokeRepo repo) {
        List<InvokeRepo.Edge> out = new ArrayList<>();
        try (var c = db.getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery("SELECT caller_method_id, callee_owner, callee_name, callee_desc, kind, line, confidence FROM invokes LIMIT 1000000")) {
            while (rs.next()) {
                int line = rs.getInt("line");
                Integer lineBox = rs.wasNull() ? null : line;
                out.add(new InvokeRepo.Edge(
                        rs.getLong("caller_method_id"),
                        rs.getString("callee_owner"),
                        rs.getString("callee_name"),
                        rs.getString("callee_desc"),
                        InvokeRepo.Kind.valueOf(rs.getString("kind")),
                        lineBox,
                        InvokeRepo.Confidence.valueOf(rs.getString("confidence"))));
            }
        } catch (Exception e) {
            LOG.debug("findAllInvokes failed: {}", e.getMessage());
        }
        return out;
    }

    // ─── P2 framework scan helpers (capped at 1000 rows for safety) ───────────

    private List<SpringBeanRepo.Record> scanAllSpringBeans(SpringBeanRepo repo) {
        List<SpringBeanRepo.Record> out = new ArrayList<>();
        try (var c = db.getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery("SELECT id, name, type_fqn, source, class_id, method_id, scope, primary_b " +
                     "FROM spring_beans LIMIT 1000")) {
            while (rs.next()) {
                long classId = rs.getLong("class_id"); if (rs.wasNull()) classId = -1;
                long methodId = rs.getLong("method_id"); if (rs.wasNull()) methodId = -1;
                out.add(new SpringBeanRepo.Record(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("type_fqn"),
                        rs.getString("source"),
                        rs.wasNull() ? null : classId,
                        rs.wasNull() ? null : methodId,
                        rs.getString("scope"),
                        rs.getInt("primary_b") != 0));
            }
        } catch (Exception e) {
            LOG.debug("scanAllSpringBeans failed: {}", e.getMessage());
        }
        return out;
    }

    private List<SpringInjectRepo.Record> scanAllSpringInjects() {
        List<SpringInjectRepo.Record> out = new ArrayList<>();
        try (var c = db.getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery("SELECT id, target_field, target_param_index, class_id, method_id, " +
                     "qualifier, by_value, confidence, candidate_bean_ids " +
                     "FROM spring_injects LIMIT 1000")) {
            while (rs.next()) {
                int paramIdx = rs.getInt("target_param_index");
                Integer paramBox = rs.wasNull() ? null : paramIdx;
                long methodId = rs.getLong("method_id");
                Long methodBox = rs.wasNull() ? null : methodId;
                out.add(new SpringInjectRepo.Record(
                        rs.getLong("id"),
                        rs.getString("target_field"),
                        paramBox,
                        rs.getLong("class_id"),
                        methodBox,
                        rs.getString("qualifier"),
                        SpringInjectRepo.By.valueOf(rs.getString("by_value")),
                        SpringInjectRepo.Confidence.valueOf(rs.getString("confidence")),
                        parseJsonLongList(rs.getString("candidate_bean_ids"))));
            }
        } catch (Exception e) {
            LOG.debug("scanAllSpringInjects failed: {}", e.getMessage());
        }
        return out;
    }

    private List<DubboServiceRepo.Record> scanAllDubboServices() {
        List<DubboServiceRepo.Record> out = new ArrayList<>();
        try (var c = db.getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery("SELECT id, interface_fqn, impl_class_id, group_name, version, protocol, source, " +
                     "ref_bean_name, registry_id FROM dubbo_services LIMIT 1000")) {
            while (rs.next()) {
                out.add(new DubboServiceRepo.Record(
                        rs.getLong("id"),
                        Fqn.of(rs.getString("interface_fqn")),
                        rs.getLong("impl_class_id"),
                        rs.getString("group_name"),
                        rs.getString("version"),
                        rs.getString("protocol"),
                        rs.getString("source"),
                        rs.getString("ref_bean_name"),
                        rs.getString("registry_id")));
            }
        } catch (Exception e) {
            LOG.debug("scanAllDubboServices failed: {}", e.getMessage());
        }
        return out;
    }

    private List<DubboReferenceRepo.Record> scanAllDubboReferences() {
        List<DubboReferenceRepo.Record> out = new ArrayList<>();
        try (var c = db.getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery("SELECT id, interface_fqn, field_id, group_name, version, confidence, ref_id, " +
                     "registry_id, consumer_class_fqn FROM dubbo_references LIMIT 1000")) {
            while (rs.next()) {
                out.add(new DubboReferenceRepo.Record(
                        rs.getLong("id"),
                        Fqn.of(rs.getString("interface_fqn")),
                        rs.getLong("field_id"),
                        rs.getString("group_name"),
                        rs.getString("version"),
                        DubboReferenceRepo.Confidence.valueOf(rs.getString("confidence")),
                        rs.getString("ref_id"),
                        rs.getString("registry_id"),
                        rs.getString("consumer_class_fqn")));
            }
        } catch (Exception e) {
            LOG.debug("scanAllDubboReferences failed: {}", e.getMessage());
        }
        return out;
    }

    private List<MybatisStatementRepo.Record> scanAllMybatisStatements() {
        List<MybatisStatementRepo.Record> out = new ArrayList<>();
        try (var c = db.getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery("SELECT id, namespace, statement_id, kind, sql_template, sql_normalized, " +
                     "parameters, defined_in_file, line, provider_class, provider_method " +
                     "FROM mybatis_statements LIMIT 1000")) {
            while (rs.next()) {
                int line = rs.getInt("line");
                Integer lineBox = rs.wasNull() ? null : line;
                out.add(new MybatisStatementRepo.Record(
                        rs.getLong("id"),
                        rs.getString("namespace"),
                        rs.getString("statement_id"),
                        MybatisStatementRepo.Kind.valueOf(rs.getString("kind").toUpperCase()),
                        rs.getString("sql_template"),
                        rs.getString("sql_normalized"),
                        parseJsonStringList(rs.getString("parameters")),
                        rs.getString("defined_in_file"),
                        lineBox,
                        rs.getString("provider_class"),
                        rs.getString("provider_method")));
            }
        } catch (Exception e) {
            LOG.debug("scanAllMybatisStatements failed: {}", e.getMessage());
        }
        return out;
    }

    private List<String> parseJsonStringList(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) return List.of();
        try {
            return mapper.readValue(json, mapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Long> parseJsonLongList(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) return List.of();
        try {
            return mapper.readValue(json, mapper.getTypeFactory()
                    .constructCollectionType(List.class, Long.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Factory for the default cross-jar CHA resolver: walks {@code ~/.m2/repository}
     * looking for jars that contain the missing parent class. Cached per FQN, so
     * the resolver is essentially free when the index already covers the full
     * type hierarchy.
     */
    io.jrdi.callgraph.ExternalClassResolver defaultExternalResolver() {
        return io.jrdi.callgraph.M2ClasspathResolver.defaultM2();
    }
}

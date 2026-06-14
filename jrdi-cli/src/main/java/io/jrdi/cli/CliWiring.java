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

import io.jrdi.core.coord.Gav;
import io.jrdi.core.symbol.Fqn;
import io.jrdi.core.symbol.MethodKey;
import io.jrdi.pipeline.ArtifactInput;
import io.jrdi.pipeline.IndexPipeline;
import io.jrdi.pipeline.IndexReport;
import io.jrdi.resolver.MavenSettingsParser;
import io.jrdi.storage.Db;
import io.jrdi.storage.Migrator;
import io.jrdi.storage.repo.ClassRepo;
import io.jrdi.storage.repo.DubboMethodConfigRepo;
import io.jrdi.storage.repo.DubboReferenceRepo;
import io.jrdi.storage.repo.DubboServiceRepo;
import io.jrdi.storage.repo.InvokeRepo;
import io.jrdi.storage.repo.MybatisResultMapRepo;
import io.jrdi.storage.repo.MybatisStatementRepo;
import io.jrdi.storage.repo.SpringBeanRepo;
import io.jrdi.storage.repo.SpringInjectRepo;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;

/**
 * CLI subcommand wiring: open a SQLite DB, run pipeline, print IndexReport.
 */
public final class CliWiring {

    private CliWiring() {
    }

    /** Open a Db at the given URL (with prefix normalization for user convenience). */
    public static Db openDb(String rawUrl) {
        String url = JrdiCommand.normalizeDbUrl(rawUrl);
        Db db = Db.open(url);
        Migrator.migrate(db.dataSource());
        return db;
    }

    public static int runIndex(String target, String dbUrl, String repoId, boolean withSources) {
        try (Db db = openDb(dbUrl)) {
            IndexPipeline pipeline = new IndexPipeline(db, MavenSettingsParser.load());
            IndexReport report;
            if (target == null) {
                System.err.println("ERROR: index requires a jar path or GAV");
                return 2;
            }
            Path asPath = Path.of(target);
            if (Files.isRegularFile(asPath)) {
                // Local jar path
                Gav gav = gavFromPath(asPath);
                ArtifactInput input = new ArtifactInput(gav, asPath, withSources
                        ? Optional.of(localSourcesSibling(asPath)) : Optional.empty());
                report = pipeline.indexRepo(repoId, asPath.getParent().toString(), List.of(input));
            } else if (looksLikeGav(target)) {
                Gav gav = Gav.parse(target);
                report = pipeline.indexOneGav(repoId, ".", gav);
            } else {
                System.err.println("ERROR: target must be a jar path or GAV (org:art:ver)");
                return 2;
            }
            System.out.println("=== IndexReport ===");
            System.out.println("repoId:                " + report.repoId());
            System.out.println("elapsed:               " + report.elapsed().toMillis() + " ms");
            System.out.println("artifactsVisited:     " + report.artifactsVisited());
            System.out.println("classesIndexed:        " + report.classesIndexed());
            if (report.classesSkipped() > 0) {
                System.out.println("classesSkipped:       " + report.classesSkipped() +
                        "  (incremental: unchanged from previous index)");
            }
            System.out.println("methodsIndexed:        " + report.methodsIndexed());
            System.out.println("fieldsIndexed:         " + report.fieldsIndexed());
            System.out.println("invokesIndexed:        " + report.invokesIndexed());
            System.out.println("lambdasIndexed:        " + report.lambdasIndexed());
            System.out.println("issuesRecorded:        " + report.issuesRecorded());
            System.out.println("perArtifactClassCount: " + report.perArtifactClassCount());
            if (report.springBeansRecorded() > 0 || report.springInjectsRecorded() > 0) {
                System.out.println("spring beans:          " + report.springBeansRecorded());
                System.out.println("spring injects:        " + report.springInjectsRecorded());
            }
            if (report.dubboServicesRecorded() > 0 || report.dubboReferencesRecorded() > 0) {
                System.out.println("dubbo services:        " + report.dubboServicesRecorded());
                System.out.println("dubbo references:      " + report.dubboReferencesRecorded());
            }
            if (report.mybatisStatementsRecorded() > 0) {
                System.out.println("mybatis statements:    " + report.mybatisStatementsRecorded());
            }
            return 0;
        }
    }

    public static int runQuery(String target, String dbUrl, String from, String to, int depth, boolean includeReflect) {
        try (Db db = openDb(dbUrl)) {
            // Heuristic: if target contains '#', it's a method ref
            int hash = target.indexOf('#');
            String fqn;
            String rest;
            if (hash < 0) {
                fqn = target;
                rest = null;
            } else {
                fqn = target.substring(0, hash);
                rest = target.substring(hash + 1);
            }
            if (rest == null) {
                // Class lookup
                ClassRepo repo = SqliteRepos.classRepo(db);
                var hit = repo.findByFqn(Fqn.fromDotted(fqn));
                if (hit.isEmpty()) {
                    System.out.println("class not found: " + fqn);
                    return 1;
                }
                System.out.println("class:    " + hit.get().fqn().slashed());
                System.out.println("access:   " + hit.get().access());
                Fqn sup = hit.get().superFqn();
                System.out.println("super:    " + (sup == null ? "" : sup.dotted()));
                return 0;
            }
            // method ref: owner#name(desc)
            int paren = rest.indexOf('(');
            if (paren < 0) {
                System.out.println("method ref must be owner#name(desc)");
                return 2;
            }
            String owner = fqn;
            String name = rest.substring(0, paren);
            String desc = rest.substring(paren);  // (desc)V with slashed descriptors
            MethodKey key = new MethodKey(name, desc);
            var methodRepo = SqliteRepos.methodRepo(db);
            var mr = methodRepo.findByKey(Fqn.fromDotted(owner), key);
            if (mr.isEmpty()) {
                System.out.println("method not found: " + fqn);
                return 1;
            }
            if (from == null) {
                // Default: callers_of
                InvokeRepo invokeRepo = SqliteRepos.invokeRepo(db);
                List<InvokeRepo.Edge> callers = invokeRepo.findCallersOf(
                        owner.replace('.', '/'), name, desc);
                List<InvokeRepo.Edge> filtered = new java.util.ArrayList<>();
                for (var c : callers) {
                    if (!includeReflect && c.confidence() == InvokeRepo.Confidence.UNCERTAIN) continue;
                    filtered.add(c);
                }
                System.out.println("=== callers of " + fqn + " ===");
                System.out.println("count: " + filtered.size());
                for (var c : filtered) {
                    System.out.println("  " + c.calleeOwner() + "#" + c.calleeName() + c.calleeDesc() +
                            " (callerMethodId=" + c.callerMethodId() +
                            ", kind=" + c.kind() +
                            ", conf=" + c.confidence() +
                            (c.line() == null ? "" : ", line=" + c.line()) + ")");
                }
                return 0;
            }
            // find_path
            return runFindPath(db, from, to, depth);
        }
    }

    private static int runFindPath(Db db, String fromRef, String toRef, int maxDepth) {
        // Parse owner#name(desc)
        var fromParts = parseRef(fromRef);
        var toParts = parseRef(toRef);
        if (fromParts == null || toParts == null) {
            System.out.println("ref format must be owner#name(desc)");
            return 2;
        }
        var fromMethod = SqliteRepos.methodRepo(db).findByKey(
                Fqn.fromDotted(fromParts.owner()),
                new MethodKey(fromParts.name(), fromParts.desc()));
        var toMethod = SqliteRepos.methodRepo(db).findByKey(
                Fqn.fromDotted(toParts.owner()),
                new MethodKey(toParts.name(), toParts.desc()));
        if (fromMethod.isEmpty() || toMethod.isEmpty()) {
            System.out.println("method(s) not found in DB");
            return 1;
        }
        // Build call graph in memory and BFS.
        io.jrdi.mcp.JrdiMcpService service = new io.jrdi.mcp.JrdiMcpService(db);
        io.jrdi.callgraph.CallGraph graph = service.buildCallGraph();
        var fromRefObj = new io.jrdi.callgraph.MethodRef(
                Fqn.fromDotted(fromParts.owner()),
                new MethodKey(fromParts.name(), fromParts.desc()));
        var toRefObj = new io.jrdi.callgraph.MethodRef(
                Fqn.fromDotted(toParts.owner()),
                new MethodKey(toParts.name(), toParts.desc()));
        var path = new io.jrdi.callgraph.BfsPathFinder(graph).findPath(fromRefObj, toRefObj, maxDepth);
        if (path.isEmpty()) {
            System.out.println("no path within depth " + maxDepth);
            return 1;
        }
        System.out.println("=== path (" + (path.get().size() - 1) + " hops) ===");
        for (var p : path.get()) {
            System.out.println("  " + p.dashed());
        }
        return 0;
    }

    public static int runDoctor(String dbUrl) {
        try (Db db = openDb(dbUrl)) {
            System.out.println("=== jrdi doctor ===");
            System.out.println("dialect:        " + db.dialect().name());
            // Counts
            var classRepo = SqliteRepos.classRepo(db);
            var methodRepo = SqliteRepos.methodRepo(db);
            var invokeRepo = SqliteRepos.invokeRepo(db);
            var issueRepo = SqliteRepos.issueRepo(db);
            System.out.println("classes:        " + count("SELECT COUNT(*) FROM classes", db));
            System.out.println("methods:        " + count("SELECT COUNT(*) FROM methods", db));
            System.out.println("invokes:        " + count("SELECT COUNT(*) FROM invokes", db));
            System.out.println("issues:         " + count("SELECT COUNT(*) FROM issues", db));
            System.out.println("uncertain reflections: " + issueRepo.findByKind("uncertain_reflect").size());
            System.out.println("missing source: " + issueRepo.findByKind("missing_source").size());
            return 0;
        }
    }

    public static int runStats(String dbUrl, boolean asJson) {
        try (Db db = openDb(dbUrl)) {
            Stats stats = collectStats(db);
            if (asJson) {
                System.out.println(stats.toJson());
            } else {
                System.out.println(stats.toHuman());
            }
            return 0;
        } catch (Exception e) {
            System.err.println("ERROR: stats failed: " + e.getMessage());
            return 2;
        }
    }

    /**
     * Collect per-table counts and a framework breakdown (annotation vs xml) for
     * the dual-source tables. Internal to {@link #runStats}.
     */
    private static Stats collectStats(Db db) {
        Stats s = new Stats();
        s.dialect = db.dialect().name();
        s.url = db.jdbcUrl();
        s.schemaVersion = detectSchemaVersion(db);
        // Core tables
        s.repos = count("SELECT COUNT(*) FROM repos", db);
        s.artifacts = count("SELECT COUNT(*) FROM artifacts", db);
        s.files = count("SELECT COUNT(*) FROM files", db);
        s.classes = count("SELECT COUNT(*) FROM classes", db);
        s.methods = count("SELECT COUNT(*) FROM methods", db);
        s.fields = count("SELECT COUNT(*) FROM fields", db);
        s.invokes = count("SELECT COUNT(*) FROM invokes", db);
        s.lambdas = count("SELECT COUNT(*) FROM lambdas", db);
        s.issues = count("SELECT COUNT(*) FROM issues", db);
        // P2 framework tables
        s.springBeans = count("SELECT COUNT(*) FROM spring_beans", db);
        s.springInjects = count("SELECT COUNT(*) FROM spring_injects", db);
        // Dubbo: split annotation / xml by `source` column
        s.dubboServicesTotal = count("SELECT COUNT(*) FROM dubbo_services", db);
        s.dubboServicesFromAnnotation = count(
                "SELECT COUNT(*) FROM dubbo_services WHERE source='annotation'", db);
        s.dubboServicesFromXml = count(
                "SELECT COUNT(*) FROM dubbo_services WHERE source='xml'", db);
        s.dubboReferencesTotal = count("SELECT COUNT(*) FROM dubbo_references", db);
        // references don't have a source column; use confidence + ref_id to split
        s.dubboReferencesFromAnnotation = count(
                "SELECT COUNT(*) FROM dubbo_references WHERE ref_id=''", db);
        s.dubboReferencesFromXml = count(
                "SELECT COUNT(*) FROM dubbo_references WHERE ref_id != ''", db);
        s.dubboMethodConfigs = count("SELECT COUNT(*) FROM dubbo_method_configs", db);
        s.dubboRegistries = count("SELECT COUNT(*) FROM dubbo_registries", db);
        // MyBatis: statements have a defined_in_file column; XML has the .xml extension
        s.mybatisStatementsTotal = count("SELECT COUNT(*) FROM mybatis_statements", db);
        s.mybatisStatementsFromAnnotation = count(
                "SELECT COUNT(*) FROM mybatis_statements WHERE defined_in_file LIKE '%.java'", db);
        s.mybatisStatementsFromXml = count(
                "SELECT COUNT(*) FROM mybatis_statements WHERE defined_in_file LIKE '%.xml'", db);
        s.mybatisResultMaps = count("SELECT COUNT(*) FROM mybatis_result_maps", db);
        // V5: Spring Boot auto-configurations
        s.springBootAutoconfigs = count("SELECT COUNT(*) FROM spring_boot_autoconfigs", db);
        s.springBootAutoconfigsFactories = count(
                "SELECT COUNT(*) FROM spring_boot_autoconfigs WHERE source_format='factories'", db);
        s.springBootAutoconfigsImports = count(
                "SELECT COUNT(*) FROM spring_boot_autoconfigs WHERE source_format='imports'", db);
        // V6: auto-config conditions
        s.springAutoconfigConditions = count("SELECT COUNT(*) FROM spring_autoconfig_conditions", db);
        s.springAutoconfigConditionsOnClass = count(
                "SELECT COUNT(*) FROM spring_autoconfig_conditions WHERE condition_type='on_class'", db);
        s.springAutoconfigConditionsOnProperty = count(
                "SELECT COUNT(*) FROM spring_autoconfig_conditions WHERE condition_type='on_property'", db);
        // Issue breakdown
        s.uncertainReflections = count(
                "SELECT COUNT(*) FROM issues WHERE kind='uncertain_reflect'", db);
        s.missingSource = count(
                "SELECT COUNT(*) FROM issues WHERE kind='missing_source'", db);
        // Index freshness — most recent artifact indexed_at
        s.lastIndexedAt = scalarString(
                "SELECT MAX(indexed_at) FROM repos", db);
        return s;
    }

    /** Detect the highest applied Flyway version. Cheap probe — one SELECT. */
    private static String detectSchemaVersion(Db db) {
        try {
            String v = scalarString(
                    "SELECT version FROM flyway_schema_history " +
                    "WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1", db);
            return v == null ? "?" : v;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String scalarString(String sql, Db db) {
        try (var c = db.dataSource().getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery(sql)) {
            if (rs.next()) {
                Object v = rs.getObject(1);
                return v == null ? null : v.toString();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static class Stats {
        String dialect;
        String url;
        String schemaVersion;
        long repos, artifacts, files, classes, methods, fields, invokes, lambdas, issues;
        long springBeans, springInjects;
        long dubboServicesTotal, dubboServicesFromAnnotation, dubboServicesFromXml;
        long dubboReferencesTotal, dubboReferencesFromAnnotation, dubboReferencesFromXml;
        long dubboMethodConfigs;
        long dubboRegistries;
        long mybatisStatementsTotal, mybatisStatementsFromAnnotation, mybatisStatementsFromXml;
        long mybatisResultMaps;
        long springBootAutoconfigs, springBootAutoconfigsFactories, springBootAutoconfigsImports;
        long springAutoconfigConditions, springAutoconfigConditionsOnClass, springAutoconfigConditionsOnProperty;
        long uncertainReflections, missingSource;
        String lastIndexedAt;

        String toHuman() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== jrdi stats ===\n");
            sb.append("dialect:         ").append(dialect).append('\n');
            sb.append("url:             ").append(url).append('\n');
            sb.append("schema version:  V").append(schemaVersion).append('\n');
            sb.append("last indexed:    ")
                    .append(lastIndexedAt == null ? "(never)" : lastIndexedAt)
                    .append('\n');
            sb.append('\n');
            sb.append("── Core / P1 ──\n");
            appendRow(sb, "repos",         repos);
            appendRow(sb, "artifacts",     artifacts);
            appendRow(sb, "files",         files);
            appendRow(sb, "classes",       classes);
            appendRow(sb, "methods",       methods);
            appendRow(sb, "fields",        fields);
            appendRow(sb, "invokes",       invokes);
            appendRow(sb, "lambdas",       lambdas);
            appendRow(sb, "issues",        issues);
            if (uncertainReflections > 0 || missingSource > 0) {
                sb.append("    ├─ uncertain_reflect: ").append(uncertainReflections).append('\n');
                sb.append("    └─ missing_source:    ").append(missingSource).append('\n');
            }
            sb.append('\n').append("── P2 framework ──\n");
            appendRow(sb, "spring_beans",       springBeans);
            appendRow(sb, "spring_injects",     springInjects);
            appendRow(sb, "dubbo_services",     dubboServicesTotal,
                    "annotation=" + dubboServicesFromAnnotation +
                    ", xml=" + dubboServicesFromXml);
            appendRow(sb, "dubbo_references",   dubboReferencesTotal,
                    "annotation=" + dubboReferencesFromAnnotation +
                    ", xml=" + dubboReferencesFromXml);
            appendRow(sb, "dubbo_method_configs", dubboMethodConfigs, null);
            appendRow(sb, "dubbo_registries", dubboRegistries, null);
            appendRow(sb, "mybatis_statements", mybatisStatementsTotal,
                    "annotation=" + mybatisStatementsFromAnnotation +
                    ", xml=" + mybatisStatementsFromXml);
            appendRow(sb, "mybatis_result_maps", mybatisResultMaps, null);
            appendRow(sb, "spring_boot_autoconfigs", springBootAutoconfigs,
                    "factories=" + springBootAutoconfigsFactories +
                    ", imports=" + springBootAutoconfigsImports);
            appendRow(sb, "spring_autoconfig_conditions", springAutoconfigConditions,
                    "on_class=" + springAutoconfigConditionsOnClass +
                    ", on_property=" + springAutoconfigConditionsOnProperty);
            return sb.toString();
        }

        private static void appendRow(StringBuilder sb, String label, long value) {
            appendRow(sb, label, value, null);
        }

        private static void appendRow(StringBuilder sb, String label, long value, String note) {
            sb.append(String.format("  %-26s %10d", label, value));
            if (note != null) sb.append("  (").append(note).append(')');
            sb.append('\n');
        }

        /** Compact single-line JSON for scripting / LLM context. */
        String toJson() {
            StringBuilder sb = new StringBuilder("{");
            appendJson(sb, "dialect", dialect, true);
            appendJson(sb, "schemaVersion", "V" + schemaVersion, false);
            appendJson(sb, "classes", classes, false);
            appendJson(sb, "methods", methods, false);
            appendJson(sb, "invokes", invokes, false);
            appendJson(sb, "springBeans", springBeans, false);
            appendJson(sb, "springInjects", springInjects, false);
            appendJson(sb, "dubboServices", dubboServicesTotal, false);
            appendJson(sb, "dubboServicesXml", dubboServicesFromXml, false);
            appendJson(sb, "dubboReferences", dubboReferencesTotal, false);
            appendJson(sb, "dubboMethodConfigs", dubboMethodConfigs, false);
            appendJson(sb, "dubboRegistries", dubboRegistries, false);
            appendJson(sb, "mybatisStatements", mybatisStatementsTotal, false);
            appendJson(sb, "mybatisStatementsXml", mybatisStatementsFromXml, false);
            appendJson(sb, "mybatisResultMaps", mybatisResultMaps, false);
            appendJson(sb, "springBootAutoconfigs", springBootAutoconfigs, false);
            appendJson(sb, "springBootAutoconfigsFactories", springBootAutoconfigsFactories, false);
            appendJson(sb, "springBootAutoconfigsImports", springBootAutoconfigsImports, false);
            appendJson(sb, "springAutoconfigConditions", springAutoconfigConditions, false);
            appendJson(sb, "springAutoconfigConditionsOnClass", springAutoconfigConditionsOnClass, false);
            appendJson(sb, "springAutoconfigConditionsOnProperty", springAutoconfigConditionsOnProperty, false);
            appendJson(sb, "issues", issues, false);
            appendJson(sb, "lastIndexedAt", lastIndexedAt, false);
            sb.append('}');
            return sb.toString();
        }

        private static void appendJson(StringBuilder sb, String key, Object val, boolean first) {
            if (!first) sb.append(',');
            sb.append('"').append(key).append("\":");
            if (val == null) {
                sb.append("null");
            } else if (val instanceof Number) {
                sb.append(val);
            } else {
                sb.append('"').append(val.toString().replace("\"", "\\\"")).append('"');
            }
        }
    }

    public static int runRebuild(String dbUrl) {
        // For SQLite: clean (drop all objects) then re-apply migrations.
        // This is what Flyway.clean() + Flyway.migrate() does.
        try (Db db = openDb(dbUrl)) {
            System.out.println("=== jrdi rebuild ===");
            System.out.println("dialect:    " + db.dialect().name());
            System.out.println("url:        " + db.jdbcUrl());
            Migrator.clean(db.dataSource());
            Migrator.migrate(db.dataSource());
            System.out.println("schema rebuilt at version 1");
            return 0;
        } catch (Exception e) {
            System.err.println("ERROR: rebuild failed: " + e.getMessage());
            return 2;
        }
    }

    public static int runServe(String dbUrl, Integer httpPort, String m2CacheDir) {
        io.jrdi.mcp.JrdiMcpService service;
        try {
            List<Path> m2Roots = parseM2Roots(m2CacheDir);
            if (m2Roots != null && !m2Roots.isEmpty()) {
                service = io.jrdi.mcp.JrdiMcpService.openSqliteWithM2(
                        JrdiCommand.normalizeDbUrl(dbUrl), m2Roots);
            } else {
                service = io.jrdi.mcp.JrdiMcpService.openSqlite(
                        JrdiCommand.normalizeDbUrl(dbUrl));
            }
        } catch (Exception e) {
            System.err.println("ERROR: failed to open db: " + e.getMessage());
            return 2;
        }
        io.jrdi.mcp.JrdiMcpServer server = new io.jrdi.mcp.JrdiMcpServer(service);
        try {
            if (httpPort != null) {
                server.serveHttp(httpPort);
                Thread.currentThread().join();  // block
            } else {
                server.serveStdio(System.in, System.out);
            }
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            return 1;
        }
        return 0;
    }

    private static long count(String sql, Db db) {
        try (var c = db.getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private record RefParts(String owner, String name, String desc) {}

    private static RefParts parseRef(String s) {
        int hash = s.indexOf('#');
        if (hash < 0) return null;
        String owner = s.substring(0, hash);
        String rest = s.substring(hash + 1);
        int paren = rest.indexOf('(');
        if (paren < 0) return null;
        return new RefParts(owner, rest.substring(0, paren), rest.substring(paren));
    }

    private static boolean looksLikeGav(String s) {
        int first = s.indexOf(':');
        if (first < 0) return false;
        int second = s.indexOf(':', first + 1);
        return second > first;
    }

    private static Gav gavFromPath(Path jar) {
        // Best-effort: derive from jar filename like "name-1.0.0.jar"
        String fname = jar.getFileName().toString();
        if (fname.endsWith(".jar")) fname = fname.substring(0, fname.length() - 4);
        // Find the last '-' that introduces a version (digits.digits.digits)
        int lastDash = -1;
        for (int i = fname.length() - 1; i >= 0; i--) {
            if (fname.charAt(i) == '-') {
                String after = fname.substring(i + 1);
                if (after.matches("\\d+(\\.\\d+)*([.-].*)?")) {
                    lastDash = i;
                    break;
                }
            }
        }
        if (lastDash < 0) {
            return Gav.of("local", fname, "0.0.0");
        }
        String name = fname.substring(0, lastDash);
        String version = fname.substring(lastDash + 1);
        // group: best guess — same as name
        return Gav.of(name, name, version);
    }

    private static Path localSourcesSibling(Path jar) {
        String fname = jar.getFileName().toString();
        if (fname.endsWith(".jar")) {
            return jar.getParent().resolve(fname.replace(".jar", "-sources.jar"));
        }
        return null;
    }

    // ─── m2 lazy resolution (0.2.0) ─────────────────────────────────

    /**
     * Parse a comma-separated list of m2 root paths. Returns null
     * if {@code raw} is null or empty.
     */
    static List<Path> parseM2Roots(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<Path> out = new ArrayList<>();
        for (String p : raw.split(",")) {
            String trimmed = p.trim();
            if (trimmed.isEmpty()) continue;
            Path path = Path.of(trimmed);
            if (!Files.isDirectory(path)) {
                System.err.println("WARNING: m2 root is not a directory: " + path);
                continue;
            }
            out.add(path);
        }
        return out;
    }

    /**
     * Pre-warm the m2 caches: walk every jar under the given roots
     * and extract its class facts. Useful after a clean install.
     */
    public static int runM2Warm(String dbUrl, List<Path> roots) {
        io.jrdi.storage.Db db;
        try {
            db = io.jrdi.storage.Db.open(JrdiCommand.normalizeDbUrl(dbUrl));
            io.jrdi.storage.Migrator.migrate(db.dataSource());
        } catch (Exception e) {
            System.err.println("ERROR: failed to open db: " + e.getMessage());
            return 2;
        }
        try (db) {
            io.jrdi.bytecode.M2LazyResolver resolver = new io.jrdi.bytecode.M2LazyResolver(
                    io.jrdi.storage.repo.sqlite.SqliteRepos.m2Repo(db), roots);
            int n = resolver.warmAll();
            System.out.println("m2-warm: extracted facts from " + n + " jar(s)");
            return 0;
        } catch (Exception e) {
            System.err.println("ERROR: m2-warm failed: " + e.getMessage());
            return 1;
        }
    }
}

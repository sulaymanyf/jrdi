# jrdi — Architecture

> **Status**: 0.1.0-M1, 201 tests passing, 17 modules.
> This document describes the *current* design. Earlier milestones (P1, P2, P3.x) are reflected in `RELEASE-NOTES-0.1.0-M1.md`.

## Table of Contents

1. [Goals & Non-Goals](#1-goals--non-goals)
2. [Layered Design](#2-layered-design)
3. [Module Map (17 modules)](#3-module-map-17-modules)
4. [Storage Schema (V1 + V2 + V3 + V4 + V5 + V6)](#4-storage-schema-v1--v2--v3--v4--v5--v6)
5. [Indexing Pipeline (2-pass)](#5-indexing-pipeline-2-pass)
6. [Cross-Jar CHA](#6-cross-jar-cha)
7. [Incremental Indexing (P3.7)](#7-incremental-indexing-p37)
8. [MCP Tool Surface (17 tools + 2 resources + 6 prompts)](#8-mcp-tool-surface-17-tools--2-resources--6-prompts)
9. [Failure Modes & Mitigation](#9-failure-modes--mitigation)
10. [Notable Design Decisions](#10-notable-design-decisions)
11. [Observability](#11-observability)
12. [Known Limitations (post-M1)](#12-known-limitations-post-m1)

---

## 1. Goals & Non-Goals

**Goals (M1)**

- Build a *fact graph* of Java code: classes, methods, fields, invokes, lambdas, generics,
  reflection, line numbers, plus framework metadata (Spring beans, Dubbo services, MyBatis
  statements).
- Expose that graph to LLM clients (Claude Code, Cursor, OpenCode, Cline, …) as MCP tools
  over JSON-RPC 2.0 (stdio or HTTP).
- Cross-jar class hierarchy analysis via lazy ASM scan of the local Maven cache.
- Incremental indexing (per-class SHA-256) so re-indexing unchanged jars is < 10ms.
- Two source-of-truth line numbers: `sources.jar` when available, CFR-decompiled otherwise
  (flagged `virtual=true`).

**Non-Goals (M1)**

- Distributed / multi-tenant. Single-process, single-DB.
- Real-time watch mode. Use repeated `index` calls (now fast due to incremental skip).
- Kotlin / Scala / Groovy. Java 17+ source syntax only.
- Deep cross-jar CHA (full type-hierarchy walk across transitive deps). Lazy m2 scan covers
  the direct parent / implemented interfaces; full transitive resolution is post-M1.
- **Runtime / dynamic analysis.** jrdi is fundamentally a *static* analyzer — it reads
  `.class` bytes and `.java` source, builds a fact graph, and exits. It never attaches to
  a running JVM. Things that jrdi explicitly does NOT do:
  - Java agent / `-javaagent:` instrumentation
  - JFR (Java Flight Recorder) event capture
  - heap dump (`.hprof`) analysis
  - call-site profiling or coverage feedback
  - allocation / GC / JMM analysis

  These are different product categories (async-profiler, JFR, Glowroot, async-profiler 2,
  Datadog APM, etc.). jrdi *simulates* some runtime behavior statically — e.g. `Class.forName`
  reflection edges, `@Autowired` candidate resolution, `invokedynamic` BSM linking — but
  always by reading compiled artifacts, never by running code.

  If you need observed runtime data, run your app with JFR enabled
  (`-XX:StartFlightRecording=...`) or async-profiler attached, and use jrdi for the static
  half of the picture. They are complementary, not overlapping.

---

## 2. Layered Design

```
                   ┌──────────────────────────────────────────────────────────┐
                   │       Entry points:  CLI  +  MCP server  (stdio / HTTP)  │
                   │       (jrdi-cli  +  jrdi-mcp-server)                     │
                   └──────────┬─────────────────────────────┬───────────────┘
                              │ tool calls                  │ DB queries
                              ▼                             ▼
                   ┌──────────────────────────────────────────────────────────┐
                   │  Tool layer (jrdi-mcp-server)                            │
                   │    17 tools  +  2 resources  +  6 prompt templates       │
                   │    CHA + BFS + Spring/Dubbo/MyBatis candidate resolution  │
                   └──────────┬─────────────────────────────┬───────────────┘
                              │ writes/reads               │ reads
                              ▼                             ▼
                   ┌──────────────────────────────────────────────────────────┐
                   │  Storage (jrdi-storage)                                  │
                   │    SQLite  +  PostgreSQL  (Flyway-managed)             │
                   │    Dialect-aware SQL via M2ClasspathResolver hints        │
                   └──────────┬─────────────────────────────┬───────────────┘
                              ▲                             ▲
                              │ writes                      │ reads
                              │                             │
                   ┌──────────────────────────────────────────────────────────┐
                   │  Indexing pipeline (jrdi-pipeline)                       │
                   │    Pass 1: bytecode + line numbers (per .class)          │
                   │    Pass 2a: framework beans (Spring record)               │
                   │    Pass 2b: framework injects (Spring resolve)            │
                   │    Pass 2c: Dubbo + MyBatis (per-class, no dependency)   │
                   └──────────┬─────────────────────────────┬───────────────┘
                              │                             ▲
                              ▼                             │
                   ┌──────────────────────────────────────────────────────────┐
                   │  Per-artifact analyzers                                   │
                   │   Resolver → ClassGraph → Bytecode → Source → Decompile   │
                   │   Spring → Dubbo → MyBatis                                 │
                   └──────────────────────────────────────────────────────────┘
```

The arrows in the middle (writes / reads) are the **only** contract between layers: storage is
the single source of truth, and analyzers speak to storage only via well-typed rows.

### Why layering

- **Testability.** Each layer has a stable test surface; the bytecode pass can be unit-tested
  without DB, the MCP service can be tested with in-memory streams.
- **Substitutability.** Storage is JDBC; a different RDBMS can plug in by writing a new dialect.
  Bytecode reads bytes; Soot or WALA could be swapped in without touching the call-graph layer.
- **Failure isolation.** When CFR crashes for one class, only that class is flagged `virtual=true`;
  the rest of the jar still indexes cleanly.
- **Two-pass framework split.** The framework beans are recorded *before* any injects are
  resolved, so a class's `@Autowired OrderApi` can match `OwnerProviderImpl` (whose class is
  alphabetically later in the jar).

---

## 3. Module Map (17 modules)

| Module | Role | Key types |
|---|---|---|
| `jrdi-core` | Pure data model, zero deps | `Fqn`, `MethodKey`, `CallEdge`, `Confidence`, `Gav`, `VersionSelector`, `Descriptors` |
| `jrdi-storage` | SQLite + PG schema, Flyway, JDBC repos, dialect detection | `Db`, `Migrator`, `SqliteRepos`, `M2ClasspathResolver` lives in callgraph |
| `jrdi-resolver` | `settings.xml` parser, jar/sources fetch, sha256 cache | `MavenSettingsParser`, `ArtifactFetcher`, `Cache`, `RemotePlan`, `ResolverSession` |
| `jrdi-classgraph` | Jar class listing, META-INF scanning | `JarClassLister`, `MetaInfScanner`, `ClasspathScanner` |
| `jrdi-bytecode` | ASM bytecode facts (classes/methods/fields/invokes/lambdas + implemented interfaces) | `BytecodePass`, `ClassVisitorEx`, `MethodVisitorEx`, `LambdaMetaFactoryHandler`, `ReflectionFrame`, `PassResult` |
| `jrdi-source` | JavaParser + sources.jar line attribution | `SourceLoader`, `AstBuilder`, `MethodMatcher` |
| `jrdi-decompile` | CFR fallback for missing sources | `CfrDecompiler`, `VirtualLineAssigner` |
| `jrdi-callgraph` | CHA + BFS, cross-jar resolution via m2 cache | `MethodRef`, `CallGraph`, `ChaResolver`, `BfsPathFinder`, `EdgeExpander`, `ExternalClassResolver`, `M2ClasspathResolver` |
| `jrdi-spring` | `@Component`/`@Autowired`/etc. + interface-aware candidate resolution | `SpringPass`, `SpringAnnotations` |
| `jrdi-dubbo` | `@DubboService` + `@DubboReference` (with `KNOWN_AMBIGUOUS_SHORT_NAMES` guard for Spring collision) | `DubboPass` |
| `jrdi-mybatis` | `@Select` + JSqlParser normalization | `MybatisPass` |
| `jrdi-pipeline` | 2-pass orchestrator (bytecode + framework) | `IndexPipeline`, `IndexReport`, `ArtifactInput` |
| `jrdi-search` | Lucene full-text search (reserved for 0.2.0) | `LuceneIndex` |
| `jrdi-cli` | picocli user surface + MCP server (one fat-jar) | `JrdiCommand` + `CliWiring` (6 subcommands: `index`, `query`, `doctor`, `rebuild`, `serve`, `stats`) |
| `jrdi-mcp-server` | Standalone MCP server (optional alternative to CLI's `serve`) | `JrdiMcpService`, `JrdiMcpServer`, `SchemaV1` |
| `jrdi-it` | End-to-end integration tests | `PetclinicShapedE2EIT`, `PostgresE2EIT` (Testcontainers, opt-in) |

### Dependency DAG

```
                                  ┌─────────────────────┐
                                  │   jrdi-bytecode      │
                                  │   (ASM, interfaces)  │
                                  └──────────┬──────────┘
                                             │
 cli ────> pipeline ────> callgraph ────> storage ────> core
 │      │                 │  │              │  │
 │      │                 │  ├──> search    │  │
 │      │                 │                 │  │
 │      ├──> spring       │                 │  │
 │      ├──> dubbo        │                 │  │  (P2.7: + DubboXmlPass)
 │      ├──> mybatis      │                 │  │  (P2.7: + MybatisXmlPass)
 │      │                 │                 │  │
 │      ├──> mcp-server ──┘                 │  │
 │      │                                   │  │
 └─────> (transitively)                    └─────> (transitively)
```

No cycles. The CLI and MCP-server are the only entry points; everything else is library code.

---

## 4. Storage Schema (V1 + V2 + V3 + V4 + V5 + V6 + V7 + V8 + V9)

### V1 (initial, 14 tables)

```
repos ─< artifacts ─< files
                  ├─< classes ─< methods ─< invokes
                  │             ├─< fields
                  │             └─< lambdas
                  ├─< (direct)  invokes
                  ├─< (direct)  lambdas
                  └─< issues
```

### V2 (adds `classes.interfaces`)

P3.2 needed the implemented interfaces of each class so the Spring DI candidate resolver can
match `OwnerService.@Autowired OrderApi` to `OwnerProviderImpl implements OrderApi`. We store
interfaces in a single new column:

```sql
ALTER TABLE classes ADD COLUMN interfaces TEXT NOT NULL DEFAULT '';
-- Format: ",com/acme/api/OwnerApi,com/acme/api/Orderable,"
-- (comma-bracketed so LIKE '%,FQN,%' works without false positives)
```

### Full table list (V2)

| Table | Purpose | Key indexes |
|---|---|---|
| `repos` | Indexed repositories (one row per jrdi index call) | `name UNIQUE` |
| `artifacts` | Per-repo artifacts (group, name, version, sha256) | `(repo_id, gav) UNIQUE` |
| `files` | Per-artifact source files with `sha256` (for P3.7 incremental) | `(artifact_id, rel_path) UNIQUE` |
| `classes` | Class declarations with access, super, source, **interfaces** | `fqn UNIQUE` |
| `methods` | Per-class method declarations with start/end line | `(class_id, name, desc) UNIQUE` |
| `fields` | Per-class field declarations | `(class_id, name, desc) UNIQUE` |
| `invokes` | Caller → callee edges with kind and confidence | `(callee_owner, callee_name, callee_desc)` |
| `lambdas` | Lambda enclosing → synthetic link | `enclosing_method_id` |
| `issues` | Indexer-detected anomalies | `kind, severity` |
| `spring_beans` | Spring-managed beans (component-scan, @Configuration+@Bean, @Repository) | `type_fqn` |
| `spring_injects` | @Autowired / @Resource / @Value sites with candidate beans | `class_id` |
| `dubbo_services` | @DubboService **or** `<dubbo:service>` providers (interface → impl) | **V3 UNIQUE** `(interface_fqn, group_name, version, ref_bean_name, impl_class_id)` |
| `dubbo_references` | @DubboReference **or** `<dubbo:reference>` consumers (interface → field) | **V3 UNIQUE** `(interface_fqn, group_name, version, ref_id, field_id)` |
| `mybatis_statements` | MyBatis SQL with template + JSqlParser-normalized form (annotation **or** XML) | **V3 UNIQUE** `(namespace, statement_id)` |
| `dubbo_method_configs` | `<dubbo:method>` per-method tuning (timeout, retries, LB, async) | **V4 UNIQUE** `(service_id, reference_id, method_name)` |
| `mybatis_result_maps` | `<resultMap>` row-mapper shape (type, extends, property/association/collection counts) | **V4 UNIQUE** `(namespace, map_id)` |
| `spring_boot_autoconfigs` | Spring Boot auto-configurations (spring.factories + AutoConfiguration.imports) | **V5 UNIQUE** `(class_fqn, source_file, source_format, key_in_factories)` |
| `spring_autoconfig_conditions` | `@Conditional*` annotations extracted from auto-config classes (V6) | **V6 UNIQUE** `(autoconfig_class, condition_type, required_class, required_bean_type, required_property, applied_to)` |

### V3 — Framework-table upsert (P2.7)

P2.7 added XML support for Dubbo (`<dubbo:service>` / `<dubbo:reference>`) and MyBatis
(`*Mapper.xml`). The V3 migration promotes the previously-non-unique framework-table
indexes to UNIQUE composites so we can do real `INSERT … ON CONFLICT DO UPDATE` upserts
instead of plain `INSERT`. This is what makes re-indexing an unchanged jar idempotent —
without V3, every re-run would append duplicate rows.

V3 also adds two columns:

- `dubbo_services.ref_bean_name TEXT NOT NULL DEFAULT ''` — empty for
  annotation-discovered services, the Spring bean name for XML-discovered ones.
- `dubbo_references.ref_id TEXT NOT NULL DEFAULT ''` — empty for
  annotation-discovered references, the XML `id` attribute for XML-discovered ones.

These columns are the join keys the LLM uses to correlate XML-discovered
Dubbo configs back to Spring beans:

```
XML service:  interface="com.acme.OrderApi", ref="orderImpl"
Spring bean:  name="orderImpl", type_fqn=com.acme.OrderImpl
→ the impl is com.acme.OrderImpl
```

The migrator applies `V3__framework_upsert.sql` (or its PG mirror) automatically.

### V4 — Per-method Dubbo config + MyBatis resultMaps (P2.7+)

P2.7+ closes two gaps in the XML coverage that M1 had left as "post-M1":

1. **`<dubbo:method>` children** of `<dubbo:service>` / `<dubbo:reference>`. These
   carry per-method tuning: `timeout`, `retries`, `loadbalance`, `async`, `sent`.
   The V4 migration adds `dubbo_method_configs` with a foreign key to the parent
   service (or reference) row. Either `service_id` or `reference_id` is non-zero
   per row; both are `0` (sentinel) for the unused side. Method configs are
   idempotent on `(service_id, reference_id, method_name)`.

2. **`<resultMap>` elements** in MyBatis mapper files. These define the row-mapper
   shape: what columns map to what fields, including nested `<association>` and
   `<collection>`. The V4 migration adds `mybatis_result_maps` with the namespace,
   map id, target type FQN, `extends` chain, and counts of properties / associations
   / collections. We don't record every `<id>` / `<result>` row — just the
   aggregate shape so the LLM can answer "is this a flat row or a deep graph" without
   bloating the DB.

### V5 — Spring Boot auto-configurations

P2.7++ adds discovery of Spring Boot auto-configurations. There are two formats
in the wild and we support both:

1. **Pre-3.0** — `META-INF/spring.factories` is a Properties file where the
   well-known keys (`EnableAutoConfiguration`, `ApplicationListener`,
   `ApplicationContextInitializer`, etc.) map to comma-separated FQN lists.
2. **3.0+** — `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
   is one FQN per line, with `#` comments.

The V5 migration adds `spring_boot_autoconfigs(class_fqn, source_file, source_format, key_in_factories)`
with `UNIQUE(class_fqn, source_file, source_format, key_in_factories)`. Each
discovered entry is recorded with its source format so the LLM can answer
"is this jar pre-3.0 (spring.factories) or 3.0+ (imports) style?" and
"what auto-configurations does this project pull in?".

### V6 — Spring Boot auto-config condition analysis

V5 tells us *which* auto-configs are on the classpath. V6 tells us *what
conditions* they gate on. For every auto-config class recorded in V5, the
{@code SpringConditionalPass} uses ASM to walk class-level and method-level
`@Conditional*` annotations and records each condition in
`spring_autoconfig_conditions(autoconfig_class, condition_type, required_class,
required_bean_type, required_property, applied_to)`. The 10 known condition
types — `ConditionalOnClass`, `ConditionalOnMissingClass`, `ConditionalOnBean`,
`ConditionalOnMissingBean`, `ConditionalOnSingleCandidate`,
`ConditionalOnWebApplication`, `ConditionalOnNotWebApplication`,
`ConditionalOnProperty`, `ConditionalOnResource`, `ConditionalOnJava` — are
extracted; custom conditions are recorded as `condition_type="other"` with
no requirement fields.

The LLM uses this to predict which auto-configs will actually fire at runtime:
a `@ConditionalOnClass(X.class)` condition is met iff `X` is on the
classpath (join with the `classes` table on `required_class = classes.fqn`).

### V7 — Dubbo registry declarations

V7 adds `dubbo_registries` so the LLM can reason about where a service is
being published. The table captures `<dubbo:registry id="zk" address="zookeeper://..."/>`
declarations from `dubbo-provider.xml` / `dubbo-consumer.xml`; each
`<dubbo:parameter>` child element is serialised into a JSON `parameters` map.

V7 also widens the `dubbo_services` and `dubbo_references` UNIQUE keys to
include `registry_id` — the same `<dubbo:service>` can target multiple
registries, and that fan-out is preserved as one row per registry.

### V8 — MyBatis `@XxxProvider` runtime-SQL binding

V8 adds two columns to `mybatis_statements` so the LLM can follow dynamic
SQL bindings to their source:

| column            | type    | meaning                                              |
|-------------------|---------|------------------------------------------------------|
| `provider_class`  | TEXT    | FQN of the class that produces the SQL (e.g. `com.acme.OwnerSqlProvider`) |
| `provider_method` | TEXT    | Name of the static method that returns the SQL string |

`@SelectProvider`, `@InsertProvider`, `@UpdateProvider`, `@DeleteProvider`
all flow into this path. The `sql_template` and `sql_normalized` columns
are empty (the SQL doesn't exist until runtime), but the LLM can use the
binding to locate the provider class via `find_classes` /
`find_call_graph`, then read its method body.

### V9 — Cross-jar Dubbo reference resolution

V9 adds `consumer_class_fqn TEXT` to `dubbo_references` so cross-jar
references — where the `@DubboReference` lives in a module that jrdi
didn't index — can still report the consumer class. The pass tries
three resolutions in order:

1. `fieldRepo.findByKey(owner, name, desc)` — local bytecode match.
   When the consumer class is in the indexed module, this finds the
   field and we record `field_id` + `consumer_class_fqn` (the same info,
   two ways of accessing it).
2. If the field isn't in the local index, the pass falls back to a
   `UNCERTAIN` reference with `field_id = 0` and `consumer_class_fqn =
   owner.slashed()`. The LLM can still ask "which classes consume
   this Dubbo interface?" via `find_dubbo_references(consumerClass=...)`.
3. The V9 migration also **rebuilds the UNIQUE index** to include
   `consumer_class_fqn`. Without this, two consumers in different
   classes (both with `field_id=0` in the cross-jar case) would collide
   on the same row and the LLM would see only the last consumer.

The same column backs the new `consumerClass` argument of
`find_dubbo_references` — the MCP tool can now answer "what does
`com.acme.web.OrderController` inject from Dubbo?" with one query.

### Dialect dispatch

`Migrator.migrate()` inspects the JDBC URL and picks the migration directory:
- `jdbc:sqlite:...` → `classpath:db/migration` (V1 + V2 + V3 + V4 + V5 + V6 + V7 + V8 + V9)
- `jdbc:postgresql:...` → `classpath:db/pg-migration` (V1 + V2 + V3 + V4 + V5 + V6 + V7 + V8 + V9, PG-specific types)

Both dialects share the same `ClassRepo` / `MethodRepo` / … interfaces. New tables are added
in lockstep across both.

### Indexes target the query patterns

- `classes(fqn)` for `find_symbol`
- `methods(class_id, name)` for `describe_method`
- `invokes(callee_owner, callee_name, callee_desc)` for `callers_of`
- `invokes(caller_method_id)` for `callees_of`
- `invokes(kind, line)` for reflection-uncertainty queries
- `files(artifact_id, rel_path)` for P3.7 incremental hash check
- `classes(super_fqn)` + `interfaces LIKE '%,FQN,%'` for `find_spring_injects` cross-jar match

---

## 5. Indexing Pipeline (2-pass)

### Pass sequence

```
ArtifactInput(GAV, jar, sources-jar)
  │
  ▼  resolve(GAV)  ─────────────────────────►  ArtifactRepo.upsert()
  │
  ▼  JarClassLister.list(jar)                  FQN list
  │
  ▼  PASS 1 — per class:
       ├── readClassBytes(jar, slashed)
       ├── SHA-256 check (P3.7):
       │     └── if files.sha256 matches → return ClassVisitStats.markSkipped()
       ├── BytecodePass.run(bytes)             PassResult (classes/methods/fields/invokes/lambdas + interfaces)
       │     │
       │     ├── ClassRepo.upsert(...)         — writes interfaces column
       │     ├── MethodRepo.upsert
       │     ├── FieldRepo.upsert
       │     ├── InvokeRepo.insertAll
       │     ├── LambdaRepo.upsert
       │     └── FileRepo.upsert(sha256)       — record for next incremental run
       │
       ├── SourceMatcher (or CFR fallback)     start_line / end_line correction
       │
       └── issues                              `uncertain_reflect`, `class_process`, etc.
  │
  ▼  PASS 2 — framework passes (need full class set first):
       │
       ├── 2a. Spring recordBeans (all classes)
       │       — populates spring_beans with @Component, @Service, @Repository,
       │         @Controller, @Configuration+@Bean.
       │
       ├── 2b. Spring resolveInjects (all classes)
       │       — for each @Autowired/@Resource, finds candidates from full bean set
       │         using qualifier → name → exact-type → implements/extends (PROBABLE).
       │
        └── 2c. Dubbo + MyBatis (per-class; no class-set dependency)
   │
   ▼  PASS 3 — XML framework passes (P2.7):
        │
        ├── 3a. SpringXmlPass.scanJar(jar)   — reads <bean> and <context:component-scan>
        │       in META-INF/spring/ and root XML files. JDK built-in
        │       DocumentBuilder, XXE-hardened, namespace-aware. Records each
        │       bean as a row in spring_beans with source="xml".
        │
        ├── 3b. DubboXmlPass.scanJar(jar)   — reads <dubbo:service>/<dubbo:reference>
        │       in META-INF/spring/ and root XML files. Auto-joins
        │       ref="someBean" → impl_class_id via spring_beans.findByName +
        │       classes.findByFqn. Also extracts nested <dubbo:method>
        │       per-method tuning (timeout, retries, loadbalance, async, sent).
        │
        └── 3c. MybatisXmlPass.scanJar(jar) — reads *Mapper.xml files anywhere
                in the jar. JDK built-in DocumentBuilder, DOCTYPE allowed
                (every mybatis mapper has one), XXE-hardened. Inlines
                <include refid="..."/>, elides dynamic tags from the
                normalised view, captures parameters from #{...} and ${...},
                and records <resultMap> shape (type, extends, property/association/
                collection counts).
   │
   ▼  IndexReport
```

### Why the 2-pass split for Spring

The original `processOneClass` did bytecode + framework passes together. That worked for
*most* projects but failed for cross-class interface injection:

1. `OwnerService` is processed in the jar (alphabetical class 6 of 8)
2. At that moment, `JdbcOwnerRepository` (class 7) hasn't been bytecode-processed yet, so its
   `classes.interfaces` column is `''`
3. `find_spring_injects` for `ownerRepository` calls `findSubtypesOf(OwnerRepository)` which
   returns `[]` (no row matches the LIKE)
4. Result: `candidates=[]`, `confidence=UNCERTAIN` — silently wrong

The fix: do *all* bytecode first (filling `classes.interfaces` for every class), then run
framework passes. `SpringPass` is now split into:

- `recordBeans(String source, Fqn owner)` — pure side-effect: writes `@Service`/etc. to
  `spring_beans`
- `resolveInjects(String source, Fqn owner)` — reads `spring_beans.findAll()` (which now
  includes beans recorded by `recordBeans` for the *whole* artifact), then resolves candidates
  for `@Autowired` sites

The pipeline runs `recordBeans` across all classes first, then `resolveInjects` across all
classes. Result: `JdbcOwnerRepository` is a bean before `OwnerService`'s injects are resolved.

### Source line-number policy

1. `sources.jar` is the **primary** source of truth: parse with JavaParser, locate the
   `MethodDeclaration` by `(name, erased descriptor)`, capture `getBegin().line` and
   `getEnd().line`.
2. If sources.jar is missing **or** the method isn't found in the source, fall back to CFR:
   decompile the .class bytes, re-parse the result, locate the method there. Mark
   `methods.virtual_lines = 1`.
3. The `LineNumberTable` ASM attribute is the "worst case" if both fail; currently leaves
   `start_line = null`.

### Reflection policy

The bytecode pass only emits a `REFLECT` edge when it can:

| Source | Edge emitted | Confidence |
|---|---|---|
| `Class.forName("com.acme.Foo")` constant arg | `callee_owner = com/acme/Foo` | `PROBABLE` |
| `Class.forName(<non-constant>)` | `callee_owner = "?"` | `UNCERTAIN` |
| `Method.invoke(...)` (no static target) | — | `UNCERTAIN` |
| `ClassLoader.loadClass(...)` | as `Class.forName` | as `Class.forName` |
| `Constructor.newInstance(...)` | — | `UNCERTAIN` |

`UNCERTAIN` edges do not feed the call graph. They go into `issues` for the LLM to surface.

### Lambda policy

- `invokedynamic` with BSM = `LambdaMetafactory.metafactory` → emit a `DYNAMIC` edge to the
  synthetic method, plus a `lambdas` row linking enclosing → synthetic.
- `invokedynamic` with any other BSM → single `DYNAMIC + PROBABLE` edge to the BSM handle
  (useful for `StringConcatFactory` etc.).

---

## 6. Cross-Jar CHA

### The problem

CHA needs the class hierarchy to expand `OwnerService.getOwner() → ownerApi.findOwner()` when
`ownerApi` is typed as the interface `OrderApi`. If `OrderApi` and its impls are not indexed
(different jar, third-party dep), CHA returns nothing.

### The solution: `M2ClasspathResolver`

A new interface `ExternalClassResolver` in `jrdi-callgraph`:

```java
public interface ExternalClassResolver {
    Optional<ExternalClass> resolve(Fqn fqn);
    record ExternalClass(Fqn fqn, Fqn superFqn, List<Fqn> interfaces, List<MethodSig> methods) {}
}
```

Default impl `M2ClasspathResolver` walks configured roots (default: `~/.m2/repository`):

```
M2ClasspathResolver.findInClasspath(fqn):
  for each jar in configured roots:
    if jar contains "com/acme/Foo.class":
      read class header (constant pool + interfaces + method sigs, no method bodies)
      cache and return ExternalClass
```

Performance: cold miss = 50-200ms (walking ~5k jars in default m2). Subsequent hits = O(1)
via a `ConcurrentHashMap<Fqn, Optional<ExternalClass>>` cache.

### `ChaResolver` integration

`ChaResolver` was extended to take an optional `ExternalClassResolver`. When a FQN is not in
the in-memory parent map, the resolver falls back to the external one. Newly-discovered parent
links are added to the in-memory hierarchy for the lifetime of the resolver.

`JrdiMcpService.buildCallGraph()` now constructs the CHA with `M2ClasspathResolver.defaultM2()` as
the external resolver. Effect: indexing a Spring app gives correct `find_path` results that
cross into `spring-core` etc. without any user configuration.

### Verified by tests

- `CrossJarChaTest` (4 cases) — CHA path via m2, missing class returns empty, parent chain
- `CrossJarChaMcpIT` — end-to-end `find_path` from a Service to a method on an external
  parent, via the real `JrdiMcpServer`

---

## 7. Incremental Indexing (P3.7)

### What it does

`IndexPipeline` computes the SHA-256 of every `.class` file's bytes. The hash is stored in
`files.sha256` keyed by `(artifact_id, 'classes/<fqn>.class')`.

On re-index:
- If hash matches → `ClassVisitStats.markSkipped()` (counted as `classesSkipped` in the
  report; no per-class work done)
- If hash differs (or row absent) → drop the old class row (CASCADE clears dependents) and
  re-process

### Verified

Re-indexing the `petclinic-mini` example:
- First pass: 8 classes indexed, 206ms
- Second pass: 0 classes indexed, **8 classes skipped**, 7ms (~30× speedup)

### Storage gotcha fixed

`SqliteClassRepo.upsert` originally called `findByFqn` *inside* the try-with-resources block
of its own connection, while the HikariCP pool size is 4. The nested query needed another
connection and would block on the pool exhaustion. Fixed by using `Statement.RETURN_GENERATED_KEYS`
for the insert path, and a separate `findIdOnConnection` (re-using the same connection) for the
update path.

`SqliteRepoRepo.upsert` and `SqliteArtifactRepo.upsert` had the same issue — fixed with
explicit "find existing → update" / "insert" branches (sidestepping SQLite's
`getGeneratedKeys()` after `ON CONFLICT DO UPDATE` which returns the AUTOINCREMENT counter
instead of the rowid).

---

## 8. MCP Tool Surface (17 tools + 2 resources + 6 prompts)

The server speaks **JSON-RPC 2.0** over **stdio** (newline-delimited) or **HTTP** (POST).
Protocol version: `2024-11-05`.

### Protocol methods

| Method | Args | Output | Use case |
|---|---|---|---|
| `initialize` | `{protocolVersion, capabilities}` | server info | MCP handshake |
| `tools/list` | — | array of `{name, description, inputSchema}` | Discover tools |
| `tools/call` | `{name, arguments}` | text content | Run a tool |
| `resources/list` | — | array of `{uri, name, description, mimeType}` | Discover resources |
| `resources/read` | `{uri}` | `{contents: [{uri, mimeType, text}]}` | Read a resource |
| `prompts/list` | — | array of `{name, description, arguments}` | Discover prompts |
| `prompts/get` | `{name, arguments}` | `{messages: [{role, content}]}` | Get a prompt template |

### P1 tools (general JVM facts)

| Tool | Args | What you get |
|---|---|---|
| `index_status` | — | `{classes, methods, invokes, issues, uncertainReflections, dialect}` |
| `find_symbol` | `{prefix}` | `{matched, class}` — first exact FQN match |
| `describe_method` | `{owner, name, desc}` | `{found, method{...}}` with `startLine`, `endLine` |
| `callers_of` | `{owner, name, desc, includeReflect}` | `{count, callers[]}` |
| `callees_of` | `{callerMethodId}` | `{count, callees[]}` — outgoing from one method |
| `find_path` | `{fromOwner, fromName, fromDesc, toOwner, toName, toDesc, maxDepth}` | `{found, path[]}` — BFS shortest path (cross-jar via §6) |
| `list_issues` | `{kind}` | `{count, issues[]}` |

### P2 tools (framework-aware)

| Tool | Args | What you get |
|---|---|---|
| `find_spring_beans` | `{type?, name?}` | `{count, beans[]}` — Spring-managed beans by type or name |
| `find_spring_injects` | `{class?}` | `{count, injects[]}` — `@Autowired` sites with candidate beans (interface-aware) |
| `find_spring_autoconfigs` | `{class?, key?, format?}` | `{count, autoconfigs[]}` — Spring Boot auto-configurations (V5) |
| `find_spring_autoconfig_conditions` | `{autoconfigClass?, requiredClass?, type?}` | `{count, conditions[]}` — `@Conditional*` gates (V6) |
| `find_dubbo_services` | `{interface?}` | `{count, services[]}` |
| `find_dubbo_references` | `{interface?, consumerClass?}` | `{count, references[]}` — consumer class is the new filter for cross-jar resolution (V9) |
| `find_dubbo_method_configs` | `{serviceId?, referenceId?}` | `{count, methodConfigs[], parent, parentId}` — per-method `<dubbo:method>` tuning |
| `find_mybatis_statements` | `{namespace?, statementId?}` | `{count, statements[]}` — SQL with normalized form, plus `providerClass`/`providerMethod` for `@XxxProvider` bindings (V8) |
| `find_mybatis_result_maps` | `{namespace?, typeFqn?}` | `{count, resultMaps[]}` — row-mapper shape |

### Resources

| URI | Content |
|---|---|
| `jrdi://schema` | V1/V2 schema description (tables + tool names) — useful for LLM context |
| `jrdi://stats` | Live fact counts (same as `index_status` but as a resource) |

### Prompts

| Name | Purpose | Chained tools |
|---|---|---|
| `find_callers` | Find all callers of a method | `callers_of` |
| `find_bean_wiring` | Trace Spring bean injection for a class | `find_spring_beans`, `find_spring_injects` |
| `find_path` | Shortest call path between two methods | `find_path` |
| `find_dubbo_method_tuning` | Audit per-method Dubbo timeout/retries/LB/async for a service | `find_dubbo_services`, `find_dubbo_method_configs` |
| `find_mybatis_query_shape` | For each SQL in a Mapper, describe what it returns | `find_mybatis_statements`, `find_mybatis_result_maps` |
| `find_rpc_call_chain` | Map a Dubbo interface's full graph: providers, consumers, per-method tuning | `find_dubbo_services`, `find_dubbo_method_configs`, `find_dubbo_references` |

Prompts are rendered as `user`-role messages with a `text` content; LLM clients pass them to
the chat model, which then chains the relevant tool calls.

### Verified by end-to-end smoke test

A Python script (51 assertions) sends real JSON-RPC over HTTP to a live `jrdi-cli serve --http 7890`
and validates:
- `initialize` returns protocolVersion `2024-11-05`
- `tools/list` returns 17 tools (7 P1 + 9 P2 + 1 provider-binding)
- `resources/list` returns 2 resources
- `prompts/list` returns 6 prompts
- All 5 framework tools return real data for the petclinic-mini example
- `find_path` from `OwnerService.getOwner` to `OwnerApi.findOwner` returns 1-hop path
- Unknown tool → `error.code = -32602`
- Missing class → `found = false`, `count = 0`

The stdio transport was also tested end-to-end via piping real JSON-RPC into
`jrdi-cli serve --stdio`.

---

## 9. Failure Modes & Mitigation

| Failure | Symptom | Mitigation |
|---|---|---|
| No `sources.jar` for a third-party | `start_line = null`; `methods.virtual_lines = 1` after CFR | UI shows "approx"; facts remain queryable |
| `settings.xml` missing | Falls back to `central` mirror | Logged at `WARN`; CLI accepts `--maven-settings` |
| Maven Resolver 1.9.x refactor | (we dodged it; see §10) | Direct settings.xml + HTTP fetch via `RemotePlan` |
| CFR outputs `Analysing type X` preamble | First-line garbage | `CfrDecompiler` strips it before re-parsing |
| Cross-jar parent not in DB | CHA returns just declared owner | `M2ClasspathResolver` lazy-scans m2 cache |
| Class iteration order (Spring 2-pass) | `@Autowired` interface field gets no candidate | Pipeline: 2a-recordBeans, 2b-resolveInjects |
| SQLite pool exhaustion on upsert | `findByFqn` nested inside upsert blocks | Inline `findIdOnConnection` reuses same connection |
| `ON CONFLICT` getGeneratedKeys pitfall | `upsert` returns wrong id | SELECT-first then explicit UPDATE/INSERT branches |
| LLM client connection refused | MCP server not visible in tool list | Absolute paths, verify java in PATH, JDK ≥ 21 |
| Incremental re-index reports `classesSkipped: 0` | sha256 mismatch | Run `jrdi rebuild` once to reseed, then incremental works |

---

## 10. Notable Design Decisions

### Maven Resolver: hand-rolled instead of `maven-resolver-impl`

The 1.9.x refactor of `maven-resolver-impl` removed `MavenRepositorySystemUtils` and split the
service locator across multiple jars. Wiring it cleanly pulls ~20 transitive deps and 30+
services (POMs, version ranges, dependency resolution) — none of which jrdi needs. We do:

1. Parse `~/.m2/settings.xml` with `maven-settings-builder:3.9.9` (lightweight, single jar).
2. Local lookup: walk `~/.m2/repository/{group}/{artifact}/{version}/{artifact}-{version}.jar`.
3. Remote fetch: standard Maven layout via `HttpURLConnection` (one file at a time).

This is **~3 MB of transitive deps** instead of **~10 MB**, and the test surface is the
network and disk, not the Aether service registry.

### MCP Java SDK: hand-rolled JSON-RPC 2.0

`io.modelcontextprotocol:mcp:0.7.0` is not on Maven Central. The only available MCP-for-Java
is `org.springframework.ai:spring-ai-mcp`, which transitively pulls Spring AI's entire model
abstraction. jrdi is a *sidecar service* with no need for chat models, so the simpler path was
to implement JSON-RPC 2.0 directly. ~260 lines, JDK-only, no third-party deps. Tests run with
in-memory streams.

### Spring framework analyzers: short-name disambiguation

`@Service` is a Spring annotation; legacy Alibaba Dubbo 2.6.x has `@Service` in
`com.alibaba.dubbo.config.annotation`. Short-name matching would conflate them. We use a
`KNOWN_AMBIGUOUS_SHORT_NAMES` set in `DubboPass.matchesAny` that skips short-name match for
`Service` and `Reference`; users must use the FQN for legacy Alibaba. The Spring test and
integration test both cover this.

### Spring candidate resolution: interface-aware

`@Autowired OrderApi orderApi` previously had UNCERTAIN confidence with empty candidates
because `OrderProviderImpl` was a bean but its `typeFqn` didn't match the field type. The
fix is two-part:

1. **Storage (V2)**: `classes.interfaces` column records the implemented interfaces of every
   class (extracted by ASM in `BytecodePass`).
2. **Lookup (P3.6)**: `ClassRepo.findSubtypesOf(Fqn iface)` returns classes whose
   `super_fqn = iface` OR whose `interfaces` LIKE-contains `iface`. The Spring pass
   intersects that with the bean set and reports `PROBABLE` confidence.
3. **Pipeline (P3.6)**: 2-pass split so `findSubtypesOf` sees fully-populated `interfaces`
   for *every* class, not just those processed so far alphabetically.

### Lucene over SQLite FTS5

Reserved for the future `search_full_text` tool. Needs prefix queries, typo tolerance,
relevance scoring. SQLite FTS5 supports prefix but not BM25/typo. Lucene is already in our
BOM; the 9 MB cost is worth it.

### CFR over Vineflower

Vineflower is better at records/switch patterns but is published via the ForgeGradle
infrastructure, not Maven Central. CFR 0.152 is on Maven Central and good enough for M1.

### One CLI, one MCP server (single fat-jar)

The `jrdi-cli` shaded fat-jar embeds the MCP server. The standalone `jrdi-mcp-server` jar
exists for users who want to ship just the server. The two share 100% of the runtime (same
`JrdiMcpServer` + `JrdiMcpService`).

### Vitual threads: deferred

The plan called for `StructuredTaskScope` to parallelize per-class indexing. We still
**single-thread** per artifact (one class at a time) because SQLite write contention dominates
on the in-memory shared cache. The horizontal scale-out is to run multiple `jrdi serve`
against the same DB; per-jar parallelism is intentionally conservative until we benchmark.

### SQLite pool size 4 (not 1)

Originally 1, but the 2-pass framework split made a single class do many sequential queries.
Pool size 4 lets Spring + class lookups proceed without contention on the main thread.

---

## 11. Observability

- **Logs**: SLF4J + Logback, default `text` format to stderr. Adjust at runtime via
  `-Dlogback.configurationFile=...`.
- **MCP `index_status`**: lightweight health check; LLM can call it to detect stale indexing.
- **`issues` table**: persistent observability surface. `list_issues --kind uncertain_reflect`
  is the canonical "what does jrdi not understand?" query.
- **`IndexReport` per-index run**: prints `classesIndexed / classesSkipped / spring beans / spring
  injects / dubbo services / mybatis statements`. The `classesSkipped` line tells you whether
  P3.7 incremental skip kicked in.
- **Test counters**: `mvn verify` → `tests: 201, failures: 0, errors: 0, skipped: 0` (plus
  `PostgresE2EIT` opt-in via `-Djrdi.pg.it=true`).

---

## 12. Known Limitations (post-M1)

| Limitation | Impact | Plan |
|---|---|---|
| No `--watch` mode (file-system monitor) | Re-index requires manual command | 0.2.0: `jrdi index --watch <dir>` |
| Shallow class hierarchy beyond `interfaces` and direct super | Multi-level type erasure not followed through | 0.2.0: deep CHA traversal using stored interfaces |
| `m2` cross-jar resolver is best-effort | Slow on first cold miss (~50-200ms) | 0.2.0: persistent cache |
| Per-class candidate resolution at `PROBABLE` only (single hit) | No narrowing by name when there are multiple candidates | 0.2.0: name-tying for interfaces |
| HTTP transport is request/response, not SSE | LLM clients that need streaming updates | 0.2.0: SSE for `index_status` |
| Single-process, single-DB | No horizontal scale-out within one jrdi process | 0.3.0: read-replica routing |
| No Kotlin / Scala / Groovy | Source syntax | 0.3.0 |
| **Static-only — no runtime / dynamic analysis** | No agent, no JFR, no `.hprof`, no profiling. jrdi reads compiled artifacts, never runs them. | Use JFR / async-profiler / Glowroot *alongside* jrdi for the dynamic half |
| **Dubbo XML: ref→impl class is not auto-resolved** | (resolved in P2.7+) XML config carries `ref="someBean"` (a Spring bean name), not an impl class FQN. The MCP tool returns the raw ref. | Auto-join with `find_spring_beans(name=ref)` and `classes.findByFqn` runs at pass-3 time. Falls back to ref_bean_name when the chain breaks. |
| **MyBatis XML: dynamic-tag conditions are elided from the normalised view** | `<if test="...">`, `<where>`, `<foreach>`, etc. are kept in `sqlTemplate` but stripped from `sqlNormalized`. Condition expressions are not evaluated. | The LLM reads `sqlTemplate` for full source; `sqlNormalized` is best-effort for similarity queries. |
| **MyBatis XML: `<resultMap>` shape not extracted** | (resolved in P2.7+) | `mybatis_result_maps` table now records the type, extends, and per-row counts. |
| **Spring XML: no `<context:annotation-config>` / `<tx:annotation-driven>` introspection** | We log these triggers at DEBUG but don't index the activated annotations. | 0.2.0: trace which annotations each XML file enables. |

---

## Appendix: Data flow examples

### "What Spring beans does `com.acme.OwnerService` inject?"

```
LLM  ──[tools/call find_bean_wiring]──►  JrdiMcpServer
                                              │
                                              ├─► resources/read jrdi://stats        (LLM gets V1/V2 schema context)
                                              ├─► tools/call find_spring_beans       (returns [orderService])
                                              ├─► tools/call find_spring_injects     (returns 2 sites with candidates)
                                              │
LLM  ◄──[2 sites: ownerApi→[OwnerProviderImpl], ownerRepository→[JdbcOwnerRepository]]──
```

### "Find call path from `Main.main` to `JdbcOwnerRepository.findById`"

```
LLM  ──[tools/call find_path with from/to]──►  JrdiMcpServer
                                                  │
                                                  ├─► ChaResolver.subtypeClosure(...)         (in-memory)
                                                  ├─► M2ClasspathResolver.resolve(Main)       (m2 cache miss → ASM scan)
                                                  ├─► BfsPathFinder.findPath(graph)             (BFS, maxDepth=6)
                                                  │
LLM  ◄──[found=true, path=[Main.main, ...]]──
```

### Persistence of an inject

```
Pipeline (PASS 1 — per .class, alphabetical):
  class 1  MapperConfig     → ClassRepo.upsert(interfaces=[])
  class 2  OwnerMapper      → ClassRepo.upsert(interfaces=[])
  ...
  class 5  OwnerProviderImpl → ClassRepo.upsert(interfaces=[OrderApi])  ← bytecode read
  class 6  OwnerService     → ClassRepo.upsert(interfaces=[])
  class 7  JdbcOwnerRepository → ClassRepo.upsert(interfaces=[OwnerRepository])
  class 8  OwnerRepository  → ClassRepo.upsert(interfaces=[])

Pipeline (PASS 2a — recordBeans, all classes):
  for each class: record @Service / @Repository / @Bean into spring_beans
  → all 5 beans now present

Pipeline (PASS 2b — resolveInjects, all classes):
  for each class: resolve @Autowired sites against full spring_beans
  → OwnerService.@Autowired OrderApi → finds OwnerProviderImpl (via interfaces LIKE)
  → OwnerService.@Autowired OwnerRepository → finds JdbcOwnerRepository (via interfaces LIKE)
```

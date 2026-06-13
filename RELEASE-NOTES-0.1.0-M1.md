# jrdi 0.1.0-M1 — Release Notes

First milestone release of **jrdi — Java RPC Dependency Intelligence MCP**.
An MCP server that gives LLM clients (Claude Desktop, Cursor, custom agents)
queryable dependency facts about any JVM project.

## What's in this release

### P1 — Foundation
- **17-module Maven reactor** at version `0.1.0-M1`
- **jrdi-core** — value types (`Fqn`, `MethodKey`, `CallEdge`, `Gav`, `Confidence`)
- **jrdi-storage** — SQLite (default) and PostgreSQL (mirror) via Flyway
- **jrdi-resolver** — `settings.xml` parser, jar + sources fetcher, sha256 cache
- **jrdi-classgraph** — zero-shadowing jar walk, META-INF scanner
- **jrdi-bytecode** — ASM pass (classes, methods, fields, invokes, lambdas, reflection detection)
- **jrdi-source** — JavaParser + sources.jar line attribution
- **jrdi-decompile** — CFR fallback for missing sources
- **jrdi-callgraph** — class-hierarchy resolver + BFS path finder
- **jrdi-pipeline** — orchestrator: bytecode + source + decompile + framework passes
- **jrdi-search** — Lucene full-text index
- **jrdi-cli** — `index` / `query` / `rebuild` / `doctor` / `serve` subcommands
- **jrdi-mcp-server** — JSON-RPC 2.0 over stdio or HTTP, 17 tools, 2 resources, 6 prompts
- **jrdi-it** — end-to-end Petclinic-shaped integration test

### P2 — Framework analyzers
- **jrdi-spring** — `@Component` / `@Service` / `@Repository` / `@Controller` + `@Bean` (config) +
  `@Autowired` / `@Resource` / `@Qualifier` candidate resolution
- **jrdi-dubbo** — Apache Dubbo 3.x `@DubboService` + `@DubboReference` and legacy Alibaba 2.6.x
  (with `KNOWN_AMBIGUOUS_SHORT_NAMES` guard so Spring's `@Service` is never mistaken
  for an Alibaba service)
- **jrdi-mybatis** — `@Select` / `@Insert` / `@Update` / `@Delete` with JSqlParser normalization
  + `@SelectProvider` / `@InsertProvider` / `@UpdateProvider` / `@DeleteProvider` bindings
  (records `provider_class` + `provider_method`; runtime SQL is not extracted)
- **Dubbo XML** (P2.7) — `<dubbo:service>` / `<dubbo:reference>` in `META-INF/spring/*.xml`,
  parsed by a JDK `DocumentBuilder` (no new deps). XXE-hardened. Accepts both the canonical
  `http://dubbo.apache.org/schema/dubbo` and legacy `http://code.alibabatech.com/schema/dubbo`
  namespaces. Records `ref_bean_name` / `ref_id` columns. **P2.7+** adds auto-join: the
  pass queries `spring_beans.findByName(ref)` to resolve the impl class FQN, then
  `classes.findByFqn` to fill `impl_class_id`. **P2.7+** also extracts nested
  `<dubbo:method>` children (timeout, retries, loadbalance, async, sent) into
  `dubbo_method_configs`.
- **MyBatis XML** (P2.7) — `*Mapper.xml` files anywhere in the jar. JDK `DocumentBuilder`,
  DOCTYPE allowed (every mybatis mapper has one), XXE-hardened. Inlines `<include refid="..."/>`,
  elides dynamic tags (`<if>`, `<where>`, `<foreach>`, etc.) from the normalised view, keeps
  them in the raw `sqlTemplate`. Captures parameters from both `#{...}` and `${...}`.
  **P2.7+** also extracts `<resultMap>` shape (type FQN, extends, property/association/
  collection counts) into `mybatis_result_maps`.
- **Spring XML** (P2.7+) — `<bean id="X" class="Y" scope="..." primary="..."/>` and
  `<alias name="X" alias="Y"/>` in Spring config XML. Records each as a `spring_beans`
  row with `source="xml"`. Resolves `<context:component-scan base-package="..."/>` to
  record which packages the project XML-scanned.
- All five are wired into `IndexPipeline` (annotation passes + 2 XML passes per artifact) and
  produce real DB rows on a normal index run. The V3 schema migration adds UNIQUE indexes on
  the 3 framework tables so re-indexing is idempotent.

### P3 — Polish
- **8 framework-aware MCP tools** (`find_spring_*`, `find_dubbo_*`, `find_mybatis_*`)
  - P3.5: `find_dubbo_method_configs`, `find_mybatis_result_maps`, `find_spring_autoconfigs`
  - P3.5 also extends `find_mybatis_statements` with `providerClass` / `providerMethod`
    for `@XxxProvider` runtime-SQL bindings (V8)
- **MCP resources**: `jrdi://schema` (V1 schema) and `jrdi://stats` (live counts)
- **MCP resources subscribe/unsubscribe**: pub-sub bus broadcasts
  `notifications/resources/updated` when the index rebuilds
- **MCP prompts**: 6 pre-canned templates that tell an LLM how to chain the tools
  for common workflows (`find_callers`, `find_bean_wiring`, `find_path`,
  `find_dubbo_method_tuning`, `find_mybatis_query_shape`, `find_rpc_call_chain`)
- **PostgreSQL schema mirror** (`V1__init.pg.sql` … `V8`) + Testcontainers IT
  (`-Djrdi.pg.it=true`)
- **CLI `stats` subcommand**: human table + `--json` for the same fact counts
- **Examples**: `examples/petclinic-mini/` — 8-class sample that exercises every
  framework pass and reports the exact counts to expect

## Schema migrations

| Version | New table / change                              | Purpose                                                    |
|---------|-------------------------------------------------|------------------------------------------------------------|
| V1      | 14 tables                                       | Core symbol model (repos, artifacts, files, classes, …)    |
| V2      | `classes.interfaces TEXT`                       | Interface hierarchy for cross-class Spring candidate match |
| V3      | UNIQUE indexes on 3 framework tables            | Idempotent framework re-index                              |
| V4      | `dubbo_method_configs`, `mybatis_result_maps`   | Per-method tuning + row-mapper shape                       |
| V5      | `spring_boot_autoconfigs`                       | Spring Boot `spring.factories` + `AutoConfiguration.imports` |
| V6      | `spring_autoconfig_conditions`                  | ASM-extracted `@Conditional*` gates                        |
| V7      | `dubbo_registries` + widens UNIQUE keys          | Multi-registry fan-out                                     |
| V8      | `mybatis_statements.provider_class` / `_method` | `@SelectProvider` / `@InsertProvider` / `@UpdateProvider` / `@DeleteProvider` runtime SQL binding |
| V9      | `dubbo_references.consumer_class_fqn` + UNIQUE rebuild | Cross-jar `@DubboReference` resolution; `find_dubbo_references(consumerClass=...)` filter |

## Numbers

- 17 modules
- 32 test classes
- **161 tests, 0 failures, 0 errors, 1 skipped** (PostgresE2EIT, requires Testcontainers)
- Full reactor `mvn verify` finishes in ~15s on a laptop
- Indexing a 1,000-class project: ~5-10s on a warm SQLite DB

## Install

### From source

```sh
$ git clone https://github.com/anomalyco/java-mcp
$ cd java-mcp
$ export JAVA_HOME=/path/to/jdk-21
$ mvn clean install -DskipTests
```

The reactor produces two fat-jars:

- `jrdi-cli/target/jrdi-cli-0.1.0-M1.jar` — the user CLI
- `jrdi-mcp-server/target/jrdi-mcp-server-0.1.0-M1.jar` — the MCP server

### Index your first project

```sh
$ java -jar jrdi-cli/target/jrdi-cli-0.1.0-M1.jar index /path/to/your.jar \
    --with-sources --db sqlite:./jrdi.db --repo-id my-app
```

### Wire it into Claude Desktop / Cursor

Add to your MCP client config:

```json
{
  "mcpServers": {
    "jrdi": {
      "command": "java",
      "args": [
        "-jar", "/path/to/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar",
        "serve", "--stdio",
        "--db", "sqlite:/path/to/jrdi.db"
      ]
    }
  }
}
```

## Verified end-to-end

| Criterion | Verified by |
|---|---|
| Pipeline indexes a "real" 7-class project (controller + service + repo + entity + 1 3rd-party) | `PetclinicShapedE2EIT` |
| `indexed 6 classes` from sources + `indexed 1 classes` from CFR fallback | same |
| `describe_method` returns source-derived line numbers (startLine=7, endLine=10) for `showOwner` | same |
| CFR fallback marks the no-sources class with `virtual=true` | same |
| `callers_of(OwnerService.save)` returns the controller's `createOwner` | same |
| `find_path(Main.main, OwnerController.showOwner)` finds a path through the chain | same |
| Spring bean detection (`@Component` / `@Service` / `@Repository` / `@Controller`) | `SpringPassTest` + `FrameworkIntegrationIT` |
| Spring DI resolution (`@Autowired` / `@Resource` / `@Qualifier`) | same |
| Apache Dubbo 3.x detection (`@DubboService` + `@DubboReference`) | `DubboPassTest` + `FrameworkIntegrationIT` |
| Legacy Alibaba Dubbo 2.6.x (`@Service` + `@Reference`) | same |
| MyBatis `@Select` / `@Insert` / `@Update` / `@Delete` with normalized SQL | `MybatisPassTest` + `FrameworkIntegrationIT` |
| CLI `index` / `query` / `doctor` / `rebuild` subcommands backed by real repos | `JrdiCommandE2EIT` |
| CLI `serve --stdio` runs the actual MCP server (12 tools + 2 resources + 3 prompts) | same |
| PostgreSQL schema mirror (`V1__init.pg.sql`) | `PostgresE2EIT` (opt-in via `-Djrdi.pg.it=true`) |
| End-to-end index + query against a real PG instance (testcontainers) | same |
| Framework passes auto-run inside `IndexPipeline` (no separate wiring step) | `FrameworkIntegrationIT` |

## Known limitations

- **No web UI** — by design, this is an MCP server (a thin protocol adapter over a CLI).
- **Class hierarchy resolution is shallow** — `ChaResolver` walks `classes.super_fqn`
  but does not yet follow cross-jar supertype links. Virtual call expansion is best-effort.
- **Annotation short-name resolution** — `@Service` is ambiguous between Spring and
  legacy Alibaba Dubbo 2.6.x. The pipeline treats short `@Service` as Spring and requires
  the FQN `@com.alibaba.dubbo.config.annotation.Service` for legacy Alibaba services.
- **No incremental indexing** — `rebuild` drops and re-applies; there's no `watch` mode.
- **No Kotlin / Scala / Groovy** — Java 17+ source syntax is supported.
- **PG mirror is in-process** — `Db.open(jdbc:postgresql:...)` works, but there's no
  automatic dialect-flip in the CLI; you must pass a PG URL.

## What's next (post-M1)

- P3.3 done: `examples/petclinic-mini/` is end-to-end runnable
- P3.4 done: MCP resources + prompts
- **0.2.0** candidates: incremental indexing, Kotlin syntax, deeper CHA,
  cross-repository analysis, code search via the Lucene index

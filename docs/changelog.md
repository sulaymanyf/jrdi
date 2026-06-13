# Release notes

## 0.1.0-M1 — 2026-06-14

First milestone release of **jrdi — Java RPC Dependency Intelligence MCP**.

### P1 — Foundation

- **17-module Maven reactor** at version `0.1.0-M1`
- **jrdi-core** — value types (`Fqn`, `MethodKey`, `CallEdge`, `Gav`, `Confidence`)
- **jrdi-storage** — SQLite (default) and PostgreSQL (mirror) via Flyway, V1–V9 migrations
- **jrdi-resolver** — `settings.xml` parser, jar + sources fetcher, sha256 cache
- **jrdi-classgraph** — zero-shadowing jar walk, META-INF scanner
- **jrdi-bytecode** — ASM pass (classes, methods, fields, invokes, lambdas, reflection detection)
- **jrdi-source** — JavaParser + sources.jar line attribution
- **jrdi-decompile** — CFR fallback for missing sources
- **jrdi-callgraph** — class-hierarchy resolver + BFS path finder, cross-jar CHA
- **jrdi-pipeline** — two-pass orchestrator: bytecode first, then framework
- **jrdi-search** — Lucene full-text index
- **jrdi-cli** — `index` / `query` / `rebuild` / `doctor` / `serve` / `stats` subcommands
- **jrdi-mcp-server** — JSON-RPC 2.0 over stdio or HTTP, 17 tools, 2 resources, 6 prompts
- **jrdi-it** — end-to-end Petclinic-shaped integration test

### P2 — Framework analyzers

- **jrdi-spring** — `@Component` / `@Service` / `@Repository` / `@Controller` + `@Bean` (config) + `@Autowired` / `@Resource` / `@Qualifier` 4-step candidate resolution (qualifier → name → exact-type → implements/extends)
- **jrdi-dubbo** — Apache Dubbo 3.x `@DubboService` + `@DubboReference` and legacy Alibaba 2.6.x (with `KNOWN_AMBIGUOUS_SHORT_NAMES` guard so Spring's `@Service` is never mistaken for an Alibaba service)
- **jrdi-mybatis** — `@Select` / `@Insert` / `@Update` / `@Delete` with JSqlParser normalization + `@SelectProvider` / `@InsertProvider` / `@UpdateProvider` / `@DeleteProvider` runtime-SQL bindings (records `provider_class` + `provider_method`; runtime SQL is not extracted)
- **Spring XML** — `<bean>` / `<alias>` / `<context:component-scan>` in Spring config XML. Each becomes a `spring_beans` row with `source="xml"`
- **Dubbo XML** — `<dubbo:service>` / `<dubbo:reference>` in `META-INF/spring/*.xml`, parsed by JDK `DocumentBuilder` (no new deps). XXE-hardened. Accepts both canonical `http://dubbo.apache.org/schema/dubbo` and legacy `http://code.alibabatech.com/schema/dubbo` namespaces. **Auto-join** the `ref_bean_name` → `impl_class_id` via spring_beans→classes lookup; falls back to direct FQN lookup when the ref is a class FQN. **Per-method config** via nested `<dubbo:method>` (timeout, retries, loadbalance, async, sent) into `dubbo_method_configs`. **Registry tracking** via `<dubbo:registry>` (id, address, port, parameters) into `dubbo_registries`. The same service can target multiple registries — UNIQUE key includes `registry_id`.
- **MyBatis XML** — `*Mapper.xml` files anywhere in the jar. JDK `DocumentBuilder`, DOCTYPE allowed (every mybatis mapper has one), XXE-hardened. Inlines `<include refid="..."/>`, elides dynamic tags (`<if>`, `<where>`, `<foreach>`, etc.) from the normalized view, keeps them in the raw `sqlTemplate`. Captures parameters from both `#{...}` and `${...}`. Also extracts `<resultMap>` shape (type FQN, extends, property/association/collection counts) into `mybatis_result_maps`.

### P2.7+ — Cross-jar / runtime

- **Cross-jar CHA** via `ExternalClassResolver` + `M2ClasspathResolver` (the call-graph tool can follow a method's invocations into your `~/.m2` jars)
- **Incremental indexing** — per-class SHA-256; unchanged classes are skipped on re-index (~30× speedup on warm DBs)
- **Cross-jar `@DubboReference` resolution** — V9 schema adds `dubbo_references.consumer_class_fqn`; the pass records the consumer class even when the field is in a dependency that wasn't indexed. The V9 migration rebuilds the UNIQUE index to include this column, otherwise two consumers in different classes would collide
- **Cross-file `<dubbo:service ref="X">`** — auto-resolves through `spring_beans` first, then direct FQN lookup in `classes`

### P3 — Polish

- **17 framework-aware MCP tools** (7 P1 + 9 P2 + 1 provider-binding in `find_mybatis_statements`)
- **MCP resources**: `jrdi://schema` (V1 schema) and `jrdi://stats` (live counts)
- **MCP resources subscribe/unsubscribe**: pub-sub bus broadcasts `notifications/resources/updated` when the index rebuilds (works for Claude Desktop / Cursor; OpenCode does not implement resources client-side, so this is a no-op for that client)
- **6 MCP prompts**: `find_callers`, `find_bean_wiring`, `find_path`, `find_dubbo_method_tuning`, `find_mybatis_query_shape`, `find_rpc_call_chain` — pre-canned templates that tell an LLM how to chain the tools for common workflows
- **PostgreSQL schema mirror** (`V1__init.pg.sql` … `V9`) + Testcontainers IT (`-Djrdi.pg.it=true`)
- **CLI `stats` subcommand**: human table + `--json` for the same fact counts
- **Examples**: `examples/petclinic-mini/` — 8-class sample that exercises every framework pass and reports the exact counts to expect
- **Standalone fat-jars** — `scripts/package.sh` builds `jrdi-{ver}-bin.{zip,tar.gz}` with both jars, checksums, README, LICENSE, NOTICE
- **GitHub Actions CI** — `mvn verify` on Ubuntu + macOS for every push / PR

### Schema migrations

| V | Change | Purpose |
|---|---|---|
| V1 | 14 tables | Core symbol model (repos, artifacts, files, classes, methods, fields, invokes, lambdas) |
| V2 | `classes.interfaces TEXT` | Interface hierarchy for cross-class Spring candidate match |
| V3 | UNIQUE indexes on 3 framework tables | Idempotent framework re-index |
| V4 | `dubbo_method_configs`, `mybatis_result_maps` | Per-method tuning + row-mapper shape |
| V5 | `spring_boot_autoconfigs` | Spring Boot `spring.factories` + `AutoConfiguration.imports` |
| V6 | `spring_autoconfig_conditions` | ASM-extracted `@Conditional*` gates |
| V7 | `dubbo_registries` + widens UNIQUE keys | Multi-registry fan-out |
| V8 | `mybatis_statements.provider_class` / `_method` | `@XxxProvider` runtime SQL binding |
| V9 | `dubbo_references.consumer_class_fqn` + UNIQUE rebuild | Cross-jar consumer resolution |

### Numbers

- 17 modules
- 32 test classes
- **161 tests, 0 failures, 0 errors, 1 skipped** (PostgresE2EIT, requires Testcontainers)
- Full reactor `mvn verify` finishes in ~15s on a laptop
- Indexing a 1,000-class project: ~5-10s on a warm SQLite DB
- CI: ~45s on macOS-latest, ~50s on ubuntu-latest (with Maven cache)

## License

[Apache License 2.0](https://github.com/sulaymanyf/jrdi/blob/master/LICENSE) — Copyright 2026 sulaymanyf.
See [NOTICE](https://github.com/sulaymanyf/jrdi/blob/master/NOTICE) for the list of third-party components bundled in the fat-jars.

# jrdi — Java RPC Dependency Intelligence

**An MCP server that exposes JVM project dependencies to LLM clients.**

jrdi extracts the dependency graph (classes, methods, fields, calls, Spring/Dubbo/MyBatis framework facts) from any JVM project, stores it in SQLite or PostgreSQL, and exposes **17 MCP tools + 2 resources + 6 prompt templates** to LLM clients (Claude Code, Cursor, OpenCode, Cline, etc.).

!!! tip "One-line mental model"
    jrdi is `static-analysis` + `MCP` + `LLM-readable facts`. It runs at build time, not runtime. It tells you what your code *looks like* — not what it *does* at the moment a request hits it.

## What it answers

| Question | Tool(s) |
|---|---|
| "How does this controller reach the service layer?" | `callers_of`, `find_path` |
| "Who will break if I change this DAO method?" | `callers_of` |
| "What beans does Spring see? Who injects whom?" | `find_spring_beans`, `find_spring_injects` |
| "What's the Dubbo call graph? Which methods are tuned (timeout/retries)?" | `find_dubbo_services`, `find_dubbo_references`, `find_dubbo_method_configs` |
| "What SQL does this MyBatis mapper run? N+1 risk? Row-mapper shape?" | `find_mybatis_statements`, `find_mybatis_result_maps` |
| "How many classes? Who is dead code?" | `index_status`, `list_issues` |

## What it does NOT do

- **No** Kotlin / Scala / Groovy support (Java 17+ source syntax only)
- **No** incremental `watch` mode (re-running `index` is per-class SHA-256 cached, ~30× faster on the second pass)
- **No** runtime profiling (no agents, no JVMTI; static bytecode + source only)
- **No** cross-jar deep CHA on arbitrary 3rd-party libraries (we walk the jars on your classpath, but we don't go chasing transitive deps into the maven central universe)
- **No** "AI summaries" — the LLM is the AI; we hand it the facts

## How to read this site

- **[Install](install.md)** — download a fat-jar or build from source
- **[Quickstart](quickstart.md)** — go from zero to indexed in 5 minutes
- **[MCP tools](mcp-tools.md)** — full reference for the 17 tools + JSON-RPC examples
- **[MCP clients](mcp-clients.md)** — how to wire jrdi into Claude Code, Cursor, OpenCode, Cline, Zed, etc.
- **[Architecture](architecture.md)** — internals: pipeline, storage schema V1–V9, framework passes, protocol
- **[Release notes](changelog.md)** — what's in 0.1.0-M1

# Install

jrdi is distributed as two standalone fat-jars (Java 21+, all dependencies bundled). Pick whichever you need:

| Jar | What you get |
|---|---|
| `jrdi-cli-0.1.0-M1.jar` | CLI + embedded MCP server (`jrdi serve`). Use this for local dev, indexing, one-off queries, and most LLM clients. |
| `jrdi-mcp-server-0.1.0-M1.jar` | Standalone MCP server. No CLI, no `index` / `query` subcommands. Use this if you only want to expose the MCP surface to a remote LLM client. |

## Download from GitHub Releases

```sh
VERSION=0.1.0-M1
BASE="https://github.com/sulaymanyf/jrdi/releases/download/$VERSION"

# The CLI fat-jar
curl -L -o jrdi-cli.jar "$BASE/jrdi-cli-$VERSION.jar"

# The standalone MCP server
curl -L -o jrdi-mcp.jar "$BASE/jrdi-mcp-server-$VERSION.jar"

# Verify checksums
curl -L -O "$BASE/jrdi-$VERSION-checksums.sha256"
shasum -a 256 --check jrdi-$VERSION-checksums.sha256
```

Or grab the pre-packed zip / tarball that contains both jars + README + LICENSE + NOTICE:

```sh
curl -L -O "https://github.com/sulaymanyf/jrdi/releases/download/0.1.0-M1/jrdi-0.1.0-M1-bin.zip"
unzip jrdi-0.1.0-M1-bin.zip
```

## Verify it works

```sh
$ java -jar jrdi-cli.jar --version
jrdi 0.1.0-M1

$ java -jar jrdi-cli.jar doctor
=== jrdi doctor ===
dialect:        sqlite
classes:        0
methods:        0
invokes:        0
issues:         0
uncertain reflections: 0
missing source: 0
```

## Run as a container

Pre-built images live on GitHub Container Registry. The image is **~270 MB** (Alpine + JRE 21 + both fat-jars + tini for graceful shutdown).

### Quick start (0.3.0-M1)

```sh
# 1. Self-diagnostics (no data needed, runs in <2s)
docker run --rm ghcr.io/sulaymanyf/jrdi:0.3.0-M1 doctor

# 2. Index the project + its direct deps (one shot, 0.3.0)
docker run --rm \
    -v /path/to/your/project:/data/src:ro \
    -v /path/to/your/project/.jrdi:/data/.jrdi \
    -v $HOME/.m2:/root/.m2:ro \
    ghcr.io/sulaymanyf/jrdi:0.3.0-M1 \
    init /data/src --depth 1

# 3. Start the MCP server with on-miss auto-index
docker run --rm -i \
    -v /path/to/your/project/.jrdi:/data/.jrdi \
    -v $HOME/.m2:/root/.m2:ro \
    ghcr.io/sulaymanyf/jrdi:0.3.0-M1 mcp
```

The `init` step reads your project's `pom.xml`, finds every
direct dependency, and ASM-extracts them from `~/.m2` into the
main index — so LLM queries can traverse cross-jar edges
without manual per-jar indexing. On-miss auto-indexing continues
at query time for any class not already in the index.

The data directory `/data/.jrdi` holds the SQLite DB, the
Lucene index, and the m2 cache — bind-mount it for persistence.

### `jrdi init` — what it does

`jrdi init <project-dir> [--depth N]` is the new (0.3.0)
one-shot setup:

- Reads `<project-dir>/pom.xml` (no `mvn` invocation)
- Resolves the dep graph to `--depth N` (default = 1 = direct deps)
- For each dep GAV, finds the jar in `~/.m2/repository` and
  ASM-extracts the class facts into the main `classes` /
  `methods` / `invokes` tables
- Records `classes.source_jar` so the LLM knows which classes
  came from your project vs a dep

```sh
$ jrdi init ~/projects/my-app --depth 1 --db sqlite:./jrdi.db
init: project my-app
init: resolved 47 dep(s) at depth=1
init: indexed 45 dep(s) into the main tables
init: 2 dep(s) skipped (jar not found in m2 roots)
```

For a Spring Boot 3 project with 47 direct deps, this takes
~3-5 seconds. Without `init`, the LLM would discover each dep
one-by-one via the on-miss hook — slower for a one-time setup,
but lets you start with zero setup cost.

### On-miss auto-indexing

With `--m2-cache-dir` passed to `serve`, the MCP server will
auto-extract any class that doesn't yet exist in the main
index when a query needs it:

```sh
# Docker
docker run --rm -i \
    -v $PWD/.jrdi:/data/.jrdi \
    -v $HOME/.m2:/root/.m2:ro \
    ghcr.io/sulaymanyf/jrdi:0.3.0-M1 mcp
```

The LLM can ask `find_path(from=com.acme.MyService, to=org.springframework.web.servlet.DispatcherServlet)`
and jrdi will:
1. Look up `com.acme.MyService` — found in main index
2. Look up `DispatcherServlet` — not in main index
3. Trigger lazy m2 extraction of `spring-webmvc-X.Y.Z.jar`
4. ASM-extract the class (and its invokes)
5. Insert into the main tables with `source_jar` set
6. Return the path

Subsequent queries hit the warm cache.

### Lazy cross-jar m2 resolution (still supported)

The old 0.2.0 behavior — `m2-warm` to pre-warm the cache — still
works, but it's not required for 0.3.0:

```sh
# Old way (still supported)
jrdi m2-warm ~/.m2/repository

# New way: just use init + serve
jrdi init . --depth 2
jrdi serve --m2-cache-dir ~/.m2/repository --stdio
```

### Available tags

| Tag | When it's pushed | Use it for |
|---|---|---|
| `0.1.0-M1` | every release tag | pinning a known version (production) |
| `0.1` | every release tag | floating major.minor |
| `latest` | only on stable (non-prerelease) tags | always the newest stable release |
| `edge` | every push to `master` | trying unreleased changes |

### Pin the image by digest (production)

```sh
DIGEST="sha256:..."   # grab from `docker pull --quiet` output
docker run --rm "${DIGEST}" doctor
```

### MCP client config

For **Claude Desktop** (`claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "jrdi": {
      "command": "docker",
      "args": [
        "run", "--rm", "-i",
        "-v", "/absolute/path/to/.jrdi:/data/.jrdi",
        "ghcr.io/sulaymanyf/jrdi:0.1.0-M1", "mcp"
      ]
    }
  }
}
```

For **Cursor** / **Cline** / **OpenCode** the same shape works — see [MCP clients](mcp-clients.md) for the file paths per client.

### Build the image locally

```sh
git clone https://github.com/sulaymanyf/jrdi.git
cd jrdi
docker build -t jrdi:dev .
docker run --rm jrdi:dev doctor
```

The build is multi-stage (Maven 3.9 + Temurin 21 on the way in, JRE 21 + Alpine on the way out) and uses BuildKit cache so a rebuild with no source changes finishes in <10s.

## Build from source

If you want the latest unreleased changes, or you're hacking on jrdi itself:

```sh
git clone https://github.com/sulaymanyf/jrdi.git
cd jrdi
JAVA_HOME=/path/to/jdk-21 mvn clean install -DskipTests
```

This produces the same two jars under `jrdi-cli/target/` and `jrdi-mcp-server/target/`.

## System requirements

For the **CLI / fat-jar** path:

- **Java 21+** (we use records, pattern matching, `Thread.ofVirtual()`). Test on Temurin / Liberica / Microsoft builds.
- **~150 MB heap** for medium projects (1000–5000 classes). Default 4 GB is generous; trim with `JAVA_OPTS=-Xmx2g` for tiny projects.
- **Maven 3.9+** only required for *building*, not running.
- **No** native libs, **no** system Python. The Testcontainers-based Postgres IT is opt-in.

For the **container** path:

- **Docker 20.10+** (any runtime that understands multi-stage builds; tested on Docker Desktop 4.x and OrbStack).
- **~270 MB disk** for the pulled image.
- **Bind-mount** at least the data dir (`/data/.jrdi`) to keep the index across container restarts.

## What's next?

- → [Quickstart](quickstart.md) — index your first project in 5 minutes
- → [MCP clients](mcp-clients.md) — wire jrdi into Claude Code / Cursor / OpenCode

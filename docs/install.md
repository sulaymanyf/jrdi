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

### Quick start

```sh
# Self-diagnostics (no data needed, runs in <2s)
docker run --rm ghcr.io/sulaymanyf/jrdi:0.2.0-M1 doctor

# Index a local jar — bind-mount the jar AND the data dir
docker run --rm \
    -v /path/to/jar:/data/source:ro \
    -v $PWD/.jrdi:/data/.jrdi \
    ghcr.io/sulaymanyf/jrdi:0.2.0-M1 index /data/source/my-app-1.0.0.jar

# MCP over stdio (for Claude Desktop / Cursor / OpenCode)
docker run --rm -i -v $PWD/.jrdi:/data/.jrdi ghcr.io/sulaymanyf/jrdi:0.2.0-M1 mcp

# MCP over HTTP/SSE
docker run --rm -p 7890:7890 -v $PWD/.jrdi:/data/.jrdi \
    ghcr.io/sulaymanyf/jrdi:0.2.0-M1 mcp-http 7890
```

The `mcp` and `mcp-http` shortcuts are thin wrappers around `jrdi-cli serve --stdio` / `serve --http <port>`. The data directory `/data/.jrdi` holds the SQLite DB, the Lucene index, and the M2 cache — bind-mount it for persistence.

### Lazy cross-jar m2 resolution (0.2.0+)

To enable cross-jar call-graph resolution into your `~/.m2`
dependencies, pass `--m2-cache-dir` to `serve`. The first query
that needs a cross-jar fact (e.g. `find_dubbo_services` for an
interface whose impl is in an unindexed jar) triggers a one-time
ASM extraction of that jar, cached to `m2_*` tables.

```sh
# CLI
jrdi serve --m2-cache-dir ~/.m2/repository --stdio

# Docker: bind-mount your .m2 + enable the flag
docker run --rm -i \
    -v $PWD/.jrdi:/data/.jrdi \
    -v $HOME/.m2:/root/.m2:ro \
    -e MAVEN_OPTS="" \
    ghcr.io/sulaymanyf/jrdi:0.2.0-M1 \
    mcp-warm ~/.m2/repository
# (the mcp-warm shortcut is a future convenience; for now, use
#  the entrypoint directly to pre-warm:)
docker run --rm \
    -v $PWD/.jrdi:/data/.jrdi \
    -v $HOME/.m2:/root/.m2:ro \
    ghcr.io/sulaymanyf/jrdi:0.2.0-M1 \
    m2-warm /root/.m2/repository
```

Pre-warming (the second form) is recommended after a clean
install: the first call to `find_dubbo_services` will then
return cross-jar facts instantly, no per-query ASM overhead.

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

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

## Build from source

If you want the latest unreleased changes, or you're hacking on jrdi itself:

```sh
git clone https://github.com/sulaymanyf/jrdi.git
cd jrdi
JAVA_HOME=/path/to/jdk-21 mvn clean install -DskipTests
```

This produces the same two jars under `jrdi-cli/target/` and `jrdi-mcp-server/target/`.

## System requirements

- **Java 21+** (we use records, pattern matching, `Thread.ofVirtual()`). Test on Temurin / Liberica / Microsoft builds.
- **~150 MB heap** for medium projects (1000–5000 classes). Default 4 GB is generous; trim with `JAVA_OPTS=-Xmx2g` for tiny projects.
- **Maven 3.9+** only required for *building*, not running.
- **No** native libs, **no** system Python, **no** Docker (for the CLI). The Testcontainers-based Postgres IT is opt-in.

## What's next?

- → [Quickstart](quickstart.md) — index your first project in 5 minutes
- → [MCP clients](mcp-clients.md) — wire jrdi into Claude Code / Cursor / OpenCode

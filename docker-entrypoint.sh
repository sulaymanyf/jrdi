#!/bin/sh
#
# Docker entrypoint for the jrdi image. Dispatches the right jar
# based on the first argument:
#
#   doctor        → `jrdi-cli doctor`
#   index         → `jrdi-cli index ...`
#   query         → `jrdi-cli query ...`
#   stats         → `jrdi-cli stats ...`
#   rebuild       → `jrdi-cli rebuild ...`
#   serve         → `jrdi-cli serve ...` (covers both --stdio and --http)
#   shell         → drop into /bin/sh for debugging
#   <default>     → if no arg or `CMD=doctor` from Dockerfile, run doctor
#
# All jrdi data files (the SQLite index DB, the Lucene index, the
# M2 cache) live under ${JRDI_DATA} (default /data/.jrdi). The
# directory is created on first run with sane perms.

set -eu

JRDI_HOME="${JRDI_HOME:-/opt/jrdi}"
JRDI_DATA="${JRDI_DATA:-/data/.jrdi}"
CLI_JAR="${JRDI_HOME}/jars/jrdi-cli.jar"
MCP_JAR="${JRDI_HOME}/jars/jrdi-mcp-server.jar"
JAVA="${JAVA_HOME:-/opt/java/openjdk}/bin/java"
[ -x "$JAVA" ] || JAVA="$(command -v java)"

# Ensure data dir exists + writeable. The user may have bind-mounted
# an empty dir or a populated one; both are fine.
mkdir -p "${JRDI_DATA}"
chmod 0755 "${JRDI_DATA}"

# Run the jar with the JVM flags the user set (Xmx, encoding, etc.)
# plus a couple of sane defaults for running inside a container.
run_jar() {
    jar="$1"; shift
    exec "$JAVA" $JAVA_OPTS \
        -Djrdi.data.dir="${JRDI_DATA}" \
        -Djava.util.logback.configurationFile="${JRDI_HOME}/logback.xml" \
        -jar "$jar" "$@"
}

# Pick the right subcommand.
cmd="${1:-doctor}"

case "$cmd" in
    doctor|index|query|stats|rebuild|serve)
        # All of these go through the CLI fat-jar.
        run_jar "$CLI_JAR" "$@"
        ;;
    mcp)
        # `mcp` is a shortcut for `serve --stdio` (the common case
        # for Claude Desktop / Cursor).
        shift
        run_jar "$CLI_JAR" serve --stdio "$@"
        ;;
    mcp-http)
        # `mcp-http <port>` is a shortcut for `serve --http <port>`.
        shift
        port="${1:-7890}"
        run_jar "$CLI_JAR" serve --http "$port"
        ;;
    shell|sh|bash)
        # Drop into a shell for debugging. The user shouldn't normally
        # need this; the entrypoint logs a hint when invoked.
        echo "jrdi shell — try: jrdi doctor / jrdi index ...args / jrdi mcp"
        exec /bin/sh
        ;;
    -h|--help|help)
        cat <<EOF
jrdi ${JRDI_VERSION:-dev} container entrypoint.

Usage: docker run [docker-opts] jrdi:VERSION <subcommand> [args]

Subcommands:
  doctor                       Self-diagnostics (DB, fact counts)
  index <jar-or-gav>           Index a local jar or Maven GAV
  query ...                    One-off symbol / caller / path query
  stats [--json]               Fact counts
  rebuild                      Drop and re-apply the schema
  serve [--stdio|--http PORT]  Run the MCP server (stdio or HTTP/SSE)
  mcp                          Shortcut: serve --stdio (LLM clients)
  mcp-http [PORT]              Shortcut: serve --http [PORT] (default 7890)
  shell                        Drop into /bin/sh for debugging
  help                         This message

Data directory: ${JRDI_DATA}
Override with:  docker run -e JRDI_DATA=/path ...

Examples:
  docker run --rm -i -v \$PWD/.jrdi:/data/.jrdi ghcr.io/sulaymanyf/jrdi:VERSION mcp
  docker run --rm -p 7890:7890 -v \$PWD/.jrdi:/data/.jrdi \\
      ghcr.io/sulaymanyf/jrdi:VERSION mcp-http 7890
  docker run --rm -v \$PWD/.jrdi:/data/.jrdi \\
      ghcr.io/sulaymanyf/jrdi:VERSION index /data/source/my-app-1.0.0.jar
EOF
        ;;
    *)
        # Unknown subcommand — assume the user passed a raw `jrdi-cli`
        # flag set and just forward. Most useful for `java -jar ...`
        # debug cases: `docker run --rm --entrypoint /bin/sh jrdi:dev`.
        run_jar "$CLI_JAR" "$@"
        ;;
esac

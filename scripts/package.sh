#!/usr/bin/env bash
# Package the two jrdi fat-jars for distribution.
#
# Output:
#   dist/jrdi-0.1.0-M1-bin.zip        — CLI + MCP server + checksums + license + README
#   dist/jrdi-0.1.0-M1-bin.tar.gz     — same, gzipped tarball for *nix users
#   dist/jrdi-cli-0.1.0-M1.jar        — the CLI fat-jar (also uploaded separately)
#   dist/jrdi-mcp-server-0.1.0-M1.jar — the standalone MCP server fat-jar
#   dist/*.sha256                     — SHA-256 checksums for every jar
#
# Usage:  scripts/package.sh [VERSION]
#   VERSION defaults to 0.1.0-M1.

set -euo pipefail

VERSION="${1:-0.1.0-M1}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"
mkdir -p "$DIST"

CLI_JAR="$ROOT/jrdi-cli/target/jrdi-cli-$VERSION.jar"
MCP_JAR="$ROOT/jrdi-mcp-server/target/jrdi-mcp-server-$VERSION.jar"

if [ ! -f "$CLI_JAR" ] || [ ! -f "$MCP_JAR" ]; then
    echo "FATAL: fat-jars not found. Run 'mvn -pl jrdi-cli,jrdi-mcp-server -am package -DskipTests' first." >&2
    exit 1
fi

# Copy with stable names so download URLs are immutable per release.
cp "$CLI_JAR"  "$DIST/jrdi-cli-$VERSION.jar"
cp "$MCP_JAR"  "$DIST/jrdi-mcp-server-$VERSION.jar"

# SHA-256 checksums (one file per jar; same dir as the jars).
( cd "$DIST" && shasum -a 256 "jrdi-cli-$VERSION.jar" "jrdi-mcp-server-$VERSION.jar" > "jrdi-$VERSION-checksums.sha256" )

# Pack a self-contained release zip: jars + checksums + README + LICENSE.
# Use a staging dir so paths inside the archive are basenames only
# (no /Users/yefan/... leakage).
STAGE="$(mktemp -d)"
trap "rm -rf '$STAGE'" EXIT

cp "$DIST/jrdi-cli-$VERSION.jar"        "$STAGE/"
cp "$DIST/jrdi-mcp-server-$VERSION.jar"  "$STAGE/"
cp "$DIST/jrdi-$VERSION-checksums.sha256" "$STAGE/"
[ -f "$ROOT/LICENSE" ] && cp "$ROOT/LICENSE" "$STAGE/"
[ -f "$ROOT/NOTICE"  ] && cp "$ROOT/NOTICE"  "$STAGE/"
cp "$ROOT/README.md"                      "$STAGE/"

( cd "$STAGE" && zip -q "$DIST/jrdi-$VERSION-bin.zip" * )
( cd "$STAGE" && tar czf "$DIST/jrdi-$VERSION-bin.tar.gz" . )

# Final summary
echo
echo "Release artifacts written to $DIST:"
ls -lh "$DIST" | sed 's/^/  /'
echo
echo "SHA-256 checksums:"
cat "$DIST/jrdi-$VERSION-checksums.sha256" | sed 's/^/  /'

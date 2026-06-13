# syntax=docker/dockerfile:1.7
#
# jrdi — Java RPC Dependency Intelligence
# Multi-stage Dockerfile. Build once, ship a ~250 MB image that
# contains both fat-jars and a slim JRE 21.
#
# Build:
#   docker build -t jrdi:dev .
#
# Run (CLI one-off):
#   docker run --rm -v $PWD:/data jrdi:dev doctor
#   docker run --rm -v $PWD:/data jrdi:dev index /data/my-app.jar
#
# Run (MCP stdio — for Claude Desktop / Cursor / etc.):
#   docker run --rm -i -v $PWD/.jrdi:/data/.jrdi jrdi:dev serve --stdio
#
# Run (MCP HTTP):
#   docker run --rm -p 7890:7890 -v $PWD/.jrdi:/data/.jrdi jrdi:dev serve --http 7890
#
# The entrypoint auto-detects --http / --stdio and binds to the
# right interface. Index DB lives under /data/.jrdi/ by default.

# ─── Stage 1: build with Maven + JDK 21 ────────────────────────────
#
# The official maven:3.9 image bundles Temurin JDK 21, so we don't
# have to apt-get install maven on top of a JDK base — saves ~50 MB
# in the build context and avoids version drift between Maven and
# the JDK.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Cache the dependency layer first (these rarely change).
COPY pom.xml ./
COPY jrdi-bom/pom.xml jrdi-bom/
COPY jrdi-core/pom.xml jrdi-core/
COPY jrdi-storage/pom.xml jrdi-storage/
COPY jrdi-resolver/pom.xml jrdi-resolver/
COPY jrdi-classgraph/pom.xml jrdi-classgraph/
COPY jrdi-bytecode/pom.xml jrdi-bytecode/
COPY jrdi-source/pom.xml jrdi-source/
COPY jrdi-decompile/pom.xml jrdi-decompile/
COPY jrdi-callgraph/pom.xml jrdi-callgraph/
COPY jrdi-spring/pom.xml jrdi-spring/
COPY jrdi-dubbo/pom.xml jrdi-dubbo/
COPY jrdi-mybatis/pom.xml jrdi-mybatis/
COPY jrdi-pipeline/pom.xml jrdi-pipeline/
COPY jrdi-search/pom.xml jrdi-search/
COPY jrdi-mcp-server/pom.xml jrdi-mcp-server/
COPY jrdi-cli/pom.xml jrdi-cli/
COPY jrdi-it/pom.xml jrdi-it/
RUN mvn -B -T 1C -q -DskipTests \
        -pl jrdi-bom,jrdi-core,jrdi-storage,jrdi-resolver,jrdi-classgraph,jrdi-bytecode,jrdi-source,jrdi-decompile,jrdi-callgraph,jrdi-spring,jrdi-dubbo,jrdi-mybatis,jrdi-pipeline,jrdi-search,jrdi-mcp-server,jrdi-cli -am \
        install

# Now copy the actual source and rebuild the two modules whose jars
# we ship in the runtime image.
COPY . .
RUN mvn -B -T 1C -DskipTests \
        -pl jrdi-cli,jrdi-mcp-server -am \
        package

# ─── Stage 2: runtime with slim JRE 21 + jars ──────────────────────
#
# We start from eclipse-temurin:21-jre (not the JDK). Final image
# footprint is ~250 MB instead of ~700 MB for the full JDK.
#
# The 1.x image series uses `ubuntu:jammy` under the hood, so we
# could `apt-get install -y` utilities, but we deliberately keep the
# runtime as minimal as possible: only `ca-certificates` (for HTTPS
# to ~/.m2 / Maven Central when resolving GAVs) and `tini` (PID-1
# signal forwarding for graceful shutdown of the MCP server).

FROM eclipse-temurin:21-jre-alpine AS runtime

ARG JRDI_VERSION=0.1.0-M1
ENV JRDI_VERSION=${JRDI_VERSION}
ENV JRDI_HOME=/opt/jrdi
ENV JRDI_DATA=/data/.jrdi
ENV JAVA_OPTS="-Xmx2g -Dfile.encoding=UTF-8"

# ca-certificates: HTTPS to Maven Central from inside the container.
# tini: PID-1 that reaps zombies and forwards SIGTERM, so `docker stop`
# cleanly tears down the MCP server. Both ship in the Alpine repos.
RUN apk add --no-cache ca-certificates tini

# Layout:
#   /opt/jrdi/bin/jrdi            — entrypoint (this image's "jrdi" binary)
#   /opt/jrdi/jars/jrdi-cli.jar   — CLI + embedded MCP server
#   /opt/jrdi/jars/jrdi-mcp-server.jar — standalone MCP server (optional)
#   /opt/jrdi/LICENSE             — Apache 2.0
#   /opt/jrdi/NOTICE              — third-party attribution
#   /opt/jrdi/README.md           — pointer to the docs site
#   /data/.jrdi/                  — index DB lives here (mount point)
#   /data/source/                 — mount point for source jars
COPY --from=build /src/jrdi-cli/target/jrdi-cli-*.jar                ${JRDI_HOME}/jars/jrdi-cli.jar
COPY --from=build /src/jrdi-mcp-server/target/jrdi-mcp-server-*.jar  ${JRDI_HOME}/jars/jrdi-mcp-server.jar
COPY --from=build /src/LICENSE                                       ${JRDI_HOME}/LICENSE
COPY --from=build /src/NOTICE                                        ${JRDI_HOME}/NOTICE
COPY --from=build /src/README.md                                     ${JRDI_HOME}/README.md
COPY docker-entrypoint.sh                                            ${JRDI_HOME}/bin/jrdi
RUN chmod +x ${JRDI_HOME}/bin/jrdi

# Persistent index data. The user is expected to bind-mount this:
#   docker run -v $PWD/.jrdi:/data/.jrdi ...
VOLUME ["/data/.jrdi"]
WORKDIR /data

# Health check: doctor runs in <2s, exits 0 if the DB is reachable.
# We deliberately don't run an MCP roundtrip here (would require JSON).
HEALTHCHECK --interval=60s --timeout=10s --start-period=5s --retries=3 \
    CMD ${JRDI_HOME}/bin/jrdi doctor || exit 1

# Default: run the CLI. Override with:
#   docker run jrdi:dev serve --stdio
#   docker run jrdi:dev serve --http 7890
#   docker run jrdi:dev doctor
#   docker run jrdi:dev index /data/source/my-app.jar
ENTRYPOINT ["/sbin/tini", "--", "/opt/jrdi/bin/jrdi"]
CMD ["doctor"]

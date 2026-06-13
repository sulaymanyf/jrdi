package io.jrdi.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;

/**
 * Root command. Routes to the six sub-commands: {@code index}, {@code serve},
 * {@code query}, {@code rebuild}, {@code doctor}, {@code stats}.
 */
@Command(
        name = "jrdi",
        mixinStandardHelpOptions = true,
        version = "jrdi 0.1.0-M1",
        description = "Java RPC Dependency Intelligence — LLM-queryable fact graph for JVM projects.",
        subcommands = {
                JrdiCommand.IndexCmd.class,
                JrdiCommand.ServeCmd.class,
                JrdiCommand.QueryCmd.class,
                JrdiCommand.RebuildCmd.class,
                JrdiCommand.DoctorCmd.class,
                JrdiCommand.StatsCmd.class
        }
)
public final class JrdiCommand implements Runnable {

    @Override
    public void run() {
        // No-arg invocation prints help
        CommandLine.usage(this, System.out);
    }

    @Command(name = "index", description = "Index a local jar (or a GAV) into the jrdi DB")
    public static class IndexCmd implements Runnable {
        @Parameters(arity = "0..1", description = "Path to a jar OR a GAV like org.acme:lib:1.0.0")
        String target;

        @Option(names = {"--db"}, defaultValue = "sqlite:./jrdi.db", description = "JDBC URL (default: ${DEFAULT-VALUE})")
        String dbUrl;

        @Option(names = {"--repo-id"}, defaultValue = "local", description = "Repo identifier (default: ${DEFAULT-VALUE})")
        String repoId;

        @Option(names = {"--with-sources"}, description = "Try to fetch sources.jar (or use <jar>-sources.jar sibling)")
        boolean withSources;

        @Override
        public void run() {
            int code = CliWiring.runIndex(target, dbUrl, repoId, withSources);
            if (code != 0) System.exit(code);
        }
    }

    @Command(name = "serve", description = "Start the MCP server (stdio or HTTP/SSE)")
    public static class ServeCmd implements Runnable {
        @Option(names = {"--stdio"}, description = "Serve over stdio (default for IDE clients)")
        boolean stdio;

        @Option(names = {"--http"}, description = "Serve over HTTP/SSE on the given port")
        Integer httpPort;

        @Option(names = {"--db"}, defaultValue = "sqlite:./jrdi.db", description = "JDBC URL")
        String dbUrl;

        @Override
        public void run() {
            int code = CliWiring.runServe(dbUrl, stdio ? null : httpPort);
            if (code != 0) System.exit(code);
        }
    }

    @Command(name = "query", description = "Run a one-off query (class / method / callers / path)")
    public static class QueryCmd implements Runnable {
        @Parameters(arity = "1", description = "Class FQN or method ref like 'com.acme.Foo#bar()V'")
        String target;

        @Option(names = {"--db"}, defaultValue = "sqlite:./jrdi.db", description = "JDBC URL")
        String dbUrl;

        @Option(names = {"--from"}, description = "From method (for find-path)")
        String from;

        @Option(names = {"--to"}, description = "To method (for find-path)")
        String to;

        @Option(names = {"--depth"}, defaultValue = "8", description = "Max path depth")
        int depth;

        @Option(names = {"--include-reflect"}, description = "Include REFLECT-confidence edges")
        boolean includeReflect;

        @Override
        public void run() {
            int code = CliWiring.runQuery(target, dbUrl, from, to, depth, includeReflect);
            if (code != 0) System.exit(code);
        }
    }

    @Command(name = "rebuild", description = "Drop the jrdi schema and re-apply migrations")
    public static class RebuildCmd implements Runnable {
        @Option(names = {"--db"}, defaultValue = "sqlite:./jrdi.db", description = "JDBC URL")
        String dbUrl;

        @Override
        public void run() {
            int code = CliWiring.runRebuild(dbUrl);
            if (code != 0) System.exit(code);
        }
    }

    @Command(name = "doctor", description = "Self-diagnostics: DB connectivity, fact counts, issue types")
    public static class DoctorCmd implements Runnable {
        @Option(names = {"--db"}, defaultValue = "sqlite:./jrdi.db", description = "JDBC URL")
        String dbUrl;

        @Override
        public void run() {
            int code = CliWiring.runDoctor(dbUrl);
            if (code != 0) System.exit(code);
        }
    }

    @Command(name = "stats", description = "Detailed fact counts: schema version, per-table rows, framework breakdown")
    public static class StatsCmd implements Runnable {
        @Option(names = {"--db"}, defaultValue = "sqlite:./jrdi.db", description = "JDBC URL")
        String dbUrl;

        @Option(names = {"--json"}, description = "Emit a single-line JSON instead of the human table")
        boolean json;

        @Override
        public void run() {
            int code = CliWiring.runStats(dbUrl, json);
            if (code != 0) System.exit(code);
        }
    }

    public static void main(String[] args) {
        int code = new CommandLine(new JrdiCommand()).execute(args);
        System.exit(code);
    }

    static String normalizeDbUrl(String url) {
        if (url == null) return "jdbc:sqlite::memory:";
        if (url.startsWith("jdbc:")) return url;
        if (url.startsWith("sqlite:")) return "jdbc:" + url;
        if (url.startsWith("postgres:")) return "jdbc:" + url;
        if (url.equals(":memory:")) return "jdbc:sqlite::memory:";
        return url;
    }
}

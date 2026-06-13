/*
 * Copyright 2026 sulaymanyf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */package io.jrdi.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Minimal JSON-RPC 2.0 MCP server. Two transports:
 * <ul>
 *   <li>{@code stdio} — newline-delimited JSON over stdin/stdout (for Claude Desktop / Cursor)</li>
 *   <li>{@code http-sse} — POST requests with JSON body, 200 OK with JSON response (P1 simplified
 *       transport; SSE upgrade deferred to P2)</li>
 * </ul>
 *
 * <p>The server implements the MCP "initialize / tools/list / tools/call" trio. Resources
 * and prompts are stubbed for P1; P2 adds them.
 */
public final class JrdiMcpServer {

    private static final Logger LOG = LoggerFactory.getLogger(JrdiMcpServer.class);

    private final JrdiMcpService service;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, ToolHandler> tools = new HashMap<>();
    /** Per-server pub-sub for resources. Each transport (stdio, HTTP) registers
     * a notifier that knows how to push a {@code resources/updated} notification
     * to its connected client(s). */
    private final ResourceBus resourceBus = new ResourceBus();
    private boolean initialized = false;

    public JrdiMcpServer(JrdiMcpService service) {
        this.service = service;
        registerTools();
    }

    private void registerTools() {
        tools.put("index_status", args -> service.toolIndexStatus());
        tools.put("find_symbol", service::toolFindSymbol);
        tools.put("describe_method", service::toolDescribeMethod);
        tools.put("callers_of", service::toolCallersOf);
        tools.put("callees_of", service::toolCalleesOf);
        tools.put("find_path", service::toolFindPath);
        tools.put("list_issues", service::toolListIssues);
        // P2 framework-aware tools
        tools.put("find_spring_beans", service::toolFindSpringBeans);
        tools.put("find_spring_injects", service::toolFindSpringInjects);
        tools.put("find_spring_autoconfigs", service::toolFindSpringAutoconfigs);
        tools.put("find_spring_autoconfig_conditions", service::toolFindSpringAutoconfigConditions);
        tools.put("find_dubbo_services", service::toolFindDubboServices);
        tools.put("find_dubbo_references", service::toolFindDubboReferences);
        tools.put("find_dubbo_method_configs", service::toolFindDubboMethodConfigs);
        tools.put("find_dubbo_registries", service::toolFindDubboRegistries);
        tools.put("find_mybatis_statements", service::toolFindMybatisStatements);
        tools.put("find_mybatis_result_maps", service::toolFindMybatisResultMaps);
    }

    // ─── P3.4 resources & prompts ────────────────────────────────────────────

    /** Two static resources: the V1 schema and the current fact counts. */
    private static final List<NamedResource> RESOURCES = List.of(
            new NamedResource("jrdi://schema", "jrdi V1 schema (tables + columns)",
                    "schema"),
            new NamedResource("jrdi://stats", "current fact counts",
                    "stats"));

    /** Set of valid resource URIs (used to validate subscribe requests). */
    private static final java.util.Set<String> RESOURCE_URIS = java.util.Set.of(
            "jrdi://schema", "jrdi://stats");

    /** Per-request transport-specific subscriber. Set by
     * {@link #serveStdio} (or future HTTP transport) before reading requests,
     * so that the {@code resources/subscribe} handler can register a notifier
     * that knows where to write the {@code resources/updated} notification. */
    private final ThreadLocal<java.util.function.BiConsumer<String, String>> transportNotifier
            = new ThreadLocal<>();

    /** Three pre-canned prompt templates for common debugging workflows. */
    private static final List<NamedPrompt> PROMPTS = List.of(
            new NamedPrompt("find_callers",
                    "Find all callers of a method",
                    List.of(
                            new PromptArg("owner", "FQN of the declaring class", true),
                            new PromptArg("name", "Method name", true),
                            new PromptArg("desc", "Method descriptor (slashed)", true))),
            new NamedPrompt("find_bean_wiring",
                    "Trace Spring wiring for a bean's @Autowired sites",
                    List.of(
                            new PromptArg("class", "FQN of the class to inspect", true))),
            new NamedPrompt("find_path",
                    "Find the shortest call path between two methods",
                    List.of(
                            new PromptArg("fromOwner", "From class FQN", true),
                            new PromptArg("fromName", "From method name", true),
                            new PromptArg("fromDesc", "From method descriptor", true),
                            new PromptArg("toOwner", "To class FQN", true),
                            new PromptArg("toName", "To method name", true),
                            new PromptArg("toDesc", "To method descriptor", true))),
            new NamedPrompt("find_dubbo_method_tuning",
                    "Audit a Dubbo service's per-method timeout / retry / loadbalance config",
                    List.of(
                            new PromptArg("interface", "FQN of the Dubbo service interface", true))),
            new NamedPrompt("find_mybatis_query_shape",
                    "For each SQL statement in a Mapper, describe what it returns (row shape)",
                    List.of(
                            new PromptArg("namespace", "FQN of the MyBatis Mapper interface", true))),
            new NamedPrompt("find_rpc_call_chain",
                    "Map a Dubbo interface's full call graph: providers, consumers, and per-method tuning",
                    List.of(
                            new PromptArg("interface", "FQN of the Dubbo service interface", true))));

    /** Handle one JSON-RPC request and return a response (or null for notifications). */
    public JsonNode handleRequest(JsonNode req) {
        if (!req.has("jsonrpc")) return null;
        JsonNode id = req.get("id");
        if (id == null) {
            // notification: no id, no response
            return null;
        }
        String method = req.path("method").asText("");
        JsonNode params = req.path("params");
        try {
            ObjectNode result = null;
            ObjectNode errorResponse = null;
            switch (method) {
                case "initialize" -> result = onInitialize(params);
                case "tools/list" -> result = onToolsList();
                case "resources/list" -> result = onResourcesList();
                case "resources/read" -> {
                    String uri = params.path("uri").asText("");
                    String text = readResource(uri);
                    if (text == null) {
                        errorResponse = error(id, -32602, "unknown resource: " + uri);
                    } else {
                        result = onResourceRead(uri, text);
                    }
                }
                case "resources/subscribe" -> {
                    String uri = params.path("uri").asText("");
                    if (!RESOURCE_URIS.contains(uri)) {
                        errorResponse = error(id, -32602, "unknown resource: " + uri);
                    } else {
                        ResourceBus.Subscription sub = registerSubscription(uri, req);
                        result = mapper.createObjectNode();  // empty result on success
                        // The subscription token is a hint for the client; the
                        // server's bus is the source of truth.
                        if (sub != null) result.put("subscriptionId", sub.hashCode());
                    }
                }
                case "resources/unsubscribe" -> {
                    // The MCP spec lets the client send either a URI or a
                    // subscription token. We track by URI and notifier identity;
                    // since we don't keep a token map per request, the
                    // pragmatic approach is to remove the FIRST subscriber for
                    // the URI (which is almost always the right one in
                    // single-client scenarios). For multi-subscriber cases the
                    // transport-close path also drops everything.
                    String uri = params.path("uri").asText("");
                    int removed = removeFirstSubscriber(uri);
                    result = mapper.createObjectNode();
                    result.put("removed", removed);
                }
                case "prompts/list" -> result = onPromptsList();
                case "prompts/get" -> {
                    String name = params.path("name").asText("");
                    result = onPromptGet(name, params.path("arguments"));
                }
                case "tools/call" -> {
                    CallResult cr = onToolsCall(req);
                    if (cr.isError()) {
                        errorResponse = error(id, cr.code(), cr.message());
                    } else {
                        result = cr.value();
                    }
                }
                default -> errorResponse = error(id, -32601, "method not found: " + method);
            }
            if (errorResponse != null) return errorResponse;
            ObjectNode resp = mapper.createObjectNode();
            resp.put("jsonrpc", "2.0");
            resp.set("id", id);
            resp.set("result", result);
            return resp;
        } catch (Exception e) {
            LOG.error("handler failed for {}", method, e);
            return error(id, -32603, e.getMessage());
        }
    }

    private ObjectNode onInitialize(JsonNode params) {
        initialized = true;
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        ObjectNode caps = mapper.createObjectNode();
        caps.set("tools", mapper.createObjectNode());
        caps.set("resources", mapper.createObjectNode());
        // Tell the client we support resource subscription (the MCP
        // "subscribe" capability flag). The client can then issue
        // resources/subscribe and receive resources/updated notifications.
        ObjectNode resourcesCap = mapper.createObjectNode();
        resourcesCap.put("subscribe", true);
        caps.set("resources", resourcesCap);
        caps.set("prompts", mapper.createObjectNode());
        result.set("capabilities", caps);
        ObjectNode server = mapper.createObjectNode();
        server.put("name", "jrdi");
        server.put("version", "0.1.0-M1");
        result.set("serverInfo", server);
        return result;
    }

    private ObjectNode onToolsList() {
        ObjectNode result = mapper.createObjectNode();
        var arr = mapper.createArrayNode();
        for (String name : tools.keySet()) {
            ObjectNode tool = mapper.createObjectNode();
            tool.put("name", name);
            tool.put("description", descriptionFor(name));
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", mapper.createObjectNode());
            tool.set("inputSchema", schema);
            arr.add(tool);
        }
        result.set("tools", arr);
        return result;
    }

    private String descriptionFor(String name) {
        return switch (name) {
            case "index_status" -> "Return current DB fact counts.";
            case "find_symbol" -> "Find a class by FQN.";
            case "describe_method" -> "Return method details (lines, signature).";
            case "callers_of" -> "List the call sites that invoke a method.";
            case "callees_of" -> "List the methods called by a method (callerMethodId).";
            case "find_path" -> "BFS shortest path between two methods (maxDepth).";
            case "list_issues" -> "List stored indexing issues by kind.";
            default -> "";
        };
    }

    // ─── P3.4 resource / prompt handlers ──────────────────────────────────────

    /**
     * Register a subscription for the given URI on the current request's
     * transport. The notifier is taken from the per-request
     * {@link #transportNotifier}; if none is set (e.g. when handleRequest
     * is called outside serveStdio/serveHttp), the subscription is a no-op
     * and the caller still gets a successful response — they just won't
     * receive notifications until they reconnect.
     *
     * <p>Return value: the opaque subscription handle, or null if no
     * transport notifier was set up.
     */
    private ResourceBus.Subscription registerSubscription(String uri, JsonNode req) {
        var notifier = transportNotifier.get();
        if (notifier == null) {
            LOG.debug("subscribe({}) requested without an active transport", uri);
            return null;
        }
        // Wrap the per-request notifier as a Consumer<String> that ignores
        // the second arg (we only need the URI in the JSON-RPC notification).
        return resourceBus.subscribe(uri, actualUri -> notifier.accept(actualUri, "resources/updated"));
    }

    /**
     * Publish a "resource changed" notification. Called by the pipeline
     * (via a static accessor on the running server) or by
     * {@code serveStdio}'s poll loop. The MCP spec is silent on whether
     * the server should also send the new value of the resource, so we
     * just notify — the client re-reads if it wants the new value.
     */
    public int publishResourceUpdate(String uri) {
        return resourceBus.publish(uri);
    }

    /** Internal accessor for tests. */
    ResourceBus bus() {
        return resourceBus;
    }

    /**
     * Remove the first subscriber for the given URI. Used by the
     * {@code resources/unsubscribe} handler. Returns 1 if removed, 0 otherwise.
     */
    private int removeFirstSubscriber(String uri) {
        return resourceBus.removeFirstSubscriber(uri);
    }

    private ObjectNode onResourcesList() {
        ObjectNode result = mapper.createObjectNode();
        var arr = mapper.createArrayNode();
        for (var r : RESOURCES) {
            ObjectNode node = mapper.createObjectNode();
            node.put("uri", r.uri());
            node.put("name", r.uri());
            node.put("description", r.description());
            node.put("mimeType", "application/json");
            arr.add(node);
        }
        result.set("resources", arr);
        return result;
    }

    private ObjectNode onResourceRead(String uri, String text) {
        ObjectNode result = mapper.createObjectNode();
        var contents = mapper.createArrayNode();
        ObjectNode content = mapper.createObjectNode();
        content.put("uri", uri);
        content.put("mimeType", "application/json");
        content.put("text", text);
        contents.add(content);
        result.set("contents", contents);
        // Reads of jrdi://stats also fire a notification so any concurrent
        // subscribers see the change immediately. This is a small optimisation:
        // the spec says clients should subscribe and re-read on notification,
        // but if they happen to be reading anyway, no harm done.
        if ("jrdi://stats".equals(uri)) {
            resourceBus.publish(uri);
        }
        return result;
    }

    private ObjectNode onPromptsList() {
        ObjectNode result = mapper.createObjectNode();
        var arr = mapper.createArrayNode();
        for (var p : PROMPTS) {
            ObjectNode node = mapper.createObjectNode();
            node.put("name", p.name());
            node.put("description", p.description());
            var argsArr = mapper.createArrayNode();
            for (var a : p.arguments()) {
                ObjectNode arg = mapper.createObjectNode();
                arg.put("name", a.name());
                arg.put("description", a.description());
                arg.put("required", a.required());
                argsArr.add(arg);
            }
            node.set("arguments", argsArr);
            arr.add(node);
        }
        result.set("prompts", arr);
        return result;
    }

    private ObjectNode onPromptGet(String name, JsonNode arguments) {
        ObjectNode result = mapper.createObjectNode();
        result.put("name", name);
        String desc = "jrdi prompt: " + name;
        result.put("description", desc);
        // Two messages: a "user" role with the prompt text. LLM clients will
        // typically feed this to their chat model to elicit a plan that uses
        // the jrdi tools.
        var messages = mapper.createArrayNode();
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "user");
        ObjectNode content = mapper.createObjectNode();
        content.put("type", "text");
        content.put("text", renderPromptText(name, arguments));
        msg.set("content", content);
        messages.add(msg);
        result.set("messages", messages);
        return result;
    }

    private String renderPromptText(String name, JsonNode args) {
        return switch (name) {
            case "find_callers" -> """
                    Find all callers of the method
                    %s#%s%s

                    Use the jrdi MCP tool `callers_of` with these arguments:
                    {"owner": "%s", "name": "%s", "desc": "%s"}

                    Summarize the call sites by class + line number and flag any
                    that are in test code, lambdas, or have UNCERTAIN confidence.
                    """.formatted(
                            text(args, "owner", ""), text(args, "name", ""), text(args, "desc", ""),
                            text(args, "owner", ""), text(args, "name", ""), text(args, "desc", ""));
            case "find_bean_wiring" -> """
                    Trace Spring wiring for the bean `%s`.

                    Steps:
                    1. Call `find_spring_beans` with `{"type": "%s"}` to confirm
                       the bean is registered.
                    2. Call `find_spring_injects` with `{"class": "%s"}` to get
                       the @Autowired sites on this class.
                    3. For each @Autowired site, call `find_spring_beans` again
                       to identify candidate beans.
                    4. Report whether each inject is resolved (CERTAIN), fuzzy
                       (PROBABLE), or unresolved (UNCERTAIN).
                    """.formatted(text(args, "class", ""), text(args, "class", ""), text(args, "class", ""));
            case "find_path" -> """
                    Find the shortest call path between
                      FROM: %s#%s%s
                      TO:   %s#%s%s

                    Use the jrdi MCP tool `find_path` with these arguments:
                    {"fromOwner": "%s", "fromName": "%s", "fromDesc": "%s",
                     "toOwner":   "%s", "toName":   "%s", "toDesc":   "%s"}

                    If the path has more than 8 hops, also list the entry-point
                    methods that could reach the target in a single step
                    (callers_of on the target).
                    """.formatted(
                            text(args, "fromOwner", ""), text(args, "fromName", ""), text(args, "fromDesc", ""),
                            text(args, "toOwner", ""),   text(args, "toName", ""),   text(args, "toDesc", ""),
                            text(args, "fromOwner", ""), text(args, "fromName", ""), text(args, "fromDesc", ""),
                            text(args, "toOwner", ""),   text(args, "toName", ""),   text(args, "toDesc", ""));
            case "find_dubbo_method_tuning" -> """
                    Audit the per-method tuning of the Dubbo service `%s`.

                    Steps:
                    1. Call `find_dubbo_services` with `{"interface": "%s"}` to
                       get the provider rows. If multiple providers exist, list
                       each one and ask which to inspect.
                    2. For each provider, note the `id` field. Call
                       `find_dubbo_method_configs` with `{"serviceId": <id>}`
                       to get the per-method `<dubbo:method>` config.
                    3. For each method config, report:
                       - Whether timeout / retries / loadbalance are set or
                         left to framework defaults.
                       - Whether `async=true` is set (changes the call
                         signature to return Future/CompletableFuture).
                       - Any inconsistencies (e.g. a method declared
                         async=true but the Java interface returns void).
                    4. If the user is asking about a specific method, focus the
                       answer on that one. Otherwise, summarise the table.
                    """.formatted(text(args, "interface", ""), text(args, "interface", ""));
            case "find_mybatis_query_shape" -> """
                    Describe what every SQL statement in the MyBatis Mapper
                    `%s` returns, including the row-mapper shape.

                    Steps:
                    1. Call `find_mybatis_statements` with
                       `{"namespace": "%s"}` to list every statement in the
                       mapper. For each, note the `kind` (SELECT/INSERT/
                       UPDATE/DELETE) and the `resultMap` reference (if any).
                    2. Call `find_mybatis_result_maps` with
                       `{"namespace": "%s"}` to get the row-mapper shape for
                       every result map in this mapper.
                    3. Join the two: for each statement, if `resultMap` is
                       set, look up the matching `mapId` in the result-map
                       list. Report the row shape (property count,
                       association count, collection count).
                    4. Highlight:
                       - SELECTs whose result is a deep graph (high
                         association / collection count) — possible N+1
                         risk if the statement is run inside a loop.
                       - INSERTs / UPDATEs whose SQL touches more tables
                         than the statement name suggests.
                       - Dynamic-tag-heavy statements whose `sqlNormalized`
                         view diverges significantly from the literal
                         `sqlTemplate`.
                    """.formatted(text(args, "namespace", ""),
                                  text(args, "namespace", ""),
                                  text(args, "namespace", ""));
            case "find_rpc_call_chain" -> """
                    Map the full call graph of the Dubbo interface `%s`:
                    who provides it, who consumes it, and what the per-method
                    config looks like.

                    Steps:
                    1. Call `find_dubbo_services` with
                       `{"interface": "%s"}` — note every provider row
                       (group, version, source, ref_bean_name, implClassId).
                    2. For each provider, call `find_dubbo_method_configs`
                       with `{"serviceId": <id>}` to get the per-method
                       tuning.
                    3. Call `find_dubbo_references` with
                       `{"interface": "%s"}` — note every consumer row
                       (group, version, ref_id, confidence). Consumers with
                       `confidence=UNCERTAIN` are XML-discovered; consumers
                       with `confidence=PROBABLE` are annotation-discovered.
                    4. For each consumer, optionally call
                       `find_dubbo_method_configs` with
                       `{"referenceId": <id>}` for consumer-side per-method
                       tuning.
                    5. Render a single diagram:
                       ```
                       interface %s
                       ├── providers:
                       │   ├── com.acme.X (group=g1, version=1.0.0)
                       │   │   ├── findById: timeout=5000, retries=3
                       │   │   └── save:     timeout=10000, async=true
                       │   └── com.acme.Y (group=g2, version=2.0.0)
                       └── consumers:
                           ├── com.acme.A.ownerApi (PROBABLE, annotation)
                           └── com.acme.B.orderService (UNCERTAIN, XML)
                       ```
                    """.formatted(text(args, "interface", ""),
                                  text(args, "interface", ""),
                                  text(args, "interface", ""),
                                  text(args, "interface", ""));
            default -> "Unknown prompt: " + name;
        };
    }

    private static String text(JsonNode args, String key, String fallback) {
        if (args == null) return fallback;
        JsonNode v = args.get(key);
        return v == null || v.isNull() ? fallback : v.asText(fallback);
    }

    private CallResult onToolsCall(JsonNode req) {
        if (!initialized) return CallResult.error(-32002, "server not initialized");
        JsonNode params = req.path("params");
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        ToolHandler h = tools.get(name);
        if (h == null) return CallResult.error(-32602, "unknown tool: " + name);
        ObjectNode result = mapper.createObjectNode();
        var content = mapper.createArrayNode();
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "text");
        item.put("text", h.invoke(args).toString());
        content.add(item);
        result.set("content", content);
        return CallResult.ok(result);
    }

    private ObjectNode error(JsonNode id, int code, String msg) {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id);
        ObjectNode err = mapper.createObjectNode();
        err.put("code", code);
        err.put("message", msg);
        resp.set("error", err);
        return resp;
    }

    @FunctionalInterface
    private interface ToolHandler {
        ObjectNode invoke(JsonNode args);
    }

    /** Either an error or a value, to be unpacked by the dispatcher. */
    private record CallResult(boolean isError, int code, String message, ObjectNode value) {
        static CallResult ok(ObjectNode v) { return new CallResult(false, 0, null, v); }
        static CallResult error(int code, String msg) { return new CallResult(true, code, msg, null); }
    }

    // ----------------- transports -----------------

    /** Run the JSON-RPC loop reading from {@code in} writing to {@code out} (newline-delimited). */
    public void serveStdio(InputStream in, PrintStream out) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        // Register a transport-specific notifier for this connection. The
        // BiConsumer takes (uri, method-name-on-the-OutputStream) and writes
        // a JSON-RPC notification to the stdio OutputStream. ThreadLocal
        // because serveStdio can be called from any thread but only one
        // connection is active per invocation.
        transportNotifier.set((uri, method) -> {
            try {
                ObjectNode note = mapper.createObjectNode();
                note.put("jsonrpc", "2.0");
                note.put("method", "resources/updated");
                ObjectNode p = mapper.createObjectNode();
                p.put("uri", uri);
                note.set("params", p);
                synchronized (out) {
                    out.println(mapper.writeValueAsString(note));
                    out.flush();
                }
            } catch (Exception e) {
                LOG.warn("notification write failed for {}: {}", uri, e.getMessage());
            }
        });
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonNode req = mapper.readTree(line);
                    JsonNode resp = handleRequest(req);
                    if (resp != null) {
                        out.println(mapper.writeValueAsString(resp));
                        out.flush();
                    }
                } catch (Exception e) {
                    LOG.warn("malformed request: {}", line, e);
                }
            }
        } finally {
            transportNotifier.remove();
        }
    }

    /** Run an HTTP server on the given port accepting POST + GET requests. */
    public void serveHttp(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/mcp", exchange -> {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                try (InputStream is = exchange.getRequestBody()) {
                    JsonNode req = mapper.readTree(is);
                    JsonNode resp = handleRequest(req);
                    byte[] body = (resp == null ? "{}" : mapper.writeValueAsString(resp))
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                }
            } else {
                byte[] body = "jrdi mcp server — POST a JSON-RPC request to /mcp"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        LOG.info("jrdi mcp http server listening on http://0.0.0.0:{}/mcp", port);
    }

    public static void main(String[] args) throws IOException {
        String dbUrl = System.getProperty("jrdi.db", "sqlite:./jrdi.db");
        JrdiMcpService svc = JrdiMcpService.openSqlite(dbUrl);
        JrdiMcpServer server = new JrdiMcpServer(svc);
        if (args.length > 0 && args[0].equals("--http")) {
            int port = args.length > 1 ? Integer.parseInt(args[1]) : 7800;
            server.serveHttp(port);
        } else {
            server.serveStdio(System.in, System.out);
        }
    }

    // ─── P3.4 resource + prompt data ──────────────────────────────────────────

    private record NamedResource(String uri, String description, String kind) {}
    private record NamedPrompt(String name, String description, List<PromptArg> arguments) {}
    private record PromptArg(String name, String description, boolean required) {}

    private String readResource(String uri) {
        return switch (uri) {
            case "jrdi://schema" -> SchemaV1.SCHEMA_JSON;
            case "jrdi://stats" -> service.toolIndexStatus().toString();
            default -> null;
        };
    }
}

# jrdi — MCP Tool Reference (P1 + P2 framework tools)

This is the precise JSON-RPC 2.0 contract for each tool. All tools are in
`io.jrdi.mcp.JrdiMcpService` and exposed through `JrdiMcpServer`.

## Tool index

**General JVM facts (P1):**
- `index_status` — fact counts and DB dialect
- `find_symbol` — exact FQN lookup
- `describe_method` — method detail with source lines
- `callers_of` — list callers of a method
- `callees_of` — list callees of a method
- `find_path` — BFS shortest path between two methods
- `list_issues` — indexer issues

**Framework-aware (P2):**
- `find_spring_beans` — Spring beans by type/name
- `find_spring_injects` — Spring @Autowired sites for a class
- `find_dubbo_services` — Dubbo providers by interface
- `find_dubbo_references` — Dubbo consumers by interface
- `find_dubbo_method_configs` — per-method Dubbo tuning (timeout, retries, loadbalance, async)
- `find_mybatis_statements` — MyBatis SQL by namespace / statement id
- `find_mybatis_result_maps` — MyBatis row-mapper shape (type, extends, property/association/collection counts)

## Protocol

```
Client                                  jrdi MCP server
  │ -- JSON-RPC over stdin (or HTTP) -->  │
  │                                       ├── handle "initialize"  (once)
  │                                       ├── handle "tools/list"   (any)
  │                                       └── handle "tools/call"   (any)
  │ <-- JSON-RPC response --------------  │
```

All requests and responses are JSON objects. A request has `jsonrpc`, `id`, `method`, and
optional `params`. A response has `jsonrpc`, `id`, and either `result` or `error`.

## initialize

First call. Establishes the protocol session.

**Request**

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {}
}
```

**Response**

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2024-11-05",
    "capabilities": { "tools": {} },
    "serverInfo": { "name": "jrdi", "version": "0.1.0-M1" }
  }
}
```

## tools/list

**Response**

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tools": [
      {"name": "index_status",   "description": "..."},
      {"name": "find_symbol",    "description": "..."},
      {"name": "describe_method","description": "..."},
      {"name": "callers_of",     "description": "..."},
      {"name": "callees_of",     "description": "..."},
      {"name": "find_path",      "description": "..."},
      {"name": "list_issues",    "description": "..."}
    ]
  }
}
```

## tools/call

**Request**

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "<tool-name>",
    "arguments": { ... }
  }
}
```

**Response**

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "<stringified JSON of the tool-specific result>"
      }
    ]
  }
}
```

**Error envelope**

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "error": { "code": -32602, "message": "unknown tool: not_a_tool" }
}
```

---

## Tool: index_status

No arguments. Returns the current fact counts and the active storage dialect.

**Response**

```json
{
  "classes": 1284,
  "methods": 9420,
  "invokes": 23180,
  "issues": 7,
  "uncertainReflections": 2,
  "dialect": "sqlite"
}
```

## Tool: find_symbol

Exact FQN lookup. (P2: prefix + fuzzy.)

**Arguments**

```json
{ "prefix": "com.acme.petclinic.OwnerController" }
```

**Response**

```json
{ "matched": true, "class": { "id": 1, "fqn": "com/acme/petclinic/OwnerController", "access": 1, "superFqn": "java/lang/Object", "fileId": null, "signatureRaw": null, "source": "jar" } }
```

If unmatched:

```json
{ "matched": false, "prefix": "com.acme.NotThere" }
```

## Tool: describe_method

Returns detail for a single method, including source-derived line numbers.

**Arguments**

```json
{
  "owner": "com.acme.petclinic.OwnerController",
  "name":  "showOwner",
  "desc":  "(Ljava/lang/String;)Ljava/lang/String;"
}
```

**Response (when sources.jar present)**

```json
{
  "found": true,
  "method": {
    "id": 2,
    "classId": 1,
    "name": "showOwner",
    "desc": "(Ljava/lang/String;)Ljava/lang/String;",
    "signatureRaw": null,
    "startLine": 7,
    "endLine": 10,
    "virtual": false
  }
}
```

**Response (when sources.jar missing — CFR-decompiled)**

```json
{
  "found": true,
  "method": { "id": 2, ..., "startLine": 23, "endLine": 28, "virtual": true }
}
```

`virtual=true` is the signal to the UI: "this is a decompiler estimate, not a real source line."

## Tool: callers_of

Lists every call site that invokes a given method.

**Arguments**

```json
{
  "owner": "com/acme/petclinic/OwnerService",
  "name":  "save",
  "desc":  "(Lcom/acme/petclinic/Owner;)V",
  "includeReflect": false
}
```

**Response**

```json
{
  "count": 2,
  "callers": [
    { "callerMethodId": 7, "calleeOwner": "com/acme/petclinic/OwnerService", "calleeName": "save", "calleeDesc": "(Lcom/acme/petclinic/Owner;)V", "kind": "VIRTUAL", "line": 32, "confidence": "CERTAIN" },
    { "callerMethodId": 12, ..., "kind": "STATIC", "line": 41, "confidence": "CERTAIN" }
  ]
}
```

`includeReflect=true` keeps `UNCERTAIN` reflection edges in the result (P2: split into
`reflectCertain` / `reflectUncertain`).

## Tool: callees_of

Lists every method called by a given method (resolved through CHA).

**Arguments**

```json
{ "callerMethodId": 12 }
```

**Response**

```json
{
  "count": 4,
  "callees": [
    { "callerMethodId": 12, "calleeOwner": "com/acme/petclinic/OwnerService", "calleeName": "save", ..., "kind": "VIRTUAL", "confidence": "CERTAIN" },
    { ..., "calleeOwner": "com/acme/petclinic/Owner", "calleeName": "getFirstName", ..., "kind": "VIRTUAL", "confidence": "PROBABLE" }
  ]
}
```

Virtual calls collapse to the declared target in P1 (CHA is empty by default). P2 wires
`classes.super_fqn` into a populated `ChaResolver` so the result expands to all subclasses.

## Tool: find_path

BFS shortest path between two methods. The `maxDepth` field bounds the search
(default 8); set higher for very deep call chains.

**Arguments**

```json
{
  "fromOwner": "com/acme/petclinic/Main",
  "fromName":  "main",
  "fromDesc":  "([Ljava/lang/String;)V",
  "toOwner":   "com/acme/petclinic/OwnerController",
  "toName":    "showOwner",
  "toDesc":    "(Ljava/lang/String;)Ljava/lang/String;",
  "maxDepth":  10
}
```

**Response (path found)**

```json
{
  "found": true,
  "path": [
    "com/acme/petclinic/Main#main([Ljava/lang/String;)V",
    "com/acme/petclinic/OwnerController#createOwner(Ljava/lang/String;Ljava/lang/String;)V",
    "com/acme/petclinic/OwnerController#showOwner(Ljava/lang/String;)Ljava/lang/String;"
  ]
}
```

**Response (no path within depth)**

```json
{ "found": false }
```

## Tool: list_issues

Returns stored indexing issues, filtered by `kind`. The `kind` is a free-form tag from
the analyzer that recorded the issue.

**Known kinds** (P1)

| `kind` | Meaning |
|---|---|
| `missing_source` | Class has no `sources.jar` (and CFR also failed) |
| `uncertain_reflect` | `Method.invoke` or `Class.forName(<non-constant>)` |
| `class_process` | A class crashed during indexing (the row is also absent) |
| `jar_read` | The .jar file couldn't be opened (corrupt, wrong layout) |
| `cfr_used` | CFR fallback was used to recover line numbers |

**Arguments**

```json
{ "kind": "uncertain_reflect" }
```

**Response**

```json
{
  "count": 2,
  "issues": [
    { "id": 4, "kind": "uncertain_reflect", "target": "com/acme/Reflection", "message": "non-constant reflect argument", "severity": "WARN", "detectedAt": "2026-06-12T23:14:50" }
  ]
}
```

---

## Error Codes

| Code | Meaning |
|---|---|
| `-32601` | `method not found` (unknown JSON-RPC method) |
| `-32602` | `unknown tool` (in `tools/call`) |
| `-32603` | internal handler error |
| `-32002` | `server not initialized` (call `initialize` first) |


---

## P2 framework tools

### find_spring_beans

Query Spring beans. At least one of `type` or `name` is recommended; if both are empty,
returns up to 1000 beans (use with care on large indexes).

**Request**

```json
{
  "jsonrpc": "2.0",
  "id": 10,
  "method": "tools/call",
  "params": {
    "name": "find_spring_beans",
    "arguments": { "type": "com.acme.demo.OrderService" }
  }
}
```

**Response** — `content[0].text` is a JSON object:

```json
{
  "count": 1,
  "beans": [
    {
      "id": 42,
      "name": "orderService",
      "typeFqn": "com/acme/demo/OrderService",
      "source": "annotation",
      "classId": 17,
      "methodId": null,
      "scope": "singleton",
      "primary": false
    }
  ]
}
```

`source` is one of `annotation` (component scan), `config` (`@Bean`), `xml`, or `factory` (`spring.factories`).

### find_spring_injects

List `@Autowired` / `@Resource` / `@Value` sites for a class.

**Request**

```json
{
  "jsonrpc": "2.0",
  "id": 11,
  "method": "tools/call",
  "params": {
    "name": "find_spring_injects",
    "arguments": { "class": "com.acme.demo.OrderService" }
  }
}
```

**Response**

```json
{
  "count": 1,
  "injects": [
    {
      "id": 5,
      "targetField": "orderRepo",
      "targetParamIndex": null,
      "classId": 17,
      "methodId": null,
      "qualifier": null,
      "by": "TYPE",
      "confidence": "CERTAIN",
      "candidateBeanIds": [33]
    }
  ]
}
```

`by` is the resolution axis: `TYPE` / `NAME` / `QUALIFIER` / `VALUE`. `candidate_bean_ids` is the IDs of
matching beans (empty list when no match was found).

### find_dubbo_services

Query Dubbo providers. `interface` is the FQN of the service interface.

The `source` field discriminates provenance: `"annotation"` for `@DubboService`
on a class, `"xml"` for `<dubbo:service>` in a Spring config. The `ref_bean_name`
field carries the XML `ref` attribute (the Spring bean name) for XML-discovered
services; for annotation-discovered services it's `""`. When the impl class is
known (annotation case), `implClassId` is non-zero; for XML it's `0` because the
XML config only carries the bean name. The LLM joins with
`find_spring_beans(name=ref_bean_name)` to find the impl class.

**Lazy m2 resolution (0.2.0+):** if the server was started with
`--m2-cache-dir <root>`, a query that finds a service with
`implClassId = 0` triggers the lazy resolver to open the
relevant m2 jar and extract its class facts. The response
includes two extra fields when this happens:

- `implResolution`: `"lazy"` — the impl was found via m2, not
  the main index
- `hint`: a string pointing the LLM at `m2_classes` to read the
  resolved impl class

The LLM can then ask `find_mybatis_statements` or `callers_of`
on the resolved FQN to chain further. Without `--m2-cache-dir`,
the resolver is a no-op and the `implClassId = 0` sentinel is
returned as-is.

**Request**

```json
{
  "jsonrpc": "2.0",
  "id": 12,
  "method": "tools/call",
  "params": {
    "name": "find_dubbo_services",
    "arguments": { "interface": "com.acme.api.OrderApi" }
  }
}
```

**Response**

```json
{
  "count": 2,
  "services": [
    {
      "id": 7,
      "interfaceFqn": "com/acme/api/OrderApi",
      "implClassId": 17,
      "group": "g1",
      "version": "1.0.0",
      "protocol": "dubbo",
      "source": "annotation",
      "refBeanName": ""
    },
    {
      "id": 22,
      "interfaceFqn": "com/acme/api/OrderApi",
      "implClassId": 0,
      "group": "g2",
      "version": "2.0.0",
      "protocol": "dubbo",
      "source": "xml",
      "refBeanName": "orderServiceImpl"
    }
  ]
}
```

### find_dubbo_references

Query Dubbo consumers. Same FQN semantics as `find_dubbo_services`.

**Request / response** — same shape as above, with `references` instead of `services`.
The `fieldId` is the field on the consuming class that holds the proxy.
`consumerClassFqn` is the slashed FQN of the consumer class — useful for
cross-jar lookups where the consumer lives in a dependency that jrdi
didn't index. Both `fieldId = 0` and `confidence = UNCERTAIN` indicate a
cross-jar reference (the pass couldn't find the field in the local bytecode
index).

**New arg (V9):** `consumerClass` — restricts the result to references
declared in a specific consumer class. Pair this with `interface` to
answer "does `com.acme.web.OrderController` consume the OrderService?"
in one call:

```json
{
  "jsonrpc": "2.0",
  "id": 14,
  "method": "tools/call",
  "params": {
    "name": "find_dubbo_references",
    "arguments": {
      "interface": "com.acme.api.OrderService",
      "consumerClass": "com.acme.web.OrderController"
    }
  }
}
```

### find_mybatis_statements

Query MyBatis statements. `namespace` is the fully-qualified mapper interface; `statementId`
is the method name.

**Request**

```json
{
  "jsonrpc": "2.0",
  "id": 13,
  "method": "tools/call",
  "params": {
    "name": "find_mybatis_statements",
    "arguments": {
      "namespace": "com.acme.OrderMapper",
      "statementId": "selectById"
    }
  }
}
```

**Response**

```json
{
  "count": 1,
  "statements": [
    {
      "id": 1,
      "namespace": "com.acme.OrderMapper",
      "statementId": "selectById",
      "kind": "SELECT",
      "sqlTemplate": "select * from orders where id = #{id}",
      "sqlNormalized": "SELECT * FROM orders WHERE id = ?",
      "parameters": ["id"],
      "definedInFile": "OrderMapper.java",
      "line": 7,
      "providerClass": "",
      "providerMethod": ""
    }
  ]
}
```

`kind` is `SELECT` / `INSERT` / `UPDATE` / `DELETE`. `sqlNormalized` is the
parameterized form (literals replaced with `?` via JSqlParser).

For `@SelectProvider` / `@InsertProvider` / `@UpdateProvider` / `@DeleteProvider`
annotations, `sqlTemplate` and `sqlNormalized` are empty (the SQL is generated
at runtime). The response still includes `providerClass` (FQN of the provider
class) and `providerMethod` (name of the static method) so the LLM can locate
the SQL source by following the binding.

### find_dubbo_method_configs

Per-method Dubbo tuning config — extracted from `<dubbo:method>` elements nested
inside a `<dubbo:service>` or `<dubbo:reference>`. Pass `serviceId` to look at a
provider's methods, or `referenceId` for a consumer's methods. At least one is
required.

**Request**

```json
{
  "jsonrpc": "2.0",
  "id": 20,
  "method": "tools/call",
  "params": {
    "name": "find_dubbo_method_configs",
    "arguments": { "serviceId": 17 }
  }
}
```

**Response**

```json
{
  "count": 2,
  "parent": "service",
  "parentId": 17,
  "methodConfigs": [
    {
      "id": 3,
      "serviceId": 17,
      "referenceId": 0,
      "methodName": "findById",
      "timeoutMs": 5000,
      "retries": 3,
      "loadbalance": "random",
      "async": false,
      "sent": false
    },
    {
      "id": 4,
      "serviceId": 17,
      "referenceId": 0,
      "methodName": "save",
      "timeoutMs": 10000,
      "retries": null,
      "loadbalance": null,
      "async": true,
      "sent": false
    }
  ]
}
```

`timeoutMs` / `retries` / `loadbalance` are `null` when the developer didn't set
them (the framework defaults apply at runtime).

### find_mybatis_result_maps

MyBatis row-mapper shape — extracted from `<resultMap>` elements. Pass `namespace`
to list all result maps in a Mapper interface, or `typeFqn` to find all result
maps that target a given Java class.

**Request**

```json
{
  "jsonrpc": "2.0",
  "id": 21,
  "method": "tools/call",
  "params": {
    "name": "find_mybatis_result_maps",
    "arguments": { "namespace": "com.acme.OrderMapper" }
  }
}
```

**Response**

```json
{
  "count": 1,
  "resultMaps": [
    {
      "id": 1,
      "namespace": "com.acme.OrderMapper",
      "mapId": "orderResult",
      "typeFqn": "com.acme.Order",
      "extendsRef": "baseResult",
      "autoMapping": true,
      "propertyCount": 5,
      "associationCount": 1,
      "collectionCount": 0
    }
  ]
}
```

`propertyCount` counts `<id>` and `<result>` children. `associationCount` counts
`<association>`. `collectionCount` counts `<collection>`. The LLM uses these to
answer "is this query returning a flat row or a deep graph?".

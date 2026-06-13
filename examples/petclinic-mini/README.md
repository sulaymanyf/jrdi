# petclinic-mini

A small **petclinic-shaped** sample used to demonstrate `jrdi` end-to-end:

- build the jar + sources jar with Maven
- index it with `jrdi-cli`
- query the result via the CLI or the MCP server

The project contains:

- 1 Spring `@Service` (OwnerService) with two `@Autowired` sites
- 1 Spring `@Repository` (JdbcOwnerRepository)
- 1 Spring `@Configuration` with one `@Bean` (MapperConfig)
- 1 Dubbo provider (`@DubboService` on OwnerProviderImpl)
- 1 MyBatis mapper with `@Select` + `@Insert` + `@Update` + `@Delete`
- 1 plain DTO record (no annotations) — to show that the framework passes
  are no-ops on classes without their annotations

## Build

```sh
$ JAVA_HOME=/path/to/jdk-17+ mvn -B clean package
```

Produces:

- `target/petclinic-mini-1.0.0.jar`
- `target/petclinic-mini-1.0.0-sources.jar`

## Index

```sh
$ JRDI=/path/to/java-mcp/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar
$ java -jar $JRDI index target/petclinic-mini-1.0.0.jar \
    --with-sources --db sqlite:./petclinic.db --repo-id demo
```

Expected `IndexReport`:

```
classesIndexed:        8
methodsIndexed:        27
fieldsIndexed:         6
invokesIndexed:        13
spring beans:          5        # OwnerService, JdbcOwnerRepository, OwnerProviderImpl + MapperConfig + ownerMapper bean
spring injects:        2        # OwnerService.ownerApi + OwnerService.ownerRepository
dubbo services:        2        # OwnerProviderImpl implements OwnerApi
mybatis statements:    2        # findById (SELECT) + insert (INSERT)
```

(The exact MyBatis count depends on JSqlParser's ability to normalize the SQL
template — long/multi-line SQL may not parse and falls back to whitespace-only
normalization. The other passes are deterministic.)

## Query

```sh
# Find a class
$ java -jar $JRDI query com.acme.petclinic.service.OwnerService --db sqlite:./petclinic.db
class:    com/acme/petclinic/service/OwnerService
access:   33
super:    java.lang.Object

# Find its callers
$ java -jar $JRDI query com.acme.petclinic.service.OwnerService#getOwner(I)Lcom/acme/petclinic/api/OwnerDto; \
    --db sqlite:./petclinic.db
=== callers of com.acme.petclinic.service.OwnerService ===
count: 0
```

## Serve (MCP)

```sh
$ java -jar $JRDI serve --stdio --db sqlite:./petclinic.db
```

Then send JSON-RPC requests over stdin:

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"find_spring_beans","arguments":{}}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"find_dubbo_services","arguments":{}}}
```

## What this exercises

| jrdi feature                        | Triggered by this example |
|-------------------------------------|---------------------------|
| Class + method + field + invoke extraction | every class |
| Spring `@Service`/`@Repository`/`@Configuration` + `@Bean` | 5 beans |
| Spring `@Autowired` candidate resolution | 2 injects |
| Dubbo `@DubboService` (provider)    | 2 services (class + interface) |
| MyBatis `@Select` / `@Insert` / `@Update` / `@Delete` + JSqlParser normalize | 4 statements |
| Plain Java no-op (records, interfaces) | `OwnerDto`, `OwnerApi`, `OwnerRepository` |
| CFR fallback for classes without sources | (not exercised — sources.jar is built) |
| Reflection uncertainty              | (not exercised — no reflection in the demo) |

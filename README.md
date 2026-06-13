# jrdi — Java RPC Dependency Intelligence

**一个把 Java 项目的依赖关系喂给 LLM 的 Model Context Protocol (MCP) 服务器。**

jrdi 把 JVM 项目(类、方法、字段、调用关系、Spring/Dubbo/MyBatis 框架元数据)抽取出来,存进 SQLite 或 PostgreSQL,然后通过 **16 个 MCP 工具 + 2 个资源 + 6 个 prompt 模板** 暴露给 LLM 客户端(Claude Code、Cursor、OpenCode、Cline 等)。

---

# jrdi — Java RPC Dependency Intelligence

**An MCP server that exposes JVM project dependencies to LLM clients.**

jrdi extracts the dependency graph (classes, methods, fields, calls, Spring/Dubbo/MyBatis framework facts) from any JVM project, stores it in SQLite or PostgreSQL, and exposes **17 MCP tools + 2 resources + 6 prompt templates** to LLM clients (Claude Code, Cursor, OpenCode, Cline, etc.).

## 它能做什么 / What it does

| 问题 / Question | 工具 / Tool |
|---|---|
| "这个 controller 怎么调到 service 的?" | `callers_of`, `find_path` |
| "改这个 DAO 方法会影响谁?" | `callers_of` |
| "Spring 项目有哪些 bean? 互相怎么注入的?" | `find_spring_beans`, `find_spring_injects` + `find_bean_wiring` prompt |
| "Dubbo 调用链长什么样? 哪些远程, 哪些本地? 哪个方法配了超时/重试?" | `find_dubbo_services`, `find_dubbo_references`, `find_dubbo_method_configs` |
| "这个 MyBatis mapper 跑哪些 SQL? 有没有 N+1 风险? 返回的 shape 长什么样?" | `find_mybatis_statements`, `find_mybatis_result_maps` |
| "整个项目有多少类?多少方法?哪些类没人引用?" | `index_status`, `list_issues` |

## 它不能做什么 / What it does NOT do

- **不**支持 Kotlin / Scala / Groovy (只 Java 17+ 源语法)
- **不**做增量 watch 模式 (但支持重复 `index` 的 SHA-256 跳过)
- **不**做跨 jar 类层级深 CHA 之外的运行时分析 (只能看静态字节码 + 源码)
- **不**有 web UI (LLM 客户端就是 UI)

## 谁应该用 / Who should use this

- **理解老 Java 项目**: 用 jrdi + LLM,自然语言问 "X 的调用链长什么样",而不是手动 grep + 画图
- **重构规划**: "如果我改 `OrderService.save` 这个方法,影响面有多大?"
- **架构梳理**: 自动输出 bean wiring 表、Dubbo 接口表、MyBatis SQL 列表
- **代码审查辅助**: LLM 拿着事实图,问 "这个 service 的所有 SQL 是啥?"

## 系统要求 / Requirements

- **JDK 21+**
- **Maven 3.9+**
- (可选) 任何标准 Maven 仓库(本地 `~/.m2/repository` 即可,不需要联网)

```sh
$ java -version
openjdk version "21.0.10" 2026-01-20

$ mvn -version
Apache Maven 3.9.15
```

---

## 1. 构建 / Build

```sh
$ git clone <this-repo>
$ cd java-mcp
$ JAVA_HOME=/path/to/jdk-21 mvn clean install -DskipTests
```

会生成两个 fat-jar (所有依赖打成一个文件):

- `jrdi-cli/target/jrdi-cli-0.1.0-M1.jar` — 命令行工具(索引 / 查询 / 提供 MCP 服务)
- `jrdi-mcp-server/target/jrdi-mcp-server-0.1.0-M1.jar` — 纯 MCP 服务器(可选,CLI 也能提供)

## 2. 索引你的项目 / Index your project

### 2.1 从本地 jar

```sh
$ java -jar jrdi-cli/target/jrdi-cli-0.1.0-M1.jar index \
    --repo-id my-app \
    --db sqlite:./jrdi.db \
    --with-sources \
    /path/to/my-app-1.0.0.jar
```

`--with-sources` 会尝试找同名的 `-sources.jar`,找到就用于源码行号 + 框架 passes;找不到就用 CFR 反编译兜底。

### 2.2 从 Maven GAV(自动从 `~/.m2/repository` 拉)

```sh
$ java -jar jrdi-cli/target/jrdi-cli-0.1.0-M1.jar index \
    --repo-id central \
    --db sqlite:./jrdi.db \
    org.springframework.boot:spring-boot:3.3.0
```

需要 `~/.m2/settings.xml` 里有可达的仓库。覆盖方式:

```sh
$ java -jar ... index \
    --maven-settings /path/to/corp-settings.xml \
    --db sqlite:./jrdi.db \
    com.acme.internal:my-service:1.0.0
```

### 2.3 一次性索引多个 jar

```sh
$ java -jar ... index --db sqlite:./jrdi.db --repo-id monolith \
    /path/to/lib1.jar /path/to/lib2.jar /path/to/lib3.jar
```

### 2.4 增量重索引(已有 DB)

第二次跑相同的命令时,jrdi 会**跳过 .class 字节没变的所有类**(SHA-256 比对):

```sh
# 第一次: 8 个类,206ms
$ java -jar ... index .../petclinic-1.0.0.jar --db sqlite:./jrdi.db
classesIndexed:        8
elapsed:               206 ms

# 第二次: 0 个类,7ms (8 个被跳过)
$ java -jar ... index .../petclinic-1.0.0.jar --db sqlite:./jrdi.db
classesSkipped:       8  (incremental: unchanged from previous index)
elapsed:               7 ms
```

只重处理改过的类。完整说明见 `docs/incremental-indexing.md`。

### 2.5 索引 PostgreSQL(团队/生产)

```sh
$ java -jar ... index \
    --db "jdbc:postgresql://db.example.com:5432/jrdi?user=jrdi&password=xxx" \
    --with-sources \
    /path/to/my-app-1.0.0.jar
```

jrdi 自动用 `V1__init.pg.sql` schema,而不是 SQLite 那个。

## 3. CLI 快捷查询(不需要 MCP)/ CLI quick queries

```sh
# 详细统计:每张表多少行,annotation vs xml 拆分
$ java -jar jrdi-cli/target/jrdi-cli-0.1.0-M1.jar stats --db sqlite:./jrdi.db
=== jrdi stats ===
dialect:         sqlite
schema version:  V4
last indexed:    2026-06-13T17:23:28Z

── Core / P1 ──
  repos                               1
  artifacts                           3
  files                              42
  classes                            167
  methods                            421
  ...
  issues                              0

── P2 framework ──
  spring_beans                       14
  spring_injects                      5
  dubbo_services                     6  (annotation=4, xml=2)
  dubbo_references                   3  (annotation=2, xml=1)
  dubbo_method_configs               7
  mybatis_statements                18  (annotation=5, xml=13)
  mybatis_result_maps                 2
  spring_boot_autoconfigs            24  (factories=15, imports=9)
  spring_autoconfig_conditions      41  (on_class=28, on_property=13)

# JSON 输出,方便 LLM 直接消费
$ java -jar jrdi-cli-0.1.0-M1.jar stats --db sqlite:./jrdi.db --json
{"dialect":"sqlite","schemaVersion":"V4","classes":167,"methods":421, ... }

# 简短诊断
$ java -jar ... doctor --db sqlite:./jrdi.db
=== jrdi doctor ===
classes:        167
methods:        421
issues:         0

# 查一个类
$ java -jar ... query com.acme.OwnerService --db sqlite:./jrdi.db
class:    com/acme/OwnerService
access:   33
super:    java.lang.Object

# 查方法调用者
$ java -jar ... query com.acme.OwnerService#save(Lcom/acme/OwnerDto;)V \
    --db sqlite:./jrdi.db
=== callers of com.acme.OwnerService ===
count: 4
  com/acme/OwnerController#createOwner(Lcom/acme/OwnerDto;)V
  com/acme/OwnerController#updateOwner(ILcom/acme/OwnerDto;)V
  ...

# 找调用路径
$ java -jar ... query com.acme.OwnerController#createOwner \
    --from com/acme/OwnerController#createOwner \
    --to   com/acme/JdbcOwnerRepository#save \
    --depth 6 \
    --db sqlite:./jrdi.db
=== path (2 hops) ===
  com/acme/OwnerController#createOwner
  com/acme/OwnerService#save
  com/acme/JdbcOwnerRepository#save

# 列出所有 Spring bean
$ java -jar ... query --db sqlite:./jrdi.db --help   # 看完整选项
```

---

## 4. MCP 服务器配置 / Configuring the MCP server

jrdi 通过 **stdio** 或 **HTTP** 提供 MCP 服务。**stdio 是主流 LLM 客户端的默认传输方式**,本节重点讲这个。

### 4.1 通用 stdio 启动方式

```sh
# 用 jrdi-cli 自带的 serve 子命令(推荐)
$ java -jar jrdi-cli/target/jrdi-cli-0.1.0-M1.jar serve \
    --stdio --db sqlite:./my-project.db

# 或者用专门的 jrdi-mcp-server
$ java -Djrdi.db=sqlite:./my-project.db \
    -jar jrdi-mcp-server/target/jrdi-mcp-server-0.1.0-M1.jar
```

LLM 客户端通过 stdin/stdout 跟 jrdi 交换 JSON-RPC 2.0 消息。

### 4.2 Claude Code

Claude Code 是 Anthropic 的 CLI 工具。在 `~/.claude.json` 或项目级 `.mcp.json` 配置:

**项目级** `.mcp.json`(推荐,提交到 git 让团队共享):

```json
{
  "mcpServers": {
    "jrdi": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar",
        "serve",
        "--stdio",
        "--db", "sqlite:./jrdi.db"
      ],
      "env": {
        "JAVA_HOME": "/path/to/jdk-21"
      }
    }
  }
}
```

或用 `claude mcp add` 命令:

```sh
$ claude mcp add jrdi \
    --command java \
    --args "-jar,/path/to/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar,serve,--stdio,--db,sqlite:$PWD/jrdi.db"
```

启动 Claude Code 后,输入 `/mcp` 看到 `jrdi` 就表示连接成功。

**注意**: Claude Code 启动时,`--db` 路径是相对 MCP 客户端的工作目录。建议用绝对路径,或在 `env` 里设 `PWD=/your/project/root`。

### 4.3 OpenCode

OpenCode 是 [opencode.ai](https://opencode.ai) 的开源 AI 编程助手。

配置文件: `~/.config/opencode/config.json`(全局)或 `<project>/.opencode/config.json`(项目)

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "jrdi": {
      "type": "local",
      "command": ["java", "-jar", "/absolute/path/to/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar", "serve", "--stdio", "--db", "sqlite:./jrdi.db"],
      "enabled": true
    }
  }
}
```

OpenCode 也支持远程/stdio-remote 模式,可以用 `jrdi serve --http 7800` 起一个 HTTP server,然后:

```json
{
  "mcp": {
    "jrdi": {
      "type": "remote",
      "url": "http://localhost:7800/mcp"
    }
  }
}
```

### 4.4 Cursor

Cursor 的 MCP 配置在 `~/.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "jrdi": {
      "command": "java",
      "args": [
        "-jar",
        "/Users/you/dev/java-mcp/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar",
        "serve",
        "--stdio",
        "--db", "sqlite:./jrdi.db"
      ]
    }
  }
}
```

重启 Cursor,在 Composer 面板里会看到 `jrdi` 工具可用。

### 4.5 Cline (VS Code)

Cline 的 MCP 配置在 VS Code 的 `cline_mcp_settings.json` 里:

**macOS**: `~/Library/Application Support/Code/User/globalStorage/rooveterat.terraformer.line-2/settings/cline_mcp_settings.json`

```json
{
  "mcpServers": {
    "jrdi": {
      "command": "java",
      "args": [
        "-jar",
        "/Users/you/dev/java-mcp/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar",
        "serve",
        "--stdio",
        "--db", "sqlite:./jrdi.db"
      ],
      "disabled": false
    }
  }
}
```

### 4.6 Continue.dev (VS Code / JetBrains)

`~/.continue/config.json`:

```json
{
  "experimental": {
    "modelContextProtocolServers": [
      {
        "name": "jrdi",
        "command": "java",
        "args": [
          "-jar", "/path/to/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar",
          "serve", "--stdio",
          "--db", "sqlite:./jrdi.db"
        ]
      }
    ]
  }
}
```

### 4.7 Zed

Zed 配置: `~/.config/zed/settings.json` (全局) 或 `.zed/settings.json` (项目)

```json
{
  "context_servers": {
    "jrdi": {
      "command": {
        "path": "java",
        "args": [
          "-jar", "/path/to/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar",
          "serve", "--stdio",
          "--db", "sqlite:./jrdi.db"
        ]
      }
    }
  }
}
```

### 4.8 通用 stdio JSON (适用于任何 MCP 客户端)

```json
{
  "command": "java",
  "args": [
    "-jar", "/path/to/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar",
    "serve", "--stdio",
    "--db", "sqlite:/absolute/path/to/jrdi.db"
  ]
}
```

要点:
- **必须用绝对路径** — LLM 客户端的 CWD 通常跟项目根目录不一致
- **必须先 `index` 才有数据** — MCP 是只读接口,不会自动索引
- **stdio 是首选** — 比 HTTP 快、无网络、易调试

### 4.9 启动顺序

典型的 5 分钟上手流程:

```sh
# 1. 一次性 build
$ git clone <this-repo> && cd java-mcp
$ JAVA_HOME=/path/to/jdk-21 mvn clean install -DskipTests

# 2. 索引项目(替换成你的 jar 路径)
$ java -jar jrdi-cli/target/jrdi-cli-0.1.0-M1.jar index \
    --with-sources --db sqlite:./jrdi.db \
    /path/to/your-app-1.0.0.jar

# 3. 配置 LLM 客户端(参见上面各小节)

# 4. 启动 LLM 客户端,问问题:
#    "What Spring beans does com.acme.OwnerService inject?"
#    "Find call path from Main.main to JdbcOwnerRepository.findById"
```

## 5. MCP 工具一览 / MCP tool reference

jrdi 暴露 **12 个工具** + **2 个资源** + **3 个 prompt 模板**。详见 `docs/mcp-tools.md`,摘要:

| 类别 | 工具 |
|---|---|
| **通用 JVM 事实** | `index_status`, `find_symbol`, `describe_method`, `callers_of`, `callees_of`, `find_path`, `list_issues` |
| **Spring** | `find_spring_beans`, `find_spring_injects` |
| **Dubbo** | `find_dubbo_services`, `find_dubbo_references` |
| **MyBatis** | `find_mybatis_statements` |
| **资源** | `jrdi://schema`, `jrdi://stats` |
| **Prompt 模板** | `find_callers`, `find_bean_wiring`, `find_path` |

调用示例(JSON-RPC over stdio 或 HTTP):

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "find_path",
    "arguments": {
      "fromOwner": "com.acme.Main",
      "fromName": "main",
      "fromDesc": "([Ljava/lang/String;)V",
      "toOwner": "com.acme.JdbcOwnerRepository",
      "toName": "findById",
      "toDesc": "(I)Lcom/acme/Owner;",
      "maxDepth": 6
    }
  }
}
```

## 6. 端到端示例 / End-to-end example

`examples/petclinic-mini/` 是个迷你 petclinic 项目,有 8 个类、覆盖所有 3 个框架:

```sh
$ cd examples/petclinic-mini
$ JAVA_HOME=/path/to/jdk-17+ mvn -B clean package
$ java -jar ../../jrdi-cli/target/jrdi-cli-0.1.0-M1.jar index \
    target/petclinic-mini-1.0.0.jar \
    --with-sources \
    --db sqlite:./petclinic.db \
    --repo-id demo

=== IndexReport ===
classesIndexed:        8
methodsIndexed:        27
spring beans:          5
spring injects:        2      ← 包括 ownerApi (接口) 和 ownerRepository (接口)
dubbo services:        2
mybatis statements:    2
```

然后用 LLM 客户端问:

> "Trace Spring wiring for the bean `com.acme.petclinic.service.OwnerService`"

LLM 收到 `find_bean_wiring` prompt,按指引调用 `find_spring_beans` → `find_spring_injects` → 报告:
- 1 个 `ownerService` bean
- 2 个 @Autowired 注入点:
  - `ownerApi` → 候选 [ownerProviderImpl] (实现 OwnerApi)
  - `ownerRepository` → 候选 [jdbcOwnerRepository] (实现 OwnerRepository)

## 7. 跨 jar 类层级解析(自动)/ Cross-jar CHA

jrdi 自动从 `~/.m2/repository` 扫描,补全没有直接索引的父类型。Spring 应用索引完后,`find_path` 会自然延伸到 `spring-core` 等 3rd-party jar。

不需要任何配置。详细架构见 `docs/architecture.md`。

## 8. PostgreSQL(团队/生产)

```sh
# 1. 创建数据库
$ createdb jrdi
$ createuser jrdi
$ psql -c "GRANT ALL ON DATABASE jrdi TO jrdi"

# 2. 索引(自动用 PG schema)
$ java -jar jrdi-cli/target/jrdi-cli-0.1.0-M1.jar index \
    --db "jdbc:postgresql://localhost:5432/jrdi?user=jrdi" \
    --with-sources \
    /path/to/my-app-1.0.0.jar

# 3. 启动 MCP server(读 PG)
$ java -jar jrdi-cli/target/jrdi-cli-0.1.0-M1.jar serve \
    --stdio \
    --db "jdbc:postgresql://localhost:5432/jrdi?user=jrdi"
```

`examples/petclinic-mini` 也有 `PostgresE2EIT`,用 Testcontainers 跑真实 PG 容器,默认关闭。手动开:

```sh
$ mvn -pl jrdi-it verify -Djrdi.pg.it=true   # 需要 Docker
```

## 9. 故障排查 / Troubleshooting

### "no sources jar for X"
预期行为。字节码 pass 找到了类,但 sources jar 缺失(或没传 `--with-sources`)。jrdi 用 CFR 反编译兜底,methods 标 `virtual=true`。**3rd-party 库是正常的;自己代码缺 sources 就该传 `--with-sources`。**

### "Connection refused" / Claude Code 看不到 jrdi
1. 跑一次 `java -jar jrdi-cli/target/jrdi-cli-0.1.0-M1.jar doctor --db sqlite:./jrdi.db` 验证 CLI 本身能用
2. 检查 LLM 客户端的 MCP 配置 JSON:路径绝对吗?JSON 合法吗?
3. Claude Code: `/mcp` 看连接状态;Cursor:Settings → MCP;OpenCode:`/mcp`

### 索引后 LLM 问问题但回答 "I don't see this class"
LLM 只能看到 jrdi 给的事实图。索引是只读的——重新跑 `index` 才会更新。

### "M2 / Maven 解析失败"
`~/.m2/settings.xml` 可能有 `<mirrorOf>*</mirrorOf>` 指到不可达的网络。用 `--maven-settings /path/to/corp.xml` 覆盖。

### jrdi 索引后重启 MCP server 才能让 LLM 看到新数据
是的。**LLM 工具调用是实时查 DB 的**,但 LLM 的"记忆"是会话级的。如果 LLM 已经"学过"当前 DB 内容,重新索引后可能需要明确告知 "the DB has been updated"。

## 10. 完整文档 / Further reading

- `docs/architecture.md` — 模块图、存储 schema、工具设计
- `docs/mcp-tools.md` — 12 个工具的完整 JSON-RPC 契约 + 资源 + prompt
- `docs/quickstart.md` — 5 分钟快速上手(English)
- `RELEASE-NOTES-0.1.0-M1.md` — 0.1.0-M1 发布说明
- `examples/petclinic-mini/README.md` — 端到端示例

## 11. 限制 / Known limitations (0.1.0-M1)

| 限制 | 影响 | 计划 |
|---|---|---|
| 类层级解析只跟 `super_fqn` | virtual/interface 调用只有部分支持 | 0.2.0: 深 CHA + 跨 jar 完整父类型 |
| 不支持增量 watch 模式 | 重新索引需要手动跑命令 | 0.2.0: `--watch` 模式 |
| 类层级解析**会**做跨 jar 解析(用 m2 缓存) | 正确但慢(单次冷查 ~50-200ms) | 0.2.0: 缓存优化 |
| 不支持 Kotlin/Scala/Groovy | 源语法 | 0.3.0 |
| HTTP 模式非流式 | 简单的 POST+JSON 响应,无 SSE | 0.2.0 |

## 12. 17 个模块的架构 / Architecture

```
jrdi-core           数据模型(Fqn, MethodKey, CallEdge, Confidence, Gav)
jrdi-storage        SQLite + PostgreSQL + Flyway 迁移
jrdi-resolver       settings.xml 解析 + GAV 解析 + jar 下载
jrdi-classgraph     jar 里的 class 列举
jrdi-bytecode       ASM 字节码分析(class/method/field/invoke/lambda/interfaces)
jrdi-source         JavaParser 源码行号归属
jrdi-decompile      CFR 反编译兜底
jrdi-callgraph      CHA + BFS 路径查找(支持 m2 跨 jar 解析)
jrdi-spring         @Service/@Autowired 分析(支持 interface 候选解析)
jrdi-dubbo          @DubboService/@Reference 分析
jrdi-mybatis        @Select 等 + JSqlParser 归一化
jrdi-pipeline       编排:2-pass bytecode + framework passes
jrdi-search         Lucene 全文搜索(预留)
jrdi-cli            picocli 入口
jrdi-mcp-server     JSON-RPC 2.0 MCP 服务器
jrdi-it             端到端集成测试
```

详细架构图见 `docs/architecture.md`。

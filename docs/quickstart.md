# jrdi — Quickstart (5 min)

> This page is a quick-reference. Full install instructions are in [Install](install.md); full client config is in [MCP clients](mcp-clients.md).

## 1. Get the jar

Either [download the prebuilt fat-jar](install.md#download-from-github-releases) or build from source:

```sh
$ git clone https://github.com/sulaymanyf/jrdi.git
$ cd jrdi
$ JAVA_HOME=/path/to/jdk-21 mvn clean install -DskipTests
```

Outputs:
- `jrdi-cli/target/jrdi-cli-0.1.0-M1.jar` — CLI + MCP server in one
- `jrdi-mcp-server/target/jrdi-mcp-server-0.1.0-M1.jar` — MCP server only (optional)

## 2. Index

```sh
# Local jar
$ java -jar jrdi-cli/target/jrdi-cli-0.1.0-M1.jar index \
    --with-sources --db sqlite:./jrdi.db \
    /path/to/my-app-1.0.0.jar

# Maven GAV (uses ~/.m2/settings.xml)
$ java -jar jrdi-cli/target/jrdi-cli-0.1.0-M1.jar index \
    --db sqlite:./jrdi.db \
    org.springframework.boot:spring-boot:3.3.0
```

## 3. Wire to your LLM

See [MCP clients](mcp-clients.md) for full config for Claude Code, OpenCode, Cursor, Cline, Continue, Zed, Windsurf, Roo.

Quick example for **Claude Code** (`<project>/.mcp.json`):

```json
{
  "mcpServers": {
    "jrdi": {
      "command": "java",
      "args": [
        "-jar", "/Users/you/dev/java-mcp/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar",
        "serve", "--stdio",
        "--db", "sqlite:/Users/you/projects/my-app/jrdi.db"
      ]
    }
  }
}
```

## 4. Ask

> "What Spring beans does `com.acme.OwnerService` inject?"

> "Find call path from `Main.main` to `JdbcOwnerRepository.findById` within 6 hops."

> "List issues in `com.acme.Reflection`."

## Troubleshooting

| 现象 | 原因 | 修法 |
|---|---|---|
| `M2 / Maven 解析失败` | settings.xml 镜到不可达 | `--maven-settings <path>` |
| `no sources jar for X` | 3rd-party 正常;自己代码传 `--with-sources` | 补传 flag |
| `Connection refused` (LLM 客户端) | 路径不对 | 用绝对路径 |
| 索引后 LLM 看不到 | jrdi 是只读,需要 LLM 重新查 | 让 LLM 重新调用工具 |
| 启动慢 (5-10s) | 第一次冷启动要加载 Flyway + 连接池 | 正常,后续调用 <100ms |

## Next

- [Home](index.md) — 完整中英介绍
- [MCP clients](mcp-clients.md) — 所有主流 LLM 客户端配置
- [MCP tools](mcp-tools.md) — 17 个工具的 JSON-RPC 契约
- [Architecture](architecture.md) — 17 模块架构
- [Release notes](changelog.md) — 0.1.0-M1 发布说明
- [`examples/petclinic-mini/`](https://github.com/sulaymanyf/jrdi/tree/master/examples/petclinic-mini) — 端到端示例

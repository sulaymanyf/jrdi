# jrdi — MCP 客户端配置 / MCP Client Configuration

本文档列出 **所有主流 LLM 编程工具**的 jrdi MCP 配置方法。

This document lists jrdi MCP configuration for all major LLM coding tools.

## TL;DR

jrdi 通过 **stdio** 提供 MCP 服务,所有工具都用同一种配置格式:

```json
{
  "command": "java",
  "args": [
    "-jar", "/absolute/path/to/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar",
    "serve", "--stdio",
    "--db", "sqlite:/absolute/path/to/your/jrdi.db"
  ]
}
```

## 前置条件 / Prerequisites

1. **JDK 21+** 已安装 (`java -version` ≥ 21)
2. **jrdi 已 build**: `mvn clean install -DskipTests`
3. **数据已索引**:
   ```sh
   $ java -jar jrdi-cli/target/jrdi-cli-0.1.0-M1.jar index \
       --with-sources \
       --db sqlite:./jrdi.db \
       /path/to/your-app-1.0.0.jar
   ```
4. **路径用绝对路径** — LLM 客户端的 CWD 通常跟项目根目录不一致

## 1. Claude Code (Anthropic)

Claude Code 是 Anthropic 的官方 CLI。

**项目级配置** (推荐,提交到 git 让团队共享) — `<project>/.mcp.json`:

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

**全局配置** — `~/.claude.json`:

```json
{
  "mcpServers": {
    "jrdi": {
      "command": "java",
      "args": [
        "-jar", "/Users/you/dev/java-mcp/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar",
        "serve", "--stdio",
        "--db", "sqlite:/Users/you/dev/my-app/jrdi.db"
      ]
    }
  }
}
```

或用 `claude mcp add` CLI:

```sh
$ claude mcp add jrdi \
    --command java \
    --args "-jar,/Users/you/dev/java-mcp/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar,serve,--stdio,--db,sqlite:$PWD/jrdi.db"
```

验证: 启动 `claude`,输入 `/mcp`,应看到 `jrdi: connected`。

## 2. OpenCode (opencode.ai)

OpenCode 是 [opencode.ai](https://opencode.ai) 的开源 AI 编程助手,基于 SST/opencode 框架。

**全局配置** — `~/.config/opencode/config.json`:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "jrdi": {
      "type": "local",
      "command": [
        "java",
        "-jar", "/Users/you/dev/java-mcp/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar",
        "serve", "--stdio",
        "--db", "sqlite:/Users/you/dev/my-app/jrdi.db"
      ],
      "enabled": true
    }
  }
}
```

**项目级配置** — `<project>/.opencode/config.json`(同上内容)。

**HTTP 远程模式**(可选,适合 jrdi 在另一台机器上跑):

```sh
# 1. 在远程机器起 jrdi HTTP server
$ java -jar jrdi-cli/target/jrdi-cli-0.1.0-M1.jar serve --http 7800 --db sqlite:./jrdi.db
```

```json
{
  "mcp": {
    "jrdi": {
      "type": "remote",
      "url": "http://jrdi-server.internal:7800/mcp"
    }
  }
}
```

## 3. Cursor

配置文件: `~/.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "jrdi": {
      "command": "java",
      "args": [
        "-jar", "/Users/you/dev/java-mcp/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar",
        "serve", "--stdio",
        "--db", "sqlite:/Users/you/dev/my-app/jrdi.db"
      ]
    }
  }
}
```

**项目级**: `<project>/.cursor/mcp.json`(同上内容)

重启 Cursor。`Settings → MCP` 应看到 `jrdi` 已连接。

## 4. Cline (VS Code 扩展)

**VS Code → Settings → Cline → MCP Servers → Edit Configuration** 会打开 `cline_mcp_settings.json`:

**macOS**: `~/Library/Application Support/Code/User/globalStorage/rooveterat.terraformer.line-2/settings/cline_mcp_settings.json`

**Linux**: `~/.config/Code/User/globalStorage/rooveterat.terraformer.line-2/settings/cline_mcp_settings.json`

**Windows**: `%APPDATA%\Code\User\globalStorage\rooveterat.terraformer.line-2\settings\cline_mcp_settings.json`

```json
{
  "mcpServers": {
    "jrdi": {
      "command": "java",
      "args": [
        "-jar", "C:\\dev\\java-mcp\\jrdi-cli\\target\\jrdi-cli-0.1.0-M1.jar",
        "serve", "--stdio",
        "--db", "sqlite:C:\\dev\\my-app\\jrdi.db"
      ],
      "disabled": false
    }
  }
}
```

## 5. Continue.dev (VS Code / JetBrains)

`~/.continue/config.json`:

```json
{
  "experimental": {
    "modelContextProtocolServers": [
      {
        "name": "jrdi",
        "command": "java",
        "args": [
          "-jar", "/Users/you/dev/java-mcp/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar",
          "serve", "--stdio",
          "--db", "sqlite:/Users/you/dev/my-app/jrdi.db"
        ]
      }
    ]
  }
}
```

## 6. Zed

**全局**: `~/.config/zed/settings.json`

**项目级**: `<project>/.zed/settings.json`

```json
{
  "context_servers": {
    "jrdi": {
      "command": {
        "path": "java",
        "args": [
          "-jar", "/Users/you/dev/java-mcp/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar",
          "serve", "--stdio",
          "--db", "sqlite:/Users/you/dev/my-app/jrdi.db"
        ]
      }
    }
  }
}
```

## 7. Roo Code / Roo Cline (VS Code)

跟 Cline 一样: `cline_mcp_settings.json`:

```json
{
  "mcpServers": {
    "jrdi": {
      "command": "java",
      "args": [
        "-jar", "/Users/you/dev/java-mcp/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar",
        "serve", "--stdio",
        "--db", "sqlite:/Users/you/dev/my-app/jrdi.db"
      ]
    }
  }
}
```

## 8. Windsurf

`~/.codeium/windsurf/mcp_config.json`:

```json
{
  "mcpServers": {
    "jrdi": {
      "command": "java",
      "args": [
        "-jar", "/Users/you/dev/java-mcp/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar",
        "serve", "--stdio",
        "--db", "sqlite:/Users/you/dev/my-app/jrdi.db"
      ]
    }
  }
}
```

## 9. 通用 stdio 模板 (用于任何 MCP 客户端)

```json
{
  "command": "java",
  "args": [
    "-jar", "<absolute path>/jrdi-cli/target/jrdi-cli-0.1.0-M1.jar",
    "serve", "--stdio",
    "--db", "sqlite:<absolute path>/jrdi.db"
  ],
  "env": {
    "JAVA_HOME": "<absolute path to JDK 21>"
  }
}
```

要点:
- **路径必须绝对** — LLM 客户端的 CWD 不可预测
- `--db` 默认 `sqlite:./jrdi.db`,相对当前工作目录
- `env.JAVA_HOME` 是为了让某些 nerdctl 环境也能找到 java(部分 MCP 客户端会 strip env)

## 10. HTTP 模式(团队/远程)

适合:
- jrdi 在另一台机器上跑(共享 DB)
- 多个 LLM 客户端想连接同一个 jrdi

```sh
# 起 HTTP server(默认 7800)
$ java -jar jrdi-cli/target/jrdi-cli-0.1.0-M1.jar serve --http 7800 --db sqlite:./jrdi.db
# POST JSON-RPC 到 http://localhost:7800/mcp
```

LLM 客户端的 MCP 配置:

**OpenCode**:
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

**Claude Code / Cursor / Cline 等**:用各自客户端的"remote / SSE / HTTP"配置项(部分支持,部分仅 stdio)。查你的客户端文档。

## 11. 验证连接 / Verify

不管用哪个客户端,验证步骤都一样:

1. 启动客户端后,输入 `/mcp` (Claude Code) 或在 MCP 面板查看
2. 应该看到 `jrdi: connected` (绿色)
3. 试着问 LLM: "List Spring beans" 或 "What's in jrdi://stats?"

如果连接失败:
- 路径是否绝对?
- java 是否在 PATH 中(用 `which java` 验证)?
- JDK 版本 >= 21? (`java -version`)
- 试着手动跑: `java -jar .../jrdi-cli-0.1.0-M1.jar serve --stdio --db .../jrdi.db`
  - 应该看到等待 stdin 输入(没有任何输出)
  - 按 Ctrl+C 退出
  - 如果手動能跑,MCP 客户端应该也能

## 12. 例子 / Examples

完整端到端示例见 [`examples/petclinic-mini/`](https://github.com/sulaymanyf/jrdi/tree/master/examples/petclinic-mini):

```sh
$ cd examples/petclinic-mini
$ JAVA_HOME=/path/to/jdk-17+ mvn -B clean package
$ java -jar ../../jrdi-cli/target/jrdi-cli-0.1.0-M1.jar index \
    target/petclinic-mini-1.0.0.jar --with-sources \
    --db sqlite:./petclinic.db --repo-id demo

# 然后配置你的 LLM 客户端连向 sqlite:./petclinic.db
# 问:"What Spring beans does OwnerService inject?"
# LLM 会调用 find_spring_beans + find_spring_injects,
# 返回 ownerService 1 个 bean,2 个 @Autowired 注入点(接口类型)
```

# fake-claude

一个跑在本机的命令行 AI 助手，底层由 DeepSeek 模型提供能力。启动后在终端里对话，模型可以调用本地工具读写文件、执行受限制的只读命令，也可以通过 MCP 接入外部工具服务。

> 中文名：屎山小助手 😄

## 特性

- **终端交互**：`> 你：` / `> AI：` 的流式对话，输入 `exit` 退出
- **会话记忆**：基于 `SessionMemoryAdvisor`，超过 5 轮自动递归摘要压缩，保留最近 5 条原始消息
- **本地工具**：
  - `ShellTools` / `FileSystemTools`（spring-ai-agent-utils）
  - 自定义 `ToolService`：只读 shell 命令 + 受控文件写入
- **MCP 接入**：通过 stdio 启动 MCP 服务（示例为 Docker 版 GitHub MCP Server）
- **安全边界**：命令白名单、路径越界拒绝、写入前自动 `.bak` 备份、输出截断

## 环境要求

| 依赖 | 版本 / 说明 |
| --- | --- |
| JDK | 17+ |
| Maven | 使用自带 `mvnw` 即可 |
| DeepSeek API Key | 必填，见下方配置 |
| Docker | 可选，仅使用 MCP 功能时需要 |

## 快速开始

### 1. 配置 API Key

通过环境变量注入，不要把 key 写进配置文件：

```bash
# Windows (cmd)
set DEEPSEEK_API_KEY=sk-xxxxxxxx

# Windows (PowerShell)
$env:DEEPSEEK_API_KEY="sk-xxxxxxxx"

# Linux / macOS
export DEEPSEEK_API_KEY=sk-xxxxxxxx
```

### 2. 运行

```bash
./mvnw spring-boot:run
```

Windows：

```cmd
mvnw.cmd spring-boot:run
```

打包运行：

```bash
./mvnw clean package
java -jar target/fake-claude-0.0.1-SNAPSHOT.jar
```

## 配置说明

配置文件：`src/main/resources/application.properties`

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `spring.ai.deepseek.api-key` | `${DEEPSEEK_API_KEY}` | API Key，走环境变量 |
| `spring.ai.deepseek.chat.options.model` | `deepseek-flash` | 使用的模型 |
| `spring.ai.deepseek.chat.options.temperature` | `0.7` | 采样温度 |
| `fake-claude.shell.work-dir` | `${user.dir}` | shell 工具默认工作目录 |
| `fake-claude.shell.timeout-seconds` | `60` | 命令超时，取值 1–300 |
| `fake-claude.shell.charset` | `GBK` | 读取子进程输出用的编码，Windows 控制台默认 GBK，乱码时改成 `UTF-8` |
| `fake-claude.fs.base-dir` | `${user.dir}` | 文件工具的写入根目录，越界一律拒绝 |

## 内置工具与安全策略

`ToolService` 对模型暴露的能力是**刻意收窄**的：

**允许的 shell 命令（白名单）**

- `dir`
- `git --version`
- `java -version`
- `echo <简单文本>`

此外的任何命令（构建、脚本、安装依赖、联网、删除文件等）都会被拒绝，并提示用户在自己的终端中执行。

**文件写入限制**

- 只能写入 `fake-claude.fs.base-dir` 之内，词法路径和 `toRealPath()` 双重校验
- 拒绝写入 `.git` 目录
- 拒绝符号链接 / junction / 非普通文件目标
- 覆盖已有文件前自动生成同名 `.bak` 备份
- 单次写入上限 20 万字符，shell 输出上限约 20 KB（超出截断）

## 项目结构

```
fake-claude/
├── pom.xml
├── LICENSE
├── README.md
├── docs/                      开发日志
└── src/
    ├── main/
    │   ├── java/com/example/fakeclaude/
    │   │   ├── FakeClaudeApplication.java   入口 + 命令行对话循环
    │   │   ├── ChatMemoryConfiguration.java 会话记忆与摘要压缩
    │   │   └── ToolService.java             自定义工具（shell / 文件）
    │   └── resources/
    │       ├── application.properties
    │       ├── banner.txt
    │       └── mcp/mcp-servers-config.json  MCP 服务配置
    └── test/java/com/example/fakeclaude/
```

## MCP 配置

编辑 `src/main/resources/mcp/mcp-servers-config.json`，把 token 换成自己的：

```json
{
  "mcpServers": {
    "github": {
      "command": "docker",
      "args": ["run", "-i", "--rm", "-e", "GITHUB_PERSONAL_ACCESS_TOKEN", "mcp/github"],
      "env": { "GITHUB_PERSONAL_ACCESS_TOKEN": "your_token" }
    }
  }
}
```

> ⚠️ 这个文件会被提交到仓库。请勿把真实 token 写进去——改用环境变量或本机未跟踪的配置覆盖。

## 免责声明

本项目仅用于学习与个人效率场景。模型生成的命令与文件改动**不一定正确**，请在使用前自行确认；由此造成的任何损失由使用者承担。本项目与 Anthropic、OpenAI、DeepSeek 均无关联。

## License

[MIT](LICENSE)

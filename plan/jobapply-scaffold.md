# 脚手架：本地单模块 Spring Boot

状态：完成。日期：2026-10-22。

产品目标与技术路线见 [jobapply-base.md](jobapply-base.md)。逻辑目录见 [jobapply-architecture.md](jobapply-architecture.md)。本文件只记这一刀实际落地的工程。

## 目标

在 `backend/` 放一个能编译的 Spring Boot 应用。Spring 上下文在没有网络、没有 API key 的情况下能启动。LangGraph4j 1.9.6 与 Spring AI 2.0.1 在 classpath 上可解析。

## 不做

- Agent 行为、Harness 循环、Workflow 图、Checkpoint
- Memory、业务 API、Web 端口
- 前端框架、`storage/` 子目录
- 多模块 reactor、Docker、模型调用、真实密钥

## 钉死的版本

| 项 | 坐标或值 |
| --- | --- |
| 语言 | Java 21（`java.version` = 21；本机 Homebrew `openjdk@21` 21.0.13，在 `~/.zshrc` 设为全局默认） |
| 构建 | Maven 3.10.0（Homebrew）。单模块，`groupId` `jobapply`，`artifactId` `jobapply`，`version` `0.0.1-SNAPSHOT` |
| Spring Boot | `org.springframework.boot:spring-boot-starter-parent:4.1.1` |
| Spring AI | BOM `org.springframework.ai:spring-ai-bom:2.0.1`；直接依赖 `spring-ai-client-chat` |
| LangGraph4j | `org.bsc.langgraph4j:langgraph4j-core:1.9.6` |
| LangGraph4j Spring AI | `org.bsc.langgraph4j:langgraph4j-spring-ai:1.9.6` |
| 测试 | `spring-boot-starter-test`（版本由 Boot 父 POM 管理） |

版本与 `../Eco_Agent/plan/afts-scaffold.md` 相同。没有加入 Spring AI Alibaba，也没有加入 `spring-ai-starter-model-*`。

## 创建了什么

- `backend/pom.xml`
- `backend/src/main/java/jobapply/JobApplyApplication.java`
- `backend/src/main/resources/application.yml`（只有 `spring.application.name: jobapply`）
- `backend/src/test/java/jobapply/JobApplyApplicationTest.java`
- 空包，各只有 `package-info.java`：`config`、`agents`、`harness`（`runtime`、`context`、`policies`）、`workflow`（`graphs`、`nodes`、`state`、`checkpoints`）、`memory`、`tools`、`domain`、`persistence`、`observability`、`api`
- 仓库根：`frontend/`、`storage/`、`docs/`（`.gitkeep`），`.gitignore`

应用使用 `spring-boot-starter`，不打开 Web 端口。测试用 `@SpringBootTest` 启动上下文，并用类字面量确认 `StateGraph`、`SpringAIStateSerializer`、`ChatClient` 在 classpath 上。

## 完成定义

- 在 `backend/` 执行 `mvn -B clean test` 退出码 0。
- `jobapply.JobApplyApplicationTest`：Tests run: 1, Failures: 0, Errors: 0, Skipped: 0。
- 启动日志含 `Spring Boot :: (v4.1.1)`，`application.yml` 中没有密钥。

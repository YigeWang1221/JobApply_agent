# 开发日志

只记已经发生的事实。动机和未决问题写在 `dev-recoding.md`。每条使用下面的格式。

```text
日期：
指令：用户要求做什么
结果：实际落地了什么
验证：命令或检查；没做就写「未运行」
偏差：和指令不一致的地方；没有就写「无」
```

## 2026-10-22 · 流程文档与脚手架

- 日期：2026-10-22
- 指令：用 Spring AI + LangGraph4j 开发 JobApply，参考 `Eco_Agent` 的 md 文件和 `backend/`、`frontend/` 结构，先搭基础脚手架。中途要求 JDK 不要按项目单独配置，改为系统级默认。
- 结果：新增 `AGENTS.md`、`CLAUDE.md`、`dev-plan.md`、`dev-log.md`、`dev-limit.md`、`dev-recoding.md`、`.cursor/rules/dev-process.mdc`、`plan/`（`README.md`、`00-process.md`、`jobapply-base.md`、`jobapply-architecture.md`、`jobapply-scaffold.md`）、`.gitignore`。`backend/` 单模块 Spring Boot：`pom.xml`、`JobApplyApplication`、`application.yml`、上下文测试，`jobapply` 下 16 个只有 `package-info.java` 的包。`frontend/`、`storage/`、`docs/` 为空目录。本机经 Homebrew 安装 `openjdk@21`（21.0.13）与 `maven`（3.10.0），并在 `~/.zshrc` 设置 `JAVA_HOME` 与 `PATH`，作为全局默认 JDK。未提交。
- 验证：新 shell 中 `java -version` 为 21.0.13。在 `backend/` 执行 `mvn -B clean test` 退出码 0，`BUILD SUCCESS`；`jobapply.JobApplyApplicationTest`：Tests run: 1, Failures: 0, Errors: 0, Skipped: 0；日志含 `Spring Boot :: (v4.1.1)`。`mvn dependency:tree` 解析为 `spring-ai-client-chat:2.0.1`、`spring-ai-model:2.0.1`、`spring-ai-commons:2.0.1`、`langgraph4j-core:1.9.6`、`langgraph4j-spring-ai:1.9.6`。
- 偏差：相对 Eco 的目录去掉了 `execution/`、`python/`、`workflow/delegation`（见 `plan/jobapply-architecture.md`，待确认）。`/Library/Java/JavaVirtualMachines` 软链接需要 sudo，未做。

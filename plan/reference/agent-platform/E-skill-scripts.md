# E. 导入的 Agent Skill 里的 `scripts/`：主流 Agent 怎么跑、怎么防它读密钥

参考材料，不是施工步骤。2026-11-25 调研。问题来源：`plan-A-java.md` 4.1 写了「`scripts/` 不执行」，理由之一是第三方脚本能读到 `storage/settings/.env`、`personal.env`；用户质疑「别的 Agent 并不怕」。

## 看了什么（版本）

| 对象 | 来源 | 版本 / 地址 |
| --- | --- | --- |
| agentskills.io 规范 | 本地克隆 `agentskills` | `83021e2`（`docs/specification.mdx`、`docs/client-implementation/adding-skills-support.mdx`、`docs/skill-creation/using-scripts.mdx`） |
| Codex CLI | 本地克隆 `codex` | `9f3cb25`（`codex-rs/sandboxing/src/seatbelt*.{rs,sbpl}`、`codex-rs/skills/src/invocation.rs`、`codex-rs/protocol/src/protocol.rs`、`codex-rs/config/src/shell_environment_policy.rs`、`codex-rs/core/README.md`） |
| opencode | 本地克隆 `opencode` | `ea8aa5b`（`packages/core/src/plugin/agent.ts`、`permission/policy.ts`、`skill.ts`） |
| Goose | 本地克隆 `goose` | `8256b9a`（`documentation/blog/2026-02-23-goose-v1-25-0/index.md`、`documentation/docs/guides/environment-variables.md`） |
| Hermes | `~/.hermes/hermes-agent`（只读代码，未读配置/密钥/记忆/日志） | `e5dad4ac5`（`SECURITY.md`、`tools/skills_guard.py`、`tools/environments/local.py`、`tools/code_execution_tool.py`、`agent/file_safety.py`） |
| Deep Agents | `../deepagents` | `3e7d5fa`（`libs/deepagents/deepagents/middleware/skills.py`、`backends/local_shell.py`） |
| spring-ai-agent-utils | 本地克隆 | `3c9d5dc`（`docs/tools/ShellTools.md`、`docs/tools/SkillsTool.md`） |
| pi（pi-mono，顺带） | 本地克隆 | `bfafd8e`（`packages/coding-agent/docs/security.md`、`skills.md`） |
| Claude Code | 官方文档 | https://code.claude.com/docs/en/sandboxing ，https://code.claude.com/docs/en/skills ，https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview |
| Gemini CLI | 官方文档 | https://geminicli.com/docs/cli/sandbox/ （页面注明 2026-06-18 起被 Antigravity CLI 取代） |

## 一句话结论

**没有一家给 skill 脚本单独的执行机制，全都是让模型用通用 shell 工具去跑**；它们「不怕」不是因为脚本读不到密钥，而是：(1) 定位是开发者工具，把装 skill 等同于「装软件」，信任交给用户；(2) 有的有通用的 shell 防线（审批 / OS 沙箱）。**默认配置下，所有本地 Agent 里的脚本都能读到 `~/.env`、`~/.ssh` 这类家目录文件**——即便开了沙箱也一样（Codex、Claude Code、Gemini 的 Seatbelt 默认都是「全盘可读、限制写」）。真正把密钥挡住的只有两种做法：**单独 deny 读某些路径**（Claude Code `denyRead` / `credentials`、Codex `unreadable_globs`），或者**跑在容器 / 远端里**（Claude API、Docker 后端）。

## 逐个看

### agentskills.io 规范
1. 机制：`scripts/` 只是「agents can run 的可执行代码」，支持哪些语言「depend on the agent implementation」（`specification.mdx` 191–197 行）。`using-scripts.mdx` 讲的是 `uv run` / `npx` / `deno run` 等，默认 Agent 有 shell。
2. 防护：规范**没有**脚本安全章节。唯一与安全相关的是实现指南里的「Trust considerations」：项目级 skill 可能来自不可信仓库，建议按「文件夹是否受信」决定加载（`adding-skills-support.mdx` 89–91 行）；以及专用激活工具「可以 enforce permissions or prompt for user consent」。反而建议**把 skill 目录加白名单**，免得每次读文件都弹确认（310–312 行）。
3. 能否读 `.env`：规范不管，取决于宿主。
4. `allowed-tools`（实验性）：预批准的工具列表，例如 `Bash(git:*)`——是**放权**，不是限制。

### Claude Code
1. 机制：通用 Bash 工具。官方原文：「Code: Executable scripts … that Claude runs using bash」。`${CLAUDE_SKILL_DIR}` 给脚本绝对路径；`allowed-tools: Bash(${CLAUDE_SKILL_DIR}/scripts/render.sh *)` 能让脚本**免确认**运行（仅限该轮）。
2. 防护：默认是**权限模式**——Bash 命令按规则走，没命中 allow 的会弹确认。**OS 沙箱默认关闭**（`/sandbox` 或 `sandbox.enabled: true` 打开）；macOS 用 Seatbelt，Linux/WSL2 用 bubblewrap，基于开源 `@anthropic-ai/sandbox-runtime`。沙箱打开后有「auto-allow」：在沙箱里跑的命令**不再逐条确认**。网络走本机代理，允许域名表默认空。
3. 读 `.env`：**能**。文档原话：沙箱默认「read access to the entire computer, except certain denied directories. This default still allows reading credential files」。要挡得自己配：`sandbox.filesystem.denyRead`（如 `["~/"]` + `allowRead: ["."]`）、`sandbox.credentials.files/envVars`（deny `~/.ssh`、`GITHUB_TOKEN` 等），或 `permissions.blockReadsOutsideWorkingDirectories`。
4. 信任：「Use Skills only from trusted sources … Treat like installing software」；项目 skill 的 `allowed-tools` 在未受信目录也生效，提醒用户先审。Claude API 上的 skill 跑在**无网络的容器**里——这是唯一默认把宿主密钥隔开的形态。

### Codex CLI
1. 机制：通用 shell 工具。`skills/src/invocation.rs` 只是**事后识别**某条 shell 命令是不是在跑 skill 脚本（看 `.py/.sh/.js/.ts/.rb/.pl/.ps1`），用于统计/隐式激活，不是执行器。
2. 防护：**默认就开 OS 沙箱**。macOS 固定调用 `/usr/bin/sandbox-exec`（`seatbelt.rs:105`，故意不走 PATH 防篡改），profile 以 `(deny default)` 起步（`seatbelt_base_policy.sbpl`，抄自 Chrome renderer 策略）。`workspace-write` = 只读策略 + 工作区可写（`.git`、`.codex` 仍只读），`network_access` 默认 `false`（`protocol.rs`）。审批：受信项目默认 `on-request`（模型需要越过沙箱时才问），未受信用旧的 untrusted 逻辑（`core/src/config/mod.rs` ~4160 行）。Linux 用 bubblewrap / landlock。
3. 读 `.env`：**能**。只读/工作区写两种模式下 `has_full_disk_read_access` 时 profile 生成 `(allow file-read*)`（`seatbelt.rs` 878–881 行）。可以配 `unreadable_roots` / `unreadable_globs`，会生成 `(deny file-read* (regex …))`（548–581 行），但默认是空的。环境变量：`shell_environment_policy.ignore_default_excludes` 默认 `true`（`shell_environment_policy.rs:136`），即**不**过滤 `*KEY*`/`*SECRET*`/`*TOKEN*`，子进程继承全部环境变量。
4. 信任：没有针对 skill 脚本的特别规则；靠沙箱 + 审批这套通用机制。

### opencode
1. 机制：通用 `shell`（bash）工具；`skill.ts` 只告诉模型「`scripts/` 等相对路径相对于 skill 目录」。
2. 防护：**无 OS 沙箱**。只有权限规则（allow/ask/deny）。默认 agent 基本全放开；内置规则里 `read` 工具对 `*.env`、`*.env.*` 是 `ask`，`*.env.example` 放行（`plugin/agent.ts` 87–90 行）；未命中规则默认 `ask`（`permission/policy.ts:104`）。
3. 读 `.env`：`read` 工具读会被问；**脚本通过 shell 读不受这条规则约束**，能读。
4. 信任：无专门规定。

### Goose
1. 机制：skill 通过 Summon 扩展的 `load` 装进上下文，脚本由 developer 扩展的 shell 工具跑。
2. 防护：v1.25 曾加过可选 macOS Seatbelt 沙箱（`GOOSE_SANDBOX=true`），**已移除**：博客顶部注明「The goose server process … runs with the same permissions as your user account and is not sandboxed at the OS level」。只剩 `GOOSE_MODE`（`auto` / `approve` / `smart_approve`），**默认 `auto`**，即不确认直接跑（`environment-variables.md:257`）。
3. 读 `.env`：**能**。
4. 信任：无专门规定。

### Hermes
1. 机制：通用 `terminal` 工具（本地后端），另有 `execute_code` 跑 Python。
2. 防护：默认 `terminal.backend: local`，**直接在宿主机跑**，容器（Docker/Modal/Daytona）可选。危险命令审批默认开（只针对「危险」命令模式，不是每条）。终端子进程**剥掉各家 provider 的 API key 环境变量**（`tools/environments/local.py` 的 blocklist）；`execute_code` 子进程只放行 skill 声明的 `env_passthrough`；所有显示输出过 `agent/redact.py` 做密钥脱敏。社区 skill 安装前过 **Skills Guard**（正则静态扫描外传/注入/破坏/持久化，`community` 来源有任何发现就拦，`openai/skills`、`anthropics/skills` 视为 trusted）。`agent/file_safety.py` 只禁**写** `~/.hermes/.env`、`~/.ssh` 等。
3. 读 `.env`：**能**。代码自己承认：「scripts can still read secrets from disk (e.g. open('~/.hermes/.env'))」（`code_execution_tool.py:1238`），所以才对输出做脱敏。
4. 信任：`SECURITY.md`：「Installed Skills: High trust. Equivalent to local host code; skills can read environment variables and run arbitrary commands.」

### Deep Agents
1. 机制：系统提示「Skills may contain Python scripts … Always use absolute paths」，用后端的 `execute` 工具跑（`middleware/skills.py` ~900 行）。
2. 防护：取决于后端。`LocalShellBackend` 明写「NO sandboxing or isolation」，建议开 HITL（每条审批）或换 `BaseSandbox`（Daytona/Modal/Runloop/Vercel 等远端沙箱，`libs/partners/`）。值得注意：`inherit_env` 默认 `False`，**默认不继承环境变量**。
3. 读 `.env`：本地后端**能**，文档原话「Agents can read any accessible file, including secrets (API keys, credentials, .env files, SSH keys, etc.)」。远端沙箱里读不到宿主文件。
4. 信任：同上警告。

### spring-ai-agent-utils（Java，与我们最像）
1. 机制：`SkillsTool` 只加载说明（「Support for … helper scripts」），脚本由 `ShellTools`（Bash）跑。
2. 防护：`ExecBackend` 可插拔；默认 `LocalExecBackend` 在宿主跑；可选 `DockerCliExecBackend`。`cleanEnvironment(true)` 可不继承 JVM 环境变量，**默认继承**（`ShellTools.md` 259–266 行）。无审批。
3. 读 `.env`：本地后端**能**。
4. 信任：无专门规定。

### Gemini CLI
1. 机制：通用 `run_shell_command`。
2. 防护：完整沙箱需 `-s` / `GEMINI_SANDBOX` / `"sandbox": true` 打开；macOS 用 Seatbelt，默认 profile `permissive-open`：写只限项目目录、**读放开、网络放开**；另有 `restrictive-*`、`strict-*`（读也限制）。Docker/Podman 模式只挂载工作区。
3. 读 `.env`：Seatbelt 默认 profile **能**；`strict-*` 或容器模式不能。

### pi（顺带）
`security.md`：「it does not ask for approval before every tool call」；安全只来自 OS/容器边界，「project trust … does not make that content or the resulting actions safe」。`skills.md`：项目 skill 能让模型跑脚本，授予项目信任前先审。

## 总表

| Agent | 脚本怎么跑 | 默认防护 | 默认能读 `~/.env`/本地密钥文件 | 默认子进程带宿主环境变量 |
| --- | --- | --- | --- | --- |
| 规范 | 宿主决定 | 无规定 | — | — |
| Claude Code | Bash 工具 | 逐条权限确认；沙箱默认关，开了可免确认 | 能（可配 `denyRead`/`credentials`） | 是（可按名 deny） |
| Codex | shell 工具 | **Seatbelt/bwrap 默认开**，写限工作区，网络关；按需审批 | 能（可配 `unreadable_globs`） | 是 |
| opencode | shell 工具 | 权限规则，无沙箱 | 能（只有 `read` 工具对 `*.env` 问） | 是 |
| Goose | shell 工具 | `auto`，无沙箱 | 能 | 是 |
| Hermes | terminal 工具 | 危险命令审批 + 安装前静态扫描 + 输出脱敏 | 能 | **剥掉 API key 变量** |
| Deep Agents | `execute` | 本地无隔离；推荐远端沙箱 | 本地能 | **默认不继承** |
| spring-ai-agent-utils | Bash | 无；可选 Docker | 能 | 是（可 `cleanEnvironment`） |
| Gemini CLI | shell 工具 | 沙箱可选，Seatbelt 默认读全放开 | 能（`strict-*`/容器不能） | 是 |

**用户说得对的部分**：主流 Agent 确实都执行 skill 脚本，而且大多数默认不加专门防护——「不执行」在业界是少数派。**原计划理由也成立的部分**：它们默认确实挡不住脚本读 `.env`；它们能接受，是因为用户是开发者、本来就在给 Agent 一个 shell，「密钥不外泄」不是它们的硬线。JobApply 的硬线写在 `dev-limit.md`，所以要么不跑，要么跑在能挡住密钥读取的边界里。

## macOS sandbox-exec 实测（本机，2026-11-25）

- 本机 macOS 27.0，`/usr/bin/sandbox-exec` 存在。man page 标为 deprecated 多年，但 Codex、Claude Code（sandbox-runtime）、Gemini CLI、Chrome 都还在用，仍可用。
- 在 scratchpad 里用**假** `.env` 测（未碰真实密钥文件）。profile：

```scheme
(version 1)
(allow default)
(deny file-read*  (subpath "<假 storage 目录>"))
(deny file-write* (require-all (require-not (subpath "<临时输出目录>"))
                               (require-not (literal "/dev/null"))))
(deny network*)
```

  结果：`cat`、`python3 -I`、`node` 读假 `.env` 都 `Operation not permitted`；读 skill 目录正常；写 skill 目录被拒、写临时目录成功；`curl` 出不去。
- 坑：SBPL 一条规则里多个过滤条件是「或」，要「且」必须包 `require-all`（第一次写错导致临时目录也不能写）。路径要先解析符号链接（`/tmp` → `/private/tmp`），Codex 同样做了 canonicalize。
- `(allow default)` + deny 列表是 Claude Code sandbox-runtime / Gemini `permissive-*` 的思路，简单、兼容性好；Codex 是 `(deny default)` 白名单，更严但要维护一大串 sysctl/mach 规则。
- 前提：JobApply 的 macOS 包是 jpackage app-image，**没开 App Sandbox**（未见 entitlements），所以能再套 sandbox-exec；如果将来上 App Store 开 App Sandbox，嵌套 `sandbox_apply` 会失败（Codex 测试里能看到这个报错）。

## 脚本要什么运行时，我们带了什么

- 规范说常见是 Python、Bash、JavaScript；`using-scripts.mdx` 推 `uv run`（PEP 723）、`npx`、`deno`、`bun`。公开 skill（如 anthropics/skills 的 pdf/docx/xlsx）以 **Python** 为主，常依赖第三方包（pypdf、openpyxl 等）。
- App 已带：`resources/node/node`（为 Playwright MCP 打包，见 `appbuild/README.md`），所以 `.js`/`.mjs` 能直接跑。`/bin/bash`、`/bin/sh`、`/bin/zsh` 系统自带。
- App **不带 Python**。macOS 的 `/usr/bin/python3` 是 Command Line Tools 的桩，没装 CLT 时会弹系统安装框；装了也是 3.9 且没有第三方包。本机有 3.9.6，但不能假定用户机器有。
- 结论：Node 和 shell 脚本能可靠跑；Python 脚本只能「有就用、没有就告诉用户」，不为它打包 Python。

## 给 JobApply 的建议

**建议：加一个 `run_skill_script` 工具，在 sandbox-exec 里跑，导入时一次性信任，之后不再逐次确认。** 这守住「密钥不外泄」，又不违反「不反复确认」。

1. **专用工具，不是 shell**：`run_skill_script(skill, path, args[], stdin?)`。`path` 必须落在该 skill 的 `scripts/` 里（解析符号链接后再判）；按扩展名选解释器：`.js/.mjs/.cjs` → 自带 node；`.sh` → `/bin/bash`；`.py` → 找到 `python3` 才跑，否则把「本机没有 Python」原样返回给模型。不提供任意命令行，模型也就没法顺手 `cat` 别的东西。
2. **沙箱 profile**（`(allow default)` + 拒绝列表，跟 Claude Code / Gemini 的做法一致）：
   - 拒绝读：整个 storage 根目录（含 `settings/.env`、`personal.env`、简历、transcript）**再放开**当前 skill 目录（`storage/playbook/skills/<name>/`，SBPL 后写的规则优先，可用 deny 整个 storage + allow 该子目录）；`~/Library/Application Support/JobApply/`（`app.yaml`）；用户的 Chrome profile（`~/Library/Application Support/Google/Chrome`，里面是登录 cookie，等于账号）；`~/.ssh`、`~/.aws`、`~/.config`、`~/.netrc`、`~/.npmrc`、`~/.gnupg`、家目录下的 `.env*`（用 regex）。
   - 只允许写：每次运行新建的临时目录（`$TMPDIR/jobapply-skill-<id>/`）和 `/dev/null`；脚本的输出文件从这里取，需要交给投递流程时由后端复制进作业目录。
   - 网络：默认关（`(deny network*)`）。导入时如果 skill 声明需要网络（`compatibility` 里写了，或用户勾选），这个 skill 改成开。说明一次风险即可，不逐次问。
   - 进程：清空环境变量，只给 `PATH`（指向自带 node 和系统目录）、`HOME`（指向临时目录）、`LANG`、`TMPDIR`。参照 Deep Agents `inherit_env=False`、Hermes 剥 key 的做法。
   - 超时、输出截断沿用现有工具的上限；输出再过一遍现有的日志/事件脱敏（参照 Hermes `redact`）。
3. **信任在导入时给一次**：导入预览里列出 `scripts/` 的文件和大小，写明「会在沙箱里运行：读不到你的密钥、资料和浏览器登录，只能写临时目录」，用户勾选导入即视为信任；之后调用不弹框。不做 Hermes 那种正则扫描（误报多，且沙箱已经兜底）。内置 skill 同样走沙箱，不搞特权。
4. **只在 macOS 上开**：Windows/Linux 包目前没有等价边界（Linux 可以以后接 bubblewrap，像 Codex），先返回「本平台不支持运行 skill 脚本」，不降级成无沙箱执行。
5. **边界说明（写一次）**：sandbox-exec 防的是读文件和联网；`(allow default)` 下脚本仍能做 mach/IPC 调用（例如钥匙串查询会弹系统授权框，用户能看到），想更严可以改成 Codex 那种 `(deny default)` 白名单，但维护成本高，没必要先做。

如果用户不想现在做，原计划「不执行，可当文本读」也站得住，但理由应改成「没有沙箱就不执行」，而不是「我们不需要」：主流 Agent 都会执行，差别只在边界。

这份是参考结论；要不要把 `plan-A-java.md` 4.1 改成上面的方案，需要用户确认后再写进 plan。

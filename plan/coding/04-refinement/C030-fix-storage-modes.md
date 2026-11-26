# C030-fix · Mode 文件放在 storage 里，改了下次运行就生效

- 对应：[C030](C030-unified-base-agent-and-detox.md)；Master：[memory.md](../../master/memory.md)、[agent-runtime.md](../../master/agent-runtime.md)
- 状态：**已施工，等用户试（2026-11-25；`mvn -B clean verify` 0）**
- 日期：2026-11-25

## 目标

C030 之后，提示词、工具组、子任务、插件组合都写在 Mode 文件里，但 Mode 文件只从程序内部（classpath）读，还有缓存，改了要重新打包。本步让用户（或 Agent）在 `storage/modes/<名字>.md` 放一个文件就能：

- 覆盖内置的 Mode（同名文件优先）；
- 新加一个 Mode（例如新的子任务），父 Mode 在 `subagents:` 里写上它的名字就能派；
- 下一次运行开始时生效，不重启、不重新打包。

## 怎么做

1. `Modes` 读取顺序：先 `storage/modes/<名字>.md`，没有再用内置的 `modes/<名字>.md`。每次运行开始时读，不再缓存。
2. 不把内置 Mode 复制进 `storage/`（这样升级 App 时内置的会跟着更新）；要改就复制一份到 `storage/modes/` 再改。
3. 文件写错（frontmatter 读不出、插件名 / 工具组名不认识、`subagents:` 里的名字找不到）时，这次运行报错，错误里写清是哪个文件哪一项；不悄悄退回内置的。
4. 硬提交守卫、`{KEY}` 替换都在工具执行器里，Mode 文件怎么写都绕不过；最大深度仍是 2，不随 Mode 文件改。
5. `memory.md` 的 storage 目录树加 `modes/` 一行。

## 不做

- 新插件、新工具仍要写 Java（插件在 `BaseAgent` 里登记）。
- 外部 skill 导入界面、skill 脚本执行（Phase 2）。
- 设置页里编辑 Mode 的界面。

## 怎么确认能用

- `backend/` `mvn -B clean verify` 通过。
- 在临时 storage 里放一个覆盖 `session.md` 的文件和一个新子 Mode，确认下一次运行读的是新文件、写错时报出文件名。
- 真实模型下用新子 Mode：未运行，等用户试。

## 施工记录（2026-11-25）

- `agents/Modes`：`get(StorageLayout, name)`、`forJob(StorageLayout, applySegment)`；先读 `storage/modes/<name>.md`，没有再读内置；去掉缓存，每次运行开始时读。
- `agents/Mode` 加 `source`（文件路径或「内置 modes/<name>.md」），报错时用。
- `BaseAgent`、`SubagentExecutor` 调用时传 `services.layout()`；`StorageLayout.modesDir()`；`plan/master/memory.md` 目录树加 `modes/<name>.md`。
- 报错：读文件时查 frontmatter、`tool-call-limit`、`tools:` 名、`subagents:` 名（storage 和内置都没有）、Mode 名格式；组装运行时查插件名和 `allow-tools:` 名。错误带文件路径和出错项，不退回内置。
- 测试 `ModesTest`：storage 覆盖内置、只在 storage 的子 Mode 能被找到、缺子 Mode 报出文件名。
- 手动：临时 storage 里写错工具组、写坏 YAML，报错都带完整路径；没覆盖的 Mode 读内置。
- 未运行：插件名 / `allow-tools` 写错的报错只看了代码；真实模型下用新 Mode。

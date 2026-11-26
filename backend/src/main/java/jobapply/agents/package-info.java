/**
 * Base Agent 的装配（C030）：外置 Mode（{@code resources/modes/*.md}：系统提示词、工具集、插件组合、循环参数、skill 范围）
 * 的读取（{@code Modes}），按 Mode 装配一次运行的 {@code BaseAgent}，以及 Depth=2/3 单向 Subagent 树
 * （{@code SubagentExecutor}，记录在 {@code projects/<p>/subruns/<subId>.json}）。
 */
package jobapply.agents;

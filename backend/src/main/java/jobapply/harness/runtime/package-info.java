/**
 * Base Agent 的运行时（C030）：唯一的循环 {@code AgentLoopEngine}（停止、插话、上限、调模型、执行工具、结束），
 * 统一的工具登记 {@code ToolRegistry} / {@code ToolBox}（按 Mode 过滤），调主模型（{@code ModelCaller}），
 * 观察的一方（{@code LoopObserver}，界面的按步推送）。
 */
package jobapply.harness.runtime;

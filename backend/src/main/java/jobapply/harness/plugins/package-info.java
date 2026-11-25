/**
 * Base Agent 循环上的插件（C030 PluginChain）：钩子 {@code prompt}、{@code tools}、{@code context}、{@code response}、
 * {@code toolBefore}、{@code toolAfter}、{@code on(event)}。标准插件：申请状态注入与自动记录、开始时的岗位页、页面快照省略、
 * 打转提醒、对话类结束方式、旧网页正文省略、Session / 后续段的上下文。由 Mode 的 {@code plugins:} 组合。
 */
package jobapply.harness.plugins;

/**
 * Agent 工具网关（C030 Slice 2）：{@code Tool}（定义 + 执行）、一次运行的上下文 {@code RunContext} 和结果
 * {@code ToolOutcome}；各工具集（{@code BrowserFillTools}、{@code BrowserReadTools}、{@code ControlTools}、
 * {@code ResearchTools}、{@code FollowupTools}、{@code WorkbenchTools}、{@code ExperienceTools}）；用户 Chrome 的连接与
 * Playwright MCP 客户端。硬提交守卫、C 级 {@code {KEY}} 替换和快照遮值都在浏览器工具的执行里。
 */
package jobapply.tools;

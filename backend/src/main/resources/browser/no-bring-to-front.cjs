// JobApply 交给 Playwright MCP 的 --init-page（C011-fix，2026-10-24）。程序启动时把它写到 storage/cache/ 下，
// 再把路径追加到启动参数里（BrowserMcpProperties）。
//
// @playwright/mcp 0.0.83 每接管一个标签页（每个会话、每一页，包括连上 Chrome 时已经开着的页）都会 require 这个文件、
// 调一次 default({ page })。browser_tabs select 每次都调 page.bringToFront()，CDP 的 Page.bringToFront 在 macOS 上
// 会把 Chrome 拉到所有应用最前面、抢走用户正在用的窗口。这里把 Page 的 bringToFront 换成什么都不做（改在原型上，
// 这个进程里所有的页一起生效）：MCP 照样把选中的页记为当前页，后台页靠焦点模拟照常能点。
async function noBringToFront() {}

exports.default = async ({ page }) => {
  Object.getPrototypeOf(page).bringToFront = noBringToFront;
};

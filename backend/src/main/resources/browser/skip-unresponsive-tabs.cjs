// JobApply 给 Playwright MCP 进程的 preload（C011-fix，2026-10-25）。程序启动时把它写到 storage/cache/playwright-init/，
// 再用 NODE_OPTIONS=--require <路径> 交给 MCP 进程（PlaywrightMcpClient.Server.start）。
//
// 为什么要它：@playwright/mcp 0.0.83 连用户的 Chrome（--cdp-endpoint）时有两处会等「每一个」标签页，而且不设上限：
// 1. 连接时 CRBrowser.connect 等所有页初始化完（_waitForAllPagesToBeInitialized）。睡着的页（被节省内存丢弃后还没点开、
//    恢复后一直没加载、卡死转圈、挂着 alert 对话框、崩溃的页）永远初始化不完，整个连接卡到 --cdp-timeout（30 秒）报错。
// 2. 之后每次调用的末尾，Tab.headerSnapshot 对每一页调 page.title()（要页面执行脚本），一页不答，所有调用都卡住。
// 这里把两处都加上短的上限：连接时最多等 PAGE_INIT_MS，没答的页先不管（它们之后醒了照常出现在标签页列表里）；
// 取标题最多等 TITLE_MS，没答就用上次的标题（没有时为空），上一次还没答完时不再问那一页。
//
// 只改这两处，别的行为不变。打不上补丁（版本变了、结构变了）时只打一行日志，MCP 照原样运行。
'use strict';

const Module = require('module');

const PAGE_INIT_MS = 5000;
const TITLE_MS = 2000;
const TAG = '[jobapply]';
const LATE = Symbol('late');

const lastTitles = new WeakMap();
const pendingTitles = new WeakMap();

const load = Module._load;
Module._load = function jobapplyLoad(request) {
  const exported = load.apply(this, arguments);
  if (typeof request === 'string' && request.includes('playwright-core/lib/coreBundle')) {
    Module._load = load;
    try {
      patch(exported);
    } catch (e) {
      console.error(`${TAG} 标签页补丁没有打上，照原样运行：${e && e.message}`);
    }
  }
  return exported;
};

function patch(core) {
  const contextProto = core && core.server && core.server.BrowserContext && core.server.BrowserContext.prototype;
  const tabProto = core && core.tools && core.tools.Tab && core.tools.Tab.prototype;
  const missing = [];
  if (contextProto && typeof contextProto.initialize === 'function') {
    // CRBrowser.connect 先调默认 context 的 initialize（走到这个基类方法），再等所有页：在这里把那个浏览器类的等待换掉
    const initialize = contextProto.initialize;
    contextProto.initialize = function () {
      try {
        boundPageWait(this._browser && Object.getPrototypeOf(this._browser));
      } catch (e) {
        console.error(`${TAG} 连接时等标签页的上限没有加上：${e && e.message}`);
      }
      return initialize.apply(this, arguments);
    };
  } else {
    missing.push('server.BrowserContext.initialize');
  }
  if (tabProto && typeof tabProto.headerSnapshot === 'function') {
    const headerSnapshot = tabProto.headerSnapshot;
    tabProto.headerSnapshot = function () {
      try {
        boundTitle(this.page && Object.getPrototypeOf(this.page));
      } catch (e) {
        console.error(`${TAG} 取标题的上限没有加上：${e && e.message}`);
      }
      return headerSnapshot.apply(this, arguments);
    };
  } else {
    missing.push('tools.Tab.headerSnapshot');
  }
  console.error(missing.length
    ? `${TAG} 标签页补丁只打上了一部分，缺 ${missing.join('、')}`
    : `${TAG} 标签页补丁已打上：连接时每页最多等 ${PAGE_INIT_MS} ms，取标题最多等 ${TITLE_MS} ms`);
}

/** 连接时等所有页初始化：最多 PAGE_INIT_MS，没答的页跳过。 */
function boundPageWait(proto) {
  if (!proto || proto.__jobapplyBounded || typeof proto._waitForAllPagesToBeInitialized !== 'function') {
    return;
  }
  const wait = proto._waitForAllPagesToBeInitialized;
  proto.__jobapplyBounded = true;
  proto._waitForAllPagesToBeInitialized = async function () {
    const all = Promise.resolve(wait.apply(this, arguments));
    all.catch(() => {});
    let timer;
    const late = new Promise((resolve) => {
      timer = setTimeout(resolve, PAGE_INIT_MS, LATE);
    });
    try {
      if (await Promise.race([all, late]) === LATE) {
        const pages = this._crPages ? [...this._crPages.values()].map((crPage) => crPage._page) : [];
        const asleep = pages.filter((page) => page && !page.initializedOrUndefined()).length;
        console.error(`${TAG} 连接时有 ${asleep} 个标签页 ${PAGE_INIT_MS} ms 内没有回应（睡着或卡住），先不管它们`);
      }
    } finally {
      clearTimeout(timer);
    }
  };
}

/** page.title()：最多 TITLE_MS，没答就用上次的标题；那一页上一次还没答完时直接用上次的，不再问。 */
function boundTitle(proto) {
  if (!proto || proto.__jobapplyBoundedTitle || typeof proto.title !== 'function') {
    return;
  }
  const title = proto.title;
  proto.__jobapplyBoundedTitle = true;
  proto.title = function () {
    if (pendingTitles.has(this)) {
      return Promise.resolve(lastTitles.get(this) ?? '');
    }
    const asked = Promise.resolve(title.apply(this, arguments)).then((value) => {
      lastTitles.set(this, value);
      return value;
    });
    let timer;
    const late = new Promise((resolve) => {
      timer = setTimeout(resolve, TITLE_MS, LATE);
    });
    return Promise.race([asked, late]).then((value) => {
      clearTimeout(timer);
      if (value !== LATE) {
        return value;
      }
      pendingTitles.set(this, asked);
      asked.catch(() => {}).finally(() => pendingTitles.delete(this));
      return lastTitles.get(this) ?? '';
    }, (error) => {
      clearTimeout(timer);
      throw error;
    });
  };
}

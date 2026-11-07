import { useEffect, useState, useSyncExternalStore } from "react";

// 主区域显示什么，记在 location.hash 里（不加路由库）：刷新、前进后退都能回到同一页
export type Route =
  | { kind: "global" }
  | { kind: "project"; id: string }
  | { kind: "session"; id: string }
  | { kind: "job"; id: string }
  | { kind: "settings"; tab: "models" | "profile" };

export function parseRoute(hash: string): Route {
  const [kind, raw] = hash.replace(/^#\/?/, "").split("/");
  const id = raw ? decodeURIComponent(raw) : "";
  switch (kind) {
    case "project":
    case "session":
    case "job":
      return id ? { kind, id } : { kind: "global" };
    case "settings":
      return { kind: "settings", tab: id === "profile" ? "profile" : "models" };
    default:
      return { kind: "global" };
  }
}

export function href(route: Route): string {
  switch (route.kind) {
    case "global":
      return "#/global";
    case "settings":
      return `#/settings/${route.tab}`;
    default:
      return `#/${route.kind}/${encodeURIComponent(route.id)}`;
  }
}

export function go(route: Route) {
  window.location.hash = href(route);
}

export function useRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(window.location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parseRoute(window.location.hash));
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  return route;
}

// App 里的「返回」（C017-fix-back-navigation）：自己记一份页面栈，只放在内存里——
// 窗口每次加载（启动、重启后端、⌘R）都是新的 JS，栈自然是空的；不用 history.length，也不碰 WKWebView 自己的前进后退列表
const MAX_STACK = 50;
const stack: string[] = [href(parseRoute(window.location.hash))];
const listeners = new Set<() => void>();

// 进入的页和栈顶不同才压栈；返回时先弹栈再跳，所以返回不会再压一条（不会 A→B→返回→A→返回 来回跳）
window.addEventListener("hashchange", () => {
  const current = href(parseRoute(window.location.hash));
  if (stack[stack.length - 1] !== current) {
    stack.push(current);
    if (stack.length > MAX_STACK) {
      stack.shift();
    }
  }
  listeners.forEach((l) => l());
});

// 页面还在不在（App 按侧栏已读到的列表给）。没给时都当还在
type Exists = (route: Route) => boolean | Promise<boolean>;
let exists: Exists = () => true;

export function setRouteExists(check: Exists) {
  exists = check;
}

// 侧栏列表刷新后，把已删掉的页从栈里去掉（当前页不动），按钮该置灰就置灰
export function pruneStack(gone: (route: Route) => boolean) {
  const kept = [...stack.slice(0, -1).filter((h) => !gone(parseRoute(h))), stack[stack.length - 1]];
  // 去掉一页后前后两条可能相同，合成一条
  const next = kept.filter((h, i) => i === 0 || kept[i - 1] !== h);
  if (next.length !== stack.length) {
    stack.splice(0, stack.length, ...next);
    listeners.forEach((l) => l());
  }
}

let backing = false;

// 返回：一直弹到一页还在的；都没了就回全局
export async function back() {
  if (stack.length < 2 || backing) {
    return;
  }
  backing = true;
  try {
    stack.pop();
    while (stack.length > 0 && !(await exists(parseRoute(stack[stack.length - 1])))) {
      stack.pop();
    }
    if (stack.length === 0) {
      stack.push(href({ kind: "global" }));
    }
    listeners.forEach((l) => l());
    // replace：不往浏览器历史里再加一条
    window.location.replace(stack[stack.length - 1]);
  } finally {
    backing = false;
  }
}

export function useCanGoBack(): boolean {
  return useSyncExternalStore(
    (l) => { listeners.add(l); return () => listeners.delete(l); },
    () => stack.length > 1,
  );
}

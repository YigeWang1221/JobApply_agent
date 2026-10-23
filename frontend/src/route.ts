import { useEffect, useState } from "react";

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

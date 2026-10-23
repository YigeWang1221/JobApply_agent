import { useEffect, useRef, useState } from "react";
import type { Activity } from "../api";

const POLL_MS = 2000;
const COALESCE_MS = 250;

// 运行中怎么拿到新内容：先连 SSE（activity 直接用；step / message / status 合并成一次重新读取）；
// 连不上或断了就照旧每 2 秒查一次。离开页面时关掉连接
export function useLiveUpdates(url: string, running: boolean, reload: () => Promise<void>) {
  const [activity, setActivity] = useState<Activity | null | undefined>(undefined);
  const [live, setLive] = useState(false);
  const reloadRef = useRef(reload);
  useEffect(() => {
    reloadRef.current = reload;
  }, [reload]);

  useEffect(() => {
    if (typeof EventSource === "undefined") {
      return;
    }
    const source = new EventSource(url);
    let timer: number | undefined;
    const refetch = () => {
      window.clearTimeout(timer);
      timer = window.setTimeout(() => void reloadRef.current(), COALESCE_MS);
    };
    source.onopen = () => setLive(true);
    source.onerror = () => {
      // 后端还没有这个接口（404）或连接断了：关掉，退回轮询
      source.close();
      setLive(false);
    };
    source.addEventListener("activity", (event) => {
      try {
        setActivity(JSON.parse((event as MessageEvent<string>).data || "null") as Activity | null);
      } catch {
        setActivity(undefined);
      }
    });
    for (const name of ["step", "message", "status"]) {
      source.addEventListener(name, refetch);
    }
    return () => {
      window.clearTimeout(timer);
      source.close();
      setLive(false);
    };
  }, [url]);

  useEffect(() => {
    if (!running || live) {
      return;
    }
    const timer = window.setInterval(() => void reloadRef.current(), POLL_MS);
    return () => window.clearInterval(timer);
  }, [running, live]);

  // 不在跑时 SSE 给的 activity 作废，免得停止后还显示「正在…」
  useEffect(() => {
    if (!running) {
      setActivity(undefined);
    }
  }, [running]);

  return { activity, live };
}

import { useEffect, useRef, useState } from "react";
import type { Activity } from "../api";
import { eventsUrl, subscribe, type Connection, type LiveEvent } from "./eventStream";

const COALESCE_MS = 250;
// 保底：通知丢了只影响延迟。只在页面上有东西在跑时才查，不是主路径
const SAFETY_POLL_MS = 30000;
// 断开超过这么久才在页面上提示「重新连接中」
const RECONNECT_HINT_MS = 3000;

export type LiveScope = { session: string } | { job: string };

// Session 页 / 申请工作台怎么拿到新内容（C006-fix 第 1 节）：订阅 /api/events?session= 或 ?job=。
// activity 直接用（只认这一页自己的）；其它事件合并成一次重新读取；caught_up（接上了，可能漏过东西）和 reset（后端重启）也重读
export function useLiveUpdates(scope: LiveScope, running: boolean, reload: () => Promise<void>) {
  const [activity, setActivity] = useState<Activity | null | undefined>(undefined);
  const [reconnecting, setReconnecting] = useState(false);
  const reloadRef = useRef(reload);
  useEffect(() => {
    reloadRef.current = reload;
  }, [reload]);

  const sessionId = "session" in scope ? scope.session : null;
  const jobId = "job" in scope ? scope.job : null;
  const url = eventsUrl(scope);

  useEffect(() => {
    let timer: number | undefined;
    let hint: number | undefined;
    const refetch = () => {
      window.clearTimeout(timer);
      timer = window.setTimeout(() => void reloadRef.current(), COALESCE_MS);
    };
    const onEvent = (event: LiveEvent) => {
      if (event.type === "activity") {
        // Session 页只认 Session 自己的循环（没有 job）；它启动的申请线程的 activity 不算这一页的
        const mine = jobId ? event.job === jobId : event.session === sessionId && !event.job;
        if (mine) {
          setActivity(event.activity ?? null);
        }
        return;
      }
      if (event.type === "caught_up" || event.type === "reset") {
        // 以重读回来的视图为准
        setActivity(undefined);
      }
      refetch();
    };
    const onState = (state: Connection) => {
      window.clearTimeout(hint);
      if (state === "open") {
        setReconnecting(false);
      } else {
        hint = window.setTimeout(() => setReconnecting(true), RECONNECT_HINT_MS);
      }
    };
    const unsubscribe = subscribe(url, onEvent, onState);
    return () => {
      window.clearTimeout(timer);
      window.clearTimeout(hint);
      unsubscribe();
      setReconnecting(false);
    };
  }, [url, sessionId, jobId]);

  useEffect(() => {
    if (!running) {
      return;
    }
    const timer = window.setInterval(() => void reloadRef.current(), SAFETY_POLL_MS);
    return () => window.clearInterval(timer);
  }, [running]);

  // 不在跑时推送给的 activity 作废，免得停止后还显示「正在…」
  useEffect(() => {
    if (!running) {
      setActivity(undefined);
    }
  }, [running]);

  return { activity, reconnecting };
}

// 侧栏、全局 / Project Dashboard：订阅全局 /api/events（整个应用共用一条连接），台账或状态变了就重读；
// 另留一个很慢的保底刷新
const GLOBAL_COALESCE_MS = 500;
const GLOBAL_SAFETY_MS = 60000;

export function useGlobalChanges(reload: () => Promise<void> | void) {
  const reloadRef = useRef(reload);
  useEffect(() => {
    reloadRef.current = reload;
  }, [reload]);

  useEffect(() => {
    let timer: number | undefined;
    const unsubscribe = subscribe(eventsUrl(), (event) => {
      if (event.type === "changed" || event.type === "status" || event.type === "caught_up" || event.type === "reset") {
        window.clearTimeout(timer);
        timer = window.setTimeout(() => void reloadRef.current(), GLOBAL_COALESCE_MS);
      }
    });
    const safety = window.setInterval(() => void reloadRef.current(), GLOBAL_SAFETY_MS);
    return () => {
      window.clearTimeout(timer);
      window.clearInterval(safety);
      unsubscribe();
    };
  }, []);
}

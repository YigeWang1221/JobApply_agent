import type { Activity, BrowserState } from "../api";

// 应用级事件流（C006-fix 第 1 节）：GET /api/events（全局）、?session=、?job=。
// 同一个地址只开一条 EventSource，订阅的人都退出了才关。浏览器自己会带 Last-Event-ID 重连；
// 只有连接被判定为关死（例如后端没起来时代理回了 5xx）才由这里过几秒重新建一条，新连接收到 caught_up 时页面整页重读。
// browser（浏览器连接状态变了）只在全局流里
export const EVENT_NAMES = ["activity", "step", "message", "status", "changed", "browser", "caught_up", "reset"] as const;
export type EventName = (typeof EVENT_NAMES)[number];

export interface LiveEvent {
  type: EventName;
  session?: string | null;
  job?: string | null;
  activity?: Activity | null;
  browser?: BrowserState | null;
}

export type Connection = "open" | "connecting";

type Listener = (event: LiveEvent) => void;
type StateListener = (state: Connection) => void;

interface Channel {
  source: EventSource | null;
  retry: number | undefined;
  delay: number;
  state: Connection;
  listeners: Set<Listener>;
  stateListeners: Set<StateListener>;
}

const RETRY_MIN_MS = 2000;
const RETRY_MAX_MS = 15000;
const channels = new Map<string, Channel>();

export function eventsUrl(scope?: { session: string } | { job: string }): string {
  if (!scope) {
    return "/api/events";
  }
  return "session" in scope
    ? `/api/events?session=${encodeURIComponent(scope.session)}`
    : `/api/events?job=${encodeURIComponent(scope.job)}`;
}

export function subscribe(url: string, listener: Listener, onState?: StateListener): () => void {
  let channel = channels.get(url);
  if (!channel) {
    channel = { source: null, retry: undefined, delay: RETRY_MIN_MS, state: "connecting",
      listeners: new Set(), stateListeners: new Set() };
    channels.set(url, channel);
    connect(url, channel);
  }
  channel.listeners.add(listener);
  if (onState) {
    channel.stateListeners.add(onState);
    onState(channel.state);
  }
  const owned = channel;
  return () => {
    owned.listeners.delete(listener);
    if (onState) {
      owned.stateListeners.delete(onState);
    }
    if (owned.listeners.size === 0) {
      window.clearTimeout(owned.retry);
      owned.source?.close();
      channels.delete(url);
    }
  };
}

function connect(url: string, channel: Channel) {
  if (typeof EventSource === "undefined") {
    return;
  }
  const source = new EventSource(url);
  channel.source = source;
  const setState = (state: Connection) => {
    if (channel.state !== state) {
      channel.state = state;
      channel.stateListeners.forEach((fn) => fn(state));
    }
  };
  source.onopen = () => {
    channel.delay = RETRY_MIN_MS;
    setState("open");
  };
  source.onerror = () => {
    setState("connecting");
    if (source.readyState === EventSource.CLOSED && channels.get(url) === channel) {
      window.clearTimeout(channel.retry);
      channel.retry = window.setTimeout(() => connect(url, channel), channel.delay);
      channel.delay = Math.min(channel.delay * 2, RETRY_MAX_MS);
    }
  };
  for (const type of EVENT_NAMES) {
    source.addEventListener(type, (raw) => {
      let data: Omit<LiveEvent, "type"> = {};
      try {
        data = JSON.parse((raw as MessageEvent<string>).data || "{}") ?? {};
      } catch {
        // reset 这类事件可以没有数据
      }
      channel.listeners.forEach((fn) => fn({ ...data, type }));
    });
  }
}

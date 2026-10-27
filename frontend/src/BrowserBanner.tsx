import { useCallback, useEffect, useState } from "react";
import { api, errorText, type BrowserState } from "./api";
import { eventsUrl, subscribe } from "./chat/eventStream";

// 顶部的浏览器状态条（C006-fix-browser 第 1 节）：浏览器连接只有一份状态，断开或卡住（C011-fix）时在这里说原因和怎么恢复。
// 跟着全局事件流的 browser 事件；接上流（caught_up）或后端重启（reset）时重新读一次
export default function BrowserBanner() {
  const [state, setState] = useState<BrowserState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setState(await api.browser());
    } catch {
      // 后端没起来时不在这里提示（事件流和页面自己会显示）
    }
  }, []);

  useEffect(() => {
    void load();
    return subscribe(eventsUrl(), (event) => {
      if (event.type === "browser" && event.browser) {
        setState(event.browser);
        setError(null);
      } else if (event.type === "caught_up" || event.type === "reset") {
        void load();
      }
    });
  }, [load]);

  if (!state || (state.state !== "disconnected" && state.state !== "stalled")) {
    return null;
  }
  const stalled = state.state === "stalled";

  async function reconnect() {
    setBusy(true);
    setError(null);
    try {
      const next = await api.reconnectBrowser();
      setState(next);
      if (next.state === "disconnected" || next.state === "stalled") {
        setError(next.state === "stalled" ? "还是没有回应" : "还是连不上");
      }
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="browserbar" role="status">
      <span>
        <b>{stalled ? "浏览器卡住了" : "浏览器断开了"}</b>
        {state.reason && <span className="muted">（{state.reason}）</span>}
        {stalled
          ? "。Agent 正在用的那一页没有回应（卡死转圈，或者挂着要点的对话框），它会自己换个做法；别的标签页睡着不影响。"
          : <>。下次用浏览器时会自己再连；Chrome 弹出允许提示时点允许，没开过远程调试时先在 <code>chrome://inspect/#remote-debugging</code> 打开开关。</>}
        {error && <span className="error">{error}</span>}
      </span>
      <button className="primary" disabled={busy} onClick={() => void reconnect()}>
        {busy ? "连接中…" : "重新连接"}
      </button>
    </div>
  );
}

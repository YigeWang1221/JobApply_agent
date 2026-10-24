import { useCallback, useEffect, useState } from "react";
import { api, errorText, type BrowserState } from "./api";
import { eventsUrl, subscribe } from "./chat/eventStream";

// 顶部的浏览器状态条（C006-fix-browser 第 1 节）：浏览器连接只有一份状态，断开时在这里说原因和怎么恢复。
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

  if (!state || state.state !== "disconnected") {
    return null;
  }

  async function reconnect() {
    setBusy(true);
    setError(null);
    try {
      const next = await api.reconnectBrowser();
      setState(next);
      if (next.state === "disconnected") {
        setError("还是连不上");
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
        <b>浏览器断开了</b>
        {state.reason && <span className="muted">（{state.reason}）</span>}
        。在 Chrome 打开 <code>chrome://inspect/#remote-debugging</code> 的开关后点重新连接。
        {error && <span className="error">{error}</span>}
      </span>
      <button className="primary" disabled={busy} onClick={() => void reconnect()}>
        {busy ? "连接中…" : "重新连接"}
      </button>
    </div>
  );
}

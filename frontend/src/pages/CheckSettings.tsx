import { useEffect, useState } from "react";
import { api, errorText } from "../api";
import { toast } from "../toast";

// 设置页「查看间隔」（C026）：多少天查看一次投递状态（全局）。Project 主页 Dashboard 上可以单独设；到期只提醒，不自动查
export default function CheckSettings() {
  const [current, setCurrent] = useState<{ days: number; defaultDays: number } | null>(null);
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function show(settings: { days: number; defaultDays: number }) {
    setCurrent(settings);
    setText(String(settings.days));
  }

  useEffect(() => {
    api.checkSettings().then(show, (e) => setError(errorText(e)));
  }, []);

  async function save(days: number) {
    setBusy(true);
    try {
      show(await api.saveCheckSettings(days));
      setError(null);
      toast("已保存，「该查看了」按新天数算");
    } catch (e) {
      setError(errorText(e));
      toast(errorText(e), "error");
    } finally {
      setBusy(false);
    }
  }

  if (!current) {
    return (
      <section className="card">
        <h2>查看间隔</h2>
        {error ? <p className="error">{error}</p> : <p>加载中…</p>}
      </section>
    );
  }
  const value = /^\d+$/.test(text.trim()) ? Number(text.trim()) : null;
  const invalid = value === null || value < 1 || value > 90;
  return (
    <section className="card">
      <h2>查看间隔</h2>
      <div className="form-grid">
        <label>
          每几天查看一次投递状态（默认 {current.defaultDays}）
          <input type="number" min={1} max={90} inputMode="numeric" value={text}
            onChange={(e) => setText(e.target.value)} />
        </label>
      </div>
      <p className="muted small">
        最近一次查看（没查看过就从投递那天）过了这么多天，申请就显示「该查看了」。只是提醒：查状态要你在 Dashboard 点按钮。
        Project 主页的 Dashboard 上可以给一个 Project 单独设。
      </p>
      <button className="primary" disabled={busy || invalid || value === current.days} onClick={() => save(value ?? current.days)}>
        保存
      </button>{" "}
      <button disabled={busy || current.days === current.defaultDays} onClick={() => save(current.defaultDays)}>恢复默认</button>
      {invalid && <p className="error">填 1 到 90 的整数</p>}
      {error && <p className="error">{error}</p>}
    </section>
  );
}

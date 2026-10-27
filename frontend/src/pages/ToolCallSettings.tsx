import { useEffect, useState } from "react";
import { api, errorText, type ToolCallSettings as Settings } from "../api";
import { toast } from "../toast";

// 设置页「工具调用上限」（C018）：申请每次运行、对话每条消息最多几次工具调用。留空或 0 = 不限。保存后马上生效
export default function ToolCallSettings() {
  const [current, setCurrent] = useState<Settings | null>(null);
  const [perRun, setPerRun] = useState("");
  const [perMessage, setPerMessage] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function show(settings: Settings) {
    setCurrent(settings);
    setPerRun(settings.perRun > 0 ? String(settings.perRun) : "");
    setPerMessage(settings.perMessage > 0 ? String(settings.perMessage) : "");
  }

  useEffect(() => {
    api.toolCallSettings().then(show, (e) => setError(errorText(e)));
  }, []);

  // 留空 = 0 = 不限；不是非负整数时为 null
  function parse(text: string): number | null {
    const value = text.trim();
    if (value === "") return 0;
    return /^\d+$/.test(value) ? Number(value) : null;
  }

  async function save(run: number, message: number) {
    setBusy(true);
    try {
      show(await api.saveToolCallSettings(run, message));
      setError(null);
      toast("已保存，立即生效");
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
        <h2>工具调用上限</h2>
        {error ? <p className="error">{error}</p> : <p>加载中…</p>}
      </section>
    );
  }
  const run = parse(perRun);
  const message = parse(perMessage);
  const invalid = run === null || message === null;
  const dirty = !invalid && (run !== current.perRun || message !== current.perMessage);
  const label = (value: number) => (value > 0 ? `${value} 次` : "不限");
  return (
    <section className="card">
      <h2>工具调用上限</h2>
      <div className="form-grid">
        <label>
          申请：每次运行最多几次（默认 {current.defaultPerRun}）
          <input type="text" inputMode="numeric" placeholder="不限" value={perRun}
            onChange={(e) => setPerRun(e.target.value)} />
        </label>
        <label>
          对话：每条消息最多几次（默认 {current.defaultPerMessage}）
          <input type="text" inputMode="numeric" placeholder="不限" value={perMessage}
            onChange={(e) => setPerMessage(e.target.value)} />
        </label>
      </div>
      <p className="muted small">
        Agent 每点一下、读一次页面、搜一次都算一次工具调用。到了上限，Agent 停下来总结做到哪了，回复「继续」接着做。
        留空或填 0 就是不限。改了马上生效，正在跑的下一步就按新数字算。
      </p>
      <p className="muted">现在：申请 {label(current.perRun)}，对话 {label(current.perMessage)}</p>
      <button disabled={busy || !dirty} onClick={() => save(run ?? 0, message ?? 0)}>保存</button>{" "}
      <button disabled={busy || (current.perRun === current.defaultPerRun && current.perMessage === current.defaultPerMessage)}
        onClick={() => save(current.defaultPerRun, current.defaultPerMessage)}>
        恢复默认
      </button>
      {invalid && <p className="error">填正整数，或者留空 / 0 表示不限</p>}
      {error && <p className="error">{error}</p>}
    </section>
  );
}

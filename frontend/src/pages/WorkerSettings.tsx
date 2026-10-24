import { useEffect, useState } from "react";
import { api, errorText, type WorkerSettings as Settings } from "../api";
import { toast } from "../toast";

// 设置页「同时申请」（C012）：同时跑几个申请、同一个网站最多几个。保存后马上生效
export default function WorkerSettings() {
  const [current, setCurrent] = useState<Settings | null>(null);
  const [workers, setWorkers] = useState(3);
  const [perSite, setPerSite] = useState(2);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function show(settings: Settings) {
    setCurrent(settings);
    setWorkers(settings.workers);
    setPerSite(settings.perSite);
  }

  useEffect(() => {
    api.workerSettings().then(show, (e) => setError(errorText(e)));
  }, []);

  async function save() {
    setBusy(true);
    try {
      show(await api.saveWorkerSettings(workers, perSite));
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
        <h2>同时申请</h2>
        {error ? <p className="error">{error}</p> : <p>加载中…</p>}
      </section>
    );
  }
  const dirty = workers !== current.workers || perSite !== current.perSite;
  return (
    <section className="card">
      <h2>同时申请</h2>
      <div className="form-grid">
        <label>
          同时跑几个申请（1–{current.maxWorkers}）
          <input type="number" min={1} max={current.maxWorkers} value={workers}
            onChange={(e) => setWorkers(Number(e.target.value))} />
        </label>
        <label>
          同一个网站最多几个（1–{Math.max(1, workers)}）
          <input type="number" min={1} max={Math.max(1, workers)} value={perSite}
            onChange={(e) => setPerSite(Number(e.target.value))} />
        </label>
      </div>
      <p className="muted small">
        多开一个，就多一个 Chrome 标签页同时在填表，模型的 token 也用得更快。调小不会停掉正在跑的申请，只是先不开始新的，
        等在跑的少于新数字再放排队的。
      </p>
      <p className="muted">现在：{current.running} 个在跑，{current.queued} 个排队</p>
      <button disabled={busy || !dirty} onClick={save}>保存</button>
      {error && <p className="error">{error}</p>}
    </section>
  );
}

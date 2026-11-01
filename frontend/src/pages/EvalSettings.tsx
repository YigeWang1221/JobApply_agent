import { useEffect, useState } from "react";
import { api, errorText, type EvalSettings as Settings } from "../api";
import { toast } from "../toast";

// 设置页「评测」（C025）：一个开关。打开后，新开始的申请在它自己的标签页上拦下最终提交请求、每步存一份脱敏快照
// （storage/eval/app/）；默认关，关着时申请和平常一样。保存后马上生效，在跑的申请不受影响
export default function EvalSettings() {
  const [current, setCurrent] = useState<Settings | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.evalSettings().then(setCurrent, (e) => setError(errorText(e)));
  }, []);

  async function toggle(enabled: boolean) {
    setBusy(true);
    try {
      setCurrent(await api.saveEvalSettings(enabled));
      setError(null);
      toast(enabled ? "评测模式已打开：之后开始的申请不会真的提交" : "评测模式已关闭");
    } catch (e) {
      setError(errorText(e));
      toast(errorText(e), "error");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <h2>评测</h2>
      {!current ? (error ? <p className="error">{error}</p> : <p>加载中…</p>) : (
        <>
          <label>
            <input type="checkbox" checked={current.enabled} disabled={busy}
              onChange={(e) => void toggle(e.target.checked)} />
            {" "}评测模式（拦截最终提交、每步存快照）
          </label>
          <p className="muted small">
            打开后，之后开始的申请在它自己的标签页上拦下最终提交请求（不会发出），每一步存一份遮掉个人信息的快照到
            storage/eval/app/。正在跑的申请不受影响。要真的投递时记得关掉。
          </p>
          {error && <p className="error">{error}</p>}
        </>
      )}
    </section>
  );
}

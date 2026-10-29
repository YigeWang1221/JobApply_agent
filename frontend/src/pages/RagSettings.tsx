import { useEffect, useState } from "react";
import { api, errorText, type RagStatus } from "../api";
import { toast } from "../toast";

const STATE_TEXT: Record<RagStatus["state"], string> = {
  absent: "未下载",
  downloading: "下载中",
  ready: "可用",
  failed: "下载失败",
};

// 设置页「经验检索」（C022）：本地嵌入模型的状态和下载进度；下载中每秒刷新一次
export default function RagSettings() {
  const [status, setStatus] = useState<RagStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.ragStatus().then(setStatus, (e) => setError(errorText(e)));
  }, []);

  const downloading = status?.state === "downloading";
  useEffect(() => {
    if (!downloading) return;
    const timer = setInterval(() => {
      api.ragStatus().then(setStatus, (e) => setError(errorText(e)));
    }, 1000);
    return () => clearInterval(timer);
  }, [downloading]);

  async function download() {
    setBusy(true);
    try {
      setStatus(await api.downloadRagModel());
      setError(null);
    } catch (e) {
      setError(errorText(e));
      toast(errorText(e), "error");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <h2>经验检索</h2>
      <p className="muted small">
        工作台可以查以前各个工作台记下的面试、OA 题目、复盘和表单回答。用本地嵌入模型 multilingual-e5-small，
        存在数据位置的 model/RAG/ 下，下载一次以后不再联网；还没下载好时按关键词检索。
      </p>
      {!status ? (
        error ? <p className="error">{error}</p> : <p>加载中…</p>
      ) : (
        <>
          <p>
            状态：<strong>{STATE_TEXT[status.state]}</strong>
            {(downloading || status.state === "failed") && ` · ${Math.round(status.progress * 100)}%`}
          </p>
          {downloading && <progress max={1} value={status.progress} style={{ width: "100%" }} />}
          <p className={status.state === "failed" ? "error" : "muted"}>{status.message}</p>
          {(status.state === "absent" || status.state === "failed") && (
            <button disabled={busy} onClick={download}>
              {status.state === "failed" ? "重新下载" : "下载模型"}
            </button>
          )}
          {error && <p className="error">{error}</p>}
        </>
      )}
    </section>
  );
}

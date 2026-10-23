import { useCallback, useEffect, useRef, useState } from "react";
import { api, errorText, type SessionView } from "../api";
import { go } from "../route";
import { ChatLog, JobState, lastTool } from "../status";

const POLL_MS = 2000;

// 对话视图（C005-research-agent.md）：和对话 Agent 聊、调研出候选、开始申请。Agent 在后台跑，运行中每 2 秒查一次
export default function SessionPage({ id }: { id: string }) {
  const [view, setView] = useState<SessionView | null>(null);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const bottom = useRef<HTMLDivElement>(null);

  const load = useCallback(async () => {
    try {
      setView(await api.session(id));
      setError(null);
    } catch (e) {
      setError(errorText(e));
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  const running = view?.status === "running";
  useEffect(() => {
    if (!running) {
      return;
    }
    const timer = window.setInterval(() => void load(), POLL_MS);
    return () => window.clearInterval(timer);
  }, [running, load]);

  const count = view?.messages.length ?? 0;
  useEffect(() => {
    bottom.current?.scrollIntoView({ block: "end" });
  }, [count, running]);

  async function send() {
    const message = text.trim();
    if (!message) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      setView(await api.sendMessage(id, message));
      setText("");
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function stop() {
    try {
      setView(await api.stopSession(id));
    } catch (e) {
      setError(errorText(e));
    }
  }

  if (!view) {
    return error ? <p className="error">{error}</p> : <p className="muted">读取中…</p>;
  }
  const tool = lastTool(view.messages);

  return (
    <div className="sessgrid">
      <div className="panel chatpanel">
        <div className="ph">
          <div>
            <div className="muted small mono">{view.session.project} · {view.session.createdAt}</div>
            <h2 style={{ fontSize: 16 }}>{view.session.title}</h2>
          </div>
          <div>{view.session.tags.map((tag) => <span key={tag} className={`tagc ${tag}`}>{tag}</span>)}</div>
        </div>
        <div className="chatlog">
          {view.messages.length === 0 && !running && <p className="empty">还没有对话。</p>}
          <ChatLog messages={view.messages} />
          {running && <div className="thinking">Agent 正在{tool ? `用 ${tool}` : "想"}…</div>}
          {view.error && <p className="error">出错：{view.error}</p>}
          <div ref={bottom} />
        </div>
        {error && <p className="error">{error}</p>}
        <div className="composer">
          <textarea rows={2} value={text} disabled={busy}
            placeholder="例如：按我的简历找上海的后端校招 / 核验一下第一个 / 投这两个"
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
                e.preventDefault();
                if (!running) {
                  void send();
                }
              }
            }} />
          {running
            ? <button className="danger" onClick={() => void stop()}>停止</button>
            : <button className="primary" disabled={busy || !text.trim()} onClick={() => void send()}>发送</button>}
        </div>
      </div>
      <div className="panel">
        <h2>这个 Session 涉及的岗位</h2>
        {view.jobs.length === 0
          ? <p className="empty">还没有涉及任何岗位。</p>
          : (
            <div className="list">
              {view.jobs.map((job) => (
                <div key={job.id} className="li">
                  <div className="t">
                    <div>{job.company} · {job.title}</div>
                    <small>{job.city.join("、")}</small>
                  </div>
                  <div className="acts">
                    <JobState job={job} />
                    <button className="linkbtn" onClick={() => go({ kind: "job", id: job.id })}>
                      {job.leadStatus === "converted" ? "工作台" : "详情"}
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
      </div>
    </div>
  );
}

import type { SessionSummary } from "../api";
import { go } from "../route";
import { md } from "./metrics";

// 最近的 Session（示意页的行样式）：日期、标题、标签、交接摘要
export function RecentSessions({ sessions }: { sessions: SessionSummary[] }) {
  return (
    <div className="panel">
      <div className="ph">
        <h2>最近的 Session</h2>
        <span className="boardnote">标签根据 Session 里实际发生的事自动生成</span>
      </div>
      {sessions.length === 0
        ? <p className="empty">还没有 Session。点右上角「＋ 新建 Session」，让 Agent 去调研。</p>
        : (
          <div className="list">
            {sessions.slice(0, 4).map((s) => (
              <div key={s.id} className="li tm">
                <time>{md(s.createdAt)}</time>
                <div className="t">
                  <div>
                    {s.title}
                    {s.tags.map((tag) => <span key={tag} className={`tagc ${tag}`}>{tag}</span>)}
                  </div>
                  <small>{s.handoff || "还没有交接摘要"}</small>
                </div>
                <button className="linkbtn" onClick={() => go({ kind: "session", id: s.id })}>打开</button>
              </div>
            ))}
          </div>
        )}
    </div>
  );
}

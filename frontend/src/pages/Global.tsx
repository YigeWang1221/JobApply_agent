import type { ProjectSummary } from "../api";
import { go } from "../route";
import { Kpis, sumCounts } from "../status";

// 全局：所有 Project 的 KPI 一行 + 每个 Project 一张卡片。漏斗、时间线、每周节奏在里程碑第 6 段
export default function Global({ projects }: { projects: ProjectSummary[] | null }) {
  return (
    <>
      <div className="top">
        <div>
          <h1>全局</h1>
          <p>所有 Project 汇总</p>
        </div>
      </div>
      {projects === null ? <p className="muted">读取中…</p> : (
        <>
          <Kpis counts={sumCounts(projects.map((p) => p.counts))} />
          {projects.length === 0
            ? <div className="panel"><p className="empty">还没有 Project。在左边新建一个，再在里面新建 Session，让 Agent 去找岗位。</p></div>
            : (
              <div className="cards">
                {projects.map((p) => (
                  <div key={p.id} className="panel">
                    <div className="ph">
                      <h2>{p.title}</h2>
                      <span className="muted small mono">{p.id}</span>
                    </div>
                    <Kpis counts={p.counts} />
                    <div className="row">
                      <button onClick={() => go({ kind: "project", id: p.id })}>打开</button>
                      <span className="muted small">{p.sessions.length} 个 Session</span>
                    </div>
                  </div>
                ))}
              </div>
            )}
        </>
      )}
    </>
  );
}

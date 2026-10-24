import type { JobView } from "../api";
import { go } from "../route";
import { ApplicationChip, phaseText } from "../status";
import { applications, BOARD_PHASES } from "./metrics";

// 看板：按阶段分 7 列，卡片点开申请工作台。轮次进度点要等轮次数据（里程碑第 6 段）
export function Board({ jobs, showProject }: { jobs: JobView[]; showProject?: boolean }) {
  const apps = applications(jobs);
  return (
    <div className="panel">
      <div className="ph">
        <h2>看板</h2>
        <span className="boardnote">状态通过在 Session 里回复更新；点卡片打开工作台</span>
      </div>
      <div className="boardwrap">
        <div className="board">
          {BOARD_PHASES.map(([phase, name]) => {
            const items = apps.filter((j) => (j.phase ?? "") === phase);
            return (
              <div key={phase} className="col">
                <div className="colh"><b>{name}</b><span>{items.length}</span></div>
                {items.length === 0 && <div className="empty">—</div>}
                {items.map((job) => (
                  <button key={job.id} className="bcard" onClick={() => go({ kind: "job", id: job.id })}>
                    <span className="co">{job.company}</span>
                    <span className="ro">{[job.title, job.city.join("、")].filter(Boolean).join(" · ")}</span>
                    <span className="meta"><span className="dtext">{phaseText(job)}</span></span>
                    <span className="meta">
                      <span className="ptag">{[showProject ? job.project : null, job.type].filter(Boolean).join(" · ")}</span>
                      {job.due && <span className="chip wait">该查看了</span>}
                      <ApplicationChip status={job.application} />
                    </span>
                  </button>
                ))}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

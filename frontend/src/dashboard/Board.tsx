import { useState } from "react";
import type { JobView } from "../api";
import { go } from "../route";
import { OUTCOME, phaseText, RunChip } from "../status";
import { applications, BOARD_PHASES, lane } from "./metrics";

// 一列最多先显示这么多张，多的折起「还有 N 张」（C024-fix）
const FOLD_AT = 6;

// 卡片上的状态字：「已结束」列写结局（被拒、岗位下线…），其它写申请阶段（准备中的写子状态）
function stateText(job: JobView): string {
  if (job.phase === "closed") {
    const outcome = job.label?.split(":")[1] ?? "";
    return OUTCOME[outcome] ?? phaseText(job);
  }
  return phaseText(job);
}

// 看板：按阶段分 7 列，卡片压成一行（公司 · 岗位 + 状态字），点开申请工作台。
// 投递失败 / 不投了不在这里（在下面的 Failures，C024-fix）；运行 chip 只在「在跑或要你做事」时挂
export function Board({ jobs, showProject }: { jobs: JobView[]; showProject?: boolean }) {
  const apps = applications(jobs).filter((j) => lane(j) !== "dropped");
  const [unfolded, setUnfolded] = useState<Set<string>>(() => new Set());
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
            const open = unfolded.has(phase);
            const shown = open ? items : items.slice(0, FOLD_AT);
            return (
              <div key={phase} className="col">
                <div className="colh"><b>{name}</b><span>{items.length}</span></div>
                {items.length === 0 && <div className="empty colempty">暂无</div>}
                {shown.map((job) => (
                  <button key={job.id} className="bcard line" onClick={() => go({ kind: "job", id: job.id })}
                    title={[`${job.company} · ${job.title}`, job.city.join("、"), showProject ? job.project : null, job.type,
                      stateText(job), job.phase === "closed" && job.outcomeReason ? `原因：${job.outcomeReason}` : null]
                      .filter(Boolean).join("\n")}>
                    <span className="nm"><b>{job.company}</b> · {job.title}</span>
                    <span className="st">
                      <span className="dtext">{stateText(job)}</span>
                      {job.due && <span className="chip wait">该查看了</span>}
                      <RunChip job={job} />
                    </span>
                  </button>
                ))}
                {items.length > FOLD_AT && (
                  <button className="linkbtn colmore" onClick={() => {
                    const next = new Set(unfolded);
                    if (open) {
                      next.delete(phase);
                    } else {
                      next.add(phase);
                    }
                    setUnfolded(next);
                  }}>{open ? "收起" : `还有 ${items.length - FOLD_AT} 张`}</button>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

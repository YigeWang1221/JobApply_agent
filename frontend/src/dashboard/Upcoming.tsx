import { localDay, type JobView } from "../api";
import { go } from "../route";
import { PHASE, PREP } from "../status";
import { addDays, applications, md } from "./metrics";

const WAITING_PREP = new Set(["needs_input", "needs_login", "handoff_user", "awaiting_review", "failed"]);

// 接下来 7 天：7 天内到「下次查看」的申请；下面是准备中、在等你的申请
export function Upcoming({ jobs, showProject }: { jobs: JobView[]; showProject?: boolean }) {
  const today = localDay();
  const end = addDays(today, 7);
  const apps = applications(jobs);
  const soon = apps
    .filter((j) => j.phase !== "closed" && j.phase !== "prep" && j.nextCheckAt)
    .filter((j) => {
      const day = j.nextCheckAt!.slice(0, 10);
      return day > today && day <= end;
    })
    .sort((a, b) => a.nextCheckAt!.localeCompare(b.nextCheckAt!));
  const waiting = apps.filter((j) => j.phase === "prep"
    && (WAITING_PREP.has(j.prepState ?? "") || j.application === "waiting_user" || j.application === "failed"));
  return (
    <div className="panel">
      <h2>接下来 7 天</h2>
      {soon.length === 0 && waiting.length === 0
        ? <p className="empty">接下来 7 天没有安排。</p>
        : (
          <div className="list">
            {soon.map((job) => (
              <div key={job.id} className="li tm">
                <time>{md(job.nextCheckAt)}</time>
                <div className="t">
                  <div>{job.company} · 查看进展</div>
                  <small>{[job.title, PHASE[job.phase ?? ""], showProject ? job.project : null].filter(Boolean).join(" · ")}</small>
                </div>
                <button className="linkbtn" onClick={() => go({ kind: "job", id: job.id })}>打开</button>
              </div>
            ))}
            {waiting.map((job) => (
              <div key={job.id} className="li tm">
                <time>待你</time>
                <div className="t">
                  <div>{job.company} · {job.title}</div>
                  <small>{[PREP[job.prepState ?? ""] ?? job.prepState, showProject ? job.project : null].filter(Boolean).join(" · ")}</small>
                </div>
                <button className="linkbtn" onClick={() => go({ kind: "job", id: job.id })}>工作台</button>
              </div>
            ))}
          </div>
        )}
    </div>
  );
}

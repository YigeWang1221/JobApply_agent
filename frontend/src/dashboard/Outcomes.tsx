import type { JobView } from "../api";
import { go } from "../route";
import { OUTCOME_KIND } from "../status";
import { closedAt, markedOutcome, md } from "./metrics";

// 「没投成 / 不投了」（C024）：用户记的投递失败和不投了放在一起，chip 分两类，后面是原因原话和日期。
// 候选阶段的「不投」（skipped）在候选池里已经有「不投原因」，这里不重复
export function Outcomes({ jobs, showProject, onReopen }: {
  jobs: JobView[];
  showProject?: boolean;
  onReopen: (job: JobView) => void;
}) {
  const rows = jobs
    .map((job) => ({ job, kind: markedOutcome(job), day: job.closedAt ? md(job.closedAt) : md(closedAt(job)) }))
    .filter((r): r is { job: JobView; kind: "apply_failed" | "gave_up"; day: string } => r.kind !== null)
    .sort((a, b) => (b.job.closedAt ?? "").localeCompare(a.job.closedAt ?? ""));
  const failed = rows.filter((r) => r.kind === "apply_failed").length;
  return (
    <div className="panel">
      <div className="ph">
        <h2>没投成 / 不投了</h2>
        <span className="boardnote">投递失败 {failed} · 不投了 {rows.length - failed}；点错了可以撤销</span>
      </div>
      {rows.length === 0 ? <p className="empty">没有投递失败或不投了的申请。</p> : (
        <ul className="outcomes">
          {rows.map(({ job, kind, day }) => (
            <li key={job.id}>
              <span className={`chip ${kind === "apply_failed" ? "bad" : ""}`}>{OUTCOME_KIND[kind].name}</span>
              <span className="who">
                <b>{job.company}</b> · {job.title}
                {showProject && <span className="muted"> · {job.project}</span>}
              </span>
              <span className="reason" title={job.outcomeReason ?? undefined}>{job.outcomeReason ?? "（没写原因）"}</span>
              <span className="muted num">{day}</span>
              <span className="acts">
                <button className="linkbtn" onClick={() => go({ kind: "job", id: job.id })}>工作台</button>
                {job.canReopen && <button className="linkbtn" onClick={() => onReopen(job)}>撤销</button>}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

import { useState } from "react";
import type { JobView } from "../api";
import { go } from "../route";
import { LEAD, OUTCOME_KIND } from "../status";
import { closedAt, markedOutcome, md, skippedAt } from "./metrics";

// 先显示这么多行，多的折起「更早 N 条」
const SHOW = 8;

type Kind = "skipped" | "apply_failed" | "gave_up";

const KIND_NAME: Record<Kind, string> = {
  skipped: LEAD.skipped,
  apply_failed: OUTCOME_KIND.apply_failed.name,
  gave_up: OUTCOME_KIND.gave_up.name,
};

interface Row {
  job: JobView;
  kind: Kind;
  reason: string | null;
  at: string | null;
}

// 一行属于哪一类：候选池里跳过的（lead_status skipped），用户记的投递失败、不投了（旧 not_submitted 也算不投了）
function rowOf(job: JobView): Row | null {
  if (job.leadStatus === "skipped") {
    return { job, kind: "skipped", reason: job.skipReason, at: skippedAt(job) };
  }
  const kind = markedOutcome(job);
  return kind ? { job, kind, reason: job.outcomeReason, at: job.closedAt ?? closedAt(job) } : null;
}

export function failureCount(jobs: JobView[]): number {
  return jobs.filter((j) => rowOf(j) !== null).length;
}

// Failures（C024-fix）：候选池里跳过的、投递失败、不投了放在一个池子里，chip 分三类，后面是原因原话和日期。
// 看板「已结束」列、时间线、每周节奏都不再有它们；存的值不变，只是显示的分组
export function Failures({ jobs, showProject, onReopen }: {
  jobs: JobView[];
  showProject?: boolean;
  onReopen: (job: JobView) => void;
}) {
  const [all, setAll] = useState(false);
  const rows = jobs.map(rowOf).filter((r): r is Row => r !== null)
    .sort((a, b) => (b.at ?? "").localeCompare(a.at ?? ""));
  const count = (kind: Kind) => rows.filter((r) => r.kind === kind).length;
  const shown = all ? rows : rows.slice(0, SHOW);
  return (
    <div className="panel" id="failures">
      <div className="ph">
        <h2>Failures</h2>
        <span className="boardnote">
          {(["skipped", "apply_failed", "gave_up"] as const).map((k) => `${KIND_NAME[k]} ${count(k)}`).join(" · ")}；
          用户记的投递失败 / 不投了点错了可以撤销
        </span>
      </div>
      {rows.length === 0 ? <p className="empty">没有跳过、投递失败或不投了的岗位。</p> : (
        <ul className="outcomes">
          {shown.map(({ job, kind, reason, at }) => (
            <li key={job.id}>
              <span className={`chip ${kind === "apply_failed" ? "bad" : kind === "gave_up" ? "wait" : ""}`}>{KIND_NAME[kind]}</span>
              <span className="who">
                <b>{job.company}</b> · {job.title}
                {showProject && <span className="muted"> · {job.project}</span>}
              </span>
              <span className="reason" title={reason ?? undefined}>{reason ?? "（没写原因）"}</span>
              <span className="muted num">{md(at)}</span>
              <span className="acts">
                <button className="linkbtn" onClick={() => go({ kind: "job", id: job.id })}>
                  {kind === "skipped" ? "详情" : "工作台"}
                </button>
                {kind === "skipped" && job.url && <a href={job.url} target="_blank" rel="noreferrer">岗位</a>}
                {job.canReopen && <button className="linkbtn" onClick={() => onReopen(job)}>撤销</button>}
              </span>
            </li>
          ))}
        </ul>
      )}
      {rows.length > SHOW && (
        <button className="linkbtn morebtn" onClick={() => setAll(!all)}>
          {all ? "收起" : `更早 ${rows.length - SHOW} 条`}
        </button>
      )}
    </div>
  );
}

import { useState } from "react";
import { localDay, type JobView } from "../api";
import { go } from "../route";
import { labelText, OUTCOME, phaseText, ROUND_RESULT, RoundProgress, RunChip } from "../status";
import { applications, dayOf, daysBetween, FUNNEL_LEVELS, lane, md, reached } from "./metrics";

// 先显示最近活动的这么多份，多的折起「更早 N 份」
const SHOW = 8;

function appliedDay(job: JobView): string | null {
  return dayOf(job.appliedAt) ?? dayOf((job.events ?? []).find((e) => e.to === "applied")?.at);
}

// 最近一次动静：事件、查看、轮次里最晚的一天
function lastDay(job: JobView, applied: string): string {
  const days = [applied, dayOf(job.lastCheckedAt), ...(job.events ?? []).map((e) => dayOf(e.at)),
    ...(job.rounds ?? []).flatMap((r) => [dayOf(r.happenedAt), dayOf(r.scheduledAt)])]
    .filter((d): d is string => d !== null && d <= localDay());
  return days.reduce((a, b) => (a > b ? a : b), applied);
}

// 条左边的颜色：Offer 绿、被拒红、其它结束灰、在走的用正文色
function tone(job: JobView): string {
  const outcome = job.label?.split(":")[1];
  if (job.phase === "offer" || outcome === "accepted") {
    return "good";
  }
  if (outcome === "rejected") {
    return "crit";
  }
  return job.phase === "closed" ? "ended" : "flow";
}

// 时间线（C024-fix）：每份已投递的申请压成一条圆角长条（公司 · 岗位、阶段、投递日到最近动静），按最近动静排；
// 点一条展开详情（岗位信息、用的简历、阶段经过、轮次、结束原因、工作台）。投递失败 / 不投了不在这里（在 Failures）
export function Timeline({ jobs }: { jobs: JobView[] }) {
  const [open, setOpen] = useState<Set<string>>(() => new Set());
  const [all, setAll] = useState(false);
  const today = localDay();
  const rows = applications(jobs)
    .filter((job) => lane(job) !== "dropped")
    .map((job) => ({ job, applied: appliedDay(job) }))
    .filter((r): r is { job: JobView; applied: string } => r.applied !== null)
    .map((r) => ({ ...r, last: lastDay(r.job, r.applied) }))
    .sort((a, b) => b.last.localeCompare(a.last) || b.applied.localeCompare(a.applied));
  const shown = all ? rows : rows.slice(0, SHOW);
  const toggle = (id: string) => {
    const next = new Set(open);
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
    }
    setOpen(next);
  };
  return (
    <div className="panel">
      <div className="ph">
        <h2>时间线</h2>
        <span className="boardnote">每份已投递的申请一条，最近有动静的在上；点一条看详情</span>
      </div>
      {rows.length === 0 ? <p className="empty">还没有已投递的申请。</p> : (
        <ul className="tlist">
          {shown.map(({ job, applied, last }) => {
            const expanded = open.has(job.id);
            const level = reached(job);
            const end = job.phase === "closed" ? last : today;
            return (
              <li key={job.id} className={`tbar ${tone(job)}${expanded ? " open" : ""}`}>
                <button className="tbar-h" aria-expanded={expanded} onClick={() => toggle(job.id)}>
                  <span className="nm"><b>{job.company}</b> · {job.title}</span>
                  <span className="steps" aria-label={`走到 ${FUNNEL_LEVELS[level - 1] ?? "投递"}`}
                    title={FUNNEL_LEVELS.map((name, i) => `${i < level ? "●" : "○"} ${name}`).join("\n")}>
                    {FUNNEL_LEVELS.map((name, i) => <i key={name} className={i < level ? "on" : ""} />)}
                  </span>
                  <span className="stage"><RunChip job={job} /><span className="chip">{phaseText(job)}</span></span>
                  <span className="dates num">
                    {last === applied ? `投递 ${md(applied)}`
                      : `${md(applied)} → ${job.phase === "closed" ? "结束" : "最近"} ${md(last)}`}
                    <span className="muted"> · {daysBetween(applied, end)} 天</span>
                  </span>
                </button>
                {expanded && <Detail job={job} />}
              </li>
            );
          })}
        </ul>
      )}
      {rows.length > SHOW && (
        <button className="linkbtn morebtn" onClick={() => setAll(!all)}>
          {all ? "收起" : `更早 ${rows.length - SHOW} 份`}
        </button>
      )}
    </div>
  );
}

// 展开的详情：岗位信息、用的简历（和排名理由）、阶段经过、轮次、结束原因、工作台
function Detail({ job }: { job: JobView }) {
  const choice = job.resumeChoice ?? null;
  const picked = choice?.ranking?.find((r) => r.key === choice.asset) ?? choice?.ranking?.[0];
  const steps = (job.events ?? []).filter((e) => e.type !== "lead_status_changed");
  const rounds = job.rounds ?? [];
  const outcome = job.label?.split(":")[1];
  return (
    <div className="tbar-d">
      <dl>
        <dt>岗位</dt>
        <dd>
          {[job.city.join("、"), job.type, job.site].filter(Boolean).join(" · ") || "—"}
          {job.url && <> · <a href={job.url} target="_blank" rel="noreferrer">岗位链接</a></>}
          {job.officialUrl && job.officialUrl !== job.url
            && <> · <a href={job.officialUrl} target="_blank" rel="noreferrer">官方</a></>}
          {job.verified === "verified" && <span className="muted"> · 官网已核验</span>}
        </dd>
        <dt>简历</dt>
        <dd>
          {choice?.file ?? (choice?.skipped ? `没有排（${choice.skipped}）` : "—")}
          {picked?.reason && <span className="muted"> · 匹配：{picked.reason}</span>}
          {picked?.gap && <span className="muted"> · 差距：{picked.gap}</span>}
        </dd>
        <dt>经过</dt>
        <dd>
          {steps.length === 0 ? "—" : (
            <ol className="tsteps">
              {steps.map((e, i) => (
                <li key={i}><span className="num muted">{md(e.at)}</span> {e.type === "converted" ? "开始申请" : labelText(e.to)}</li>
              ))}
            </ol>
          )}
        </dd>
        {rounds.length > 0 && (
          <>
            <dt>轮次</dt>
            <dd>
              <RoundProgress rounds={rounds} />
              <ol className="tsteps">
                {rounds.map((r) => (
                  <li key={r.seq}>
                    <span className="num muted">{md(r.happenedAt ?? r.scheduledAt)}</span> {r.seq}. {r.name} · {ROUND_RESULT[r.result] ?? r.result}
                  </li>
                ))}
              </ol>
            </dd>
          </>
        )}
        {job.phase === "closed" && (
          <>
            <dt>结局</dt>
            <dd>{OUTCOME[outcome ?? ""] ?? outcome}{job.outcomeReason && <span className="muted"> · 原因：{job.outcomeReason}</span>}</dd>
          </>
        )}
      </dl>
      <button className="linkbtn" onClick={() => go({ kind: "job", id: job.id })}>打开工作台</button>
    </div>
  );
}

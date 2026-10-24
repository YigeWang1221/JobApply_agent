import { localDay, type JobView } from "../api";
import { OUTCOME, PHASE } from "../status";
import { addDays, applications, closedAt, dayMs, dayOf, md, weekStart } from "./metrics";

const W = 960;
const LEFT = 200;
const RIGHT = 16;
const ROW = 26;
const TOP = 26;
const STEPS = new Set(["screening", "assessment", "interview", "offer"]);

interface Mark {
  day: string;
  fill: string;
  sign: string;
  tip: string;
}

function cut(text: string, n: number): string {
  return text.length > n ? `${text.slice(0, n)}…` : text;
}

function appliedDay(job: JobView): string | null {
  return dayOf(job.appliedAt) ?? dayOf((job.events ?? []).find((e) => e.to === "applied")?.at);
}

// 时间线：每个已投递的申请一行，标出投递日、阶段变化（事件日志）和结束；竖线是今天
export function Timeline({ jobs }: { jobs: JobView[] }) {
  const today = localDay();
  const rows = applications(jobs)
    .map((job) => ({ job, applied: appliedDay(job) }))
    .filter((r): r is { job: JobView; applied: string } => r.applied !== null)
    .sort((a, b) => a.applied.localeCompare(b.applied));
  return (
    <div className="panel">
      <div className="ph">
        <h2>时间线</h2>
        <span className="boardnote">每个已投递的申请一行；竖线是今天</span>
      </div>
      <div className="legend">
        <span><i className="circ" style={{ border: "2px solid var(--ink-2)", background: "var(--surface)" }} />投递</span>
        <span><i className="circ" style={{ background: "var(--ink-2)" }} />阶段推进</span>
        <span><i className="circ" style={{ background: "var(--good)" }} />✓ Offer</span>
        <span><i className="circ" style={{ background: "var(--crit)" }} />✕ 被拒</span>
        <span><i className="circ" style={{ background: "var(--axis)" }} />其它结束</span>
      </div>
      {rows.length === 0
        ? <p className="empty">还没有已投递的申请。</p>
        : <div className="chartwrap"><Chart rows={rows} today={today} /></div>}
    </div>
  );
}

function Chart({ rows, today }: { rows: { job: JobView; applied: string }[]; today: string }) {
  const marksOf = (job: JobView, applied: string): Mark[] => {
    const marks: Mark[] = [];
    for (const e of job.events ?? []) {
      const day = dayOf(e.at);
      if (!day || day < applied) {
        continue;
      }
      if (e.type === "phase_changed" && STEPS.has(e.to)) {
        const offer = e.to === "offer";
        marks.push({ day, fill: offer ? "var(--good)" : "var(--ink-2)", sign: offer ? "✓" : "",
          tip: `${job.company} · ${PHASE[e.to] ?? e.to} · ${md(day)}` });
      }
      if (e.type === "closed") {
        const outcome = e.to.split(":")[1] ?? "";
        const good = outcome === "accepted";
        const bad = outcome === "rejected";
        marks.push({ day, fill: good ? "var(--good)" : bad ? "var(--crit)" : "var(--axis)",
          sign: good ? "✓" : bad ? "✕" : "", tip: `${job.company} · ${OUTCOME[outcome] ?? outcome} · ${md(day)}` });
      }
    }
    return marks;
  };
  const allDays = rows.flatMap((r) => [r.applied, ...marksOf(r.job, r.applied).map((m) => m.day)]);
  const start = addDays(weekStart(allDays.reduce((a, b) => (a < b ? a : b))), -1);
  const lastDay = [addDays(today, 7), ...allDays].reduce((a, b) => (a > b ? a : b));
  const startMs = dayMs(start);
  const span = Math.max(1, dayMs(lastDay) - startMs);
  const x = (day: string) => LEFT + ((dayMs(day) - startMs) / span) * (W - LEFT - RIGHT);
  const H = TOP + rows.length * ROW + 8;
  const ticks: string[] = [];
  for (let d = addDays(start, 1); d <= lastDay; d = addDays(d, 7)) {
    ticks.push(d);
  }
  const tx = x(today);
  return (
    <svg className="chart" viewBox={`0 0 ${W} ${H}`} width={W} role="img" aria-label="各申请的投递与阶段时间线"
      style={{ maxWidth: "100%", minWidth: 760, height: "auto" }}>
      {ticks.map((d) => (
        <g key={d}>
          <line x1={x(d)} y1={TOP - 6} x2={x(d)} y2={H} stroke="var(--grid)" strokeWidth={1} />
          <text x={x(d)} y={12} textAnchor="middle">{md(d)}</text>
        </g>
      ))}
      {rows.map(({ job, applied }, i) => {
        const y = TOP + i * ROW + ROW / 2;
        const closed = job.phase === "closed" ? closedAt(job) : null;
        const end = closed ?? today;
        const marks = marksOf(job, applied);
        const outcome = job.label?.split(":")[1];
        return (
          <g key={job.id}>
            <text x={0} y={y + 4} className="ink">{cut(job.company, 8)} · {cut(job.title, 9)}</text>
            <line x1={x(applied)} y1={y} x2={x(end > applied ? end : applied)} y2={y} stroke="var(--axis)"
              strokeWidth={2} strokeLinecap="round" />
            <circle cx={x(applied)} cy={y} r={5} fill="var(--surface)" stroke="var(--ink-2)" strokeWidth={2}>
              <title>{`${job.company} · ${job.title} · 投递 ${md(applied)}`}</title>
            </circle>
            {marks.map((m, k) => (
              <g key={k}>
                <title>{m.tip}</title>
                <circle cx={x(m.day)} cy={y} r={6} fill={m.fill} stroke="var(--surface)" strokeWidth={2} />
                {m.sign && <text x={x(m.day)} y={y + 3.5} textAnchor="middle" className="sign">{m.sign}</text>}
              </g>
            ))}
            {closed && outcome && <text x={x(closed) + 10} y={y + 4}>{OUTCOME[outcome] ?? outcome}</text>}
          </g>
        );
      })}
      <line x1={tx} y1={TOP - 8} x2={tx} y2={H} stroke="var(--accent)" strokeWidth={1.5} />
      <text x={tx + 4} y={TOP - 10} className="today">今天</text>
    </svg>
  );
}

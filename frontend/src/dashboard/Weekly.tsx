import { localDay, type JobView } from "../api";
import { addDays, applications, dayOf, md, weekStart } from "./metrics";

const W = 960;
const H = 180;
const L = 28;
const B = 26;
const T = 14;
const R = 8;
const MAX_WEEKS = 16;

// 每周节奏：按周（周一起算）统计投递数。面试数要等轮次数据（里程碑第 6 段）
export function Weekly({ jobs }: { jobs: JobView[] }) {
  const days = applications(jobs).map((j) => dayOf(j.appliedAt)).filter((d): d is string => d !== null);
  return (
    <div className="panel">
      <h2>每周节奏</h2>
      <p className="sub">按周统计的投递数（周一起算）</p>
      <div className="legend"><span><i style={{ background: "var(--s1)" }} />投递</span></div>
      {days.length === 0
        ? <p className="empty">还没有已投递的申请。</p>
        : <div className="chartwrap"><Bars days={days} /></div>}
    </div>
  );
}

function Bars({ days }: { days: string[] }) {
  const thisWeek = weekStart(localDay());
  const first = days.map(weekStart).reduce((a, b) => (a < b ? a : b));
  const weeks: string[] = [];
  for (let w = thisWeek; w >= first && weeks.length < MAX_WEEKS; w = addDays(w, -7)) {
    weeks.unshift(w);
  }
  if (weeks.length === 0) {
    weeks.push(thisWeek);
  }
  const counts = weeks.map((w) => days.filter((d) => weekStart(d) === w).length);
  const yMax = Math.ceil(Math.max(4, ...counts) / 2) * 2;
  const y = (v: number) => T + (1 - v / yMax) * (H - T - B);
  const band = (W - L - R) / weeks.length;
  const bw = Math.min(28, band / 2);
  return (
    <svg className="chart" viewBox={`0 0 ${W} ${H}`} role="img" aria-label="每周投递数"
      style={{ width: "100%", minWidth: 360, height: "auto" }}>
      {[0, yMax / 2, yMax].map((v) => (
        <g key={v}>
          <line x1={L} x2={W - R} y1={y(v)} y2={y(v)} stroke={v === 0 ? "var(--axis)" : "var(--grid)"} strokeWidth={1} />
          <text x={L - 6} y={y(v) + 4} textAnchor="end">{v}</text>
        </g>
      ))}
      {weeks.map((w, i) => {
        const cx = L + band * i + band / 2;
        const v = counts[i];
        const tip = `${md(w)} 那周：投递 ${v}`;
        const top = y(v);
        const h = y(0) - top;
        const r = Math.min(4, h);
        return (
          <g key={w}>
            <title>{tip}</title>
            {v === 0
              ? <rect x={cx - bw / 2} y={y(0) - 1} width={bw} height={1} fill="var(--s1)" opacity={0.5} />
              : <path fill="var(--s1)" d={`M${cx - bw / 2},${y(0)} V${top + r} Q${cx - bw / 2},${top} ${cx - bw / 2 + r},${top} H${cx + bw / 2 - r} Q${cx + bw / 2},${top} ${cx + bw / 2},${top + r} V${y(0)} Z`} />}
            {v > 0 && <text x={cx} y={top - 4} textAnchor="middle" className="ink">{v}</text>}
            <text x={cx} y={H - 8} textAnchor="middle">{md(w)}{w === thisWeek ? " (本周)" : ""}</text>
          </g>
        );
      })}
    </svg>
  );
}

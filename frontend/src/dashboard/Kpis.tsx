import type { JobView } from "../api";
import { applications, markedOutcome, submitted } from "./metrics";

// 10 格 KPI（示意页）：投递、公司、各阶段个数、待追踪、候选池待投（C005-fix 开场：项目状态看主页，不在对话里）
export function Kpis({ jobs }: { jobs: JobView[] }) {
  const apps = applications(jobs);
  const sent = apps.filter(submitted);
  const count = (...phases: string[]) => apps.filter((j) => phases.includes(j.phase ?? "")).length;
  const due = apps.filter((j) => j.due).length;
  // 候选池里还没决定或想投、还没转为申请的
  const leads = jobs.filter((j) => j.leadStatus === "new" || j.leadStatus === "shortlisted").length;
  const items: [string, number, "warn" | "good" | null][] = [
    ["投递", sent.length, null],
    ["公司", new Set(sent.map((j) => j.company)).size, null],
    ["准备中", count("prep"), null],
    ["筛选中", count("applied", "screening"), null],
    ["笔试 / OA", count("assessment"), null],
    ["面试中", count("interview"), null],
    ["Offer", count("offer"), count("offer") > 0 ? "good" : null],
    ["待追踪", due, due > 0 ? "warn" : null],
    ["候选池待投", leads, null],
    // C024：投递失败和不投了放在一格，明细在「没投成 / 不投了」面板
    ["没投成 / 不投了", apps.filter((j) => markedOutcome(j) !== null).length, null],
  ];
  return (
    <div className="kpis">
      {items.map(([label, value, tone]) => (
        <div key={label} className={`kpi${tone ? ` ${tone}` : ""}`}><b>{value}</b><span>{label}</span></div>
      ))}
    </div>
  );
}

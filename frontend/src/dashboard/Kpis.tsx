import { checkDue, type JobView } from "../api";
import { applications, submitted } from "./metrics";

// 8 格 KPI（示意页）：投递、公司、各阶段个数、待追踪
export function Kpis({ jobs }: { jobs: JobView[] }) {
  const apps = applications(jobs);
  const sent = apps.filter(submitted);
  const count = (...phases: string[]) => apps.filter((j) => phases.includes(j.phase ?? "")).length;
  const due = apps.filter((j) => checkDue(j)).length;
  const items: [string, number, boolean][] = [
    ["投递", sent.length, false],
    ["公司", new Set(sent.map((j) => j.company)).size, false],
    ["准备中", count("prep"), false],
    ["筛选中", count("applied", "screening"), false],
    ["笔试 / OA", count("assessment"), false],
    ["面试中", count("interview"), false],
    ["Offer", count("offer"), false],
    ["待追踪", due, due > 0],
  ];
  return (
    <div className="kpis">
      {items.map(([label, value, warn]) => (
        <div key={label} className={`kpi${warn ? " warn" : ""}`}><b>{value}</b><span>{label}</span></div>
      ))}
    </div>
  );
}

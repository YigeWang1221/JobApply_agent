import { localDay, type JobView } from "../api";

// Dashboard 的口径（tracking.md「Dashboard 组件」，C006-fix-ui）：全部从 JobView 的 phase、事件、日期算出来

export const DAY_MS = 24 * 3600 * 1000;

// 看板的列（阶段固定枚举）
export const BOARD_PHASES: [string, string][] = [
  ["prep", "准备中"],
  ["applied", "已投递"],
  ["screening", "筛选"],
  ["assessment", "笔试"],
  ["interview", "面试"],
  ["offer", "Offer"],
  ["closed", "已结束"],
];

// 漏斗的层：第 i 层（从 1 起）是「曾经走到过」这一步
export const FUNNEL_LEVELS = ["已投递", "简历筛选", "笔试 / OA", "面试中", "Offer"];
const LEVEL: Record<string, number> = { applied: 1, screening: 2, assessment: 3, interview: 4, offer: 5 };

export function applications(jobs: JobView[]): JobView[] {
  return jobs.filter((j) => j.leadStatus === "converted");
}

// 真的投出去了：现在在投递以后的阶段（服务器的分组 applied），或者投递以后才结束
export function submitted(job: JobView): boolean {
  return job.bucket === "applied" || (job.phase === "closed" && reached(job) > 0);
}

// 走到过的最远一层：当前阶段和事件日志里出现过的阶段取最大；接受 / 拒绝 Offer 算走到 Offer
export function reached(job: JobView): number {
  let level = LEVEL[job.phase ?? ""] ?? 0;
  for (const e of job.events ?? []) {
    level = Math.max(level, LEVEL[e.to] ?? 0);
  }
  if (job.label === "closed:accepted" || job.label === "closed:declined") {
    level = 5;
  }
  if (level === 0 && job.appliedAt) {
    level = 1;
  }
  return level;
}

// 时间（带时区的时间戳或 yyyy-MM-dd）→ 本地日期 yyyy-MM-dd
export function dayOf(value: string | null | undefined): string | null {
  if (!value) {
    return null;
  }
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return value;
  }
  const t = Date.parse(value);
  return Number.isNaN(t) ? value.slice(0, 10) : localDay(new Date(t));
}

// yyyy-MM-dd → 本地零点的毫秒数
export function dayMs(day: string): number {
  const [y, m, d] = day.split("-").map(Number);
  return new Date(y, m - 1, d).getTime();
}

export function md(value: string | null | undefined): string {
  const day = dayOf(value);
  return day ? day.slice(5) : "—";
}

export function daysBetween(from: string, to: string): number {
  return Math.round((dayMs(to) - dayMs(from)) / DAY_MS);
}

export function addDays(day: string, n: number): string {
  const [y, m, d] = day.split("-").map(Number);
  return localDay(new Date(y, m - 1, d + n));
}

// 这一天所在周的周一
export function weekStart(day: string): string {
  const [y, m, d] = day.split("-").map(Number);
  const date = new Date(y, m - 1, d);
  const back = (date.getDay() + 6) % 7;
  return localDay(new Date(y, m - 1, d - back));
}

// 结束的那一天：最后一条 closed 事件
export function closedAt(job: JobView): string | null {
  const closed = (job.events ?? []).filter((e) => e.type === "closed");
  return closed.length ? dayOf(closed[closed.length - 1].at) : null;
}

import { useEffect, useMemo, useRef, useState } from "react";
import { api, errorText, type JobView, type StatusChecks } from "../api";
import { stageText } from "../status";
import { toast } from "../toast";
import { checkable, stageOrder } from "./metrics";

// 查官网状态（C026）：Dashboard 和申请总表上面的一条：按阶段 / 到期 / 官网结果筛申请，按筛出来的批量查状态，改查看间隔

export type PortalFilter = "all" | "found" | "needs_login" | "not_found" | "never" | "pending";

const PORTAL_FILTERS: [PortalFilter, string][] = [
  ["all", "全部"],
  ["found", "已查到"],
  ["needs_login", "要手动登录"],
  ["not_found", "没找到"],
  ["never", "没查过"],
  ["pending", "待核准"],
];

export interface CheckFilter {
  stages: string[];
  dueOnly: boolean;
  portal: PortalFilter;
}

const FILTER_KEY = "jobapply.dashboard.checkFilter";
const EMPTY: CheckFilter = { stages: [], dueOnly: false, portal: "all" };

// 筛选记在本机浏览器里（和 useTab 一样）
export function useCheckFilter(): [CheckFilter, (next: CheckFilter) => void] {
  const [filter, setFilter] = useState<CheckFilter>(() => {
    try {
      const saved = JSON.parse(window.localStorage.getItem(FILTER_KEY) ?? "null");
      if (saved && typeof saved === "object") {
        return {
          stages: Array.isArray(saved.stages) ? saved.stages.filter((s: unknown) => typeof s === "string") : [],
          dueOnly: saved.dueOnly === true,
          portal: PORTAL_FILTERS.some(([p]) => p === saved.portal) ? saved.portal : "all",
        };
      }
    } catch {
      // 读不到就从不筛开始
    }
    return EMPTY;
  });
  return [filter, (next: CheckFilter) => {
    setFilter(next);
    try {
      window.localStorage.setItem(FILTER_KEY, JSON.stringify(next));
    } catch {
      // 本地存储不可用：只在这次打开里有效
    }
  }];
}

// 现有申请里出现过的阶段，按阶段、再按轮次排
export function stageOptions(jobs: JobView[]): string[] {
  const order = new Map<string, number>();
  for (const job of jobs) {
    if (job.leadStatus !== "converted") {
      continue;
    }
    const text = stageText(job);
    order.set(text, Math.min(order.get(text) ?? Infinity, stageOrder(job)));
  }
  return [...order.keys()].sort((a, b) => order.get(a)! - order.get(b)! || a.localeCompare(b));
}

// 只筛申请；候选原样留着。选过的阶段在这个范围里都不存在时当作不按阶段筛
export function applyFilter(jobs: JobView[], filter: CheckFilter): JobView[] {
  const options = new Set(stageOptions(jobs));
  const stages = new Set(filter.stages.filter((s) => options.has(s)));
  return jobs.filter((job) => {
    if (job.leadStatus !== "converted") {
      return true;
    }
    if (stages.size > 0 && !stages.has(stageText(job))) {
      return false;
    }
    if (filter.dueOnly && !job.due) {
      return false;
    }
    switch (filter.portal) {
      case "found":
      case "needs_login":
      case "not_found":
        return job.portalCheck?.result === filter.portal;
      case "never":
        return !job.portalCheck;
      case "pending":
        return !!job.proposal;
      default:
        return true;
    }
  });
}

function active(checks: StatusChecks | null): boolean {
  return !!checks && checks.queued.length + checks.running.length > 0;
}

/**
 * @param jobs      范围里的全部 Job（阶段选项从这里来）
 * @param filtered  筛过的 Job：「查状态」只查这里面的
 * @param projectId Project 主页传：间隔改的是这个 Project 的；全局页不传，改全局的
 * @param onDays    查看间隔（生效的天数）读到 / 改了以后告诉上层（待追踪的「每 N 天」）
 */
export function CheckBar({ jobs, filtered, filter, onFilter, projectId, onDays }: {
  jobs: JobView[];
  filtered: JobView[];
  filter: CheckFilter;
  onFilter: (next: CheckFilter) => void;
  projectId?: string;
  onDays?: (days: number) => void;
}) {
  const options = useMemo(() => stageOptions(jobs), [jobs]);
  const [checks, setChecks] = useState<StatusChecks | null>(null);
  const running = active(checks);

  // 打开页面时看一眼有没有正在查的；有就每 2 秒刷新进度
  useEffect(() => {
    let alive = true;
    api.statusChecks().then((c) => alive && setChecks(c)).catch(() => {});
    return () => {
      alive = false;
    };
  }, []);
  useEffect(() => {
    if (!running) {
      return;
    }
    const timer = window.setInterval(() => {
      api.statusChecks().then(setChecks).catch(() => {});
    }, 2000);
    return () => window.clearInterval(timer);
  }, [running]);

  const loginOnly = filter.portal === "needs_login";
  const candidates = filtered.filter((j) => checkable(j) && !j.checking);
  const ids = candidates.filter((j) => loginOnly || j.portalCheck?.result !== "needs_login").map((j) => j.id);
  const skippedLogin = candidates.length - ids.length;

  async function start() {
    try {
      const result = await api.startStatusChecks(ids);
      setChecks(result);
      const skipped = result.skipped.length;
      toast(`已开始查 ${ids.length - skipped} 个申请的官网状态${skipped ? `，跳过 ${skipped} 个` : ""}`);
    } catch (e) {
      toast(errorText(e), "error");
    }
  }
  async function stop() {
    try {
      setChecks(await api.stopStatusChecks());
      toast("已停止查状态：排队的不再查");
    } catch (e) {
      toast(errorText(e), "error");
    }
  }

  const toggleStage = (stage: string) => onFilter({
    ...filter,
    stages: filter.stages.includes(stage) ? filter.stages.filter((s) => s !== stage) : [...filter.stages, stage],
  });

  return (
    <div className="panel checkbar">
      <div className="cbrow">
        <span className="cblabel">阶段</span>
        <button className="chip cbchip" aria-pressed={!filter.stages.some((s) => options.includes(s))}
          onClick={() => onFilter({ ...filter, stages: [] })}>全部</button>
        {options.map((stage) => (
          <button key={stage} className="chip cbchip" aria-pressed={filter.stages.includes(stage)}
            onClick={() => toggleStage(stage)}>{stage}</button>
        ))}
        <label className="cbdue">
          <input type="checkbox" checked={filter.dueOnly} onChange={(e) => onFilter({ ...filter, dueOnly: e.target.checked })} />
          只看到期的
        </label>
      </div>
      <div className="cbrow">
        <span className="cblabel">官网</span>
        <div className="seg" role="group" aria-label="官网查到的结果">
          {PORTAL_FILTERS.map(([p, name]) => (
            <button key={p} aria-pressed={filter.portal === p} onClick={() => onFilter({ ...filter, portal: p })}>{name}</button>
          ))}
        </div>
        <span className="cbgap" />
        {running && checks && (
          <>
            <span className="cbprog">
              {`查状态 ${checks.done.length}/${checks.total} · 已查到 ${checks.found} · 要手动登录 ${checks.needsLogin}`}
              {checks.notFound > 0 && ` · 没找到 ${checks.notFound}`}
            </span>
            <button className="linkbtn" onClick={() => void stop()}>停止</button>
          </>
        )}
        <button className="primary" disabled={ids.length === 0}
          title="只查上面筛出来的、已投递还没结束的申请；Agent 不登录、不填密码，遇到登录页就记「要手动登录」"
          onClick={() => void start()}>查状态（{ids.length}）</button>
        {skippedLogin > 0 && <small className="muted">跳过 {skippedLogin} 个要手动登录的</small>}
        <CheckDays projectId={projectId} onDays={onDays} />
      </div>
    </div>
  );
}

// 「每 [N] 天查看一次」：Project 主页改这个 Project 的（可以回到全局），全局页改全局的
function CheckDays({ projectId, onDays }: { projectId?: string; onDays?: (days: number) => void }) {
  const [effective, setEffective] = useState<number | null>(null);
  const [override, setOverride] = useState<number | null>(null);
  const [global, setGlobal] = useState<number | null>(null);
  const [text, setText] = useState("");
  const report = useRef(onDays);
  report.current = onDays;

  function apply(days: number, own: number | null, all: number) {
    setEffective(days);
    setOverride(own);
    setGlobal(all);
    setText(String(days));
    report.current?.(days);
  }

  useEffect(() => {
    let alive = true;
    const load = projectId
      ? api.projectCheckDays(projectId).then((r) => alive && apply(r.effective, r.days, r.global))
      : api.checkSettings().then((r) => alive && apply(r.days, null, r.days));
    load.catch((e) => toast(`读不到查看间隔：${errorText(e)}`, "error"));
    return () => {
      alive = false;
    };
  }, [projectId]);

  async function save(days: number | null) {
    try {
      if (projectId) {
        const r = await api.saveProjectCheckDays(projectId, days);
        apply(r.effective, r.days, r.global);
        toast(days === null ? `这个 Project 改用全局：每 ${r.effective} 天查看一次` : `这个 Project 每 ${r.effective} 天查看一次`);
      } else {
        const r = await api.saveCheckSettings(days ?? 0);
        apply(r.days, null, r.days);
        toast(`全局：每 ${r.days} 天查看一次`);
      }
    } catch (e) {
      toast(errorText(e), "error");
      setText(effective === null ? "" : String(effective));
    }
  }

  function commit() {
    const days = Number(text);
    if (effective !== null && days === effective) {
      return;
    }
    if (!Number.isInteger(days) || days < 1 || days > 90) {
      toast("查看间隔是 1–90 天", "error");
      setText(effective === null ? "" : String(effective));
      return;
    }
    void save(days);
  }

  return (
    <span className="cbdays" title={projectId ? "这个 Project 的申请多久查看一次；不改就用全局的" : "所有 Project 默认多久查看一次"}>
      每
      <input type="number" min={1} max={90} value={text} disabled={effective === null}
        onChange={(e) => setText(e.target.value)} onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.currentTarget.blur();
          }
        }} />
      天查看一次
      {projectId && override !== null && (
        <button className="linkbtn" title={`回到全局的每 ${global} 天`} onClick={() => void save(null)}>用全局</button>
      )}
    </span>
  );
}

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

// 筛选记在本机浏览器里，每个 Project 一份（C006-fix 全局 Dashboard）：A 里选的不带到 B。
// 换 Project 时 Dashboard 页会按 Project 重新挂载 TabBody，这里只在挂载时读一次
export function useCheckFilter(projectId: string): [CheckFilter, (next: CheckFilter) => void] {
  const key = `${FILTER_KEY}.${projectId}`;
  const [filter, setFilter] = useState<CheckFilter>(() => {
    try {
      const saved = JSON.parse(window.localStorage.getItem(key) ?? "null");
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
      window.localStorage.setItem(key, JSON.stringify(next));
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

// 后端的查状态是一整批（可能混着别的 Project）；这里只数这个 Project 的 Job（C006-fix 全局 Dashboard）
interface Progress {
  active: boolean;
  done: number;
  total: number;
  found: number;
  needsLogin: number;
  notFound: number;
}

function progressOf(checks: StatusChecks | null, jobs: JobView[]): Progress {
  const mine = new Map(jobs.map((j) => [j.id, j]));
  const pick = (ids: string[] | undefined) => (ids ?? []).filter((id) => mine.has(id));
  const queued = pick(checks?.queued);
  const running = pick(checks?.running);
  const done = pick(checks?.done);
  // 查到 / 要手动登录 / 没找到按这些 Job 自己记下的 portalCheck 数（Job 列表跟着全局事件刷新）
  const result = (r: string) => done.filter((id) => mine.get(id)?.portalCheck?.result === r).length;
  return {
    active: queued.length + running.length > 0,
    done: done.length,
    total: queued.length + running.length + done.length,
    found: result("found"),
    needsLogin: result("needs_login"),
    notFound: result("not_found"),
  };
}

/**
 * @param jobs      范围里的全部 Job（阶段选项从这里来）
 * @param filtered  筛过的 Job：「查状态」只查这里面的
 * @param projectId 选中的 Project：间隔改的是它的；进度和「停止」只管它的 Job
 * @param onDays    查看间隔（生效的天数）读到 / 改了以后告诉上层（待追踪的「每 N 天」）
 */
export function CheckBar({ jobs, filtered, filter, onFilter, projectId, onDays }: {
  jobs: JobView[];
  filtered: JobView[];
  filter: CheckFilter;
  onFilter: (next: CheckFilter) => void;
  projectId: string;
  onDays?: (days: number) => void;
}) {
  const options = useMemo(() => stageOptions(jobs), [jobs]);
  const [checks, setChecks] = useState<StatusChecks | null>(null);
  const progress = useMemo(() => progressOf(checks, jobs), [checks, jobs]);
  const running = progress.active;

  // 打开页面时看一眼这个 Project 有没有正在查的；有就每 2 秒刷新进度。别的 Project 在查时这里不显示、不轮询
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
  // 一批查完后，后端把要登录 / 没找到状态页的门户在 Chrome 里开好（C026-fix）：再取两次进度，拿到开了哪些页
  const wasRunning = useRef(false);
  useEffect(() => {
    if (running) {
      wasRunning.current = true;
      return;
    }
    if (!wasRunning.current) {
      return;
    }
    wasRunning.current = false;
    const timers = [1500, 6000].map((ms) => window.setTimeout(() => {
      api.statusChecks().then(setChecks).catch(() => {});
    }, ms));
    return () => timers.forEach((t) => window.clearTimeout(t));
  }, [running]);
  const opened = running ? [] : (checks?.opened ?? []);

  // 要手动登录的也查（C026-fix）：用户在 Chrome 里登录后再点这里，Agent 自己开页再查一次
  const ids = filtered.filter((j) => checkable(j) && !j.checking).map((j) => j.id);

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
      // 只停这个 Project 的（后端按 jobIds 移出 / 停下）；别的 Project 在查的不动。
      // 空列表在后端等于停整批，所以没有 Job 时不发（没有 Job 时也不会显示「停止」）
      const mine = jobs.map((j) => j.id);
      if (mine.length === 0) {
        return;
      }
      setChecks(await api.stopStatusChecks(mine));
      toast("已停止这个 Project 的查状态：排队的不再查");
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
        {running && (
          <>
            <span className="cbprog">
              {`查状态 ${progress.done}/${progress.total} · 已查到 ${progress.found} · 要手动登录 ${progress.needsLogin}`}
              {progress.notFound > 0 && ` · 没找到 ${progress.notFound}`}
            </span>
            <button className="linkbtn" onClick={() => void stop()}>停止</button>
          </>
        )}
        {opened.length > 0 && (
          <small className="muted" title={opened.join("\n")}>
            {`已在 Chrome 里打开 ${opened.length} 页给你登录 / 查看：${opened.map(hostOf).join("、")}`}
          </small>
        )}
        <button className="primary" disabled={ids.length === 0}
          title="只查上面筛出来的、已投递还没结束的申请；Agent 不登录、不填密码，查完关掉自己的页。要登录、没找到状态页的门户在整批查完后一次在 Chrome 里打开（一个网站一页），你登录后再点查状态"
          onClick={() => void start()}>查状态（{ids.length}）</button>
        <CheckDays projectId={projectId} onDays={onDays} />
      </div>
    </div>
  );
}

// 「每 [N] 天查看一次」：改的是下拉框选中的 Project 的（可以回到全局）；全局默认值在设置页「运行」里改
function CheckDays({ projectId, onDays }: { projectId: string; onDays?: (days: number) => void }) {
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
    api.projectCheckDays(projectId).then((r) => alive && apply(r.effective, r.days, r.global))
      .catch((e) => toast(`读不到查看间隔：${errorText(e)}`, "error"));
    return () => {
      alive = false;
    };
  }, [projectId]);

  async function save(days: number | null) {
    try {
      const r = await api.saveProjectCheckDays(projectId, days);
      apply(r.effective, r.days, r.global);
      toast(days === null ? `这个 Project 改用全局：每 ${r.effective} 天查看一次` : `这个 Project 每 ${r.effective} 天查看一次`);
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
    <span className="cbdays" title="这个 Project 的申请多久查看一次；不改就用全局的（设置页「运行」）">
      每
      <input type="number" min={1} max={90} value={text} disabled={effective === null}
        onChange={(e) => setText(e.target.value)} onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.currentTarget.blur();
          }
        }} />
      天查看一次
      {override !== null && (
        <button className="linkbtn" title={`回到全局的每 ${global} 天`} onClick={() => void save(null)}>用全局</button>
      )}
    </span>
  );
}

function hostOf(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return url;
  }
}

import { useMemo, useState, type ReactNode } from "react";
import { api, errorText, type JobView, type SessionSummary } from "../api";
import { go } from "../route";
import { LEAD } from "../status";
import { toast } from "../toast";
import { Approvals } from "./Approvals";
import { AppsTable } from "./AppsTable";
import { Board } from "./Board";
import { applyFilter, CheckBar, useCheckFilter } from "./CheckBar";
import { Failures, failureCount } from "./Failures";
import { FollowUps } from "./FollowUps";
import { Funnel } from "./Funnel";
import { Kpis } from "./Kpis";
import { LeadPool } from "./LeadPool";
import { applications } from "./metrics";
import { Reviews } from "./Reviews";
import { Timeline } from "./Timeline";
import { Upcoming } from "./Upcoming";
import { Weekly } from "./Weekly";

// Dashboard 页的三页签（示意页；C006-fix 全局 Dashboard：只看顶部下拉框选中的一个 Project）：Dashboard / 候选池 / 申请总表。轮次表、题库等第 6 段
export type Tab = "dash" | "leads" | "apps";

const TABS: [Tab, string][] = [["dash", "Dashboard"], ["leads", "候选池"], ["apps", "申请总表"]];
const TAB_KEY = "jobapply.dashboard.tab";

// 选中的页签记在本机浏览器里：从工作台回来还在同一页签
export function useTab(): [Tab, (tab: Tab) => void] {
  const [tab, setTab] = useState<Tab>(() => {
    try {
      const saved = window.localStorage.getItem(TAB_KEY);
      return TABS.some(([t]) => t === saved) ? (saved as Tab) : "dash";
    } catch {
      return "dash";
    }
  });
  return [tab, (next: Tab) => {
    setTab(next);
    try {
      window.localStorage.setItem(TAB_KEY, next);
    } catch {
      // 本地存储不可用：只在这次打开里有效
    }
  }];
}

export function TabBar({ tab, onChange }: { tab: Tab; onChange: (tab: Tab) => void }) {
  return (
    <div className="tabs" role="tablist" aria-label="视图">
      {TABS.map(([t, name]) => (
        <button key={t} role="tab" aria-selected={tab === t} className={tab === t ? "active" : ""}
          onClick={() => onChange(t)}>{name}</button>
      ))}
    </div>
  );
}

/**
 * @param sessions 范围内的 Session（新的在前）：显示「发现于哪个 Session」，开始申请时找最近的 Session
 * @param recent   在 KPI 下面放的「最近的 Session」
 * @param onTab    换页签（候选池里「Failures n」跳回 Dashboard 用）
 * @param projectId 选中的 Project：筛选按它存，查看间隔、查状态进度和「停止」都只管它
 */
export function TabBody({ tab, jobs, sessions, reload, recent, onTab, projectId }: {
  tab: Tab;
  projectId: string;
  onTab?: (tab: Tab) => void;
  jobs: JobView[];
  sessions: SessionSummary[];
  reload: () => Promise<void>;
  recent?: ReactNode;
}) {
  // C026：查状态条的筛选只筛申请；看板、申请总表、待追踪、核准列表、时间线吃筛过的，KPI / 漏斗 / 每周节奏不筛
  const [filter, setFilter] = useCheckFilter(projectId);
  const [everyDays, setEveryDays] = useState<number | null>(null);
  const filtered = useMemo(() => applyFilter(jobs, filter), [jobs, filter]);
  const checkBar = (
    <CheckBar jobs={jobs} filtered={filtered} filter={filter} onFilter={setFilter} projectId={projectId} onDays={setEveryDays} />
  );
  // 动作出错时显示后端的原话，成功后重新读一遍
  async function act(action: () => Promise<unknown>, done: string, then?: () => void): Promise<boolean> {
    try {
      await action();
      toast(done);
      then?.();
      await reload();
      return true;
    } catch (e) {
      toast(errorText(e), "error");
      return false;
    }
  }
  const postingClosed = (job: JobView) =>
    act(() => api.postingClosed(job.id), `${job.company} · ${job.title}：${job.leadStatus === "converted" ? "岗位下线，已结束" : "已下线"}`);

  if (tab === "leads") {
    const titles = new Map(sessions.map((s) => [s.id, s.title]));
    // 跳过的候选不在这里列，和投递失败 / 不投了一起在 Dashboard 的 Failures（C024-fix）
    const skipped = jobs.filter((j) => j.leadStatus === "skipped").length;
    const failures = failureCount(jobs);
    return (
      <div className="panel">
        <div className="ph">
          <h2>候选池</h2>
          <span className="boardnote">调研出来的岗位，按公司、再按地点分组；公司名写法不同的由模型归成一家；岗位不必和 Project 名字的方向一致</span>
          {failures > 0 && (
            <button className="linkbtn failurelink" title={`跳过的 ${skipped} 个和投递失败 / 不投了都在 Dashboard 的 Failures`}
              onClick={() => {
                onTab?.("dash");
                window.setTimeout(() => document.getElementById("failures")?.scrollIntoView({ block: "start" }), 100);
              }}>Failures {failures}</button>
          )}
        </div>
        <LeadPool leads={jobs.filter((j) => j.leadStatus !== "converted" && j.leadStatus !== "skipped")}
          applications={applications(jobs)}
          sessionTitle={(id) => titles.get(id)}
          onClosed={postingClosed}
          onMark={(job, status, reason) => act(() => api.markLead(job.id, status, reason),
            `${job.company} · ${job.title}：${LEAD[status]}`)}
          onStart={(job) => {
            // C021：直接在这个岗位自己的工作台里启动申请线程（第一轮先排简历、给修改建议），不再往 Session 发消息
            // 发现它的 Session 删了（C013）时用这个 Project 最近的一个；都没有就新建一个
            const known = (job.discoveredIn && titles.has(job.discoveredIn) ? job.discoveredIn : undefined)
              ?? sessions.find((s) => s.project === job.project)?.id;
            void act(async () => {
              const session = known ?? (await api.createSession(job.project)).session.id;
              await api.startApplication(job.id, { project: job.project, session });
            }, `已启动 ${job.company} · ${job.title} 的工作台：先匹配简历`, () => go({ kind: "job", id: job.id }));
          }} />
      </div>
    );
  }
  if (tab === "apps") {
    return (
      <>
        {checkBar}
        <AppsTable jobs={filtered} onPostingClosed={(job) => void postingClosed(job)} />
      </>
    );
  }
  return (
    <>
      {checkBar}
      <Kpis jobs={jobs} />
      <Approvals jobs={filtered} reload={reload} />
      {recent}
      {/* 两列各自一叠（C023-A）：左边漏斗 + 每周节奏，右边接下来 / 待追踪 / 复盘，较短的一列不再留大块空白 */}
      <div className="grid2">
        <div className="stack">
          <Funnel jobs={jobs} />
          <Weekly jobs={jobs} />
        </div>
        <div className="stack">
          <Upcoming jobs={jobs} />
          <FollowUps jobs={filtered} everyDays={everyDays} />
          <Reviews jobs={jobs} />
        </div>
      </div>
      {/* 看板下面是 Failures（C024-fix）：跳过的候选、投递失败、不投了；看板「已结束」列和时间线不再有它们 */}
      <Board jobs={filtered} />
      <Failures jobs={jobs}
        onReopen={(job) => void act(() => api.reopen(job.id), `${job.company} · ${job.title}：已撤销，回到之前的状态`)} />
      <Timeline jobs={filtered} />
    </>
  );
}

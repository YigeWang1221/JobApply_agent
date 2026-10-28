import { useState, type ReactNode } from "react";
import { api, errorText, type JobView, type SessionSummary } from "../api";
import { go } from "../route";
import { LEAD } from "../status";
import { toast } from "../toast";
import { AppsTable } from "./AppsTable";
import { Board } from "./Board";
import { FollowUps } from "./FollowUps";
import { Funnel } from "./Funnel";
import { Kpis } from "./Kpis";
import { LeadPool } from "./LeadPool";
import { applications } from "./metrics";
import { Reviews } from "./Reviews";
import { Timeline } from "./Timeline";
import { Upcoming } from "./Upcoming";
import { Weekly } from "./Weekly";

// 全局页和 Project 主页共用的三页签（示意页）：Dashboard / 候选池 / 申请总表。轮次表、题库等第 6 段
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
 * @param recent   Project 主页在 KPI 下面放的「最近的 Session」
 */
export function TabBody({ tab, jobs, showProject, sessions, reload, recent }: {
  tab: Tab;
  jobs: JobView[];
  showProject?: boolean;
  sessions: SessionSummary[];
  reload: () => Promise<void>;
  recent?: ReactNode;
}) {
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
    return (
      <div className="panel">
        <div className="ph">
          <h2>候选池</h2>
          <span className="boardnote">调研出来的公司与岗位，按公司分组；岗位不必和 Project 名字的方向一致</span>
        </div>
        <LeadPool leads={jobs.filter((j) => j.leadStatus !== "converted")} applications={applications(jobs)}
          showProject={showProject} sessionTitle={(id) => titles.get(id)}
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
    return <AppsTable jobs={jobs} showProject={showProject} onPostingClosed={(job) => void postingClosed(job)} />;
  }
  return (
    <>
      <Kpis jobs={jobs} />
      {recent}
      <div className="grid2">
        <Funnel jobs={jobs} />
        <div className="stack">
          <Upcoming jobs={jobs} showProject={showProject} />
          <FollowUps jobs={jobs} showProject={showProject} />
        </div>
      </div>
      <Board jobs={jobs} showProject={showProject} />
      <Timeline jobs={jobs} />
      <div className="grid2">
        <Weekly jobs={jobs} />
        <Reviews jobs={jobs} />
      </div>
    </>
  );
}

import { useCallback, useEffect, useState } from "react";
import { api, errorText, localDay, type JobView, type ProjectSummary } from "../api";
import { useGlobalChanges } from "../chat/useLiveUpdates";
import { TabBar, TabBody, useTab } from "../dashboard/Dashboard";
import { RecentSessions } from "../dashboard/RecentSessions";
import { go, rememberDashboardProject, replaceRoute } from "../route";
import { shell } from "../shell";

// 默认选哪个 Project：最近有 Session 活动的（按 Session 的创建时间，后端没有别的活动时间），没有 Session 就第一个
function mostRecent(projects: ProjectSummary[]): string {
  let best = projects[0];
  let bestAt = "";
  for (const p of projects) {
    for (const s of p.sessions) {
      if (s.createdAt > bestAt) {
        best = p;
        bestAt = s.createdAt;
      }
    }
  }
  return best.id;
}

// Project / Dashboard 合并视图（C006-fix 全局 Dashboard，C030 IA）：顶部下拉框选一个 Project（侧栏点 Project 也切到这里），
// 同页有「新建 Session」、最近的 Session，下面是它的 Dashboard / 候选池 / 申请总表。
// 不把多个 Project 的申请混在一起看；选中的 Project 记在 URL（#/global/<id>）里
export default function Global({ projects, selected, onChanged }: {
  projects: ProjectSummary[] | null;
  selected?: string;
  onChanged: () => Promise<void>;
}) {
  const [tab, setTab] = useTab();
  // URL 里的 Project 还在就用它，否则最近有活动的
  const chosen = projects && projects.length > 0
    ? (selected && projects.some((p) => p.id === selected) ? selected : mostRecent(projects))
    : undefined;
  const [data, setData] = useState<{ id: string; project: ProjectSummary; jobs: JobView[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  async function newSession() {
    if (!chosen) {
      return;
    }
    setCreating(true);
    try {
      const view = await api.createSession(chosen);
      await onChanged();
      go({ kind: "session", id: view.session.id });
    } catch (e) {
      setError(errorText(e));
      setCreating(false);
    }
  }

  // 选中的写回 URL（replace：不压返回栈），也记给侧栏「Dashboard」按钮
  useEffect(() => {
    if (chosen) {
      rememberDashboardProject(chosen);
      if (chosen !== selected) {
        replaceRoute({ kind: "global", project: chosen });
      }
    }
  }, [chosen, selected]);

  const load = useCallback(async () => {
    if (!chosen) {
      return;
    }
    try {
      const view = await api.project(chosen);
      setData({ id: chosen, project: view.project, jobs: view.jobs });
      setError(null);
    } catch (e) {
      setError(errorText(e));
    }
  }, [chosen]);

  useEffect(() => {
    void load();
  }, [load]);
  useGlobalChanges(load);

  // 换了 Project、新的还没读到时不显示旧的
  const current = data && data.id === chosen ? data : null;
  return (
    <>
      <div className="top" onMouseDown={(e) => {
        if (e.button === 0 && shell.available() && e.target === e.currentTarget) {
          shell.startDrag();
        }
      }}>
        <div>
          <h1>Dashboard</h1>
          <p>{localDay()}{current ? ` · ${current.project.title}` : ""}</p>
        </div>
        <div className="topright">
          {projects && projects.length > 0 && (
            <select className="dashproject" aria-label="选择 Project" value={chosen}
              onChange={(e) => replaceRoute({ kind: "global", project: e.target.value })}>
              {projects.map((p) => <option key={p.id} value={p.id}>{p.title}</option>)}
            </select>
          )}
          <TabBar tab={tab} onChange={setTab} />
          <button className="primary" disabled={creating || !chosen} onClick={() => void newSession()}>＋ 新建 Session</button>
        </div>
      </div>
      {error && <p className="error">{error}</p>}
      {projects === null && !error && <p className="muted">读取中…</p>}
      {projects !== null && projects.length === 0 && (
        <div className="panel"><p className="empty">还没有 Project。在左边新建一个，再在里面新建 Session，让 Agent 去找岗位。</p></div>
      )}
      {chosen && (current === null
        ? !error && <p className="muted">读取中…</p>
        : <TabBody key={current.id} tab={tab} onTab={setTab} jobs={current.jobs} sessions={current.project.sessions}
            reload={load} projectId={current.id} recent={<RecentSessions sessions={current.project.sessions} />} />)}
    </>
  );
}

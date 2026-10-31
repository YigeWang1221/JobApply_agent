import { useCallback, useEffect, useState } from "react";
import { api, errorText, localDay, type JobView, type ProjectSummary } from "../api";
import { useGlobalChanges } from "../chat/useLiveUpdates";
import { TabBar, TabBody, useTab } from "../dashboard/Dashboard";

// 全局：所有 Project 汇总的 Dashboard / 候选池 / 申请总表（示意页）
export default function Global({ projects }: { projects: ProjectSummary[] | null }) {
  const [tab, setTab] = useTab();
  const [jobs, setJobs] = useState<JobView[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setJobs((await api.jobs()).jobs);
      setError(null);
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);
  useGlobalChanges(load);

  const sessions = (projects ?? []).flatMap((p) => p.sessions);
  return (
    <>
      <div className="top">
        <div>
          <h1>全局 Dashboard</h1>
          <p>{localDay()} · 所有 Project 汇总</p>
        </div>
        <TabBar tab={tab} onChange={setTab} />
      </div>
      {error && <p className="error">{error}</p>}
      {projects !== null && projects.length === 0 && (
        <div className="panel"><p className="empty">还没有 Project。在左边新建一个，再在里面新建 Session，让 Agent 去找岗位。</p></div>
      )}
      {jobs === null
        ? !error && <p className="muted">读取中…</p>
        : <TabBody tab={tab} jobs={jobs} showProject sessions={sessions} reload={load}
          projectTitle={(id) => projects?.find((p) => p.id === id)?.title} />}
    </>
  );
}

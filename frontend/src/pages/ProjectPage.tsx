import { useCallback, useEffect, useState } from "react";
import { api, errorText, type JobView, type ProjectSummary } from "../api";
import { useGlobalChanges } from "../chat/useLiveUpdates";
import { TabBar, TabBody, useTab } from "../dashboard/Dashboard";
import { RecentSessions } from "../dashboard/RecentSessions";
import { go } from "../route";
import { RenameTitle } from "../RenameTitle";

// Project 主页（ui.md「界面结构」，示意页）：新建 Session；Dashboard / 候选池 / 申请总表三页签；Dashboard 里有最近的 Session
export default function ProjectPage({ id, onChanged }: { id: string; onChanged: () => Promise<void> }) {
  const [tab, setTab] = useTab();
  const [project, setProject] = useState<ProjectSummary | null>(null);
  const [jobs, setJobs] = useState<JobView[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const data = await api.project(id);
      setProject(data.project);
      setJobs(data.jobs);
      setError(null);
    } catch (e) {
      setError(errorText(e));
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);
  useGlobalChanges(load);

  async function newSession() {
    setBusy(true);
    setError(null);
    try {
      const view = await api.createSession(id);
      await onChanged();
      go({ kind: "session", id: view.session.id });
    } catch (e) {
      setError(errorText(e));
      setBusy(false);
    }
  }

  return (
    <>
      <div className="top">
        <div>
          {project
            ? <RenameTitle value={project.title}
                onSave={async (title) => { await api.renameProject(id, title); await load(); await onChanged(); }} />
            : <h1>{id}</h1>}
          <p className="mono">{id}</p>
        </div>
        <div className="topright">
          <button className="primary" disabled={busy || !project} onClick={() => void newSession()}>＋ 新建 Session</button>
          <TabBar tab={tab} onChange={setTab} />
        </div>
      </div>
      {error && <p className="error">{error}</p>}
      {project && (
        <TabBody tab={tab} onTab={setTab} jobs={jobs} sessions={project.sessions} reload={load}
          recent={<RecentSessions sessions={project.sessions} />} />
      )}
    </>
  );
}

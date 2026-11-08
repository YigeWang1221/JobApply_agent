import { useCallback, useEffect, useState } from "react";
import { api, errorText, type ProjectSummary } from "../api";
import { useGlobalChanges } from "../chat/useLiveUpdates";
import { RecentSessions } from "../dashboard/RecentSessions";
import { go } from "../route";
import { RenameTitle } from "../RenameTitle";

// Project 主页（ui.md「界面结构」）：新建 Session、最近的 Session；Dashboard 只在一处（C006-fix 全局 Dashboard），
// 这里一个按钮打开选中这个 Project 的 Dashboard
export default function ProjectPage({ id, onChanged }: { id: string; onChanged: () => Promise<void> }) {
  const [project, setProject] = useState<ProjectSummary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setProject((await api.project(id)).project);
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
          <button disabled={!project} onClick={() => go({ kind: "global", project: id })}>在 Dashboard 查看</button>
          <button className="primary" disabled={busy || !project} onClick={() => void newSession()}>＋ 新建 Session</button>
        </div>
      </div>
      {error && <p className="error">{error}</p>}
      {project && <RecentSessions sessions={project.sessions} />}
    </>
  );
}

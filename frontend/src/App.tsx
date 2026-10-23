import { useCallback, useEffect, useState } from "react";
import { api, errorText, type ProjectSummary } from "./api";
import Global from "./pages/Global";
import JobPage from "./pages/JobPage";
import ProjectPage from "./pages/ProjectPage";
import SessionPage from "./pages/SessionPage";
import Settings from "./pages/Settings";
import { go, href, useRoute, type Route } from "./route";

const SIDEBAR_REFRESH_MS = 5000;

// 应用外框（C005-ui.md）：左边 Project / Session 侧栏，右边按选中的内容切换
export default function App() {
  const route = useRoute();
  const [projects, setProjects] = useState<ProjectSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setProjects((await api.projects()).projects);
      setError(null);
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => void refresh(), SIDEBAR_REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [refresh, route]);

  return (
    <div className="app">
      <Sidebar route={route} projects={projects} error={error} onCreated={refresh} />
      <main className="main">
        {route.kind === "global" && <Global projects={projects} />}
        {route.kind === "project" && <ProjectPage key={route.id} id={route.id} onChanged={refresh} />}
        {route.kind === "session" && <SessionPage key={route.id} id={route.id} />}
        {route.kind === "job" && <JobPage key={route.id} id={route.id} />}
        {route.kind === "settings" && <Settings tab={route.tab} />}
      </main>
    </div>
  );
}

function Sidebar({ route, projects, error, onCreated }: {
  route: Route;
  projects: ProjectSummary[] | null;
  error: string | null;
  onCreated: () => Promise<void>;
}) {
  const current = (r: Route) => href(r) === href(route);
  return (
    <aside className="side" aria-label="Project 与 Session">
      <div className="brand"><i aria-hidden="true"></i>JobApply</div>
      <button className="pbtn" aria-current={current({ kind: "global" })} onClick={() => go({ kind: "global" })}>
        <span>⌂ 全局</span>
      </button>
      <div>
        <h6>Projects</h6>
        {projects === null && !error && <p className="muted small">读取中…</p>}
        {error && <p className="error small">{error}</p>}
        {projects?.map((p) => (
          <div key={p.id}>
            <button className="pbtn" aria-current={current({ kind: "project", id: p.id })}
              onClick={() => go({ kind: "project", id: p.id })}>
              <span>{p.title}</span><small>{p.id}</small>
            </button>
            {p.sessions.length > 0 && (
              <ul className="sess">
                {p.sessions.map((s) => (
                  <li key={s.id}>
                    <button title={s.title} aria-current={current({ kind: "session", id: s.id })}
                      onClick={() => go({ kind: "session", id: s.id })}>
                      {s.title}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        ))}
        <NewProject onCreated={onCreated} />
      </div>
      <div className="bottom">
        <button className="pbtn" aria-current={route.kind === "settings"}
          onClick={() => go({ kind: "settings", tab: "models" })}>
          <span>⚙ 设置</span>
        </button>
      </div>
    </aside>
  );
}

function NewProject({ onCreated }: { onCreated: () => Promise<void> }) {
  const [open, setOpen] = useState(false);
  const [id, setId] = useState("");
  const [title, setTitle] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!open) {
    return <button className="linkbtn" onClick={() => setOpen(true)}>＋ 新建 Project</button>;
  }

  async function create() {
    setBusy(true);
    setError(null);
    try {
      const project = await api.createProject(id.trim(), title.trim());
      await onCreated();
      setOpen(false);
      setId("");
      setTitle("");
      go({ kind: "project", id: project.id });
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="newproject">
      <input value={id} placeholder="id，例如 autumn-2026" onChange={(e) => setId(e.target.value)} />
      <input value={title} placeholder="名字，例如 2026 秋招" onChange={(e) => setTitle(e.target.value)} />
      <div className="row">
        <button className="primary" disabled={busy || !id.trim() || !title.trim()} onClick={() => void create()}>
          新建
        </button>
        <button onClick={() => setOpen(false)}>取消</button>
      </div>
      <span className="muted small">id 只能用小写字母、数字和连字符。资料继承 base。</span>
      {error && <p className="error small">{error}</p>}
    </div>
  );
}

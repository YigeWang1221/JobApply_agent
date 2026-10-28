import { useCallback, useEffect, useState } from "react";
import { api, errorText, type ProjectSummary, type SessionSummary, type WorkspaceSummary } from "./api";
import BrowserBanner from "./BrowserBanner";
import { DeleteConfirm, type DeleteTarget } from "./DeleteConfirm";
import { Icon } from "./chat/icons";
import { useGlobalChanges } from "./chat/useLiveUpdates";
import Global from "./pages/Global";
import JobPage from "./pages/JobPage";
import ProjectPage from "./pages/ProjectPage";
import SessionPage from "./pages/SessionPage";
import Settings from "./pages/Settings";
import { RenameInput } from "./RenameTitle";
import { go, href, useRoute, type Route } from "./route";
import { toast, Toasts } from "./toast";

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

  // 切换页面时读一次；之后跟着全局事件流（/api/events）刷新，不再每 5 秒轮询
  useEffect(() => {
    void refresh();
  }, [refresh, route]);
  useGlobalChanges(refresh);

  const [collapsed, setCollapsed] = useState(() => readFlag(SIDEBAR_KEY));
  const toggle = () => {
    setCollapsed(!collapsed);
    writeFlag(SIDEBAR_KEY, !collapsed);
  };

  return (
    <div className={`app${collapsed ? " collapsed" : ""}`}>
      {collapsed
        ? <SidebarRail route={route} projects={projects} onExpand={toggle} onCreated={refresh} />
        : <Sidebar route={route} projects={projects} error={error} onCreated={refresh} onCollapse={toggle} />}
      <Toasts />
      <BrowserBanner />
      <main className={route.kind === "session" || route.kind === "job" ? "main full" : "main"}>
        {route.kind === "global" && <Global projects={projects} />}
        {route.kind === "project" && <ProjectPage key={route.id} id={route.id} onChanged={refresh} />}
        {route.kind === "session" && <SessionPage key={route.id} id={route.id} onChanged={refresh} />}
        {route.kind === "job" && <JobPage key={route.id} id={route.id} onChanged={refresh} />}
        {route.kind === "settings" && <Settings tab={route.tab} />}
      </main>
    </div>
  );
}

const SIDEBAR_KEY = "jobapply.sidebar.collapsed";

// 「新对话」建在哪个 Project：当前看的 Project / 当前 Session / 当前工作台所在的 Project，否则第一个
function projectFor(route: Route, projects: ProjectSummary[] | null): string | null {
  if (!projects || projects.length === 0) {
    return null;
  }
  if (route.kind === "project") {
    return route.id;
  }
  if (route.kind === "session") {
    const owner = projects.find((p) => p.sessions.some((s) => s.id === route.id));
    if (owner) {
      return owner.id;
    }
  }
  if (route.kind === "job") {
    // 工作台属于哪个 Project 由后端已有的数据反查（C021）：岗位被哪个 Session 发现、或挂在哪个已打开的工作台下
    const owner = projects.find((p) => p.sessions.some((s) => s.jobs.includes(route.id)
      || (s.workspaces ?? []).some((w) => w.id === route.id)));
    if (owner) {
      return owner.id;
    }
  }
  return projects[0].id;
}

function NewChat({ route, projects, onCreated, compact }: {
  route: Route;
  projects: ProjectSummary[] | null;
  onCreated: () => Promise<void>;
  compact?: boolean;
}) {
  const [busy, setBusy] = useState(false);
  const project = projectFor(route, projects);
  async function create() {
    if (!project) {
      return;
    }
    setBusy(true);
    try {
      const view = await api.createSession(project);
      await onCreated();
      go({ kind: "session", id: view.session.id });
    } catch (e) {
      toast(`新建对话失败：${errorText(e)}`, "error");
    } finally {
      setBusy(false);
    }
  }
  return (
    <button className={compact ? "iconbtn" : "newchat"} disabled={busy || !project} onClick={() => void create()}
      title={project ? `在 ${project} 里新建对话` : "先新建一个 Project"} aria-label="新对话">
      <Icon name="plus" size={15} />{!compact && " 新对话"}
    </button>
  );
}

function SidebarRail({ route, projects, onExpand, onCreated }: {
  route: Route;
  projects: ProjectSummary[] | null;
  onExpand: () => void;
  onCreated: () => Promise<void>;
}) {
  return (
    <aside className="side rail" aria-label="Project 与 Session（已收起）">
      <button className="iconbtn" title="展开侧栏" aria-label="展开侧栏" onClick={onExpand}><Icon name="side" /></button>
      <NewChat route={route} projects={projects} onCreated={onCreated} compact />
      <button className="iconbtn" title="全局" aria-label="全局" onClick={() => go({ kind: "global" })}><Icon name="home" /></button>
      <div className="bottom">
        <button className="iconbtn" title="资料与简历" aria-label="资料与简历"
          onClick={() => go({ kind: "settings", tab: "profile" })}>
          <Icon name="file" />
        </button>
        <button className="iconbtn" title="模型设置" aria-label="模型设置" onClick={() => go({ kind: "settings", tab: "models" })}>
          <Icon name="gear" />
        </button>
      </div>
    </aside>
  );
}

// 工作台状态圆点（C021）：在跑 / 排队 → running；等你、失败、中断 → waiting；停在提交前 → ready；已投递 → applied
function wsDotClass(w: WorkspaceSummary): string {
  if (w.application === "running" || w.application === "queued") {
    return "running";
  }
  if (w.application === "waiting_user" || w.application === "failed" || w.application === "interrupted") {
    return "waiting";
  }
  if (w.application === "ready") {
    return "ready";
  }
  if (w.bucket === "applied") {
    return "applied";
  }
  return "";
}

// Session 按今天 / 昨天 / 更早分组（按创建时间）
function groupByDay(sessions: SessionSummary[]): [string, SessionSummary[]][] {
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  const today = start.getTime();
  const yesterday = today - 24 * 3600 * 1000;
  const groups: Record<string, SessionSummary[]> = { 今天: [], 昨天: [], 更早: [] };
  for (const s of sessions) {
    const at = Date.parse(s.createdAt);
    groups[at >= today ? "今天" : at >= yesterday ? "昨天" : "更早"].push(s);
  }
  return Object.entries(groups).filter(([, list]) => list.length > 0);
}

function Sidebar({ route, projects, error, onCreated, onCollapse }: {
  route: Route;
  projects: ProjectSummary[] | null;
  error: string | null;
  onCreated: () => Promise<void>;
  onCollapse: () => void;
}) {
  const current = (r: Route) => href(r) === href(route);
  // 正在改名的那一项（双击进入）：project:<id> 或 session:<id>
  const [renaming, setRenaming] = useState<string | null>(null);
  // 正在确认删除的那一项（C013）
  const [deleting, setDeleting] = useState<DeleteTarget | null>(null);
  // 删掉的正是当前看的（或当前 Session 所在的 Project）时回到全局
  async function afterDelete(target: DeleteTarget) {
    const gone = target.kind === "session"
      ? route.kind === "session" && route.id === target.session.id
      : (route.kind === "project" && route.id === target.project.id)
        || (route.kind === "session" && target.project.sessions.some((s) => s.id === route.id));
    if (gone) {
      go({ kind: "global" });
    }
    await onCreated();
  }
  return (
    <aside className="side" aria-label="Project 与 Session">
      <div className="brand">
        <span><i aria-hidden="true"></i>JobApply</span>
        <button className="iconbtn" title="收起侧栏" aria-label="收起侧栏" onClick={onCollapse}><Icon name="side" /></button>
      </div>
      <NewChat route={route} projects={projects} onCreated={onCreated} />
      <button className="pbtn" aria-current={current({ kind: "global" })} onClick={() => go({ kind: "global" })}>
        <span>⌂ 全局</span>
      </button>
      <div>
        <h6>Projects</h6>
        {projects === null && !error && <p className="muted small">读取中…</p>}
        {error && <p className="error small">{error}</p>}
        {projects?.map((p) => (
          <div key={p.id}>
            {renaming === `project:${p.id}`
              ? <RenameInput className="side-rename" value={p.title} onDone={() => setRenaming(null)}
                  onSave={async (title) => { await api.renameProject(p.id, title); await onCreated(); }} />
              : (
                <div className="siderow">
                  <button className="pbtn" aria-current={current({ kind: "project", id: p.id })} title="双击改名"
                    onClick={() => go({ kind: "project", id: p.id })} onDoubleClick={() => setRenaming(`project:${p.id}`)}>
                    <span>{p.title}</span>
                    {(p.counts.dueChecks ?? 0) > 0 && (
                      <span className="duebadge" title={`${p.counts.dueChecks} 个申请该查看了`}>{p.counts.dueChecks}</span>
                    )}
                    <small>{p.id}</small>
                  </button>
                  <button className="iconbtn renamebtn" title="改名" aria-label={`给 ${p.title} 改名`}
                    onClick={() => setRenaming(`project:${p.id}`)}><Icon name="pencil" size={13} /></button>
                  <button className="iconbtn renamebtn delbtn" title="删除 Project" aria-label={`删除 ${p.title}`}
                    onClick={() => setDeleting({ kind: "project", project: p })}><Icon name="trash" size={13} /></button>
                </div>
              )}
            {groupByDay(p.sessions).map(([day, list]) => (
              <div key={day} className="sessgroup">
                <span className="sessday">{day}</span>
                <ul className="sess">
                  {list.map((s) => (
                    <li key={s.id}>
                      {renaming === `session:${s.id}` ? (
                        <RenameInput className="side-rename" value={s.title} onDone={() => setRenaming(null)}
                          onSave={async (title) => { await api.renameSession(s.id, title); await onCreated(); }} />
                      ) : (
                        <div className="siderow">
                          <button title={`${s.title}（双击改名）`} aria-current={current({ kind: "session", id: s.id })}
                            onClick={() => go({ kind: "session", id: s.id })} onDoubleClick={() => setRenaming(`session:${s.id}`)}>
                            <span className="sesst">{s.title}</span>
                            {s.status === "running" && <span className="rundot" title="运行中" aria-label="运行中" />}
                            {s.status === "interrupted" && <span className="rundot bad" title="中断了" aria-label="中断了" />}
                          </button>
                          <button className="iconbtn renamebtn" title="改名" aria-label={`给 ${s.title} 改名`}
                            onClick={() => setRenaming(`session:${s.id}`)}><Icon name="pencil" size={12} /></button>
                          <button className="iconbtn renamebtn delbtn" title="删除对话" aria-label={`删除 ${s.title}`}
                            onClick={() => setDeleting({ kind: "session", session: s })}><Icon name="trash" size={12} /></button>
                        </div>
                      )}
                      {/* 第三级（C021）：这个 Session 下已经打开的工作台，点一下直接切过去 */}
                      {(s.workspaces ?? []).length > 0 && (
                        <ul className="side-workspaces">
                          {(s.workspaces ?? []).map((w) => (
                            <li key={w.id}>
                              <button
                                className={"side-workspace" + (route.kind === "job" && route.id === w.id ? " active" : "")}
                                onClick={() => go({ kind: "job", id: w.id })}
                                title={`${w.company} · ${w.title}（${w.label}）`}>
                                <span className={"side-ws-dot " + wsDotClass(w)} aria-hidden="true" />
                                <span className="side-ws-text">{w.company} · {w.title}</span>
                              </button>
                            </li>
                          ))}
                        </ul>
                      )}
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        ))}
        <NewProject onCreated={onCreated} />
      </div>
      {deleting && (
        <DeleteConfirm target={deleting} onClose={() => setDeleting(null)} onDeleted={() => afterDelete(deleting)} />
      )}
      <div className="bottom">
        <p className="note">
          Project = 一份资料版本 + 偏好，不代表岗位类别。Session 是一次对话，可以只调研、调研加投递，或者回来更新面试情况；岗位（Job）跨多个 Session。
        </p>
        <button className="pbtn" aria-current={route.kind === "settings" && route.tab === "profile"}
          title="简历等原件、来源、事实与画像" onClick={() => go({ kind: "settings", tab: "profile" })}>
          <span>▤ 资料与简历</span>
        </button>
        <button className="pbtn" aria-current={route.kind === "settings" && route.tab === "models"}
          onClick={() => go({ kind: "settings", tab: "models" })}>
          <span>⚙ 模型设置</span>
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

function readFlag(key: string): boolean {
  try {
    return window.localStorage.getItem(key) === "1";
  } catch {
    return false;
  }
}

function writeFlag(key: string, value: boolean) {
  try {
    window.localStorage.setItem(key, value ? "1" : "0");
  } catch {
    // 本地存储不可用：只在这次打开里有效
  }
}

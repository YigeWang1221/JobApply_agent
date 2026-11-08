import { useCallback, useEffect, useRef, useState, type ReactElement } from "react";
import { api, errorText, type Asset, type AssetCategory, type ProjectSummary } from "../api";
import { lastDashboardProject, rememberDashboardProject } from "../route";
import ImportReview from "./ImportReview";
import Profile from "./Profile";
import Sources from "./Sources";

interface Notice {
  kind: "ok" | "warn" | "error";
  text: string;
}

type Tab = "originals" | "sources" | "review" | "profile";

const TABS: [Tab, string][] = [
  ["originals", "简历与原件"], ["sources", "来源"], ["review", "整理与确认"], ["profile", "事实与画像"],
];

// 「资料」页的页签：原件（C004-A）、来源（C004-A-1）、整理与确认和事实与画像（C004-A-2）
export default function Assets() {
  const [tab, setTab] = useState<Tab>("originals");
  const [run, setRun] = useState<string | null>(null);
  return (
    <>
      <nav className="subtabs" role="tablist">
        {TABS.map(([id, label]) => (
          <button key={id} role="tab" aria-selected={tab === id} className={tab === id ? "active" : ""}
            onClick={() => setTab(id)}>
            {label}
          </button>
        ))}
      </nav>
      {tab === "originals" && <Originals />}
      {tab === "sources" && <Sources onReview={(id) => { setRun(id); setTab("review"); }} />}
      {tab === "review" && <ImportReview initialRun={run} />}
      {tab === "profile" && <Profile />}
    </>
  );
}

// 「简历与原件」（C004-A-fix）：顶部选 Project（和 Dashboard 共用这次窗口里的选择）。简历归属一个 Project，
// 通用资料（成绩单、证书等）所有 Project 共用；每行可以改分类、归属，删除挪进 storage/.trash/
function Originals() {
  const [assets, setAssets] = useState<Asset[]>([]);
  const [projects, setProjects] = useState<ProjectSummary[]>([]);
  const [project, setProject] = useState<string>(lastDashboardProject() ?? "");
  const [category, setCategory] = useState<AssetCategory | "">("");
  const [notices, setNotices] = useState<Notice[]>([]);
  const [dragging, setDragging] = useState(false);
  const [uploading, setUploading] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  const reload = useCallback(async () => {
    try {
      const [list, projectList] = await Promise.all([api.assets(), api.projects()]);
      setAssets(list.assets);
      setProjects(projectList.projects);
    } catch (e) {
      setNotices([{ kind: "error", text: errorText(e) }]);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  // 记住的 Project 不在了（或还没选过）就用第一个
  const chosen = projects.some((p) => p.id === project) ? project : projects[0]?.id ?? "";

  function choose(id: string) {
    setProject(id);
    rememberDashboardProject(id);
  }

  async function upload(files: FileList | File[]) {
    setUploading(true);
    const next: Notice[] = [];
    for (const file of Array.from(files)) {
      try {
        const result = await api.upload(file, category, chosen);
        const where = result.asset.category === "简历" ? `${projectTitle(projects, result.asset.project)}的简历` : "通用资料";
        if (result.duplicate) {
          next.push({ kind: "warn", text: `${file.name}：已在${where}里（${result.asset.fileName}）` });
        } else {
          next.push({
            kind: "ok",
            text: `${file.name}：登记为${where}` + (result.analysisNote ? `；${result.analysisNote}` : ""),
          });
        }
      } catch (e) {
        next.push({ kind: "error", text: `${file.name}：${errorText(e)}` });
      }
    }
    setNotices(next);
    setUploading(false);
    await reload();
  }

  const mine = assets.filter((a) => a.category === "简历" && a.project === chosen);
  const unassigned = assets.filter((a) => a.category === "简历" && !projects.some((p) => p.id === a.project));
  const general = assets.filter((a) => a.category !== "简历");
  const rows = (list: Asset[]) => list.map((asset) =>
    <AssetRow key={asset.key} asset={asset} projects={projects} onSaved={reload} />);

  return (
    <>
      <section className="card">
        <div className="row spread">
          <h2>资料原件</h2>
          {projects.length > 0 && (
            <select aria-label="选择 Project" value={chosen} onChange={(e) => choose(e.target.value)}>
              {projects.map((p) => <option key={p.id} value={p.id}>{p.title}</option>)}
            </select>
          )}
        </div>
        <div
          className={`dropzone${dragging ? " dragging" : ""}`}
          onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
          onDragLeave={() => setDragging(false)}
          onDrop={(e) => { e.preventDefault(); setDragging(false); void upload(e.dataTransfer.files); }}
        >
          <p>把简历、成绩单、证书等文件拖到这里（每个不超过 20MB）</p>
          <div className="row">
            <select aria-label="上传为" value={category}
              onChange={(e) => setCategory(e.target.value as AssetCategory | "")}>
              <option value="">按文件名自动分类</option>
              <option value="简历">简历（归属 {projectTitle(projects, chosen)}）</option>
              <option value="通用资料">通用资料（所有 Project 共用）</option>
            </select>
            <button disabled={uploading} onClick={() => input.current?.click()}>
              {uploading ? "上传中…" : "选择文件"}
            </button>
          </div>
          <input ref={input} type="file" multiple hidden
            onChange={(e) => { if (e.target.files) { void upload(e.target.files); } e.target.value = ""; }} />
        </div>
        <p className="muted">
          原件保存在 storage/base/originals/，登记在 storage/base/assets.md。简历只给它归属的 Project 用（Agent 看不到未归属的简历），
          同一份要给别的 Project 用就在那里再传一次；通用资料所有 Project 都能用。对话里发来的文件只用于那次申请，不进这里。
          删除时挪进 storage/.trash/。分析时，文件开头的文字会发给辅助模型。
        </p>
        {notices.map((notice, i) => (
          <p key={i} className={notice.kind}>{notice.text}</p>
        ))}
      </section>
      <AssetTable title={`${projectTitle(projects, chosen)}的简历`} empty="这个 Project 还没有简历。" rows={rows(mine)} />
      {unassigned.length > 0 && (
        <AssetTable title={`未归属的简历（${unassigned.length}）`} rows={rows(unassigned)}
          note="Agent 看不到这些简历：在「Project」一栏选它属于哪个 Project，不要了就删除。" />
      )}
      <AssetTable title="通用资料" empty="还没有通用资料。" rows={rows(general)} />
    </>
  );
}

function projectTitle(projects: ProjectSummary[], id: string): string {
  return projects.find((p) => p.id === id)?.title ?? (id || "未归属");
}

function AssetTable({ title, rows, empty, note }: {
  title: string;
  rows: ReactElement[];
  empty?: string;
  note?: string;
}) {
  return (
    <section className="card">
      <h2>{title}</h2>
      {note && <p className="muted">{note}</p>}
      {rows.length === 0 ? (
        <p className="muted">{empty}</p>
      ) : (
        <table className="assets">
          <thead>
            <tr><th>文件名</th><th>分类</th><th>Project</th><th>说明</th><th></th></tr>
          </thead>
          <tbody>{rows}</tbody>
        </table>
      )}
    </section>
  );
}

function AssetRow({ asset, projects, onSaved }: { asset: Asset; projects: ProjectSummary[]; onSaved: () => void }) {
  const [description, setDescription] = useState(asset.description);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => setDescription(asset.description), [asset.description]);

  async function change(update: { description?: string; category?: AssetCategory; project?: string }) {
    setBusy(true);
    try {
      await api.updateAsset(asset.key, update);
      setError(null);
      onSaved();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    setBusy(true);
    try {
      await api.deleteAsset(asset.key);
      setError(null);
      onSaved();
    } catch (e) {
      setError(errorText(e));
      setBusy(false);
    }
  }

  const resume = asset.category === "简历";
  return (
    <tr>
      <td>{asset.fileName}<div className="muted small">{asset.size} · {asset.type}</div></td>
      <td>
        <select aria-label="分类" value={asset.category || "通用资料"} disabled={busy}
          onChange={(e) => void change({ category: e.target.value as AssetCategory })}>
          <option value="简历">简历</option>
          <option value="通用资料">通用资料</option>
        </select>
      </td>
      <td>
        {resume ? (
          <select aria-label="Project" value={asset.project} disabled={busy}
            onChange={(e) => void change({ project: e.target.value })}>
            <option value="">未归属</option>
            {projects.map((p) => <option key={p.id} value={p.id}>{p.title}</option>)}
            {asset.project && !projects.some((p) => p.id === asset.project) && (
              <option value={asset.project}>{asset.project}（已删除）</option>
            )}
          </select>
        ) : <span className="muted small">所有 Project</span>}
      </td>
      <td>
        <div className="row">
          <input value={description} maxLength={200} placeholder="手填一句说明"
            onChange={(e) => setDescription(e.target.value)} />
          {description !== asset.description && <button onClick={() => void change({ description })}>保存</button>}
        </div>
        {error && <p className="error">{error}</p>}
      </td>
      <td>
        {confirming ? (
          <div className="row">
            <button className="danger" disabled={busy} onClick={() => void remove()}>
              {busy ? "删除中…" : "确认删除"}
            </button>
            <button disabled={busy} onClick={() => setConfirming(false)}>取消</button>
          </div>
        ) : (
          <button title="挪进 storage/.trash/，用过它的岗位仍显示文件名" onClick={() => setConfirming(true)}>删除</button>
        )}
      </td>
    </tr>
  );
}

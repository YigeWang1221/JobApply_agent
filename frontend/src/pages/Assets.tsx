import { useCallback, useEffect, useRef, useState } from "react";
import { api, errorText, type Asset } from "../api";
import Sources from "./Sources";

interface Notice {
  kind: "ok" | "warn" | "error";
  text: string;
}

type Tab = "originals" | "sources";

// 「资料」页的页签：原件（C004-A）和来源（C004-A-1）。整理与确认、事实与画像在 C004-A-2 加
export default function Assets() {
  const [tab, setTab] = useState<Tab>("originals");
  return (
    <>
      <nav className="subtabs" role="tablist">
        <button role="tab" aria-selected={tab === "originals"} className={tab === "originals" ? "active" : ""}
          onClick={() => setTab("originals")}>
          原件
        </button>
        <button role="tab" aria-selected={tab === "sources"} className={tab === "sources" ? "active" : ""}
          onClick={() => setTab("sources")}>
          来源
        </button>
      </nav>
      {tab === "originals" ? <Originals /> : <Sources />}
    </>
  );
}

function Originals() {
  const [assets, setAssets] = useState<Asset[]>([]);
  const [notices, setNotices] = useState<Notice[]>([]);
  const [dragging, setDragging] = useState(false);
  const [uploading, setUploading] = useState(false);
  const input = useRef<HTMLInputElement>(null);

  const reload = useCallback(async () => {
    try {
      setAssets((await api.assets()).assets);
    } catch (e) {
      setNotices([{ kind: "error", text: errorText(e) }]);
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  async function upload(files: FileList | File[]) {
    setUploading(true);
    const next: Notice[] = [];
    for (const file of Array.from(files)) {
      try {
        const result = await api.upload(file);
        if (result.duplicate) {
          next.push({ kind: "warn", text: `${file.name}：已登记（${result.asset.key}，${result.asset.fileName}）` });
        } else {
          next.push({
            kind: "ok",
            text: `${file.name}：登记为 ${result.asset.key}` + (result.analysisNote ? `；${result.analysisNote}` : ""),
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

  return (
    <>
      <section className="card">
        <h2>资料原件</h2>
        <div
          className={`dropzone${dragging ? " dragging" : ""}`}
          onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
          onDragLeave={() => setDragging(false)}
          onDrop={(e) => { e.preventDefault(); setDragging(false); void upload(e.dataTransfer.files); }}
        >
          <p>把简历、成绩单、证书等文件拖到这里（每个不超过 20MB）</p>
          <button disabled={uploading} onClick={() => input.current?.click()}>
            {uploading ? "上传中…" : "选择文件"}
          </button>
          <input ref={input} type="file" multiple hidden
            onChange={(e) => { if (e.target.files) { void upload(e.target.files); } e.target.value = ""; }} />
        </div>
        <p className="muted">
          原件保存在 storage/base/originals/，登记在 storage/base/assets.md。分析时，文件开头的文字会发给辅助模型。
        </p>
        {notices.map((notice, i) => (
          <p key={i} className={notice.kind}>{notice.text}</p>
        ))}
      </section>
      <section className="card">
        <h2>已登记</h2>
        {assets.length === 0 ? (
          <p className="muted">还没有登记任何原件。</p>
        ) : (
          <table className="assets">
            <thead>
              <tr><th>键</th><th>文件名</th><th>大小</th><th>说明</th></tr>
            </thead>
            <tbody>
              {assets.map((asset) => <AssetRow key={asset.key} asset={asset} onSaved={reload} />)}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}

function AssetRow({ asset, onSaved }: { asset: Asset; onSaved: () => void }) {
  const [description, setDescription] = useState(asset.description);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => setDescription(asset.description), [asset.description]);

  async function save() {
    try {
      await api.describe(asset.key, description);
      setError(null);
      onSaved();
    } catch (e) {
      setError(errorText(e));
    }
  }

  return (
    <tr>
      <td className="mono">{asset.key}</td>
      <td>{asset.fileName}<div className="muted small">{asset.type}</div></td>
      <td>{asset.size}</td>
      <td>
        <div className="row">
          <input value={description} maxLength={200} placeholder="手填一句说明"
            onChange={(e) => setDescription(e.target.value)} />
          {description !== asset.description && <button onClick={save}>保存</button>}
        </div>
        {error && <p className="error">{error}</p>}
      </td>
    </tr>
  );
}

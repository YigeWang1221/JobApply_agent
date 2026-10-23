import { useCallback, useEffect, useState } from "react";
import { api, errorText, type ImportRun, type RepoSummary, type Source } from "../api";

const NOTICE =
  "秘密文件（.env、私钥、证书、credentials / secret 一类）按名字跳过，不会被读取；本地目录和 GitHub 仓库只读、不复制。" +
  "这一步只扫描和估算，不调用模型；整理时（下一步），扫描出的文字会发给你配置的模型。";

const KIND: Record<string, string> = {
  doc: "文档",
  manifest: "依赖清单",
  structured: "结构化",
  source: "源代码（前 40 行）",
  tree: "文件列表",
};

export default function Sources() {
  const [sources, setSources] = useState<Source[]>([]);
  const [type, setType] = useState<"dir" | "github">("dir");
  const [location, setLocation] = useState("");
  const [repos, setRepos] = useState<{ owner: string; repositories: RepoSummary[] } | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [run, setRun] = useState<ImportRun | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: "ok" | "warn" | "error"; text: string } | null>(null);

  const reload = useCallback(async () => {
    try {
      setSources((await api.sources()).sources);
    } catch (e) {
      setMessage({ kind: "error", text: errorText(e) });
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  async function register(kind: "dir" | "github", value: string) {
    setBusy(true);
    setMessage(null);
    try {
      const result = await api.registerSource(kind, value);
      if ("repositories" in result) {
        setRepos(result);
        setMessage({ kind: "ok", text: `${result.owner} 有 ${result.repositories.length} 个公开仓库，选一个登记` });
      } else {
        setRepos(null);
        setLocation("");
        setMessage(result.duplicate
          ? { kind: "warn", text: `已登记过（${result.source.key}）` }
          : { kind: "ok", text: `登记为 ${result.source.key}` });
        await reload();
      }
    } catch (e) {
      setMessage({ kind: "error", text: errorText(e) });
    } finally {
      setBusy(false);
    }
  }

  async function remove(key: string) {
    if (!window.confirm(`删除登记 ${key}？不会删除目录或仓库里的任何东西。`)) {
      return;
    }
    try {
      await api.deleteSource(key);
      setSelected((prev) => { const next = new Set(prev); next.delete(key); return next; });
      await reload();
    } catch (e) {
      setMessage({ kind: "error", text: errorText(e) });
    }
  }

  async function estimate() {
    setBusy(true);
    setMessage(null);
    try {
      setRun(await api.estimate(sources.filter((s) => selected.has(s.key)).map((s) => s.key)));
      await reload();
    } catch (e) {
      setMessage({ kind: "error", text: errorText(e) });
    } finally {
      setBusy(false);
    }
  }

  function toggle(key: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(key)) { next.delete(key); } else { next.add(key); }
      return next;
    });
  }

  return (
    <>
      <section className="card">
        <h2>登记来源</h2>
        <div className="row">
          <select value={type} onChange={(e) => setType(e.target.value as "dir" | "github")}>
            <option value="dir">本地目录</option>
            <option value="github">GitHub 公开仓库</option>
          </select>
          <input value={location} onChange={(e) => setLocation(e.target.value)}
            placeholder={type === "dir" ? "目录的绝对路径，例如 /Users/你/projects/demo"
              : "https://github.com/owner/repo、owner/repo，或者只写 owner"} />
          <button disabled={busy || !location.trim()} onClick={() => void register(type, location)}>登记</button>
        </div>
        {repos && (
          <ul className="models">
            {repos.repositories.map((repo) => (
              <li key={repo.fullName}>
                <span className="mono">{repo.fullName}</span>
                {repo.fork && <span className="muted small">fork</span>}
                {repo.archived && <span className="muted small">已归档</span>}
                <span className="muted small">{repo.description}</span>
                <button disabled={busy} onClick={() => void register("github", repo.fullName)}>登记</button>
              </li>
            ))}
          </ul>
        )}
        {message && <p className={message.kind}>{message.text}</p>}
        <p className="privacy">{NOTICE}</p>
      </section>
      <section className="card">
        <h2>已登记</h2>
        {sources.length === 0 ? (
          <p className="muted">还没有登记任何来源。</p>
        ) : (
          <>
            <table className="assets">
              <thead>
                <tr><th></th><th>键</th><th>位置</th><th>最近扫描</th><th>文件数</th><th></th></tr>
              </thead>
              <tbody>
                {sources.map((source) => (
                  <tr key={source.key}>
                    <td><input type="checkbox" checked={selected.has(source.key)} onChange={() => toggle(source.key)} /></td>
                    <td className="mono">{source.key}<div className="muted small">{source.type === "dir" ? "本地目录" : "GitHub"}</div></td>
                    <td className="mono">{source.location}
                      {source.version && <div className="muted small">{source.version.slice(0, 22)}</div>}</td>
                    <td className="small">{source.lastScannedAt || "—"}</td>
                    <td>{source.fileCount || "—"}</td>
                    <td><button className="danger" onClick={() => void remove(source.key)}>删除登记</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="row">
              <button disabled={busy || selected.size === 0} onClick={() => void estimate()}>
                {busy ? "扫描中…" : `估算（${selected.size} 个来源）`}
              </button>
            </div>
          </>
        )}
      </section>
      {run && run.estimate && <Estimate run={run} />}
    </>
  );
}

function Estimate({ run }: { run: ImportRun }) {
  const estimate = run.estimate!;
  return (
    <section className="card">
      <h2>估算 · {run.id}</h2>
      <div className="stats">
        <div><span className="muted small">文字量</span><strong>{estimate.chars.toLocaleString()} 字</strong></div>
        <div><span className="muted small">预计模型调用</span><strong>{estimate.modelCalls} 次</strong></div>
        <div><span className="muted small">GitHub 调用（已用 / 整理时）</span>
          <strong>{estimate.githubCallsUsed} / {estimate.githubCallsPlanned}</strong></div>
      </div>
      {estimate.warnings.map((warning, i) => <p key={i} className="warn">{warning}</p>)}
      {estimate.sources.map((source) => (
        <div key={source.key} className="provider">
          <div className="provider-head">
            <div>
              <strong className="mono">{source.key}</strong> <span className="mono small">{source.location}</span>
              <div className="muted small">
                读 {source.scan.files.length} 项，{source.scan.chars.toLocaleString()} 字；模型 {source.modelCalls} 次
                {source.type === "github" && `；GitHub 已用 ${source.scan.githubCalls} 次，整理时还要 ${source.githubCallsPlanned} 次`}
              </div>
            </div>
          </div>
          {source.scan.commits && (
            <p className="small">
              提交 {source.scan.commits.count} 个
              {source.scan.commits.firstAt && `（${source.scan.commits.firstAt.slice(0, 10)} – ${source.scan.commits.lastAt?.slice(0, 10)}）`}
              {source.scan.commits.ownPercent !== null && `，本人约 ${source.scan.commits.ownPercent}%`}
            </p>
          )}
          <details>
            <summary>会读的文件（{source.scan.files.length}）</summary>
            <ul>
              {source.scan.files.map((file) => (
                <li key={file.path}><span className="mono">{file.path}</span> · {KIND[file.kind] ?? file.kind} · {file.chars.toLocaleString()} 字</li>
              ))}
            </ul>
          </details>
          <details>
            <summary>跳过的文件（{source.scan.skipped.length}）</summary>
            <ul>
              {source.scan.skipped.map((file) => (
                <li key={file.path}><span className="mono">{file.path}</span> · <span className="muted">{file.reason}</span></li>
              ))}
            </ul>
          </details>
        </div>
      ))}
      <p className="muted small">「开始整理」在下一步（C004-A-2）加入。</p>
    </section>
  );
}

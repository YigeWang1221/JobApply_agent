import { useEffect, useState, type ReactNode } from "react";
import { api, errorText, type AppStorage, type StorageCheck } from "../api";
import { shell } from "../shell";
import { toast } from "../toast";

const SOURCE: Record<AppStorage["source"], string> = {
  override: "命令行 / 环境变量指定",
  "app-config": "app.yaml",
  default: "默认",
};

function checkText(check: StorageCheck): string {
  if (!check.ok) return check.problem ?? "不能用这个位置";
  if (check.kind === "jobapply") return `发现已有数据：${check.projects} 个 Project`;
  if (check.kind === "other") return "文件夹里有别的文件（JobApply 会在里面建自己的目录）";
  return "空文件夹，将新建";
}

// 选一个位置并保存：App 里走外壳的选文件夹框，开发版是路径输入框。保存后 App 重启后端，开发版提示手动重启
function ChooseFolder({ initial, onDone }: { initial: string; onDone?: () => void }) {
  const [path, setPath] = useState(initial);
  const [check, setCheck] = useState<StorageCheck | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [saved, setSaved] = useState(false);

  async function inspect(candidate: string) {
    setPath(candidate);
    setError(null);
    try {
      setCheck(await api.checkAppStorage(candidate));
    } catch (e) {
      setCheck(null);
      setError(errorText(e));
    }
  }

  useEffect(() => {
    if (initial) void inspect(initial);
  }, [initial]);

  async function pick() {
    const chosen = await shell.pickFolder();
    if (chosen) await inspect(chosen);
  }

  async function save() {
    setBusy(true);
    try {
      await api.saveAppStorage(path);
      setSaved(true);
      onDone?.();
      if (shell.available()) {
        toast("已切换，正在重启");
        shell.restartBackend();
      }
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  if (saved && !shell.available()) {
    return <p className="warn">已保存到 app.yaml。请重启后端，重启后用新位置：{path}</p>;
  }
  return (
    <div>
      {shell.available() ? (
        <button onClick={pick} disabled={busy}>选择文件夹…</button>
      ) : (
        <div className="row">
          <input value={path} placeholder="/绝对路径/storage" onChange={(e) => setPath(e.target.value)} />
          <button onClick={() => inspect(path)} disabled={busy || !path}>检查</button>
        </div>
      )}
      {check && (
        <p className={check.ok ? (check.kind === "other" ? "warn" : "muted") : "error"}>
          {check.path}：{checkText(check)}
        </p>
      )}
      {error && <p className="error">{error}</p>}
      <button onClick={save} disabled={busy || !check?.ok || check.path !== path.trim()}>确定</button>
    </div>
  );
}

// 第一次打开（macapp，没有 app.yaml 也没有命令行指定）：先选数据位置再进主界面
function FirstRun({ storage }: { storage: AppStorage }) {
  const [mode, setMode] = useState<"default" | "custom">("default");
  return (
    <div className="content" style={{ maxWidth: 640, margin: "10vh auto" }}>
      <section className="card">
        <h2>选数据位置</h2>
        <p className="muted">JobApply 的 Project、申请记录、资料和设置都放在这个文件夹里。以后可以在设置页更改。</p>
        <label>
          <input type="radio" checked={mode === "default"} onChange={() => setMode("default")} />
          默认：{storage.defaultRoot}
        </label>
        <label>
          <input type="radio" checked={mode === "custom"} onChange={() => setMode("custom")} />
          选择文件夹…（可以选仓库里已有的 storage/）
        </label>
        <ChooseFolder key={mode} initial={mode === "default" ? storage.defaultRoot : ""} />
      </section>
    </div>
  );
}

// 启动时问一次后端：要选位置时先显示「选数据位置」，否则直接进主界面
export function StorageGate({ children }: { children: ReactNode }) {
  const [storage, setStorage] = useState<AppStorage | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    api.appStorage().then(setStorage, () => setFailed(true));
  }, []);
  if (storage?.needsChoice) return <FirstRun storage={storage} />;
  // 接口出错时不挡住主界面，错误留给各页自己显示
  if (!storage && !failed) return null;
  return <>{children}</>;
}

// 设置页「数据位置」：当前路径和来源、在 Finder 中显示、更改…
export default function StorageLocationSettings() {
  const [storage, setStorage] = useState<AppStorage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [changing, setChanging] = useState(false);
  useEffect(() => {
    api.appStorage().then(setStorage, (e) => setError(errorText(e)));
  }, []);
  return (
    <section className="card">
      <h2>数据位置</h2>
      {!storage ? (
        error ? <p className="error">{error}</p> : <p>加载中…</p>
      ) : (
        <>
          <p>
            <code>{storage.root}</code>
            <span className="muted">（{SOURCE[storage.source]}，{storage.projects} 个 Project）</span>
          </p>
          <div className="row">
            {shell.available() && <button onClick={() => shell.reveal(storage.root)}>在 Finder 中显示</button>}
            <button onClick={() => setChanging(!changing)}>{changing ? "取消" : "更改…"}</button>
          </div>
          {changing && <ChooseFolder initial="" />}
          <p className="muted small">换位置不复制、不移动数据：旧文件夹原样保留，要带走数据请自己挪。保存后重启后端生效。</p>
        </>
      )}
    </section>
  );
}

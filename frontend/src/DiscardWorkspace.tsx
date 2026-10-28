import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { api, errorText, type WorkspaceSummary } from "./api";
import { toast } from "./toast";

/** 关闭工作台请求超时保护（20 秒） */
const TIMEOUT_MS = 20_000;

function withTimeout<T>(promise: Promise<T>): Promise<T> {
  return Promise.race([
    promise,
    new Promise<T>((_, reject) =>
      setTimeout(() => reject(new Error("20 秒没有回应，可能未完成：刷新页面看看，再试一次")), TIMEOUT_MS)),
  ]);
}

export function DiscardWorkspace({
  workspace,
  onClose,
  onDiscarded,
}: {
  workspace: WorkspaceSummary;
  onClose: () => void;
  onDiscarded: () => Promise<void> | void;
}) {
  const [busy, setBusy] = useState(false);
  const [busyAction, setBusyAction] = useState<"workspace" | "job" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const closed = useRef(false);

  const close = useCallback(() => {
    closed.current = true;
    onClose();
  }, [onClose]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        close();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [close]);

  async function handleDiscard(deleteJob: boolean) {
    setBusy(true);
    setBusyAction(deleteJob ? "job" : "workspace");
    setError(null);
    try {
      await withTimeout(api.discardWorkspace(workspace.id, deleteJob));
      toast(deleteJob
        ? `已将岗位「${workspace.company} · ${workspace.title}」挪进回收站`
        : `已关闭工作台「${workspace.company} · ${workspace.title}」`);
      if (!closed.current) {
        onClose();
      }
      await onDiscarded();
    } catch (e) {
      if (closed.current) {
        toast(`操作未完成：${errorText(e)}`, "error");
        return;
      }
      setError(errorText(e));
      setBusy(false);
      setBusyAction(null);
    }
  }

  const isRunning = workspace.application === "running" || workspace.application === "queued";

  return createPortal(
    <div className="dialogback" onClick={close}>
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="discard-title"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 id="discard-title">关闭工作台「{workspace.company} · {workspace.title}」？</h3>
        {isRunning && (
          <p className="dialog-warn">
            申请正在运行中：删除前会先强制停下。
          </p>
        )}
        <ul>
          <li>
            <strong>只关工作台：</strong>清掉填写进度和运行记录、岗位回候选池可重新投递、台账保留
          </li>
          <li>
            <strong>连岗位一起删：</strong>整个岗位进回收站（挪进 storage/.trash/）
          </li>
        </ul>
        {error && <p className="error small">{error}</p>}
        <div className="dialog-actions">
          <button onClick={close} autoFocus disabled={busy}>
            取消
          </button>
          <button
            className="primary"
            disabled={busy}
            onClick={() => void handleDiscard(false)}
          >
            {busyAction === "workspace" ? "处理中…" : "只关工作台"}
          </button>
          <button
            className="danger-outline"
            disabled={busy}
            onClick={() => void handleDiscard(true)}
          >
            {busyAction === "job" ? "处理中…" : "连岗位一起删"}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}

export default DiscardWorkspace;

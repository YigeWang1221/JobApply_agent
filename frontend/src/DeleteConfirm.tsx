import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { api, errorText, type ProjectSummary, type SessionSummary } from "./api";
import { toast } from "./toast";

// 删除 Session / Project（C013）：一个确认框，写清楚挪走什么、留下什么。不真删，挪进 storage/.trash/，挪回去就恢复。
// 参考 OpenMAIC 侧栏的行内「删除先问一句」：只问这一次，不再二次确认。
export type DeleteTarget =
  | { kind: "session"; session: SessionSummary }
  | { kind: "project"; project: ProjectSummary };

/** 删除请求最多等这么久，超时报错（框不会一直卡在「删除中」）。 */
const TIMEOUT_MS = 20_000;

function withTimeout<T>(promise: Promise<T>): Promise<T> {
  return Promise.race([
    promise,
    new Promise<T>((_, reject) =>
      setTimeout(() => reject(new Error("20 秒没有回应，可能没删成：刷新页面看看，再试一次")), TIMEOUT_MS)),
  ]);
}

export function DeleteConfirm({ target, onClose, onDeleted }: {
  target: DeleteTarget;
  onClose: () => void;
  onDeleted: () => Promise<void> | void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // 用户已经关掉框：请求晚回来时只弹提示，不再改框里的状态
  const closed = useRef(false);

  // 取消、Esc、点框外任何时候都能关（请求没回来也一样，不用刷新页面）
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

  async function remove() {
    setBusy(true);
    setError(null);
    try {
      if (target.kind === "session") {
        const result = await withTimeout(api.deleteSession(target.session.id));
        toast(result.status === "deleting"
          ? `「${target.session.title}」：Agent 停下后挪进回收站`
          : `已删除「${target.session.title}」`);
      } else {
        await withTimeout(api.deleteProject(target.project.id));
        toast(`已删除 Project「${target.project.title}」`);
      }
      if (!closed.current) {
        onClose();
      }
      await onDeleted();
    } catch (e) {
      if (closed.current) {
        toast(`删除没有完成：${errorText(e)}`, "error");
        return;
      }
      // 409（还在运行等）原样显示，框不关
      setError(errorText(e));
      setBusy(false);
    }
  }

  const name = target.kind === "session" ? target.session.title : target.project.title;
  // 挂到 body 上：按整个页面渲染，不继承侧栏的字号和按钮样式
  return createPortal(
    <div className="dialogback" onClick={close}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="delete-title"
        onClick={(e) => e.stopPropagation()}>
        <h3 id="delete-title">删除{target.kind === "session" ? "对话" : " Project"}「{name}」？</h3>
        {target.kind === "session" ? (
          <ul>
            <li>挪进回收站：这次对话的记录、附件、读过的网页。</li>
            <li>留下：它涉及的岗位（{target.session.jobs.length} 个）和这些岗位的申请，都还在 Project 里，记录里显示「会话已删除」。</li>
            {target.session.status === "running" && <li>Agent 正在运行：先让它停下，停下后再挪走。</li>}
          </ul>
        ) : (
          <ul>
            <li>挪进回收站：这个 Project 的全部对话（{target.project.sessions.length} 个）、全部岗位和申请，以及这些申请的运行记录和上传暂存。</li>
            <li>不动：资料与简历（base）、设置、站点记录（playbook）、其他 Project。</li>
            <li>有对话或申请在运行时不能删，先停止。</li>
          </ul>
        )}
        <p className="dialog-note">不是真删：放在 storage/.trash/，把文件夹挪回原处就恢复。</p>
        {error && <p className="error small">{error}</p>}
        <div className="dialog-actions">
          <button onClick={close} autoFocus>取消</button>
          <button className="danger-solid" disabled={busy} onClick={() => void remove()}>
            {busy ? "删除中…" : "删除"}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}

import { useEffect, useState } from "react";
import { api, errorText, type ProjectSummary, type SessionSummary } from "./api";
import { toast } from "./toast";

// 删除 Session / Project（C013）：一个确认框，写清楚挪走什么、留下什么。不真删，挪进 storage/.trash/，挪回去就恢复。
// 参考 OpenMAIC 侧栏的行内「删除先问一句」：只问这一次，不再二次确认。
export type DeleteTarget =
  | { kind: "session"; session: SessionSummary }
  | { kind: "project"; project: ProjectSummary };

export function DeleteConfirm({ target, onClose, onDeleted }: {
  target: DeleteTarget;
  onClose: () => void;
  onDeleted: () => Promise<void> | void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !busy) {
        onClose();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy, onClose]);

  async function remove() {
    setBusy(true);
    setError(null);
    try {
      if (target.kind === "session") {
        const result = await api.deleteSession(target.session.id);
        toast(result.status === "deleting"
          ? `「${target.session.title}」：Agent 停下后挪进回收站`
          : `已删除「${target.session.title}」`);
      } else {
        await api.deleteProject(target.project.id);
        toast(`已删除 Project「${target.project.title}」`);
      }
      onClose();
      await onDeleted();
    } catch (e) {
      // 409（还在运行等）原样显示，框不关
      setError(errorText(e));
      setBusy(false);
    }
  }

  const name = target.kind === "session" ? target.session.title : target.project.title;
  return (
    <div className="dialogback" onClick={() => !busy && onClose()}>
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
        <p className="muted small">不是真删：放在 storage/.trash/，把文件夹挪回原处就恢复。</p>
        {error && <p className="error small">{error}</p>}
        <div className="row">
          <button className="danger" disabled={busy} onClick={() => void remove()} autoFocus>删除</button>
          <button disabled={busy} onClick={onClose}>取消</button>
        </div>
      </div>
    </div>
  );
}

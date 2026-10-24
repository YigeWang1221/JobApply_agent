import { useState } from "react";
import { errorText } from "./api";
import { Icon } from "./chat/icons";
import { toast } from "./toast";

// 改名用的输入框：Enter 或失焦保存，Esc 取消；没改或为空时直接退出。保存失败时留在输入框里并提示原因
export function RenameInput({ value, onSave, onDone, className }: {
  value: string;
  onSave: (title: string) => Promise<unknown>;
  onDone: () => void;
  className?: string;
}) {
  const [draft, setDraft] = useState(value);
  const [busy, setBusy] = useState(false);

  async function save() {
    const title = draft.trim();
    if (busy) {
      return;
    }
    if (!title || title === value) {
      onDone();
      return;
    }
    setBusy(true);
    try {
      await onSave(title);
      toast("已改名");
      onDone();
    } catch (e) {
      toast(`改名失败：${errorText(e)}`, "error");
    } finally {
      setBusy(false);
    }
  }

  return (
    <input className={`rename ${className ?? ""}`} autoFocus value={draft} disabled={busy} maxLength={80}
      aria-label="新名字"
      onFocus={(e) => e.target.select()}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={() => void save()}
      onKeyDown={(e) => {
        if (e.key === "Enter" && !e.nativeEvent.isComposing) {
          e.preventDefault();
          void save();
        } else if (e.key === "Escape") {
          onDone();
        }
      }} />
  );
}

// 页面标题：旁边一个铅笔按钮，点了变成输入框
export function RenameTitle({ value, onSave }: { value: string; onSave: (title: string) => Promise<unknown> }) {
  const [editing, setEditing] = useState(false);
  if (editing) {
    return <h1><RenameInput value={value} onSave={onSave} onDone={() => setEditing(false)} /></h1>;
  }
  return (
    <h1 className="renamable">
      <span>{value}</span>
      <button className="iconbtn labeled renamebtn-page" title="改名" aria-label="改名" onClick={() => setEditing(true)}>
        <Icon name="pencil" size={13} /> 改名
      </button>
    </h1>
  );
}

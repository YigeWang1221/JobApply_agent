import { useState } from "react";
import { toast } from "../toast";
import { Icon } from "./icons";

// 复制到剪贴板；复制失败时给轻提示，不假装成功
export function CopyButton({ text, label = "复制" }: { text: string | (() => string); label?: string }) {
  const [done, setDone] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard.writeText(typeof text === "function" ? text() : text);
      setDone(true);
      window.setTimeout(() => setDone(false), 1500);
    } catch (e) {
      toast(`复制失败：${e instanceof Error ? e.message : String(e)}`, "error");
    }
  }
  return (
    <button className="iconbtn" title={label} aria-label={label} onClick={() => void copy()}>
      <Icon name={done ? "check" : "copy"} size={14} />
    </button>
  );
}

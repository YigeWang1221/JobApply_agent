import { useEffect, useState } from "react";

// 操作反馈的轻提示：任何地方调 toast(...)，App 里放一个 <Toasts />
type Kind = "ok" | "error";
interface Item {
  id: number;
  text: string;
  kind: Kind;
}

let next = 1;
const listeners = new Set<(item: Item) => void>();

export function toast(text: string, kind: Kind = "ok") {
  const item = { id: next++, text, kind };
  listeners.forEach((listener) => listener(item));
}

export function Toasts() {
  const [items, setItems] = useState<Item[]>([]);
  useEffect(() => {
    const add = (item: Item) => {
      setItems((old) => [...old, item]);
      // 错误多留一会儿
      window.setTimeout(() => setItems((old) => old.filter((i) => i.id !== item.id)), item.kind === "error" ? 6000 : 2500);
    };
    listeners.add(add);
    return () => {
      listeners.delete(add);
    };
  }, []);
  return (
    <div className="toasts" role="status" aria-live="polite">
      {items.map((item) => <div key={item.id} className={`toast ${item.kind}`}>{item.text}</div>)}
    </div>
  );
}

import { useEffect, useRef, useState, type CSSProperties, type KeyboardEvent, type PointerEvent, type ReactNode } from "react";

// 左右栏拖拽伸缩（C023-B）：一条竖向手柄 + 记在 localStorage 的宽度。不加拖拽库。
// 只有下限；上限由调用方按当前窗口推出来（保证主对话区还能用），拖动开始时取一次。

// 主对话区至少留这么宽（C023 已确认）
export const KEEP_MAIN = 400;
// 键盘每按一次调多少
const STEP = 16;

// 一栏的宽度：读 localStorage（读不到用默认值），commit 时才写
export function useStoredWidth(key: string, initial: number): [number, (width: number) => void] {
  const [width, setWidth] = useState(() => readWidth(key, initial));
  const commit = (next: number) => {
    setWidth(next);
    writeWidth(key, next);
  };
  return [width, commit];
}

// 当前窗口宽度：窗口变化时重新夹住栏宽
export function useWindowWidth(): number {
  const [width, setWidth] = useState(() => window.innerWidth);
  useEffect(() => {
    const onResize = () => setWidth(window.innerWidth);
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);
  return width;
}

export function clampWidth(width: number, min: number, max: number): number {
  return Math.round(Math.min(Math.max(width, min), Math.max(min, max)));
}

// 竖向手柄。edge = "left"：手柄在栏的右边缘（左侧栏，往右拖变宽）；"right"：手柄在栏的左边缘（右栏，往左拖变宽）。
// 拖动中直接改 target 上的 CSS 变量，不每帧重渲染页面；松手时 onCommit 记下宽度。
export function ResizeHandle({ edge, label, value, min, max, initial, target, measure, cssVar, onCommit, className }: {
  edge: "left" | "right";
  label: string;
  value: number;
  min: number;
  max: () => number;
  initial: number;
  target: () => HTMLElement | null;
  // 量哪个元素得到这栏现在的宽度；不给就量 target
  measure?: () => HTMLElement | null;
  cssVar: string;
  onCommit: (width: number) => void;
  className?: string;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const drag = useRef<{ x: number; width: number; max: number; last: number } | null>(null);
  const sign = edge === "left" ? 1 : -1;

  function apply(width: number) {
    target()?.style.setProperty(cssVar, `${width}px`);
    ref.current?.setAttribute("aria-valuenow", String(width));
  }

  // 实际显示的宽度：窗口变窄时 CSS 可能已经把栏压窄了，从看到的宽度开始调
  function current(): number {
    const width = (measure ?? target)()?.getBoundingClientRect().width;
    return width ? Math.round(width) : value;
  }

  function down(e: PointerEvent<HTMLDivElement>) {
    if (e.button !== 0) {
      return;
    }
    e.preventDefault();
    e.currentTarget.setPointerCapture(e.pointerId);
    const width = current();
    drag.current = { x: e.clientX, width, max: max(), last: width };
    document.body.classList.add("resizing");
  }

  function move(e: PointerEvent<HTMLDivElement>) {
    const d = drag.current;
    if (!d) {
      return;
    }
    d.last = clampWidth(d.width + sign * (e.clientX - d.x), min, d.max);
    apply(d.last);
  }

  function up(e: PointerEvent<HTMLDivElement>) {
    const d = drag.current;
    if (!d) {
      return;
    }
    drag.current = null;
    document.body.classList.remove("resizing");
    if (e.currentTarget.hasPointerCapture(e.pointerId)) {
      e.currentTarget.releasePointerCapture(e.pointerId);
    }
    if (d.last !== d.width) {
      onCommit(d.last);
    } else {
      // 没拖动（或被上下限挡住）：把拖动中改过的 CSS 变量还原成 React 记着的值，免得两边对不上
      apply(value);
    }
  }

  // 卸载时别把「拖动中」留在 body 上
  useEffect(() => () => document.body.classList.remove("resizing"), []);

  function key(e: KeyboardEvent<HTMLDivElement>) {
    const limit = max();
    let next: number | null = null;
    if (e.key === "ArrowLeft") {
      next = current() - sign * STEP;
    } else if (e.key === "ArrowRight") {
      next = current() + sign * STEP;
    } else if (e.key === "Home") {
      next = min;
    } else if (e.key === "End") {
      next = limit;
    }
    if (next === null) {
      return;
    }
    e.preventDefault();
    onCommit(clampWidth(next, min, limit));
  }

  return (
    <div ref={ref} className={`resizer ${edge}${className ? ` ${className}` : ""}`} role="separator"
      aria-orientation="vertical" aria-label={label} aria-valuenow={value} aria-valuemin={min}
      aria-valuemax={Math.max(min, Math.round(max()))} tabIndex={0}
      title={`${label}：拖动调宽，双击复位`}
      onPointerDown={down} onPointerMove={move} onPointerUp={up} onPointerCancel={up}
      onDoubleClick={() => onCommit(clampWidth(initial, min, max()))} onKeyDown={key} />
  );
}

// 右栏（Session 页岗位栏、工作台右栏共用）：左边缘一条手柄，宽度写进 --panel-w。
// 窗口不够宽时由 CSS 先压右栏（flex-shrink，最小 PANEL_MIN），主对话区留 KEEP_MAIN。
const PANEL_DEFAULT = 340;
const PANEL_MIN = 240;

export function ResizablePanel({ storageKey, label, children }: {
  storageKey: string;
  label: string;
  children: ReactNode;
}) {
  const [width, commit] = useStoredWidth(storageKey, PANEL_DEFAULT);
  const aside = useRef<HTMLElement>(null);
  // 上限：整个对话页的宽度减掉主对话区要留的
  const max = () => (aside.current?.parentElement?.clientWidth ?? window.innerWidth) - KEEP_MAIN;
  return (
    <>
      <ResizeHandle edge="right" className="rightpanel" label="调整右栏宽度" value={width} min={PANEL_MIN} max={max}
        initial={PANEL_DEFAULT} target={() => aside.current} cssVar="--panel-w" onCommit={commit} />
      <aside ref={aside} className="jobsside" aria-label={label} style={{ "--panel-w": `${width}px` } as CSSProperties}>
        {children}
      </aside>
    </>
  );
}

function readWidth(key: string, fallback: number): number {
  try {
    const raw = Number(window.localStorage.getItem(key));
    return Number.isFinite(raw) && raw > 0 ? raw : fallback;
  } catch {
    return fallback;
  }
}

function writeWidth(key: string, width: number) {
  try {
    window.localStorage.setItem(key, String(width));
  } catch {
    // 本地存储不可用：只在这次打开里有效
  }
}

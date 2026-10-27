// macOS App 外壳的小桥（C017）：window.webkit.messageHandlers.jobapply。开发版在 Chrome 里没有它，各处退回到手动输入 / 提示
type Bridge = { postMessage: (message: unknown) => void };

declare global {
  interface Window {
    webkit?: { messageHandlers?: { jobapply?: Bridge } };
    __jobapplyShell?: { resolve: (id: string, path: string | null) => void };
  }
}

const pending = new Map<string, (path: string | null) => void>();
let nextId = 0;

// 外壳选完文件夹后调 window.__jobapplyShell.resolve(id, path | null)
window.__jobapplyShell = {
  resolve(id, path) {
    pending.get(id)?.(path);
    pending.delete(id);
  },
};

function bridge(): Bridge | undefined {
  return window.webkit?.messageHandlers?.jobapply;
}

export const shell = {
  available: () => bridge() !== undefined,
  // 弹系统选文件夹框；取消时为 null
  pickFolder(): Promise<string | null> {
    const b = bridge();
    if (!b) return Promise.resolve(null);
    const id = `pick-${++nextId}`;
    return new Promise((resolve) => {
      pending.set(id, resolve);
      b.postMessage({ type: "pickFolder", id });
    });
  },
  // 外壳重启 Java 并重新加载窗口
  restartBackend: () => bridge()?.postMessage({ type: "restartBackend" }),
  reveal: (path: string) => bridge()?.postMessage({ type: "reveal", path }),
};

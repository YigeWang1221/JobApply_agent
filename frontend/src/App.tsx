import { useState } from "react";
import Application from "./pages/Application";
import Assets from "./pages/Assets";
import ModelSettings from "./pages/ModelSettings";

type Tab = "models" | "assets" | "application";

// 页数少，用组件状态切换，不加路由库（C004-A）；C004-B 加「申请」页
export default function App() {
  const [tab, setTab] = useState<Tab>("models");
  return (
    <div className="app">
      <header className="topbar">
        <span className="brand">JobApply</span>
        <nav className="tabs" role="tablist">
          <button role="tab" aria-selected={tab === "models"} className={tab === "models" ? "active" : ""}
            onClick={() => setTab("models")}>
            设置 · 模型
          </button>
          <button role="tab" aria-selected={tab === "assets"} className={tab === "assets" ? "active" : ""}
            onClick={() => setTab("assets")}>
            资料
          </button>
          <button role="tab" aria-selected={tab === "application"} className={tab === "application" ? "active" : ""}
            onClick={() => setTab("application")}>
            申请
          </button>
        </nav>
      </header>
      <main className="content">
        {tab === "models" && <ModelSettings />}
        {tab === "assets" && <Assets />}
        {tab === "application" && <Application />}
      </main>
    </div>
  );
}

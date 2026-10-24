import { go } from "../route";
import Assets from "./Assets";
import ModelSettings from "./ModelSettings";
import PersonalSettings from "./PersonalSettings";
import WorkerSettings from "./WorkerSettings";

// 设置：模型（C004-A，下面是「同时申请」C012、「敏感信息」C014-D）和资料（原件、来源、整理与确认、事实与画像）两个子标签
export default function Settings({ tab }: { tab: "models" | "profile" }) {
  return (
    <>
      <div className="top">
        <div>
          <h1>设置</h1>
        </div>
      </div>
      <nav className="tabs" role="tablist">
        <button role="tab" aria-selected={tab === "models"} className={tab === "models" ? "active" : ""}
          onClick={() => go({ kind: "settings", tab: "models" })}>
          模型
        </button>
        <button role="tab" aria-selected={tab === "profile"} className={tab === "profile" ? "active" : ""}
          onClick={() => go({ kind: "settings", tab: "profile" })}>
          资料
        </button>
      </nav>
      <div className="content">
        {tab === "models" ? <><ModelSettings /><WorkerSettings /><PersonalSettings /></> : <Assets />}
      </div>
    </>
  );
}

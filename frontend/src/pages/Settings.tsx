import { useState } from "react";
import { go } from "../route";
import Assets from "./Assets";
import CheckSettings from "./CheckSettings";
import ModelSettings from "./ModelSettings";
import PersonalSettings from "./PersonalSettings";
import RagSettings from "./RagSettings";
import StorageLocationSettings from "./StorageLocation";
import ToolCallSettings from "./ToolCallSettings";
import WorkerSettings from "./WorkerSettings";

type Sub = "models" | "run" | "personal" | "data";

// 「模型」标签内的子页签（C025）：模型 / 运行 / 敏感信息 / 经验检索与数据位置
const SUBS: [Sub, string][] = [
  ["models", "模型"], ["run", "运行"], ["personal", "敏感信息"], ["data", "经验检索与数据位置"],
];

const SUB_KEY = "jobapply.settings.sub";

function storedSub(): Sub {
  try {
    const value = localStorage.getItem(SUB_KEY);
    return SUBS.some(([id]) => id === value) ? (value as Sub) : "models";
  } catch {
    return "models";
  }
}

// 「运行」子页：同时申请（C012）、工具调用上限（C018）。新的运行类设置卡片加在这里
function RunSettings() {
  return (
    <>
      <WorkerSettings />
      <ToolCallSettings />
      <CheckSettings />
    </>
  );
}

// 设置：模型（C004-A，子页签见上）和资料（原件、来源、整理与确认、事实与画像）两个标签
export default function Settings({ tab }: { tab: "models" | "profile" }) {
  const [sub, setSub] = useState<Sub>(storedSub);

  function choose(next: Sub) {
    setSub(next);
    try {
      localStorage.setItem(SUB_KEY, next);
    } catch {
      // 记不住也照常切换
    }
  }

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
        {tab === "models" ? (
          <>
            <nav className="subtabs" role="tablist">
              {SUBS.map(([id, label]) => (
                <button key={id} role="tab" aria-selected={sub === id} className={sub === id ? "active" : ""}
                  onClick={() => choose(id)}>
                  {label}
                </button>
              ))}
            </nav>
            {sub === "models" && <ModelSettings />}
            {sub === "run" && <RunSettings />}
            {sub === "personal" && <PersonalSettings />}
            {sub === "data" && <><RagSettings /><StorageLocationSettings /></>}
          </>
        ) : <Assets />}
      </div>
    </>
  );
}

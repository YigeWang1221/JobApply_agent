import { useState } from "react";
import type { JobView } from "../api";
import { applications, FUNNEL_LEVELS, reached, submitted } from "./metrics";

const FILLS = ["--f1", "--f2", "--f3", "--f4", "--f5"];

// 漏斗：多少家公司（或多少个申请）曾经走到过每一层。按公司时一家公司取它各申请里走得最远的一步
export function Funnel({ jobs }: { jobs: JobView[] }) {
  const [mode, setMode] = useState<"co" | "app">("co");
  const sent = applications(jobs).filter(submitted);
  const byCompany = new Map<string, number>();
  for (const job of sent) {
    byCompany.set(job.company, Math.max(byCompany.get(job.company) ?? 0, reached(job)));
  }
  const rows = FUNNEL_LEVELS.map((name, i) => ({
    name,
    co: [...byCompany.values()].filter((v) => v >= i + 1).length,
    app: sent.filter((j) => reached(j) >= i + 1).length,
  }));
  const key = mode;
  const max = Math.max(1, rows[0][key]);
  return (
    <div className="panel">
      <div className="ph">
        <h2>漏斗：多少家公司走到了哪一步</h2>
        <div className="seg" role="group" aria-label="统计口径">
          <button aria-pressed={mode === "co"} onClick={() => setMode("co")}>按公司</button>
          <button aria-pressed={mode === "app"} onClick={() => setMode("app")}>按申请</button>
        </div>
      </div>
      <p className="sub">
        {mode === "co"
          ? "每家公司按它各个申请里走得最远的一步计 1 次；右侧灰字是同层的申请数"
          : "每个申请计 1 次；右侧灰字是同层的公司数"}
      </p>
      {sent.length === 0
        ? <p className="empty">还没有已投递的申请。</p>
        : (
          <div className="funnel">
            {rows.map((r, i) => {
              const v = r[key];
              const other = mode === "co" ? `${r.app} 申请` : `${r.co} 家`;
              const pct = rows[0][key] ? Math.round((v / rows[0][key]) * 100) : 0;
              return (
                <div key={r.name} className="frow">
                  <span className="lab">{r.name}</span>
                  <div className="ftrack">
                    <div className="fbar" style={{ width: `${(v / max) * 100}%`, background: `var(${FILLS[i]})` }}
                      title={`${r.name}：${r.co} 家公司 / ${r.app} 个申请（占已投递的 ${pct}%）`} />
                  </div>
                  <span className="val">{v}{mode === "co" ? " 家" : ""} <em>· {other}</em></span>
                </div>
              );
            })}
          </div>
        )}
    </div>
  );
}

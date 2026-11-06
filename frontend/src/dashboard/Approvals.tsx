import { useState } from "react";
import { api, errorText, type JobView, type ProposalResult } from "../api";
import { go } from "../route";
import { proposalText, stageText } from "../status";
import { toast } from "../toast";
import { md } from "./metrics";

// 核准列表（C026）：Agent 在官网查到的状态只是建议，用户勾选后核准才改阶段；驳回就丢掉这条建议
export function Approvals({ jobs, showProject, reload }: {
  jobs: JobView[];
  showProject?: boolean;
  reload: () => Promise<void>;
}) {
  const rows = jobs.filter((j) => j.proposal);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  if (rows.length === 0) {
    return null;
  }
  const chosen = rows.filter((j) => picked.has(j.id)).map((j) => j.id);
  const all = chosen.length === rows.length;
  const toggle = (id: string) => setPicked((old) => {
    const next = new Set(old);
    if (!next.delete(id)) {
      next.add(id);
    }
    return next;
  });

  async function resolve(action: (ids: string[]) => Promise<ProposalResult>, verb: string) {
    setBusy(true);
    try {
      const result = await action(chosen);
      if (result.done.length > 0) {
        toast(`已${verb} ${result.done.length} 条`);
      }
      if (result.failed.length > 0) {
        const name = (id: string) => {
          const job = rows.find((j) => j.id === id);
          return job ? `${job.company} · ${job.title}` : id;
        };
        toast(`${result.failed.length} 条没${verb}：${result.failed.map((f) => `${name(f.jobId)}（${f.reason}）`).join("；")}`, "error");
      }
      setPicked((old) => new Set([...old].filter((id) => !result.done.includes(id))));
    } catch (e) {
      toast(errorText(e), "error");
    }
    setBusy(false);
    await reload();
  }

  return (
    <div className="panel">
      <div className="ph">
        <h2>核准列表</h2>
        <span className="boardnote">Agent 在官网查到的状态，核准后才改阶段</span>
        <span className="cbgap" />
        <label className="cbdue">
          <input type="checkbox" checked={all}
            onChange={() => setPicked(all ? new Set() : new Set(rows.map((j) => j.id)))} />
          全选
        </label>
        <button className="primary" disabled={busy || chosen.length === 0}
          onClick={() => void resolve(api.approveProposals, "核准")}>核准选中</button>
        <button disabled={busy || chosen.length === 0}
          onClick={() => void resolve(api.rejectProposals, "驳回")}>驳回选中</button>
      </div>
      <div className="list">
        {rows.map((job) => {
          const p = job.proposal!;
          return (
            <div key={job.id} className="li approval">
              <input type="checkbox" checked={picked.has(job.id)} onChange={() => toggle(job.id)}
                aria-label={`选中 ${job.company} · ${job.title}`} />
              <div className="t">
                <div>
                  <button className="linkbtn" onClick={() => go({ kind: "job", id: job.id })}>
                    {job.company} · {job.title}
                  </button>
                  {showProject && <small className="muted"> · {job.project}</small>}
                </div>
                <small>{stageText(job)} → {proposalText(p)}</small>
                {p.quote && <small className="cbquote" title={p.quote}>「{p.quote}」</small>}
              </div>
              <div className="acts">
                {p.url && <a href={p.url} target="_blank" rel="noreferrer" className="linkbtn">官网</a>}
                <time>{md(p.at)}</time>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

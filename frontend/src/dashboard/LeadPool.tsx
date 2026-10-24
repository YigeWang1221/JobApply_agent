import { useState } from "react";
import type { JobView } from "../api";
import { Icon } from "../chat/icons";
import { LEAD, LeadChip, VerifiedChip } from "../status";

export type LeadMark = "new" | "shortlisted" | "skipped" | "closed";

// 候选池：调研出来、还没转为申请的岗位，按公司分组折叠。想投 / 不投（填原因）/ 已下线 / 重新开放 / 开始申请
export function LeadPool({ leads, applications, showProject, sessionTitle, onMark, onClosed, onStart }: {
  leads: JobView[];
  applications: JobView[];
  showProject?: boolean;
  sessionTitle: (id: string) => string | undefined;
  onMark: (job: JobView, status: LeadMark, reason?: string) => Promise<boolean>;
  onClosed: (job: JobView) => Promise<boolean>;
  onStart: (job: JobView) => void;
}) {
  // 正在填「不投」原因的那个岗位
  const [skipping, setSkipping] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  // 展开着的公司；默认都收起，只看公司一行的数量
  const [open, setOpen] = useState<Set<string>>(() => new Set());
  if (leads.length === 0) {
    return <p className="empty">候选池是空的。新建一个 Session，让 Agent 去调研。</p>;
  }
  const companies = [...new Set(leads.map((j) => j.company))];
  // 想投的多的公司排前面，不投的沉到下面
  const weight = (co: string) => leads.filter((j) => j.company === co && j.leadStatus !== "skipped" && j.leadStatus !== "closed").length;
  companies.sort((a, b) => weight(b) - weight(a));
  const toggle = (co: string) => {
    const next = new Set(open);
    if (next.has(co)) {
      next.delete(co);
    } else {
      next.add(co);
    }
    setOpen(next);
  };
  const allOpen = companies.every((co) => open.has(co));
  return (
    <div>
      <div className="cotools">
        <span className="muted small">{companies.length} 家公司 · {leads.length} 个岗位</span>
        <button className="linkbtn" onClick={() => setOpen(allOpen ? new Set() : new Set(companies))}>
          {allOpen ? "全部收起" : "全部展开"}
        </button>
      </div>
      {companies.map((co) => {
        const rows = leads.filter((j) => j.company === co);
        const applied = applications.filter((j) => j.company === co);
        const inflight = applied.filter((j) => j.phase !== "closed").length;
        const expanded = open.has(co);
        const counts = (["shortlisted", "new", "skipped", "closed"] as const)
          .map((status) => [status, rows.filter((j) => j.leadStatus === status).length] as const)
          .filter(([, n]) => n > 0)
          .map(([status, n]) => `${LEAD[status]} ${n}`);
        return (
          <div key={co} className="cogrp">
            <button className="cohead" aria-expanded={expanded} onClick={() => toggle(co)}>
              <span className={`chev${expanded ? " open" : ""}`}><Icon name="chevron" size={13} /></span>
              <b>{co}</b>
              <span>
                {[`候选 ${rows.length}`, ...counts,
                  applied.length > 0 ? `已申请 ${applied.length}（进行中 ${inflight}）` : null].filter(Boolean).join(" · ")}
              </span>
            </button>
            {expanded && rows.map((job) => {
              const from = job.discoveredIn ? sessionTitle(job.discoveredIn) ?? job.discoveredIn : null;
              return (
                <div key={job.id} className="lead">
                  <div className="nm">
                    <div>
                      {job.url ? <a href={job.url} target="_blank" rel="noreferrer">{job.title}</a> : job.title}
                    </div>
                    <small>
                      {[job.city.join("、"), job.type, showProject ? job.project : null,
                        from ? `发现于「${from}」` : null].filter(Boolean).join(" · ")}
                      {job.officialUrl && <> · <a href={job.officialUrl} target="_blank" rel="noreferrer">官方链接</a></>}
                      {job.skipReason && ` · 不投：${job.skipReason}`}
                      {job.note && ` · ${job.note}`}
                    </small>
                  </div>
                  <div className="chips">
                    <VerifiedChip verified={job.verified} />
                    <LeadChip status={job.leadStatus} />
                  </div>
                  <div className="acts">
                    {job.leadStatus === "new" && <button className="linkbtn" onClick={() => void onMark(job, "shortlisted")}>想投</button>}
                    {job.leadStatus === "shortlisted" && <button className="linkbtn" onClick={() => void onMark(job, "new")}>取消想投</button>}
                    {(job.leadStatus === "new" || job.leadStatus === "shortlisted") && (
                      <>
                        <button className="linkbtn" onClick={() => { setSkipping(job.id); setReason(""); }}>不投</button>
                        <button className="linkbtn" title="岗位已下线 / 停止招聘" onClick={() => void onClosed(job)}>已下线</button>
                        <button className="linkbtn" onClick={() => onStart(job)}>开始申请</button>
                      </>
                    )}
                    {job.leadStatus === "closed" && (
                      <button className="linkbtn" title="岗位又开放了" onClick={() => void onMark(job, "new")}>重新开放</button>
                    )}
                  </div>
                  {skipping === job.id && (
                    <form className="skipform" onSubmit={(e) => {
                      e.preventDefault();
                      if (reason.trim()) {
                        void onMark(job, "skipped", reason.trim()).then((ok) => ok && setSkipping(null));
                      }
                    }}>
                      <input autoFocus value={reason} placeholder="不投的原因，例如：要求博士"
                        onChange={(e) => setReason(e.target.value)}
                        onKeyDown={(e) => e.key === "Escape" && setSkipping(null)} />
                      <button className="primary" type="submit" disabled={!reason.trim()}>确定</button>
                      <button type="button" onClick={() => setSkipping(null)}>取消</button>
                    </form>
                  )}
                </div>
              );
            })}
          </div>
        );
      })}
    </div>
  );
}

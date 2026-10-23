import { useCallback, useEffect, useState } from "react";
import { api, errorText, type JobView, type ProjectSummary } from "../api";
import { go } from "../route";
import { ApplicationChip, Kpis, LeadChip, PHASE, PREP, VerifiedChip } from "../status";

const POLL_MS = 4000;

// Project 主页（ui.md「界面结构」）：新建 Session、KPI、候选池（按公司分组）、申请、最近的 Session
export default function ProjectPage({ id, onChanged }: { id: string; onChanged: () => Promise<void> }) {
  const [project, setProject] = useState<ProjectSummary | null>(null);
  const [jobs, setJobs] = useState<JobView[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const data = await api.project(id);
      setProject(data.project);
      setJobs(data.jobs);
      setError(null);
    } catch (e) {
      setError(errorText(e));
    }
  }, [id]);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => void load(), POLL_MS);
    return () => window.clearInterval(timer);
  }, [load]);

  async function newSession() {
    setBusy(true);
    setError(null);
    try {
      const view = await api.createSession(id);
      await onChanged();
      go({ kind: "session", id: view.session.id });
    } catch (e) {
      setError(errorText(e));
      setBusy(false);
    }
  }

  // 动作出错时显示后端的原话，成功后重新读一遍
  async function act(action: () => Promise<unknown>, then?: () => void) {
    setError(null);
    try {
      await action();
      then?.();
      await load();
    } catch (e) {
      setError(errorText(e));
    }
  }

  const latestSession = project?.sessions[0]?.id ?? null;
  const leads = jobs.filter((j) => j.leadStatus !== "converted");
  const applications = jobs.filter((j) => j.leadStatus === "converted");

  return (
    <>
      <div className="top">
        <div>
          <h1>{project?.title ?? id}</h1>
          <p className="mono">{id}</p>
        </div>
        <button className="primary" disabled={busy || !project} onClick={() => void newSession()}>＋ 新建 Session</button>
      </div>
      {error && <p className="error">{error}</p>}
      {project && <Kpis counts={project.counts} />}
      <div className="grid2">
        <div className="panel">
          <div className="ph">
            <h2>候选池</h2>
            <span className="muted small">调研出来的岗位，按公司分组</span>
          </div>
          <LeadPool leads={leads} applications={applications}
            onMark={(job, status) => {
              if (status === "skipped") {
                const reason = window.prompt(`不投「${job.company} · ${job.title}」的原因：`);
                if (!reason?.trim()) {
                  return;
                }
                void act(() => api.markLead(job.id, status, reason.trim()));
              } else {
                void act(() => api.markLead(job.id, status));
              }
            }}
            onStart={(job) => {
              const session = job.discoveredIn ?? latestSession;
              if (!session) {
                setError("先新建一个 Session，再开始申请");
                return;
              }
              void act(() => api.startApplication(job.id, { project: id, session }),
                () => go({ kind: "job", id: job.id }));
            }} />
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 16, minWidth: 0 }}>
          <div className="panel">
            <h2>申请</h2>
            {applications.length === 0
              ? <p className="empty">还没有转为申请的岗位。</p>
              : (
                <div className="list">
                  {applications.map((job) => (
                    <div key={job.id} className="li">
                      <div className="t">
                        <div>{job.company} · {job.title}</div>
                        <small>
                          {job.phase === "prep" && job.prepState ? PREP[job.prepState] ?? job.prepState
                            : PHASE[job.phase ?? ""] ?? job.label}
                        </small>
                      </div>
                      <div className="acts">
                        <ApplicationChip status={job.application} />
                        <button className="linkbtn" onClick={() => go({ kind: "job", id: job.id })}>工作台</button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
          </div>
          <div className="panel">
            <h2>最近的 Session</h2>
            {!project || project.sessions.length === 0
              ? <p className="empty">还没有 Session。</p>
              : (
                <div className="list">
                  {project.sessions.slice(0, 6).map((s) => (
                    <div key={s.id} className="li">
                      <div className="t">
                        <div>
                          {s.title}
                          {s.tags.map((tag) => <span key={tag} className={`tagc ${tag}`}>{tag}</span>)}
                        </div>
                        <small>{s.handoff || s.createdAt}</small>
                      </div>
                      <button className="linkbtn" onClick={() => go({ kind: "session", id: s.id })}>打开</button>
                    </div>
                  ))}
                </div>
              )}
          </div>
        </div>
      </div>
    </>
  );
}

function LeadPool({ leads, applications, onMark, onStart }: {
  leads: JobView[];
  applications: JobView[];
  onMark: (job: JobView, status: "new" | "shortlisted" | "skipped") => void;
  onStart: (job: JobView) => void;
}) {
  if (leads.length === 0) {
    return <p className="empty">候选池是空的。新建一个 Session，让 Agent 去调研。</p>;
  }
  const companies = [...new Set(leads.map((j) => j.company))];
  // 想投的多的公司排前面，不投的沉到下面
  const weight = (co: string) => leads.filter((j) => j.company === co && j.leadStatus !== "skipped").length;
  companies.sort((a, b) => weight(b) - weight(a));
  return (
    <div>
      {companies.map((co) => {
        const rows = leads.filter((j) => j.company === co);
        const applied = applications.filter((j) => j.company === co).length;
        return (
          <div key={co} className="cogrp">
            <div className="cohead">
              <b>{co}</b>
              <span>候选 {rows.length}{applied > 0 ? ` · 已转申请 ${applied}` : ""}</span>
            </div>
            {rows.map((job) => (
              <div key={job.id} className="lead">
                <div className="nm">
                  <div>
                    {job.url ? <a href={job.url} target="_blank" rel="noreferrer">{job.title}</a> : job.title}
                  </div>
                  <small>
                    {[job.city.join("、"), job.type].filter(Boolean).join(" · ")}
                    {job.officialUrl && <> · <a href={job.officialUrl} target="_blank" rel="noreferrer">官方链接</a></>}
                    {job.skipReason && ` · 不投：${job.skipReason}`}
                    {job.note && ` · ${job.note}`}
                  </small>
                </div>
                <div className="acts">
                  <VerifiedChip verified={job.verified} />
                  <LeadChip status={job.leadStatus} />
                  {job.leadStatus === "new" && <button className="linkbtn" onClick={() => onMark(job, "shortlisted")}>想投</button>}
                  {job.leadStatus === "shortlisted" && <button className="linkbtn" onClick={() => onMark(job, "new")}>取消想投</button>}
                  {job.leadStatus !== "skipped" && (
                    <>
                      <button className="linkbtn" onClick={() => onMark(job, "skipped")}>不投</button>
                      <button className="linkbtn" onClick={() => onStart(job)}>开始申请</button>
                    </>
                  )}
                </div>
              </div>
            ))}
          </div>
        );
      })}
    </div>
  );
}

import { useEffect, useState } from "react";
import type { JobView } from "../api";
import { go } from "../route";
import { JobState } from "../status";
import { Icon, Spinner } from "./icons";
import { Markdown } from "./Markdown";
import { describeTool, fetchPreview, leadOf, searchResults, summarize, type Step, type Turn } from "./process";

// 一轮回复上方的「过程」：默认折叠成一句摘要；运行中展开、实时追加。展开后按时间列出思考和每一步工具调用
export function ProcessBlock({ turn, live, jobs }: { turn: Turn; live: boolean; jobs: JobView[] }) {
  const [open, setOpen] = useState(live);
  useEffect(() => setOpen(live), [live]);
  if (turn.entries.length === 0) {
    return null;
  }
  return (
    <div className={`process${open ? " open" : ""}`}>
      <button className="process-head" aria-expanded={open} onClick={() => setOpen(!open)}>
        {live ? <Spinner /> : <Icon name="chevron" size={14} />}
        <span>{summarize(turn, live)}</span>
      </button>
      {open && (
        <ol className="process-list">
          {turn.entries.map((entry, i) => (
            <li key={i}>
              {entry.kind === "note"
                ? <div className="note"><Markdown text={entry.text} /></div>
                : entry.step.kind === "thinking"
                  ? <Thinking text={entry.step.text ?? ""} />
                  : <ToolCard step={entry.step} jobs={jobs} />}
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

function Thinking({ text }: { text: string }) {
  const [full, setFull] = useState(false);
  const long = text.length > 240 || text.split("\n").length > 3;
  return (
    <div className="thought">
      <Icon name="think" size={14} />
      <div>
        <p className={full ? "" : "clamp"}>{text}</p>
        {long && <button className="textbtn" onClick={() => setFull(!full)}>{full ? "收起" : "展开"}</button>}
      </div>
    </div>
  );
}

const STATUS_LABEL = { running: "进行中", done: "完成", failed: "失败", rejected: "被拒绝" } as const;

function StatusMark({ status }: { status: Step["status"] }) {
  if (status === "running") {
    return <Spinner />;
  }
  const icon = status === "done" ? "check" : status === "failed" ? "cross" : "ban";
  return <span className={`stepstatus ${status}`} title={STATUS_LABEL[status]}><Icon name={icon} size={13} /></span>;
}

function ToolCard({ step, jobs }: { step: Step; jobs: JobView[] }) {
  const [open, setOpen] = useState(false);
  const { icon, label } = describeTool(step);
  const lead = step.name === "add_lead" || step.name === "update_lead" ? leadOf(step) : null;
  return (
    <div className={`toolcard ${step.status}`}>
      <button className="toolcard-head" aria-expanded={open} onClick={() => setOpen(!open)}>
        <Icon name={icon} size={14} />
        <span className="toolcard-label">{label}</span>
        <StatusMark status={step.status} />
      </button>
      {lead && <LeadCard lead={lead} jobs={jobs} />}
      {open && <ToolDetail step={step} />}
    </div>
  );
}

function LeadCard({ lead, jobs }: { lead: ReturnType<typeof leadOf>; jobs: JobView[] }) {
  const job = jobs.find((j) => j.id === lead.job)
    ?? jobs.find((j) => lead.company && j.company === lead.company && j.title === lead.title);
  const company = job?.company ?? lead.company;
  const title = job?.title ?? lead.title;
  if (!company && !title && !lead.job) {
    return null;
  }
  const id = job?.id ?? lead.job;
  return (
    <button className="leadcard" disabled={!id} onClick={() => id && go({ kind: "job", id })}>
      <span className="leadcard-t">{[company, title].filter(Boolean).join(" · ") || id}</span>
      <small>{job ? job.city.join("、") : lead.city}</small>
      {job && <span className="leadcard-s"><JobState job={job} /></span>}
    </button>
  );
}

function ToolDetail({ step }: { step: Step }) {
  const args = Object.entries(step.args ?? {});
  const result = step.result ?? null;
  return (
    <div className="toolcard-body">
      {args.length > 0 && (
        <dl className="toolargs">
          {args.map(([key, value]) => (
            <div key={key}>
              <dt>{key}</dt>
              <dd>{typeof value === "string" ? value : JSON.stringify(value)}</dd>
            </div>
          ))}
        </dl>
      )}
      {result === null
        ? <p className="muted small">{step.status === "running" ? "等结果…" : "没有结果。"}</p>
        : step.status === "rejected"
          ? <p className="warn small">原因：{result.replace(/^被拒绝：?/, "")}</p>
          : <ToolResult name={step.name ?? ""} result={result} />}
    </div>
  );
}

function ToolResult({ name, result }: { name: string; result: string }) {
  if (name === "web_search") {
    const items = searchResults(result);
    if (items.length > 0) {
      return (
        <ol className="searchresults">
          {items.map((item, i) => (
            <li key={i}>
              <a href={item.url} target="_blank" rel="noopener noreferrer">{item.title}</a>
              <small className="mono">{item.url}</small>
              {item.text && <p>{item.text}</p>}
            </li>
          ))}
        </ol>
      );
    }
  }
  if (name === "web_fetch") {
    const page = fetchPreview(result);
    return (
      <div className="fetchpreview">
        <p className="muted small">{page.head}</p>
        {page.paragraphs.map((p, i) => <p key={i}>{p}</p>)}
        {(page.more || page.savedTo) && (
          <p className="muted small">{page.savedTo ? `全文在 ${page.savedTo}` : "……"}</p>
        )}
      </div>
    );
  }
  if (name === "read_skill") {
    const body = result.split("\n\n").slice(1).join("\n\n");
    if (body) {
      return <div className="skillbody"><Markdown text={body} /></div>;
    }
  }
  return <pre className="toolresult">{result}</pre>;
}

import type { ChatMessage, JobView, ProjectCounts, ToolStep } from "./api";

// 状态的中文说法和小标签，几个页面共用

export const LEAD: Record<string, string> = {
  new: "新发现",
  shortlisted: "想投",
  skipped: "不投",
  converted: "已转申请",
};

export const APPLICATION: Record<string, string> = {
  running: "Agent 正在做",
  waiting_user: "等你",
  ready: "停在提交前",
  failed: "失败",
};

export const PREP: Record<string, string> = {
  selected: "已选中",
  preparing: "填写中",
  needs_input: "等你回答",
  needs_login: "等你登录",
  handoff_user: "交给你",
  failed: "失败",
  awaiting_review: "等你审阅",
  user_submitting: "你在提交",
  verification_pending: "待核实投递",
  unconfirmed: "投递未确认",
  paused: "暂停",
};

export const PHASE: Record<string, string> = {
  prep: "准备中",
  applied: "已投递",
  screening: "简历筛选",
  assessment: "笔试 / OA",
  interview: "面试中",
  offer: "Offer",
  closed: "已结束",
};

const APPLICATION_CHIP: Record<string, string> = {
  running: "rev",
  waiting_user: "wait",
  ready: "good",
  failed: "bad",
};

export function LeadChip({ status }: { status: string }) {
  const kind = status === "shortlisted" ? "rev" : status === "new" ? "inp" : "";
  return <span className={`chip ${kind}`}>{LEAD[status] ?? status}</span>;
}

export function ApplicationChip({ status }: { status: string | null }) {
  if (!status) {
    return null;
  }
  return <span className={`chip ${APPLICATION_CHIP[status] ?? ""}`}>{APPLICATION[status] ?? status}</span>;
}

export function VerifiedChip({ verified }: { verified: JobView["verified"] }) {
  if (verified === "verified") {
    return <span className="chip good">官网已核验</span>;
  }
  if (verified === "not_found") {
    return <span className="chip bad">官网没找到</span>;
  }
  return <span className="chip">未核验</span>;
}

// 一个 Job 现在走到哪：候选状态，或者申请的阶段和 prep 状态
export function JobState({ job }: { job: JobView }) {
  if (job.leadStatus !== "converted") {
    return <LeadChip status={job.leadStatus} />;
  }
  const phase = job.phase === "prep" && job.prepState ? PREP[job.prepState] ?? job.prepState
    : PHASE[job.phase ?? ""] ?? job.label;
  return (
    <>
      <span className="chip">{phase}</span>
      <ApplicationChip status={job.application} />
    </>
  );
}

export function Kpis({ counts }: { counts: ProjectCounts }) {
  const items: [string, number, boolean][] = [
    ["候选", counts.leads, false],
    ["想投", counts.shortlisted, false],
    ["准备中", counts.preparing, false],
    ["等你", counts.waiting, counts.waiting > 0],
    ["停在提交前", counts.ready, counts.ready > 0],
    ["已投递", counts.applied, false],
  ];
  return (
    <div className="kpis">
      {items.map(([label, value, warn]) => (
        <div key={label} className={`kpi${warn ? " warn" : ""}`}><b>{value}</b><span>{label}</span></div>
      ))}
    </div>
  );
}

export function sumCounts(all: ProjectCounts[]): ProjectCounts {
  return all.reduce((sum, c) => ({
    leads: sum.leads + c.leads,
    shortlisted: sum.shortlisted + c.shortlisted,
    preparing: sum.preparing + c.preparing,
    waiting: sum.waiting + c.waiting,
    ready: sum.ready + c.ready,
    applied: sum.applied + c.applied,
  }), { leads: 0, shortlisted: 0, preparing: 0, waiting: 0, ready: 0, applied: 0 });
}

// 对话：用户在右，Agent 在左；工具调用折叠成一行一行的「名字 · 说明 → 结果」
export function ChatLog({ messages }: { messages: ChatMessage[] }) {
  return (
    <>
      {messages.map((m, i) => m.role === "user"
        ? <div key={i} className="msg u">{m.text}</div>
        : (
          <div key={i} style={{ display: "contents" }}>
            {m.text && <div className="msg a">{m.text}</div>}
            {m.tools.length > 0 && <Steps tools={m.tools} />}
          </div>
        ))}
    </>
  );
}

function Steps({ tools }: { tools: ToolStep[] }) {
  return (
    <details className="steps">
      <summary>{tools.length === 1 ? tools[0].name : `${tools.length} 个工具调用：${tools.map((t) => t.name).join("、")}`}</summary>
      <ul>
        {tools.map((t, i) => (
          <li key={i}><code>{t.name}</code>{t.summary ? ` · ${t.summary}` : ""}{t.result ? ` → ${t.result}` : ""}</li>
        ))}
      </ul>
    </details>
  );
}

export function lastTool(messages: ChatMessage[]): string | null {
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.role === "assistant" && m.tools.length > 0) {
      return m.tools[m.tools.length - 1].name;
    }
  }
  return null;
}

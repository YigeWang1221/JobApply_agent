import { useState } from "react";
import { api, errorText, type JobView, type RoundView } from "./api";
import { toast } from "./toast";

// 状态的中文说法和小标签，几个页面共用

export const LEAD: Record<string, string> = {
  new: "新发现",
  shortlisted: "想投",
  skipped: "不投",
  closed: "已下线",
  converted: "已转申请",
};

export const APPLICATION: Record<string, string> = {
  queued: "排队中",
  running: "Agent 正在做",
  waiting_user: "等你",
  ready: "停在提交前",
  failed: "失败",
  interrupted: "中断",
  done: "已结束",
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

export const OUTCOME: Record<string, string> = {
  rejected: "被拒",
  withdrawn: "主动撤回",
  accepted: "接受 Offer",
  declined: "拒绝 Offer",
  ghosted: "长期无回应",
  not_submitted: "最终未投",
  posting_closed: "岗位下线",
};

// 阶段的说法：prep 显示子状态，closed 带上结局（label 形如 closed:posting_closed）
export function phaseText(job: JobView): string {
  if (job.phase === "prep" && job.prepState) {
    return PREP[job.prepState] ?? job.prepState;
  }
  if (job.phase === "closed") {
    const outcome = job.label?.split(":")[1];
    return outcome ? `已结束 · ${OUTCOME[outcome] ?? outcome}` : PHASE.closed;
  }
  return PHASE[job.phase ?? ""] ?? job.label;
}

const APPLICATION_CHIP: Record<string, string> = {
  queued: "rev",
  running: "rev",
  waiting_user: "wait",
  ready: "good",
  failed: "bad",
  interrupted: "wait",
  done: "",
};

// 轮次结果（C014-A）：unknown 是服务器算的「用户未同步」（约定时间过了 7 天还是待结果）
export const ROUND_RESULT: Record<string, string> = {
  pending: "待结果",
  passed: "通过",
  failed: "未通过",
  cancelled: "取消",
  unknown: "未同步",
};
const ROUND_DOT: Record<string, string> = { pending: "◐", passed: "●", failed: "✕", cancelled: "⊘", unknown: "◐" };
export const ROUND_CHIP: Record<string, string> = { pending: "rev", passed: "good", failed: "bad", unknown: "wait" };

// 申请的「进度」（tracking.md 表 1）：一轮一个圆点（● 通过、◐ 进行中或待结果、✕ 未通过、⊘ 取消），后面是最新一轮
export function RoundProgress({ rounds }: { rounds: RoundView[] }) {
  if (rounds.length === 0) {
    return <span className="muted">—</span>;
  }
  const latest = rounds[rounds.length - 1];
  return (
    <span className="rprog" title={rounds.map((r) => `${r.seq}. ${r.name}：${ROUND_RESULT[r.result] ?? r.result}`).join("\n")}>
      <span className="rdots" aria-hidden="true">
        {rounds.map((r) => <i key={r.seq} className={r.result}>{ROUND_DOT[r.result] ?? "○"}</i>)}
      </span>
      {latest.name} · {ROUND_RESULT[latest.result] ?? latest.result}
    </span>
  );
}

export function LeadChip({ status }: { status: string }) {
  const kind = status === "shortlisted" ? "rev" : status === "new" ? "inp" : status === "closed" ? "bad" : "";
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
  const phase = phaseText(job);
  return (
    <>
      <span className="chip">{phase}</span>
      <ApplicationChip status={job.application} />
    </>
  );
}

// 停在提交前的确认（C007）：用户在 Chrome 里亲自点了提交以后告诉 JobApply。工作台右栏和 Session 对话里共用
export function SubmitConfirm({ job, onDone }: { job: JobView; onDone: () => void | Promise<void> }) {
  const [busy, setBusy] = useState(false);
  const [notYet, setNotYet] = useState(false);
  async function act(action: () => Promise<unknown>, done: string) {
    setBusy(true);
    try {
      await action();
      toast(done);
      await onDone();
    } catch (e) {
      toast(errorText(e), "error");
    } finally {
      setBusy(false);
    }
  }
  const name = `${job.company} · ${job.title}`;
  return (
    <div className="submitcard" role="group" aria-label={`${name} 提交了吗`}>
      <p>
        {job.pageLooksSubmitted
          ? <>页面像是已经投递成功了，确认一下：<b>{name}</b> 提交了吗？</>
          : <><b>{name}</b> 已经填好，提交了吗？</>}
      </p>
      {!notYet ? (
        <div className="row">
          <button className="primary" disabled={busy}
            onClick={() => void act(() => api.markSubmitted(job.id), `${name}：记为已投递`)}>
            我已经在 Chrome 里提交了
          </button>
          <button disabled={busy} onClick={() => setNotYet(true)}>还没提交 / 不投了</button>
        </div>
      ) : (
        <div className="row">
          <button disabled={busy}
            onClick={() => void act(() => api.markNotSubmitted(job.id, false), `${name}：先暂停，之后可以接着申请`)}>
            还没提交，先放着
          </button>
          <button disabled={busy}
            onClick={() => void act(() => api.markNotSubmitted(job.id, true), `${name}：不投了`)}>
            不投了
          </button>
          <button className="linkbtn" disabled={busy} onClick={() => setNotYet(false)}>返回</button>
        </div>
      )}
    </div>
  );
}

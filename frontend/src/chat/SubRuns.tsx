import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { api, errorText, type SubRunView } from "../api";
import { eventsUrl, subscribe } from "./eventStream";
import { Icon, Spinner } from "./icons";
import { Markdown } from "./Markdown";
import { TranscriptTurns } from "./MessageList";
import { elapsed, type Step } from "./process";

// Subagent 运行（C030）：父运行调 delegate 交出去的一件事，在对话里折成一张状态卡（ui.md「可折叠的 Subagent 状态卡」）。
// 列表先读一次 REST，之后跟着推送里的 subrun 事件（开始、结束）更新；展开看步骤时读单个，运行中每几秒重读一次

export const MODE: Record<string, string> = {
  "web-research": "找岗位 / 核实",
  "resume-match": "简历匹配",
  "portal-check": "查官网状态",
  "interview-prep": "面试准备",
  reflect: "投递复盘",
};

const STATUS: Record<string, string> = { running: "进行中", done: "完成", stopped: "停下了", failed: "出错了" };
const STEPS_POLL_MS = 3000;

const SubRunsContext = createContext<SubRunView[]>([]);

export function SubRunsProvider({ runs, children }: { runs: SubRunView[]; children: ReactNode }) {
  return <SubRunsContext.Provider value={runs}>{children}</SubRunsContext.Provider>;
}

// 这一页（Session 或工作台）的子运行：先读列表，再按推送更新（同一条 /api/events 连接）
export function useSubRuns(scope: { session: string } | { job: string }): SubRunView[] {
  const [runs, setRuns] = useState<SubRunView[]>([]);
  const sessionId = "session" in scope ? scope.session : null;
  const jobId = "job" in scope ? scope.job : null;

  useEffect(() => {
    let alive = true;
    const load = () => {
      const request = sessionId ? api.sessionSubRuns(sessionId) : api.jobSubRuns(jobId!);
      request.then((list) => alive && setRuns(list)).catch(() => undefined);
    };
    load();
    const unsubscribe = subscribe(eventsUrl(sessionId ? { session: sessionId } : { job: jobId! }), (event) => {
      if (event.type === "subrun" && event.subrun) {
        const run = event.subrun;
        setRuns((old) => [...old.filter((r) => r.id !== run.id), run]);
      } else if (event.type === "caught_up" || event.type === "reset") {
        load();
      }
    });
    return () => {
      alive = false;
      unsubscribe();
    };
  }, [sessionId, jobId]);
  return runs;
}

// delegate 这一步对应哪次子运行：结束后结果里写着编号「子任务 mode（sub-…）」；还在跑时按 mode + task 找最近的一次
function runOf(step: Step, runs: SubRunView[]): SubRunView | null {
  const id = step.result ? /（(sub-[^）]+)）/.exec(step.result)?.[1] : undefined;
  if (id) {
    const found = runs.find((r) => r.id === id);
    if (found) {
      return found;
    }
  }
  const mode = typeof step.args?.mode === "string" ? step.args.mode : null;
  const task = typeof step.args?.task === "string" ? step.args.task : null;
  const matches = runs.filter((r) => r.mode === mode && r.task === task);
  return matches[matches.length - 1] ?? null;
}

// 对话里 delegate 那一步：找到子运行就显示它的卡，找不到（旧记录、列表还没读到）就用这一步自己的参数和结果拼一张
export function DelegateCard({ step }: { step: Step }) {
  const runs = useContext(SubRunsContext);
  const run = runOf(step, runs);
  const mode = typeof step.args?.mode === "string" ? step.args.mode : run?.mode ?? "";
  const task = typeof step.args?.task === "string" ? step.args.task : run?.task ?? "";
  const status = run?.status ?? (step.status === "running" ? "running" : step.status === "done" ? "done" : "failed");
  const result = run?.result ?? (step.result ? step.result.replace(/^子任务[^：]*：\n?/, "") : null);
  return <SubRunCard run={run} mode={mode} task={task} status={status} result={result}
    startedAt={run?.startedAt ?? step.startedAt ?? null} endedAt={run?.endedAt ?? step.endedAt ?? null} />;
}

export function SubRunCard({ run, mode, task, status, result, startedAt, endedAt, defaultOpen = false }: {
  run: SubRunView | null;
  mode: string;
  task: string;
  status: string;
  result: string | null;
  startedAt: string | null;
  endedAt: string | null;
  defaultOpen?: boolean;
}) {
  const [open, setOpen] = useState(defaultOpen);
  const running = status === "running";
  const took = running ? null : elapsed(startedAt, endedAt);
  const summary = result?.split("\n").find((line) => line.trim())?.trim() ?? null;
  return (
    <div className={`toolcard subrun ${running ? "running" : status === "failed" ? "failed" : "done"}`}>
      <button className="toolcard-head" aria-expanded={open} onClick={() => setOpen(!open)}>
        <Icon name="tool" size={14} />
        <span className="toolcard-label">子任务 · {MODE[mode] ?? mode}：{firstLine(task)}</span>
        {took && <span className="muted small">{took}</span>}
        <SubRunMark status={status} />
      </button>
      {!open && !running && summary && <p className="subrun-summary">{summary}</p>}
      {open && (
        <div className="toolcard-body">
          <dl className="toolargs">
            <div><dt>任务</dt><dd>{task}</dd></div>
          </dl>
          {result
            ? <div className="subrun-result"><Markdown text={result} /></div>
            : <p className="muted small">{running ? "进行中，结果出来后交回这里。" : "没有交回内容。"}</p>}
          {run && <SubRunSteps run={run} live={running} />}
        </div>
      )}
    </div>
  );
}

function SubRunMark({ status }: { status: string }) {
  if (status === "running") {
    return <Spinner />;
  }
  const icon = status === "done" ? "check" : status === "stopped" ? "ban" : "cross";
  const kind = status === "done" ? "done" : status === "stopped" ? "rejected" : "failed";
  return <span className={`stepstatus ${kind}`} title={STATUS[status] ?? status}><Icon name={icon} size={13} /></span>;
}

// 子运行的步骤：运行中默认展开、每几秒重读；结束后点开才读
function SubRunSteps({ run, live }: { run: SubRunView; live: boolean }) {
  const [show, setShow] = useState(live);
  const [detail, setDetail] = useState<SubRunView | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!show) {
      return;
    }
    let alive = true;
    const load = () => api.subRun(run.project, run.id)
      .then((d) => {
        if (alive) {
          setDetail(d);
          setError(null);
        }
      })
      .catch((e) => alive && setError(errorText(e)));
    void load();
    const timer = live ? window.setInterval(() => void load(), STEPS_POLL_MS) : undefined;
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, [show, live, run.project, run.id, run.status]);

  // 第一条是交代的任务，上面已经写了
  const messages = (detail?.messages ?? []).filter((m, i) => !(i === 0 && m.role === "user"));
  return (
    <div className="subrun-steps">
      <button className="textbtn" aria-expanded={show} onClick={() => setShow(!show)}>
        <Icon name="down" size={13} /> {show ? "收起步骤" : "查看步骤"}
      </button>
      {show && (error
        ? <p className="error small">读不出步骤：{error}</p>
        : !detail
          ? <p className="muted small">读取中…</p>
          : messages.length === 0
            ? <p className="muted small">还没有步骤。</p>
            : <TranscriptTurns messages={messages} running={live} />)}
    </div>
  );
}

function firstLine(text: string): string {
  const line = text.split("\n").find((l) => l.trim())?.trim() ?? "";
  return line.length > 80 ? `${line.slice(0, 79)}…` : line;
}

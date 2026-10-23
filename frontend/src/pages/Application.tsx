import { useCallback, useEffect, useRef, useState } from "react";
import {
  api, ApiError, errorText, type ApplicationQuestion, type ApplicationStatus, type Asset, type FileSlot,
  type ReplyBody,
} from "../api";

const POLL_MS = 2000;

const STATUS: Record<string, string> = {
  running: "Agent 正在做",
  waiting_user: "等你",
  ready: "停在提交前，等你审阅",
  failed: "失败",
};

const PLAN: Record<string, string> = { todo: "待做", doing: "进行中", done: "完成", blocked: "卡住" };

// 申请面板（agent-runtime.md 7.1）：开始或载入一条申请线程，每 2 秒查一次状态。Agent 要你的时候显示它的话、
// 问题和上传位置，你做完以后一次回复。完整的「申请工作台」以后按 ui.md 做。
export default function Application() {
  const [jobId, setJobId] = useState("");
  const [project, setProject] = useState("");
  const [session, setSession] = useState("");
  const [active, setActive] = useState<string | null>(null);
  const [status, setStatus] = useState<ApplicationStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pollError, setPollError] = useState<string | null>(null);

  const refresh = useCallback(async (id: string) => {
    try {
      setStatus(await api.application(id));
      setPollError(null);
    } catch (e) {
      setPollError(describe(e));
    }
  }, []);

  useEffect(() => {
    if (!active) {
      return;
    }
    const timer = window.setInterval(() => void refresh(active), POLL_MS);
    return () => window.clearInterval(timer);
  }, [active, refresh]);

  async function call(action: () => Promise<ApplicationStatus>, id: string) {
    setBusy(true);
    setError(null);
    try {
      const next = await action();
      setStatus(next);
      setActive(id);
      if (!session.trim() && next.session) {
        setSession(next.session);
      }
      if (!project.trim() && next.project) {
        setProject(next.project);
      }
    } catch (e) {
      setError(describe(e));
    } finally {
      setBusy(false);
    }
  }

  const id = jobId.trim();
  const reply = async (body: Omit<ReplyBody, "session">) => {
    if (!active) {
      return;
    }
    if (!session.trim()) {
      throw new Error("先填 Session");
    }
    setStatus(await api.reply(active, { session: session.trim(), ...body }));
  };

  return (
    <>
      <section className="card">
        <h2>申请线程</h2>
        <div className="form-grid">
          <label>
            Job
            <input value={jobId} placeholder="job id" onChange={(e) => setJobId(e.target.value)} />
          </label>
          <label>
            Project
            <input value={project} placeholder="用哪个 Project 的资料" onChange={(e) => setProject(e.target.value)} />
          </label>
          <label>
            Session
            <input value={session} placeholder="session id" onChange={(e) => setSession(e.target.value)} />
          </label>
        </div>
        <div className="row">
          <button disabled={busy || !id || !project.trim() || !session.trim()}
            onClick={() => void call(() => api.startApplication(id, { project: project.trim(), session: session.trim() }), id)}>
            开始申请
          </button>
          <button disabled={busy || !id} onClick={() => void call(() => api.application(id), id)}>载入状态</button>
          {active && <span className="muted small">正在查看 {active}，每 2 秒刷新</span>}
        </div>
        {error && <p className="error">{error}</p>}
        <p className="muted small">
          Agent 停在最终提交前，不会替你点提交。填写报告和过程日志写在 storage/projects/&lt;project&gt;/jobs/&lt;job-id&gt;/ 下。
        </p>
      </section>
      {status && active && (
        <>
          <StatusCard status={status} pollError={pollError}
            onStop={() => void call(() => api.stop(active), active)} busy={busy} />
          {(status.status === "waiting_user" || status.status === "ready" || status.status === "failed") && (
            <ReplyPanel key={replyKey(status)} jobId={active} status={status} reply={reply} />
          )}
        </>
      )}
    </>
  );
}

function StatusCard({ status, pollError, onStop, busy }: {
  status: ApplicationStatus;
  pollError: string | null;
  onStop: () => void;
  busy: boolean;
}) {
  const plan = status.plan ?? [];
  return (
    <section className="card">
      <h2>状态 · {status.job}</h2>
      <dl className="facts">
        <div><dt>状态</dt><dd>{STATUS[status.status] ?? status.status}</dd></div>
        {status.prepState && <div><dt>prep_state</dt><dd>{status.prepState}</dd></div>}
        {status.round && status.round > 1 && <div><dt>轮次</dt><dd>第 {status.round} 轮</dd></div>}
      </dl>
      {plan.length > 0 && (
        <>
          <strong>Agent 的计划</strong>
          <ol className="questions">
            {plan.map((item, index) => (
              <li key={index}><span className="muted small">[{PLAN[item.status] ?? item.status}]</span> {item.text}</li>
            ))}
          </ol>
        </>
      )}
      {status.usage && (
        <div className="stats">
          <div><strong>{status.usage.toolCalls.toLocaleString()}</strong><span className="muted small">工具调用</span></div>
          <div><strong>{status.usage.modelCalls.toLocaleString()}</strong><span className="muted small">模型调用</span></div>
          <div><strong>{status.usage.tokens.toLocaleString()}</strong><span className="muted small">token</span></div>
        </div>
      )}
      {status.status === "running" && (
        <div className="row"><button className="danger" disabled={busy} onClick={onStop}>停止</button></div>
      )}
      {status.error && <p className="error">出错：{status.error}</p>}
      {pollError && <p className="error">刷新失败：{pollError}</p>}
    </section>
  );
}

// 同一次等待的问题和上传位置算一份表单；轮询拿到同样的内容时不重置用户已经填的东西
function replyKey(status: ApplicationStatus): string {
  return [
    status.status,
    status.message ?? "",
    (status.questions ?? []).map((q) => q.id).join(","),
    (status.fileSlots ?? []).map((s) => s.id).join(","),
    status.round ?? "",
  ].join("|");
}

function ReplyPanel({ jobId, status, reply }: {
  jobId: string;
  status: ApplicationStatus;
  reply: (body: Omit<ReplyBody, "session">) => Promise<void>;
}) {
  const questions = status.questions ?? [];
  const slots = status.fileSlots ?? [];
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [picked, setPicked] = useState<Record<string, string>>({});
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const ready = status.status === "ready";

  const answerable = questions.filter((q) => q.fieldClass !== "C");
  const missingAnswers = answerable.filter((q) => q.required && !answers[q.id]?.trim());
  const missingFiles = slots.filter((slot) => slot.required !== false && !picked[slot.id]);

  async function send() {
    setBusy(true);
    setError(null);
    try {
      const filled = Object.fromEntries(
        answerable.filter((q) => answers[q.id]?.trim()).map((q) => [q.id, answers[q.id].trim()]),
      );
      await reply({
        ...(text.trim() ? { text: text.trim() } : {}),
        ...(Object.keys(filled).length > 0 ? { answers: filled } : {}),
        ...(slots.length > 0 ? { files: slots.map((slot) => picked[slot.id] ?? "") } : {}),
      });
    } catch (e) {
      setError(describe(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <h2>{ready ? "停在提交前：请你审阅" : status.status === "failed" ? "出错了" : "现在要你做的事"}</h2>
      {status.message && <p className="message">{status.message}</p>}
      {status.disconnected && <p className="warn small">浏览器连接断开了。准备好后回复，系统会先重新连接。</p>}
      {questions.length > 0 && <Questions questions={questions} answers={answers} setAnswers={setAnswers} />}
      {slots.length > 0 && <Files slots={slots} offered={status.assets ?? null} picked={picked} setPicked={setPicked} />}
      {ready && <Report jobId={jobId} />}
      <label>
        {ready ? "要修改时写明要改什么" : "回复 Agent（可选，例如「登录好了」）"}
        <textarea rows={3} value={text} onChange={(e) => setText(e.target.value)} />
      </label>
      <div className="row">
        <button disabled={busy || missingAnswers.length > 0 || missingFiles.length > 0 || (ready && !text.trim())}
          onClick={() => void send()}>
          {ready ? "要求修改" : status.status === "failed" ? "重试" : "回复，继续"}
        </button>
        {missingAnswers.length > 0 && <span className="muted small">还有 {missingAnswers.length} 个必填问题</span>}
        {missingFiles.length > 0 && <span className="muted small">还有 {missingFiles.length} 个上传位置没选</span>}
      </div>
      {ready && <p className="privacy">修改说明原文会记进过程日志（run-log.jsonl），不要写个人信息。</p>}
      {error && <p className="error">{error}</p>}
    </section>
  );
}

function Questions({ questions, answers, setAnswers }: {
  questions: ApplicationQuestion[];
  answers: Record<string, string>;
  setAnswers: (answers: Record<string, string>) => void;
}) {
  return (
    <>
      <p className="muted small">
        问题保留页面原文和选项。答案只用于这一次申请（写进 job.md「本次答案」）。C 级的项目不能在这里回答，请在浏览器里亲自填。
      </p>
      <ol className="questions">
        {questions.map((q) => (
          <li key={q.id}>
            <div>
              {q.page && <span className="muted small">{q.page} · </span>}
              {q.text}
              {q.required && <span className="error"> *</span>}
            </div>
            {q.fieldClass === "C" ? (
              <p className="warn small">C 级：只能由你在浏览器里亲自填，系统不读取、不记录它的值。</p>
            ) : q.options && q.options.length > 0 ? (
              <select value={answers[q.id] ?? ""} onChange={(e) => setAnswers({ ...answers, [q.id]: e.target.value })}>
                <option value="">（选一项）</option>
                {q.options.map((option) => <option key={option} value={option}>{option}</option>)}
              </select>
            ) : (
              <input value={answers[q.id] ?? ""} onChange={(e) => setAnswers({ ...answers, [q.id]: e.target.value })} />
            )}
          </li>
        ))}
      </ol>
    </>
  );
}

function Files({ slots, offered, picked, setPicked }: {
  slots: FileSlot[];
  offered: Asset[] | null;
  picked: Record<string, string>;
  setPicked: (update: (current: Record<string, string>) => Record<string, string>) => void;
}) {
  const [assets, setAssets] = useState<Asset[]>(offered ?? []);
  const [notice, setNotice] = useState<{ kind: "ok" | "warn" | "error"; text: string } | null>(null);

  const reload = useCallback(async () => {
    try {
      setAssets((await api.assets()).assets);
    } catch (e) {
      setNotice({ kind: "error", text: errorText(e) });
    }
  }, []);

  useEffect(() => {
    if (!offered || offered.length === 0) {
      void reload();
    }
  }, [offered, reload]);

  async function upload(slot: FileSlot, file: File) {
    setNotice(null);
    try {
      const result = await api.upload(file);
      await reload();
      setPicked((current) => ({ ...current, [slot.id]: result.asset.key }));
      setNotice(result.duplicate
        ? { kind: "warn", text: `${file.name}：已登记过（${result.asset.key}），已选中` }
        : { kind: "ok", text: `${file.name}：登记为 ${result.asset.key}，已选中` });
    } catch (e) {
      setNotice({ kind: "error", text: `${file.name}：${errorText(e)}` });
    }
  }

  return (
    <>
      <p className="muted small">
        每个上传位置选一个已登记的原件，或者把新文件拖到那一格。系统复制一份等字节副本去上传，Agent 只看到文件编号和文件名。
      </p>
      {slots.map((slot) => (
        <SlotPicker key={slot.id} slot={slot} assets={assets} value={picked[slot.id] ?? ""}
          onPick={(key) => setPicked((current) => ({ ...current, [slot.id]: key }))}
          onUpload={(file) => void upload(slot, file)} />
      ))}
      {notice && <p className={notice.kind}>{notice.text}</p>}
    </>
  );
}

function Report({ jobId }: { jobId: string }) {
  const [report, setReport] = useState<string | null>(null);
  const [reportError, setReportError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);

  const loadReport = useCallback(async () => {
    try {
      setReport(await api.report(jobId));
      setReportError(null);
    } catch (e) {
      setReportError(describe(e));
    } finally {
      setLoaded(true);
    }
  }, [jobId]);

  useEffect(() => {
    void loadReport();
  }, [loadReport]);

  return (
    <>
      <div className="row">
        <strong>填写报告</strong>
        <button onClick={() => void loadReport()}>重新读取</button>
      </div>
      {reportError && <p className="error">{reportError}</p>}
      {loaded && !reportError && (report === null
        ? <p className="muted">还没有报告。</p>
        : <pre className="report">{report}</pre>)}
    </>
  );
}

function SlotPicker({ slot, assets, value, onPick, onUpload }: {
  slot: FileSlot;
  assets: Asset[];
  value: string;
  onPick: (key: string) => void;
  onUpload: (file: File) => void;
}) {
  const [dragging, setDragging] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  return (
    <div className={`dropzone slot${dragging ? " dragging" : ""}`}
      onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
      onDragLeave={() => setDragging(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDragging(false);
        const file = e.dataTransfer.files[0];
        if (file) {
          onUpload(file);
        }
      }}>
      <div>
        <strong>{slot.label}</strong>
        {slot.accept && <span className="muted small">（{slot.accept}）</span>}
        {slot.note && <div className="muted small">{slot.note}</div>}
      </div>
      <div className="row">
        <select value={value} onChange={(e) => onPick(e.target.value)}>
          <option value="">（选一个原件）</option>
          {assets.map((asset) => (
            <option key={asset.key} value={asset.key}>
              {asset.key} · {asset.fileName}{asset.description ? ` · ${asset.description}` : ""}
            </option>
          ))}
        </select>
        <button onClick={() => input.current?.click()}>拖入或选择新文件</button>
        <input ref={input} type="file" hidden accept={slot.accept ?? undefined}
          onChange={(e) => { const file = e.target.files?.[0]; if (file) { onUpload(file); } e.target.value = ""; }} />
      </div>
    </div>
  );
}

// 409（线程正在运行、浏览器被别的 Job 占着）、400（例如回答了 C 级问题）都把后端的原话显示出来
function describe(error: unknown): string {
  if (error instanceof ApiError) {
    return `${error.status}：${error.message}`;
  }
  return errorText(error);
}

import { useCallback, useEffect, useRef, useState } from "react";
import {
  api, ApiError, errorText, type ApplicationQuestion, type ApplicationStatus, type Asset, type FileSlot,
  type JobDetail, type ReplyBody,
} from "../api";
import { go } from "../route";
import { APPLICATION, ChatLog, JobState, VerifiedChip } from "../status";

const POLL_MS = 2000;

const PLAN: Record<string, string> = { todo: "待做", doing: "进行中", done: "完成", blocked: "卡住" };

// 申请工作台（C005-ui.md「页面」）：左边是 Agent 每一步做了什么和它的计划，右边是现在要你做的事。
// Agent 停在最终提交前，不会替你点提交。申请线程在跑时每 2 秒查一次
export default function JobPage({ id }: { id: string }) {
  const [detail, setDetail] = useState<JobDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pollError, setPollError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setDetail(await api.job(id));
      setPollError(null);
    } catch (e) {
      setPollError(describe(e));
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  const running = detail?.application?.status === "running";
  useEffect(() => {
    if (!running) {
      return;
    }
    const timer = window.setInterval(() => void load(), POLL_MS);
    return () => window.clearInterval(timer);
  }, [running, load]);

  async function call(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await action();
      await load();
    } catch (e) {
      setError(describe(e));
    } finally {
      setBusy(false);
    }
  }

  if (!detail) {
    return pollError ? <p className="error">{pollError}</p> : <p className="muted">读取中…</p>;
  }
  const { job, application: status, steps, events } = detail;
  const plan = status?.plan ?? [];
  const session = status?.session ?? job.discoveredIn;
  const canStart = !status && (job.leadStatus === "new" || job.leadStatus === "shortlisted"
    || (job.leadStatus === "converted" && job.phase === "prep"));
  const reply = async (body: Omit<ReplyBody, "session">) => {
    if (!session) {
      throw new Error("这个申请没有 Session");
    }
    await api.reply(job.id, { session, ...body });
    await load();
  };

  return (
    <>
      <div className="top">
        <div>
          <h1>{job.company} · {job.title}</h1>
          <p>
            {[job.city.join("、"), job.type, job.site].filter(Boolean).join(" · ")}
            {job.url && <> · <a href={job.url} target="_blank" rel="noreferrer">岗位链接</a></>}
            {job.officialUrl && <> · <a href={job.officialUrl} target="_blank" rel="noreferrer">官方链接</a></>}
          </p>
        </div>
        <div className="row">
          <VerifiedChip verified={job.verified} />
          <JobState job={job} />
          <button className="linkbtn" onClick={() => go({ kind: "project", id: job.project })}>回到 Project</button>
          {job.discoveredIn && (
            <button className="linkbtn" onClick={() => go({ kind: "session", id: job.discoveredIn! })}>发现它的 Session</button>
          )}
        </div>
      </div>
      {error && <p className="error">{error}</p>}
      {pollError && <p className="error">刷新失败：{pollError}</p>}
      <div className="workbench">
        <div className="panel">
          <div className="ph">
            <h2>Agent 做了什么</h2>
            {status && <span className="muted small">{APPLICATION[status.status] ?? status.status}</span>}
          </div>
          {plan.length > 0 && (
            <>
              <strong className="small">计划</strong>
              <ol className="questions">
                {plan.map((item, index) => (
                  <li key={index}><span><span className="muted small">[{PLAN[item.status] ?? item.status}]</span> {item.text}</span></li>
                ))}
              </ol>
            </>
          )}
          <div className="chatlog">
            {steps.length === 0 ? <p className="empty">还没有开始。</p> : <ChatLog messages={steps} />}
            {running && <div className="thinking">Agent 正在做…</div>}
          </div>
          {status?.usage && (
            <div className="stats">
              <div><strong>{status.usage.toolCalls.toLocaleString()}</strong><span className="muted small">工具调用</span></div>
              <div><strong>{status.usage.modelCalls.toLocaleString()}</strong><span className="muted small">模型调用</span></div>
              <div><strong>{status.usage.tokens.toLocaleString()}</strong><span className="muted small">token</span></div>
            </div>
          )}
        </div>
        <div style={{ minWidth: 0 }}>
          {!status && (
            <section className="card">
              <h2>开始申请</h2>
              <p className="muted small">Agent 在你的 Chrome 里打开岗位页，替你填写，停在最终提交前。最终提交由你自己点。</p>
              <div className="row">
                <button className="primary" disabled={busy || !canStart || !session}
                  onClick={() => void call(() => api.startApplication(job.id, { project: job.project, session: session! }))}>
                  开始申请
                </button>
                {!session && <span className="muted small">没有可用的 Session</span>}
                {!canStart && <span className="muted small">这个岗位现在不能开始申请</span>}
              </div>
            </section>
          )}
          {status && status.status === "running" && (
            <section className="card">
              <h2>Agent 正在做</h2>
              <p className="muted small">它要你做什么时，这里会显示。</p>
              <div className="row">
                <button className="danger" disabled={busy} onClick={() => void call(() => api.stop(job.id))}>停止</button>
              </div>
            </section>
          )}
          {status && status.error && <p className="error">出错：{status.error}</p>}
          {status && (status.status === "waiting_user" || status.status === "ready" || status.status === "failed") && (
            <ReplyPanel key={replyKey(status)} jobId={job.id} status={status} reply={reply} />
          )}
          {status && status.status !== "ready" && status.report && (
            <section className="card"><Report jobId={job.id} /></section>
          )}
        </div>
      </div>
      <div className="panel">
        <h2>事件</h2>
        {events.length === 0 ? <p className="empty">没有事件。</p> : (
          <ul className="events">
            {events.map((event, index) => (
              <li key={index}>
                <time>{event.at}</time>
                {event.from ? `${event.from} → ` : ""}{event.to}
                <span className="muted small"> · {event.actor}{event.reason ? ` · ${event.reason}` : ""}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </>
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

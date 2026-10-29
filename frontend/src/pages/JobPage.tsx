import { useCallback, useEffect, useRef, useState } from "react";
import {
  api, ApiError, awaitingSubmit, errorText, type ApplicationQuestion, type ApplicationState, type ApplicationStatus,
  type Asset, type BrowserTab, type FileSlot,
  type JobDetail, type JobEventView, type ReplyBody, type ResumeChoice, type RoundView,
  type SentAttachment, type StateField,
} from "../api";
import { Markdown } from "../chat/Markdown";
import { ChatSkeleton } from "../chat/ChatSkeleton";
import { eventsUrl, subscribe } from "../chat/eventStream";
import { Composer } from "../chat/Composer";
import { Icon } from "../chat/icons";
import { MessageList, TranscriptTurns } from "../chat/MessageList";
import { readDraft, writeDraft } from "../chat/draft";
import { useAttachments } from "../chat/useAttachments";
import { useLiveUpdates } from "../chat/useLiveUpdates";
import { go } from "../route";
import {
  APPLICATION, JobState, phaseText, ROUND_CHIP, ROUND_RESULT, RoundProgress, SubmitConfirm, VerifiedChip,
} from "../status";
import { toast } from "../toast";

const PLAN: Record<string, string> = { todo: "待做", doing: "进行中", done: "完成", blocked: "卡住" };
// 申请状态里字段的状态（C019）：小标签的文字和样式
const FIELD: Record<string, [string, string]> = {
  todo: ["待填", "chip inp"], filled: ["已填", "chip"], verified: ["已核对", "chip good"],
  needs_user: ["要问你", "chip wait"], skipped: ["跳过", "chip muted"],
};
const FIELD_DONE = new Set(["filled", "verified", "skipped"]);
// 中断（应用重启前还在跑）和等你一样：回复后接着做。排队和在跑一样：能停止、不能回复
const WAITING = new Set(["waiting_user", "ready", "failed", "interrupted"]);
// 你已经提交了（等确认）：和后端 reply 的 409 同一条规则，不再回复申请 Agent。暂停的可以回复（接着做）
const USER_DECIDED = new Set(["user_submitting", "verification_pending", "unconfirmed"]);
const RESUME_SLOT = /简历|履历|resume|cv/i;

interface ReplyForm {
  key: string;
  answers: Record<string, string>;
  picked: Record<string, string>;
}

// 申请工作台（C005-ui.md「页面」，C006-fix 改成整屏）：和 Session 页同一套布局。中间是 Agent 每一步做了什么，
// 底部输入框就是回复（文字 + 附件）；右栏是现在要你做的事：问题、上传位置、进度（申请状态，C019）、报告、用量、事件。
// Agent 停在最终提交前，不会替你点提交。新内容走 /api/events?job= 推送，断了浏览器自己重连。
// 投递以后（离开 prep，C022）工作台接着是「后续」段：投递段折起来只读，下面是另一段对话，输入框发给后续段的 Agent
export default function JobPage({ id, onChanged }: { id: string; onChanged?: () => Promise<void> }) {
  const [detail, setDetail] = useState<JobDetail | null>(null);
  const [pollError, setPollError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [text, setText] = useState("");
  const [form, setForm] = useState<ReplyForm>({ key: "", answers: {}, picked: {} });
  const [panelOpen, setPanelOpen] = useState(() => window.matchMedia("(min-width: 1100px)").matches);

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

  // 「浏览器连接断开了」从全局的连接状态推出来，而 browser 事件只在全局流里：收到就重读一次。
  // reset（后端重启）后工作台可能还留着旧的上传槽，也要重读（C006-fix-real-run 第 17 条）
  useEffect(() => subscribe(eventsUrl(), (event) => {
    if (event.type === "browser" || event.type === "reset") {
      void load();
    }
  }), [load]);

  const running = detail?.application?.status === "running" || detail?.application?.status === "queued";
  const followupRunning = detail?.followup?.status === "running";
  // 推送里的 activity 只按 Job 分：投递段和后续段不会同时在跑，哪段在跑就是哪段的
  const sse = useLiveUpdates({ job: id }, running || followupRunning, load);
  const upload = useCallback((file: File) => api.jobAttachment(id, file), [id]);
  const attach = useAttachments(upload);
  const followupUpload = useCallback((file: File) => api.followupAttachment(id, file), [id]);
  const followupAttach = useAttachments(followupUpload);
  // 后续段的输入框草稿存在本地（「该查看了」的「我看了」会预先填好一句，C007）
  const draftId = `followup.${id}`;
  const [followupText, setFollowupTextState] = useState(() => readDraft(draftId));
  useEffect(() => {
    setFollowupTextState(readDraft(draftId));
  }, [draftId]);
  const setFollowupText = (value: string) => {
    setFollowupTextState(value);
    writeDraft(draftId, value);
  };

  useEffect(() => {
    document.title = detail ? `${detail.job.company} · ${detail.job.title} · JobApply` : "JobApply";
    return () => {
      document.title = "JobApply";
    };
  }, [detail?.job.company, detail?.job.title]);

  async function call(action: () => Promise<unknown>): Promise<boolean> {
    setBusy(true);
    try {
      await action();
      await load();
      return true;
    } catch (e) {
      toast(describe(e), "error");
      return false;
    } finally {
      setBusy(false);
    }
  }

  // 直接在这个工作台里启动申请线程（C021）：第一轮先做简历匹配和修改建议，用户在下面回复确认后才会打开页面填表。
  // 不再往 Session 发消息、也不再跳回 Session（和 ApplicationRunner.start 同一条路径）
  async function startWorkspace(job: JobDetail["job"], sessionId: string | null) {
    if (await call(async () => {
      let use = sessionId;
      if (!use) {
        // 记录里的 Session 都删掉了（C013）：新建一个挂在这个 Project 下
        use = (await api.createSession(job.project)).session.id;
      }
      await api.startApplication(job.id, { project: job.project, session: use });
    })) {
      await onChanged?.();
    }
  }

  if (!detail) {
    return pollError ? <p className="error pad">{pollError}</p> : <ChatSkeleton />;
  }
  const { job, application: status, steps, events } = detail;
  // 发消息用的 Session 由后端挑（记录里的 Session 删了时换成 Project 最新的一个，C013）
  const session = detail.session;
  const discoveredDeleted = !!job.discoveredIn && detail.deletedSessions.includes(job.discoveredIn);
  // 回复申请线程时记它自己的 Session：删掉了后端也接受（申请属于 Job，C013）
  const replyAs = status?.session ?? session;
  // 能不能开始由服务器判断（和后端 start 同一条规则：状态允许、有岗位链接、还没有申请线程）
  const canStart = !status && job.canStart;
  const waiting = !!status && WAITING.has(status.status);
  // 用户确认提交（或不投）以后申请不在 prep 了，就不能再「要求修改」
  const ready = status?.status === "ready" && job.phase === "prep";

  // 同一次等待算一份表单：轮询拿到同样的内容时不重置用户已经填的东西
  const formKey = status ? replyKey(status) : "";
  const questions = waiting ? status?.questions ?? [] : [];
  const slots = waiting ? status?.fileSlots ?? [] : [];
  // C008：简历的上传位置默认选中对话 Agent 排名选定的那份（只有一个上传位置时也选它），你仍可改选
  const chosen = job.resumeChoice?.asset;
  const fresh: ReplyForm = {
    key: formKey, answers: {},
    picked: chosen ? Object.fromEntries(slots.filter((slot) => slots.length === 1 || RESUME_SLOT.test(slot.label))
      .map((slot) => [slot.id, chosen])) : {},
  };
  const current: ReplyForm = form.key === formKey ? form : fresh;
  const answerable = questions.filter((q) => q.fieldClass !== "C");
  const missingAnswers = answerable.filter((q) => q.required && !current.answers[q.id]?.trim());
  const missingFiles = slots.filter((slot) => slot.required !== false && !current.picked[slot.id]);
  // 上传位置可以选的附件：这个申请已经存下的 + 输入框里刚传好的
  const attachable = mergeAttachments(status?.attachments ?? [], attach.ready.map((file) => ({ id: file.id, name: file.name })));

  const lockedReason = !status
    ? "还没有开始申请"
    : job.phase !== "prep" || USER_DECIDED.has(job.prepState ?? "")
      ? `这个申请现在是「${job.label}」，你已经定下了，不用再回复申请 Agent`
    : running || waiting ? null : "现在不需要回复";
  // 挡住发送的只有这一条；必填问题没答、上传位置空着都不挡，Agent 会再问（C006-fix 第 5 节、第 6 节）
  const hints = [
    ready && !text.trim() && "要求修改时写明要改什么",
  ].filter(Boolean) as string[];
  const softHints = [
    missingAnswers.length > 0 && `还有 ${missingAnswers.length} 个必填问题没答，Agent 会再问`,
    missingFiles.length > 0 && `还有 ${missingFiles.length} 个必填上传位置没选，Agent 会再问`,
  ].filter(Boolean) as string[];

  async function send() {
    if (!status || !replyAs) {
      toast("这个申请没有 Session", "error");
      return;
    }
    const filled = Object.fromEntries(
      answerable.filter((q) => current.answers[q.id]?.trim()).map((q) => [q.id, current.answers[q.id].trim()]),
    );
    if (running) {
      // 运行中插话（C010）：只发这一句，附件留在输入框里等它停下再发
      if (await call(() => api.reply(job.id, { session: replyAs, text: text.trim() }))) {
        setText("");
      }
      return;
    }
    const files = attach.ready.map((file) => file.id);
    const body: ReplyBody = {
      session: replyAs,
      ...(text.trim() ? { text: text.trim() } : {}),
      ...(Object.keys(filled).length > 0 ? { answers: filled } : {}),
      ...(status.status === "waiting_user" && slots.length > 0
        ? { files: slots.map((slot) => current.picked[slot.id] ?? "") }
        : {}),
      ...(files.length > 0 ? { attachments: files } : {}),
    };
    if (await call(() => api.reply(job.id, body))) {
      setText("");
      attach.clear();
    }
  }

  const followup = detail.followup ?? null;

  async function sendFollowup() {
    const files = followupAttach.ready.map((file) => file.id);
    if (await call(() => api.followupMessage(job.id, followupText.trim(), files))) {
      setFollowupText("");
      followupAttach.clear();
    }
  }

  return (
    <div className="chatpage">
      <div className="chatmain">
        <header className="chathead">
          <div className="chathead-t">
            <h1>{job.company} · {job.title}</h1>
            <span className="muted small headmeta">
              {[job.city.join("、"), job.type, job.site].filter(Boolean).join(" · ")}
              {followup && <span title="投递以后，工作台接着是后续段">阶段：{phaseText(job)}</span>}
              {job.resumeChoice?.file && <span title="对话 Agent 按这个岗位排名选定的简历">拟用简历：{job.resumeChoice.file}</span>}
              {job.url && <a href={job.url} target="_blank" rel="noreferrer">岗位链接</a>}
              {job.officialUrl && <a href={job.officialUrl} target="_blank" rel="noreferrer">官方链接</a>}
              {sse.reconnecting && <span className="reconnecting" title="和后端的推送断开了，正在自动重连">重新连接中…</span>}
              <button className="linkbtn" onClick={() => go({ kind: "project", id: job.project })}>回到 Project</button>
              {job.discoveredIn && !discoveredDeleted && (
                <button className="linkbtn" onClick={() => go({ kind: "session", id: job.discoveredIn! })}>发现它的 Session</button>
              )}
              {discoveredDeleted && <span title={`发现它的 Session（${job.discoveredIn}）已经删除，岗位留着`}>会话已删除</span>}
              {job.phase !== "closed" && job.leadStatus !== "closed" && job.leadStatus !== "skipped" && (
                <button className="linkbtn" title="岗位已下线 / 停止招聘"
                  onClick={() => void call(() => api.postingClosed(job.id))}>岗位下线了</button>
              )}
            </span>
          </div>
          <div className="row nowrap">
            <VerifiedChip verified={job.verified} />
            <JobState job={job} />
            {!status && (
              <button className="primary" disabled={busy || !canStart}
                title={!canStart ? "这个岗位现在不能开始申请" : undefined}
                onClick={() => void startWorkspace(job, session)}>
                开始申请
              </button>
            )}
            <button className={`iconbtn labeled${waiting && !panelOpen ? " attn" : ""}`} aria-expanded={panelOpen}
              onClick={() => setPanelOpen(!panelOpen)} title={panelOpen ? "收起右栏" : "展开右栏"}>
              <Icon name="panel" size={15} /> 要你做的事
            </button>
          </div>
        </header>
        {pollError && <p className="error small pad">刷新失败：{pollError}</p>}
        {followup ? (
          <>
            <MessageList messages={followup.messages} running={followupRunning} jobs={[job]}
              inserted={followup.inserted ?? []} error={followup.error}
              activity={sse.activity !== undefined ? sse.activity : followup.activity}
              onStop={() => void call(() => api.stopFollowup(job.id))}
              before={<FillSegment steps={steps} job={job} />}
              empty={
                <p className="muted small">现在是「{phaseText(job)}」。在这里接着聊：收到的笔试 / OA 题、面试准备、面完的情况。
                  Agent 会把轮次和复盘记下来，题目进题库，别的工作台以后也用得上。</p>
              } />
            <div className="composerwrap">
              <Composer value={followupText} onChange={setFollowupText} running={followupRunning} disabled={busy}
                attach={followupAttach}
                onSend={() => void sendFollowup()}
                onStop={() => void call(() => api.stopFollowup(job.id))}
                onInterrupt={() => void call(async () => {
                  await api.stopFollowup(job.id);
                  await api.followupMessage(job.id, followupText.trim());
                  setFollowupText("");
                })}
                placeholder={followupRunning ? "Agent 正在做，可以随时插话，它下一步会看到"
                  : "说说进展（「收到 OA，周五截止」「一面问了…」），贴题目，或者让它帮你准备面试；可以附文件和截图"} />
            </div>
          </>
        ) : (
        <>
        <MessageList messages={steps} running={running} jobs={[job]} inserted={status?.inserted ?? []}
          activity={sse.activity !== undefined ? sse.activity : detail.activity}
          onStop={() => void call(() => api.stop(job.id))}
          empty={
            <div className="emptychat">
              <h2>还没有开始申请</h2>
              <p className="muted">点上面的「开始申请」，Agent 先在这个工作台里匹配简历、给出修改建议，你确认后再打开岗位页填表，停在最终提交前。最终提交由你自己点。</p>
              {!status && !canStart && <p className="muted small">这个岗位现在不能开始申请</p>}
            </div>
          } />
        <div className="composerwrap">
          {waiting && hints.length + softHints.length > 0 && (
            <p className="composerhint">{[...hints, ...softHints].join(" · ")}</p>
          )}
          <Composer value={text} onChange={setText} running={running} disabled={busy}
            attach={attach} allowEmpty={!ready && !running} blocked={hints.length > 0} lockedReason={lockedReason}
            sendLabel={ready ? "要求修改" : status?.status === "failed" ? "重试" : "回复，继续"}
            onSend={() => void send()}
            onStop={() => void call(() => api.stop(job.id))}
            onInterrupt={replyAs ? () => void call(async () => {
              await api.stop(job.id);
              await api.reply(job.id, { session: replyAs, text: text.trim() });
              setText("");
            }) : undefined}
            placeholder={running ? "Agent 正在做，可以随时插话（例如「先别填这个，改成…」），它下一步会看到" : ready ? "要修改时写明要改什么" : "回复 Agent（可选，例如「登录好了」），可以附文件和截图"} />
          {ready && <p className="privacy composerhint">修改说明原文会记进过程日志（run-log.jsonl），不要写个人信息。</p>}
        </div>
        </>
        )}
      </div>
      {panelOpen && (
        <aside className="jobsside" aria-label="现在要你做的事">
          <div className="ph">
            <h2>{ready ? "停在提交前：请你审阅" : status?.status === "failed" ? "出错了" : "现在要你做的事"}</h2>
            <button className="iconbtn" aria-label="收起" title="收起" onClick={() => setPanelOpen(false)}>
              <Icon name="cross" size={14} />
            </button>
          </div>
          <StatusNote status={status} />
          {awaitingSubmit(job) && !running && <SubmitConfirm job={job} onDone={load} />}
          {questions.length > 0 && (
            <Questions questions={questions} answers={current.answers}
              setAnswers={(answers) => setForm({ ...current, answers })} />
          )}
          {slots.length > 0 && (
            <Files slots={slots} offered={status?.assets ?? null} attachments={attachable} picked={current.picked}
              setPicked={(update) => setForm((old) => {
                const base = old.key === formKey ? old : fresh;
                return { ...base, picked: update(base.picked) };
              })} />
          )}
          <Rounds jobId={job.id} rounds={job.rounds ?? []} />
          <ResumeRank choice={job.resumeChoice ?? null} />
          <Progress state={status?.state ?? null} />
          {status && <HandTab jobId={job.id} />}
          {status && (ready || status.report) && (
            <section className="sidesec">
              <Report jobId={job.id} version={`${status.status}:${status.usage?.toolCalls ?? 0}:${status.round ?? 1}`} />
            </section>
          )}
          {status?.usage && (
            <div className="stats">
              <div><strong>{status.usage.toolCalls.toLocaleString()}</strong><span className="muted small">工具调用</span></div>
              <div><strong>{status.usage.modelCalls.toLocaleString()}</strong><span className="muted small">模型调用</span></div>
              <div><strong>{status.usage.tokens.toLocaleString()}</strong><span className="muted small">token</span></div>
            </div>
          )}
          <Events events={events} />
        </aside>
      )}
    </div>
  );
}

// 后续段上面的投递段（C022）：只读，默认折起来；下面一条分隔线「已投递 · 后续」
function FillSegment({ steps, job }: { steps: JobDetail["steps"]; job: JobDetail["job"] }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      {steps.length > 0 && (
        <section className={`fillsegment${open ? " open" : ""}`}>
          <button className="textbtn" aria-expanded={open} onClick={() => setOpen(!open)}>
            <Icon name="down" size={13} /> {open ? "收起投递过程" : `投递过程（${steps.length} 条，只读）`}
          </button>
          {open && <TranscriptTurns messages={steps} jobs={[job]} />}
        </section>
      )}
      <div className="segdivider"><span>已投递 · 后续</span></div>
    </>
  );
}

function StatusNote({ status }: { status: ApplicationStatus | null }) {
  if (!status) {
    return <p className="muted small">还没有申请。点上面的「开始申请」，Agent 先在工作台里匹配简历并给出修改建议，你确认后再打开岗位页填表。</p>;
  }
  return (
    <>
      <p className="muted small">状态：{APPLICATION[status.status] ?? status.status}</p>
      {status.status === "running" && <p className="muted small">Agent 正在做。它要你做什么时，这里会显示。</p>}
      {status.message && <p className="message">{status.message}</p>}
      {status.error && <p className="error">出错：{status.error}</p>}
      {status.disconnected && <p className="warn small">浏览器连接断开了。准备好后回复，系统会先重新连接。</p>}
      {WAITING.has(status.status) && <p className="muted small">在下面的输入框回复；这里的回答和文件选择会一起发出。</p>}
    </>
  );
}

// 进度（C019）：申请状态的现在在哪一步、计划、按区域的字段清单。每轮存盘后推送 changed，跟着刷新。不显示值
function Progress({ state }: { state: ApplicationState | null }) {
  const steps = state?.steps ?? [];
  const fields = state?.fields ?? [];
  const notes = state?.notes ?? [];
  if (!state?.stage && steps.length === 0 && fields.length === 0) {
    return null;
  }
  const done = fields.filter((f) => FIELD_DONE.has(f.status ?? "")).length;
  const asking = fields.filter((f) => f.status === "needs_user").length;
  const sections = new Map<string, StateField[]>();
  for (const field of fields) {
    const name = field.section || "其它";
    sections.set(name, [...(sections.get(name) ?? []), field]);
  }
  return (
    <section className="sidesec progress">
      <strong className="small">进度</strong>
      {state?.stage && <p className="small">现在在：{state.stage}</p>}
      {fields.length > 0 && (
        <p className="muted small">字段 {done}/{fields.length} 好了{asking > 0 && <> · <span className="warn">要问你 {asking}</span></>}</p>
      )}
      {steps.length > 0 && (
        <ol className="questions">
          {steps.map((item, index) => (
            <li key={index}><span><span className="muted small">[{PLAN[item.status] ?? item.status}]</span> {item.text}</span></li>
          ))}
        </ol>
      )}
      {[...sections.entries()].map(([name, list]) => {
        const left = list.filter((f) => !FIELD_DONE.has(f.status ?? "")).length;
        return (
          <details key={name} open={left > 0}>
            <summary className="small">{name} <span className="muted">{list.length - left}/{list.length}</span></summary>
            <ul className="checklist">
              {list.map((field) => {
                const [text, cls] = FIELD[field.status ?? "todo"] ?? [field.status ?? "", "chip"];
                return (
                  <li key={field.id}>
                    <span className={cls}>{text}</span> <span>{field.label}</span>
                    {field.note && <span className="muted small"> · {field.note}</span>}
                  </li>
                );
              })}
            </ul>
          </details>
        );
      })}
      {notes.length > 0 && (
        <details>
          <summary className="small">决定和站点规律 <span className="muted">{notes.length}</span></summary>
          <ul className="checklist">{notes.map((note, index) => <li key={index} className="small">{note}</li>)}</ul>
        </details>
      )}
    </section>
  );
}

// 轮次与复盘（C014-A）：后续段的 Agent 按你说的记下（C022）；写过复盘的点开看全文
function Rounds({ jobId, rounds }: { jobId: string; rounds: RoundView[] }) {
  const [open, setOpen] = useState<number | null>(null);
  const [review, setReview] = useState<string | null>(null);
  const [reviewError, setReviewError] = useState<string | null>(null);
  if (rounds.length === 0) {
    return null;
  }
  async function toggle(seq: number) {
    if (open === seq) {
      setOpen(null);
      return;
    }
    setOpen(seq);
    setReview(null);
    setReviewError(null);
    try {
      // 页面上只看正文，frontmatter 不显示
      setReview((await api.roundReview(jobId, seq)).replace(/^---\n[\s\S]*?\n---\n/, ""));
    } catch (e) {
      setReviewError(describe(e));
    }
  }
  return (
    <section className="sidesec">
      <strong className="small">轮次</strong> <span className="small"><RoundProgress rounds={rounds} /></span>
      <ol className="questions">
        {rounds.map((r) => {
          const when = (r.happenedAt ?? r.scheduledAt)?.slice(0, 16).replace("T", " ");
          return (
            <li key={r.seq}>
              <span>
                <strong>{r.seq}. {r.name}</strong>{" "}
                <span className={`chip ${ROUND_CHIP[r.result] ?? ""}`}>{ROUND_RESULT[r.result] ?? r.result}</span>
                <br />
                <span className="muted small">{[r.type, when, r.format, r.interviewerRole].filter(Boolean).join(" · ")}</span>
                {r.review && (
                  <>
                    <br />
                    <button className="linkbtn" onClick={() => void toggle(r.seq)}>
                      {open === r.seq ? "收起复盘" : `复盘（${r.questionCount} 道题）`}
                    </button>
                  </>
                )}
                {open === r.seq && (reviewError
                  ? <p className="error small">{reviewError}</p>
                  : review === null ? <p className="muted small">读取中…</p> : <Markdown text={review} />)}
              </span>
            </li>
          );
        })}
      </ol>
    </section>
  );
}

// 简历排名（C008）：对话 Agent 开始申请前按这个岗位排的名次、理由、缺口和修改建议
function ResumeRank({ choice }: { choice: ResumeChoice | null }) {
  if (!choice) {
    return null;
  }
  const day = choice.at.slice(0, 16).replace("T", " ");
  return (
    <section className="sidesec">
      <strong className="small">简历排名</strong> <span className="muted small">{day}</span>
      {choice.skipped ? <p className="muted small">没有排：{choice.skipped}</p> : (
        <>
          <ol className="questions">
            {(choice.ranking ?? []).map((row) => (
              <li key={row.key}>
                <span>
                  <strong>{row.rank}. {row.file}</strong>{row.key === choice.asset && <span className="muted small">（拟用）</span>}
                  <br /><span className="small">{row.reason}</span>
                  {row.gap && <><br /><span className="muted small">缺口：{row.gap}</span></>}
                </span>
              </li>
            ))}
          </ol>
          {(choice.suggestions ?? []).length > 0 && (
            <>
              <strong className="small">修改建议</strong>
              <ul className="questions">
                {choice.suggestions!.map((text, index) => <li key={index}><span className="small">{text}</span></li>)}
              </ul>
            </>
          )}
        </>
      )}
    </section>
  );
}

// 把一个标签页交给 Agent（C006-fix-browser 第 2 节）：Agent 只看得到自己的标签页；你自己开着的页面，在这里选了才交给它。
// 列表只在你点开时读一次，只给你看标题和网址，不发给模型
function HandTab({ jobId }: { jobId: string }) {
  const [tabs, setTabs] = useState<BrowserTab[] | null>(null);
  const [picked, setPicked] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const list = async () => {
    setBusy(true);
    try {
      const result = await api.freeTabs();
      setTabs(result.tabs);
      setPicked("");
      setError(null);
    } catch (e) {
      // 浏览器断开（503）：后端的原话就是要做什么（先点顶部的「重新连接」），不带状态码
      setTabs(null);
      setError(e instanceof ApiError && e.status === 503 ? e.message : describe(e));
    } finally {
      setBusy(false);
    }
  };

  const hand = async () => {
    const tab = tabs?.find((t) => String(t.index) === picked);
    if (!tab) {
      return;
    }
    setBusy(true);
    try {
      await api.handOverTab(jobId, tab);
      toast(`已把标签页「${tab.title || tab.url}」交给 Agent，回复后它会切过去`);
      setTabs(null);
    } catch (e) {
      toast(describe(e), "error");
      void list();
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="sidesec">
      <div className="row">
        <strong className="small">把一个标签页交给 Agent</strong>
        <button disabled={busy} onClick={() => void (tabs ? setTabs(null) : list())}>{tabs ? "收起" : "选标签页"}</button>
      </div>
      {!tabs && <p className="muted small">Agent 只碰它自己开的标签页。你自己开着的页面（比如已经填了一半的申请）想让它接着用，在这里交给它。</p>}
      {error && <p className="error">{error}</p>}
      {tabs && (tabs.length === 0 ? <p className="empty">Chrome 里没有可以交的标签页。</p> : (
        <>
          <select value={picked} onChange={(e) => setPicked(e.target.value)} aria-label="要交给 Agent 的标签页">
            <option value="">选一个标签页…</option>
            {tabs.map((tab) => (
              <option key={tab.index} value={String(tab.index)}>{tab.title || "（无标题）"} · {tab.url}</option>
            ))}
          </select>
          <div className="row">
            <button className="primary" disabled={busy || picked === ""} onClick={() => void hand()}>交给 Agent</button>
            <button disabled={busy} onClick={() => void list()}>刷新</button>
          </div>
        </>
      ))}
    </section>
  );
}

function Events({ events }: { events: JobEventView[] }) {
  return (
    <section className="sidesec">
      <strong className="small">事件</strong>
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

type SlotAttachment = Pick<SentAttachment, "id" | "name">;

function mergeAttachments(...lists: SlotAttachment[][]): SlotAttachment[] {
  const seen = new Set<string>();
  return lists.flat().filter((file) => !seen.has(file.id) && !!seen.add(file.id));
}

function Files({ slots, offered, attachments, picked, setPicked }: {
  slots: FileSlot[];
  offered: Asset[] | null;
  attachments: SlotAttachment[];
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
        每个上传位置可以选一个已登记的原件、这次附的文件，或者把新文件拖到那一格；也可以不选，直接在下面附文件。
        系统复制一份等字节副本去上传，Agent 只看到文件编号和文件名。
      </p>
      {slots.map((slot) => (
        <SlotPicker key={slot.id} slot={slot} assets={assets} attachments={attachments} value={picked[slot.id] ?? ""}
          onPick={(key) => setPicked((current) => ({ ...current, [slot.id]: key }))}
          onUpload={(file) => void upload(slot, file)} />
      ))}
      {notice && <p className={notice.kind}>{notice.text}</p>}
    </>
  );
}

function Report({ jobId, version }: { jobId: string; version: string }) {
  const [report, setReport] = useState<string | null>(null);
  const [reportError, setReportError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [raw, setRaw] = useState(false);

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
  }, [loadReport, version]);

  return (
    <>
      <div className="row">
        <strong>填写报告</strong>
        <button onClick={() => void loadReport()}>重新读取</button>
        {report && (
          <button onClick={() => setRaw((on) => !on)}>{raw ? "表格视图" : "查看原文"}</button>
        )}
      </div>
      {reportError && <p className="error">{reportError}</p>}
      {loaded && !reportError && (report === null
        ? <p className="muted">还没有报告。</p>
        : raw ? <pre className="report">{report}</pre> : <Markdown text={report} />)}
    </>
  );
}

function SlotPicker({ slot, assets, attachments, value, onPick, onUpload }: {
  slot: FileSlot;
  assets: Asset[];
  attachments: SlotAttachment[];
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
          <option value="">（不选）</option>
          {attachments.length > 0 && (
            <optgroup label="这次附的文件">
              {attachments.map((file) => (
                <option key={file.id} value={`attachment:${file.id}`}>{file.name}</option>
              ))}
            </optgroup>
          )}
          <optgroup label="资料库原件">
            {assets.map((asset) => (
              <option key={asset.key} value={asset.key}>
                {asset.key} · {asset.fileName}{asset.description ? ` · ${asset.description}` : ""}
              </option>
            ))}
          </optgroup>
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

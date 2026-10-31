import { useCallback, useEffect, useState } from "react";
import { api, ApiError, awaitingSubmit, errorText, type Attachment, type ChatMessage, type JobView, type SentAttachment, type SessionView } from "../api";
import { ChatSkeleton } from "../chat/ChatSkeleton";
import { Composer } from "../chat/Composer";
import { readDraft, writeDraft } from "../chat/draft";
import { Icon } from "../chat/icons";
import { MessageList } from "../chat/MessageList";
import { useAttachments } from "../chat/useAttachments";
import { useLiveUpdates } from "../chat/useLiveUpdates";
import { go } from "../route";
import { RenameTitle } from "../RenameTitle";
import { ResizablePanel } from "../ResizeHandle";
import { JobState, SubmitConfirm } from "../status";
import { toast } from "../toast";

// 对话视图（C006-front）：整屏高度，中间是消息流，底部固定输入框，右侧是这个 Session 涉及的岗位（可收起）
export default function SessionPage({ id, onChanged }: { id: string; onChanged?: () => Promise<void> }) {
  const [view, setView] = useState<SessionView | null>(null);
  const [error, setError] = useState<string | null>(null);
  // 这个 Session 删掉了（或者从来没有）：404（C013）
  const [gone, setGone] = useState(false);
  const [text, setText] = useState(() => readDraft(id));
  const [pending, setPending] = useState<{ text: string; failed: boolean; attachments: SentAttachment[] } | null>(null);
  const [jobsOpen, setJobsOpen] = useState(() => window.matchMedia("(min-width: 1100px)").matches);

  const load = useCallback(async () => {
    try {
      setView(await api.session(id));
      setError(null);
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) {
        setGone(true);
      }
      setError(errorText(e));
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  const running = view?.status === "running";
  const sse = useLiveUpdates({ session: id }, running, load);
  const upload = useCallback((file: File) => api.sessionAttachment(id, file), [id]);
  const attach = useAttachments(upload);

  useEffect(() => {
    document.title = view ? `${view.session.title} · JobApply` : "JobApply";
    return () => {
      document.title = "JobApply";
    };
  }, [view?.session.title]);

  function edit(next: string) {
    setText(next);
    writeDraft(id, next);
  }

  // 附件已经上传好，这里只带编号；重发、重试时沿用那一条的附件
  async function send(message: string, files: SentAttachment[], fromComposer: boolean) {
    const body = message.trim();
    if (!body && files.length === 0) {
      return;
    }
    setPending({ text: body, failed: false, attachments: files });
    if (fromComposer) {
      edit("");
      attach.clear();
    }
    try {
      setView(await api.sendMessage(id, body, files.map((f) => f.id)));
      setPending(null);
    } catch (e) {
      setPending({ text: body, failed: true, attachments: files });
      toast(describe(e), "error");
    }
  }

  // 打断并发送（C010）：先请这次运行停下，再发；后端跑完时把还没看到的这条接着跑
  async function interrupt() {
    try {
      await api.stopSession(id);
    } catch (e) {
      toast(describe(e), "error");
      return;
    }
    await send(text, attach.ready.map(sent), true);
  }

  const sent = (file: Attachment): SentAttachment => ({
    id: file.id, name: file.name, contentType: file.contentType, image: file.image,
    url: api.sessionAttachmentUrl(id, file.id),
  });

  async function act(action: () => Promise<SessionView>) {
    try {
      setView(await action());
    } catch (e) {
      toast(describe(e), "error");
    }
  }

  if (gone) {
    return (
      <div className="pad">
        <h2>会话已删除</h2>
        <p className="muted">这个 Session 已经删除（挪进了 storage/.trash/），或者不存在。它发现的岗位还在 Project 里。</p>
        <button className="linkbtn" onClick={() => go({ kind: "global" })}>回到全局</button>
      </div>
    );
  }
  if (!view) {
    return error ? <p className="error pad">{error}</p> : <ChatSkeleton />;
  }
  const lastUser = [...view.messages].reverse()
    .find((m): m is Extract<ChatMessage, { role: "user" }> => m.role === "user") ?? null;
  const failure = view.status === "interrupted" ? view.error ?? "上一轮没跑完就中断了。" : view.error;

  return (
    <div className="chatpage">
      <div className="chatmain">
        <header className="chathead">
          <div className="chathead-t">
            <RenameTitle value={view.session.title}
              onSave={async (title) => setView(await api.renameSession(id, title))} />
            <span className="muted small">
              {view.session.project}
              {view.session.tags.map((tag) => <span key={tag} className={`chip tagc ${tag}`}>{tag}</span>)}
              {sse.reconnecting && <span className="reconnecting" title="和后端的推送断开了，正在自动重连">重新连接中…</span>}
            </span>
          </div>
          <button className="iconbtn labeled" aria-expanded={jobsOpen} onClick={() => setJobsOpen(!jobsOpen)}
            title={jobsOpen ? "收起岗位面板" : "展开岗位面板"}>
            <Icon name="panel" size={15} /> 岗位 {view.jobs.length}
          </button>
        </header>
        {error && <p className="error small pad">刷新失败：{error}</p>}
        <MessageList messages={view.messages} running={running} jobs={view.jobs}
          activity={sse.activity !== undefined ? sse.activity : view.activity}
          pending={pending}
          inserted={view.inserted ?? []}
          onResend={pending ? () => void send(pending.text, pending.attachments, false) : undefined}
          error={failure}
          onRetry={lastUser ? () => void send(lastUser.text, lastUser.attachments ?? [], false) : undefined}
          onRegenerate={() => void act(() => api.regenerate(id))}
          empty={
            // 新建的 Session 是空白对话（C005-fix 开场）：一行固定提示，项目状态看 Project 主页
            <div className="emptychat">
              <p className="muted">说说想找什么岗位、投哪几个，或者更新一下面试情况。</p>
            </div>
          } />
        <div className="composerwrap">
          {view.jobs.filter(awaitingSubmit).map((job) => <SubmitConfirm key={job.id} job={job} onDone={load} />)}
          <Composer value={text} onChange={edit} running={running} disabled={!!pending && !pending.failed}
            attach={attach}
            onSend={() => void send(text, attach.ready.map(sent), true)}
            onStop={() => void act(() => api.stopSession(id))}
            onInterrupt={() => void interrupt()}
            placeholder="例如：按我的简历找上海的后端校招 / 核验一下第一个 / 投这两个" />
        </div>
      </div>
      {jobsOpen && <JobsPanel jobs={view.jobs} session={id} onClose={() => setJobsOpen(false)} onChanged={onChanged} />}
    </div>
  );
}

function JobsPanel({ jobs, session, onClose, onChanged }: {
  jobs: JobView[];
  session: string;
  onClose: () => void;
  onChanged?: () => Promise<void>;
}) {
  // 正在启动工作台的岗位（C021）：按钮显示「启动中…」，避免点两下
  const [starting, setStarting] = useState<string | null>(null);

  // 直接在这个岗位自己的工作台里启动 Agent（C021）：第一轮先做简历匹配与修改建议，不会再往 Session 发消息
  async function startWorkspace(job: JobView) {
    setStarting(job.id);
    try {
      await api.startApplication(job.id, { project: job.project, session });
      await onChanged?.();
      go({ kind: "job", id: job.id });
    } catch (e) {
      // 400 / 409（已经有申请线程、没有岗位链接等）照后端原话显示
      toast(describe(e), "error");
    } finally {
      setStarting(null);
    }
  }

  return (
    <ResizablePanel storageKey="jobapply.sessionPanel.width" label="这个 Session 涉及的岗位">
      <div className="ph">
        <h2>这个 Session 涉及的岗位</h2>
        <button className="iconbtn" aria-label="收起" title="收起" onClick={onClose}><Icon name="cross" size={14} /></button>
      </div>
      {jobs.length === 0
        ? <p className="empty">还没有涉及任何岗位。</p>
        : (
          <div className="list">
            {jobs.map((job) => (
              <div key={job.id} className="li">
                <div className="t">
                  <div>{job.company} · {job.title}</div>
                  <small>{job.city.join("、")}</small>
                </div>
                <div className="acts">
                  <JobState job={job} compact />
                  {job.application != null || job.leadStatus === "converted"
                    ? (
                      <button className="btn-xs" onClick={() => go({ kind: "job", id: job.id })}>打开工作台</button>
                    )
                    : (
                      <>
                        {/* 计划要求保留「详情」入口：还没开始投递、或者这个岗位现在不能开始（没有链接等）时也能打开它的页面 */}
                        <button className="btn-xs ghost" onClick={() => go({ kind: "job", id: job.id })}>详情</button>
                        {job.canStart && (
                          <button className="btn-xs primary" disabled={starting === job.id}
                            onClick={() => void startWorkspace(job)}>
                            {starting === job.id ? "启动中…" : "开始投递"}
                          </button>
                        )}
                      </>
                    )}
                </div>
              </div>
            ))}
          </div>
        )}
    </ResizablePanel>
  );
}

function describe(error: unknown): string {
  return error instanceof ApiError ? `${error.status}：${error.message}` : errorText(error);
}

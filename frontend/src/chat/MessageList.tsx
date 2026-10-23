import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import type { Activity, ChatMessage, JobView, SentAttachment } from "../api";
import { CopyButton } from "./CopyButton";
import { Icon, Spinner } from "./icons";
import { Markdown } from "./Markdown";
import { ProcessBlock } from "./ProcessBlock";
import { activityText, elapsed, fallbackActivity, shortUrl, sourcesOf, toTurns, type Turn } from "./process";

// 消息流：Session 页和申请工作台共用。自己负责滚动：在底部时跟着新内容滚，往上翻时不打扰，出现「回到底部」
export interface MessageListProps {
  messages: ChatMessage[];
  running: boolean;
  // undefined：后端没给 activity，退回「最后一个还没结果的工具」
  activity?: Activity | null;
  jobs?: JobView[];
  // 刚发出、后端还没确认的那条用户消息
  pending?: { text: string; failed: boolean; attachments?: SentAttachment[] } | null;
  onResend?: () => void;
  error?: string | null;
  onRetry?: () => void;
  onRegenerate?: () => void;
  onStop?: () => void;
  empty?: ReactNode;
}

export function MessageList(props: MessageListProps) {
  const { messages, running, activity, jobs = [], pending, error, empty } = props;
  const turns = toTurns(messages, running);
  const scroller = useRef<HTMLDivElement>(null);
  const [stick, setStick] = useState(true);
  const last = turns[turns.length - 1];
  const live = activity === undefined ? fallbackActivity(running ? last : undefined) : activity;

  // 内容变了（新消息、新步骤、状态）时，如果原本在底部就滚到底
  const signature = [messages.length, last?.entries.length ?? 0, last?.reply?.length ?? 0, pending?.text ?? "",
    pending?.failed ?? "", running, error ?? "", activityText(live)].join("|");
  useLayoutEffect(() => {
    const el = scroller.current;
    if (el && stick) {
      el.scrollTop = el.scrollHeight;
    }
  }, [signature, stick]);

  function onScroll() {
    const el = scroller.current;
    if (el) {
      setStick(el.scrollHeight - el.scrollTop - el.clientHeight < 80);
    }
  }

  function toBottom() {
    const el = scroller.current;
    el?.scrollTo({ top: el.scrollHeight, behavior: "smooth" });
    setStick(true);
  }

  const nothing = turns.length === 0 && !pending && !running;
  return (
    <div className="msgscroll" ref={scroller} onScroll={onScroll}>
      <div className="msgcol">
        {nothing && empty}
        {turns.map((turn, i) => (
          <TurnView key={turn.key} turn={turn} jobs={jobs} live={running && i === turns.length - 1}
            last={i === turns.length - 1 && !pending}
            onRegenerate={!running && i === turns.length - 1 && !pending && turn.user ? props.onRegenerate : undefined} />
        ))}
        {pending && (
          <div className={`umsg${pending.failed ? " failed" : ""}`}>
            {pending.text && <div className="bubble">{pending.text}</div>}
            <SentFiles files={pending.attachments ?? []} />
            {pending.failed && (
              <div className="msgerror">
                没发出去。
                {props.onResend && <button className="textbtn" onClick={props.onResend}><Icon name="retry" size={13} /> 重发</button>}
              </div>
            )}
          </div>
        )}
        {(running || (pending && !pending.failed)) && (
          <LiveStatus activity={live} since={live?.since ?? lastUserAt(messages)} onStop={props.onStop} />
        )}
        {error && !running && (
          <div className="msgerror block">
            <span>出错：{error}</span>
            {props.onRetry && <button className="textbtn" onClick={props.onRetry}><Icon name="retry" size={13} /> 重试</button>}
          </div>
        )}
      </div>
      {!stick && (
        <button className="tobottom" onClick={toBottom}><Icon name="down" size={14} /> 回到底部</button>
      )}
    </div>
  );
}

function TurnView({ turn, jobs, live, last, onRegenerate }: {
  turn: Turn;
  jobs: JobView[];
  live: boolean;
  last: boolean;
  onRegenerate?: () => void;
}) {
  const sources = sourcesOf(turn);
  const reply = turn.reply;
  // ask_user 的原话在回复末尾时单独标出来；拿不到原话时整条回复就是提问
  let lead = reply;
  let ask: string | null = null;
  if (reply && turn.asked) {
    if (turn.ask && reply.trimEnd().endsWith(turn.ask)) {
      lead = reply.trimEnd().slice(0, -turn.ask.length).trim() || null;
      ask = turn.ask;
    } else {
      lead = null;
      ask = reply;
    }
  }
  return (
    <>
      {turn.user && (
        <div className="umsg">
          {turn.user.text && <div className="bubble">{turn.user.text}</div>}
          <SentFiles files={turn.user.attachments} />
          <div className="msgacts">
            <Time at={turn.user.at} />
            {turn.user.text && <CopyButton text={turn.user.text} />}
          </div>
        </div>
      )}
      {(turn.entries.length > 0 || reply) && (
        <div className={`amsg${last ? " last" : ""}`}>
          <ProcessBlock turn={turn} live={live} jobs={jobs} />
          {lead && <Markdown text={lead} />}
          {ask && (
            <div className="askblock">
              <div className="askblock-h"><Icon name="ask" size={14} /> 需要你回复</div>
              <Markdown text={ask} />
            </div>
          )}
          {sources.length > 0 && !live && (
            <div className="sources">
              {sources.map((url) => (
                <a key={url} className="source" href={url} target="_blank" rel="noopener noreferrer" title={url}>
                  <Icon name="web" size={12} /> {shortUrl(url)}
                </a>
              ))}
            </div>
          )}
          {!live && reply && (
            <div className="msgacts">
              <CopyButton text={reply} />
              <Time at={turn.replyAt} />
              {onRegenerate && (
                <>
                  <button className="textbtn" title="已经做过的操作不会撤销" onClick={onRegenerate}>
                    <Icon name="retry" size={13} /> 重新生成
                  </button>
                  <span className="muted small">已经做过的操作不会撤销</span>
                </>
              )}
            </div>
          )}
        </div>
      )}
    </>
  );
}

// 用户消息下面的附件：图片是缩略图，其它是一行文件名；点开新标签页看原件
function SentFiles({ files }: { files: SentAttachment[] }) {
  if (files.length === 0) {
    return null;
  }
  return (
    <div className="msgfiles">
      {files.map((file) => file.image
        ? (
          <a key={file.id} className="msgimg" href={file.url} target="_blank" rel="noopener noreferrer" title={file.name}>
            <img src={file.url} alt={file.name} loading="lazy" />
          </a>
        )
        : (
          <a key={file.id} className="msgfile" href={file.url} target="_blank" rel="noopener noreferrer" title={file.name}>
            <Icon name="file" size={14} /> <span>{file.name}</span>
          </a>
        ))}
    </div>
  );
}

function LiveStatus({ activity, since, onStop }: { activity: Activity | null; since: string | null; onStop?: () => void }) {
  const now = useNow();
  const took = elapsed(since, now);
  return (
    <div className="livestatus">
      <Spinner />
      <span>{activityText(activity)}…</span>
      {took && <span className="muted">{took}</span>}
      {onStop && <button className="textbtn danger" onClick={onStop}><Icon name="stop" size={12} /> 停止</button>}
    </div>
  );
}

function useNow(): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  return now;
}

function lastUserAt(messages: ChatMessage[]): string | null {
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === "user") {
      return messages[i].at;
    }
  }
  return null;
}

function Time({ at }: { at: string | null }) {
  if (!at) {
    return null;
  }
  const date = new Date(at);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  const hm = date.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
  const today = date.toDateString() === new Date().toDateString();
  return <time className="muted small" dateTime={at}>{today ? hm : `${date.getMonth() + 1}月${date.getDate()}日 ${hm}`}</time>;
}

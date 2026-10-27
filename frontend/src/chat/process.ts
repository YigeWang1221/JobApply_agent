import type { Activity, ChatMessage, ProcessStep, SentAttachment, StepStatus } from "../api";
import type { IconName } from "./icons";

// 把后端的消息流整理成「一轮」：用户的一句话 + Agent 为它做的过程（思考、工具调用）+ 最后的回复。
// 后端有 steps 时照用；没有时用旧的 tools（名字、说明、结果首行）拼出来。思考只在 steps 里真的有时出现

export interface Step extends ProcessStep {
  status: StepStatus;
}

export type Entry = { kind: "step"; step: Step } | { kind: "note"; text: string };

export interface Turn {
  key: string;
  user: { text: string; at: string | null; attachments: SentAttachment[] } | null;
  entries: Entry[];
  steps: Step[];
  reply: string | null;
  replyAt: string | null;
  // ask_user 的原话（参数里有时），用来把回复里的提问单独标出来
  ask: string | null;
  asked: boolean;
  startedAt: string | null;
  endedAt: string | null;
}

export function toTurns(messages: ChatMessage[], running: boolean): Turn[] {
  const turns: Turn[] = [];
  let group: { user: Turn["user"]; items: Extract<ChatMessage, { role: "assistant" }>[] } | null = null;
  const flush = (live: boolean) => {
    if (group) {
      turns.push(build(group.user, group.items, turns.length, live));
    }
  };
  for (const message of messages) {
    if (message.role === "user") {
      flush(false);
      group = { user: { text: message.text, at: message.at, attachments: message.attachments ?? [] }, items: [] };
    } else {
      group ??= { user: null, items: [] };
      group.items.push(message);
    }
  }
  flush(running);
  return turns;
}

function build(user: Turn["user"], items: Extract<ChatMessage, { role: "assistant" }>[], index: number,
  live: boolean): Turn {
  let replyAt = -1;
  items.forEach((m, i) => {
    if (m.text?.trim()) {
      replyAt = i;
    }
  });
  const entries: Entry[] = [];
  const steps: Step[] = [];
  items.forEach((m, i) => {
    if (i !== replyAt && m.text?.trim()) {
      entries.push({ kind: "note", text: m.text });
    }
    for (const step of stepsOf(m, live)) {
      entries.push({ kind: "step", step });
      steps.push(step);
    }
  });
  const askStep = steps.find((s) => s.name === "ask_user");
  const ask = typeof askStep?.args?.message === "string" ? askStep.args.message.trim() : null;
  const first = steps.find((s) => s.startedAt)?.startedAt ?? user?.at ?? items[0]?.at ?? null;
  const lastStep = [...steps].reverse().find((s) => s.endedAt)?.endedAt ?? null;
  return {
    key: `${index}-${user?.at ?? ""}`,
    user,
    entries,
    steps,
    reply: replyAt >= 0 ? items[replyAt].text : null,
    replyAt: replyAt >= 0 ? items[replyAt].at : items[items.length - 1]?.at ?? null,
    ask,
    asked: !!askStep,
    startedAt: first,
    endedAt: lastStep ?? items[items.length - 1]?.at ?? null,
  };
}

function stepsOf(message: Extract<ChatMessage, { role: "assistant" }>, live: boolean): Step[] {
  if (message.steps && message.steps.length > 0) {
    return message.steps
      .filter((s) => s.kind === "tool" || s.text?.trim())
      .map((s) => ({ ...s, status: s.status ?? statusOf(s.result ?? null, live) }));
  }
  return (message.tools ?? []).map((t) => ({
    kind: "tool" as const,
    name: t.name,
    summary: t.summary,
    args: t.args ?? null,
    result: t.result,
    startedAt: t.startedAt ?? null,
    endedAt: t.endedAt ?? null,
    status: t.status ?? statusOf(t.result, live),
  }));
}

function statusOf(result: string | null, live: boolean): StepStatus {
  if (result == null) {
    return live ? "running" : "done";
  }
  if (result.startsWith("失败")) {
    return "failed";
  }
  if (result.startsWith("被拒绝")) {
    return "rejected";
  }
  return "done";
}

// ---- 说明文字 ----

const BROWSER_ACTION: Record<string, string> = {
  browser_click: "点击",
  browser_type: "输入",
  browser_fill_form: "填表",
  browser_select_option: "选择",
  browser_snapshot: "看页面",
  browser_find: "查找",
  browser_press_key: "按键",
  browser_wait_for: "等待",
  browser_tabs: "切换标签页",
  browser_file_upload: "上传文件",
  browser_handle_dialog: "处理弹窗",
};

const OTHER: Record<string, [IconName, string]> = {
  add_lead: ["lead", "加入候选池"],
  update_lead: ["lead", "更新候选"],
  list_jobs: ["lead", "查看候选池"],
  start_application: ["lead", "开始申请"],
  list_resumes: ["lead", "查看简历"],
  read_resume: ["lead", "读简历"],
  choose_resume: ["lead", "记下简历排名"],
  mark_submitted: ["lead", "记为已投递"],
  record_status: ["lead", "记下投递进展"],
  read_skill: ["skill", "读 skill"],
  ask_user: ["ask", "问你"],
  update_plan: ["tool", "更新计划"],
  update_state: ["tool", "更新申请状态"],
  save_draft: ["tool", "保存草稿"],
  finish: ["tool", "停在提交前"],
};

export function urlOf(step: { name?: string | null; summary?: string | null; args?: Record<string, unknown> | null }) {
  const url = typeof step.args?.url === "string" ? step.args.url : step.summary ?? "";
  return /^https?:\/\//.test(url) ? url : null;
}

export function shortUrl(url: string): string {
  try {
    const u = new URL(url);
    const path = u.pathname === "/" ? "" : u.pathname;
    const text = u.host.replace(/^www\./, "") + path;
    return text.length > 48 ? `${text.slice(0, 47)}…` : text;
  } catch {
    return url;
  }
}

// 一个工具调用的图标和一句动作说明（「搜索：…」「读取：…」）
export function describeTool(step: { name?: string | null; summary?: string | null; args?: Record<string, unknown> | null }):
  { icon: IconName; label: string } {
  const name = step.name ?? "";
  const summary = step.summary?.trim() ?? "";
  const url = urlOf(step);
  if (name === "web_search") {
    const query = typeof step.args?.query === "string" ? step.args.query : summary;
    return { icon: "search", label: `搜索：${query}` };
  }
  if (name === "web_fetch") {
    return { icon: "web", label: `读取：${url ? shortUrl(url) : summary}` };
  }
  if (name === "browser_navigate") {
    return { icon: "browser", label: `在 Chrome 打开：${url ? shortUrl(url) : summary}` };
  }
  if (name.startsWith("browser_")) {
    const action = BROWSER_ACTION[name] ?? name.replace("browser_", "");
    return { icon: "browser", label: summary ? `浏览器${action}：${summary}` : `浏览器${action}` };
  }
  const known = OTHER[name];
  if (known) {
    return { icon: known[0], label: summary && name !== "ask_user" ? `${known[1]}：${summary}` : known[1] };
  }
  return { icon: "tool", label: summary ? `${name}：${summary}` : name };
}

// 运行中的那一行：「正在思考」「正在搜索「…」」「正在 Chrome 里读 …」
export function activityText(activity: Activity | null): string {
  if (!activity || activity.kind === "thinking" || !activity.name) {
    return "正在思考";
  }
  const step = { name: activity.name, summary: activity.summary ?? "" };
  const url = urlOf(step);
  switch (activity.name) {
    case "web_search":
      return `正在搜索「${activity.summary ?? ""}」`;
    case "web_fetch":
      return `正在读 ${url ? shortUrl(url) : activity.summary ?? "网页"}`;
    case "browser_navigate":
      return `正在 Chrome 里读 ${url ? shortUrl(url) : activity.summary ?? "网页"}`;
    default:
      return `正在${describeTool(step).label}`;
  }
}

// 没有 activity 时：取最后一轮里还没有结果的工具；都有结果就是在想下一步
export function fallbackActivity(turn: Turn | undefined): Activity | null {
  const pending = turn?.steps.filter((s) => s.kind === "tool" && s.status === "running").pop();
  return pending ? { kind: "tool", name: pending.name, summary: pending.summary, since: pending.startedAt } : null;
}

// 折叠时的标题：「搜索了 3 次，读了 2 个页面，加了 1 个候选 · 用时 40 秒」
export function summarize(turn: Turn, live: boolean): string {
  const count = (test: (s: Step) => boolean) => turn.steps.filter(test).length;
  const tool = (name: string) => (s: Step) => s.name === name;
  const parts: string[] = [];
  const thinking = count((s) => s.kind === "thinking");
  const searches = count(tool("web_search"));
  const pages = count((s) => s.name === "web_fetch" || s.name === "browser_navigate");
  const added = count(tool("add_lead"));
  const updated = count(tool("update_lead"));
  const browser = count((s) => !!s.name?.startsWith("browser_") && s.name !== "browser_navigate");
  const known = thinking + searches + pages + added + updated + browser;
  const other = turn.steps.length - known;
  if (thinking) parts.push("思考");
  if (searches) parts.push(`搜索了 ${searches} 次`);
  if (pages) parts.push(`读了 ${pages} 个页面`);
  if (added) parts.push(`加了 ${added} 个候选`);
  if (updated) parts.push(`更新了 ${updated} 个候选`);
  if (browser) parts.push(`在浏览器里操作了 ${browser} 步`);
  if (other) parts.push(`${known ? "其他 " : ""}${other} 步`);
  const failed = count((s) => s.status === "failed" || s.status === "rejected");
  let text = parts.join("，") || "过程";
  if (failed) text += `（${failed} 步没成功）`;
  if (live) return `${text} · 进行中`;
  const took = elapsed(turn.startedAt, turn.endedAt);
  return took ? `${text} · 用时 ${took}` : text;
}

export function elapsed(from: string | null | undefined, to: string | null | undefined | number): string | null {
  if (!from) return null;
  const start = Date.parse(from);
  const end = typeof to === "number" ? to : to ? Date.parse(to) : NaN;
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return null;
  const seconds = Math.round((end - start) / 1000);
  if (seconds < 60) return `${seconds} 秒`;
  const minutes = Math.floor(seconds / 60);
  return minutes < 60 ? `${minutes} 分 ${seconds % 60} 秒` : `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`;
}

// ---- 工具结果的结构化显示 ----

// 本轮读过的网页（web_fetch / browser_navigate），去重
export function sourcesOf(turn: Turn): string[] {
  const urls = turn.steps
    .filter((s) => (s.name === "web_fetch" || s.name === "browser_navigate") && s.status !== "failed"
      && s.status !== "rejected")
    .map(urlOf)
    .filter((u): u is string => !!u);
  return [...new Set(urls)];
}

// web_search 的结果：「1. 标题\n   网址\n   摘要」
export function searchResults(result: string): { title: string; url: string; text: string | null }[] {
  const out: { title: string; url: string; text: string | null }[] = [];
  const lines = result.split("\n");
  for (let i = 0; i < lines.length; i++) {
    const head = /^\s*\d+\.\s+(.+)$/.exec(lines[i]);
    const url = lines[i + 1]?.trim();
    if (head && url && /^https?:\/\//.test(url)) {
      const next = lines[i + 2];
      const text = next && !/^\s*\d+\.\s/.test(next) && next.trim() ? next.trim() : null;
      out.push({ title: head[1].trim(), url, text });
      i += text ? 2 : 1;
    }
  }
  return out;
}

// web_fetch 的结果：第一行是怎么读的，可能有「全文存在 …」，后面是正文；只给前几段
export function fetchPreview(result: string): { head: string; paragraphs: string[]; savedTo: string | null; more: boolean } {
  const [head, ...rest] = result.split("\n");
  let savedTo: string | null = null;
  const body: string[] = [];
  for (const line of rest) {
    const saved = /全文存在\s*(\S+?)。?$/.exec(line);
    if (saved && savedTo === null) {
      savedTo = saved[1];
    } else if (line.trim()) {
      body.push(line.trim());
    }
  }
  return { head, paragraphs: body.slice(0, 6), savedTo, more: body.length > 6 };
}

// add_lead / update_lead：job 编号取自结果（「已放进候选池：id」）或参数 job
export function leadOf(step: Step): { job: string | null; company: string | null; title: string | null; city: string | null } {
  const arg = (key: string) => {
    const value = step.args?.[key];
    if (Array.isArray(value)) return value.join("、");
    return typeof value === "string" && value.trim() ? value.trim() : null;
  };
  const fromResult = step.result ? /(?:候选池：|候选池里了：)([^\s（(。，]+)/.exec(step.result)?.[1] ?? null : null;
  return { job: fromResult ?? arg("job"), company: arg("company"), title: arg("title"), city: arg("city") };
}

// 对后端接口的薄封装。出错时把后端返回的 {"error": "..."} 原样交给页面显示。
// 写接口一律带 Content-Type: application/json（后端的本地请求防护要求这样，没有请求体时发 {}，DELETE 也一样）；上传文件（原件、附件）除外。

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const init: RequestInit = { method };
  if (method !== "GET") {
    init.headers = { "Content-Type": "application/json" };
    init.body = JSON.stringify(body ?? {});
  }
  return parse<T>(await fetch(path, init));
}

// 上传一个文件：multipart 字段 file，不设 Content-Type（浏览器自己带 boundary）
async function uploadFile<T>(path: string, file: File): Promise<T> {
  const form = new FormData();
  form.append("file", file);
  return parse<T>(await fetch(path, { method: "POST", body: form }));
}

async function parse<T>(response: Response): Promise<T> {
  const text = await response.text();
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }
  if (!response.ok) {
    const message =
      data && typeof data === "object" && "error" in data ? String((data as { error: unknown }).error) : text;
    throw new ApiError(response.status, message || `HTTP ${response.status}`);
  }
  return data as T;
}

// 全应用只有一份的浏览器连接状态：generation 是第几次连上（还没连上过为 0）
export interface BrowserState {
  // stalled：连着，但浏览器没回话（多半是有页面没响应，C011-fix）
  state: "not_connected" | "connecting" | "connected" | "stalled" | "disconnected";
  reason: string | null;
  since: string | null;
  generation: number;
}

export interface Preset {
  id: string;
  label: string;
  format: "openai" | "anthropic" | null;
  baseUrl: string | null;
  keyRequired: boolean;
  note: string | null;
}

export interface TestRecord {
  model: string;
  ok: boolean;
  tools: boolean;
  latencyMs: number;
  at: string;
  error: string | null;
}

export interface Provider {
  id: string;
  preset: string;
  format: "openai" | "anthropic";
  baseUrl: string;
  keyVariable: string;
  keySet: boolean;
  keyLast4: string | null;
  models: string[];
  tested: TestRecord[];
  usedBy: string[];
}

export interface Assignment {
  provider: string;
  model: string;
  // 显示思考（C006-agent）：只有 Anthropic 格式的提供方用它；后端没给这个字段时设置页不显示开关
  showThinking?: boolean;
}

export type Usage = "main" | "auxiliary" | "cross_check";

export interface ModelSettings {
  providers: Provider[];
  assignments: Partial<Record<Usage, Assignment>>;
  presets: Preset[];
}

export interface TestResult {
  ok: boolean;
  latencyMs: number;
  model: string;
  supportsTools: boolean;
  promptTokens: number | null;
  completionTokens: number | null;
  error: string | null;
}

export interface Asset {
  key: string;
  fileName: string;
  type: string;
  size: string;
  sha256: string;
  uploadedAt: string;
  description: string;
}

export interface UploadResult {
  asset: Asset;
  duplicate: boolean;
  analysisNote: string | null;
}

export interface Source {
  key: string;
  type: "dir" | "github";
  location: string;
  version: string;
  registeredAt: string;
  lastScannedAt: string;
  fileCount: string;
  description: string;
}

// 目录选择框：只有子目录的名字和路径；registrable 为 false 的目录（storage 相关）可以点进去，但不能登记
export interface DirectoryListing {
  path: string;
  parent: string | null;
  registrable: boolean;
  directories: { name: string; path: string; registrable: boolean }[];
}

export interface RepoSummary {
  fullName: string;
  description: string;
  fork: boolean;
  archived: boolean;
  pushedAt: string;
}

// 登记结果：只给 GitHub owner 时返回 owner 和它的公开仓库，不登记
export type RegisterResult =
  | { source: Source; duplicate: boolean }
  | { owner: string; repositories: RepoSummary[] };

export interface SourceScan {
  version: string;
  files: { path: string; kind: string; chars: number }[];
  skipped: { path: string; reason: string }[];
  chars: number;
  commits: { count: number; firstAt: string | null; lastAt: string | null; ownPercent: number | null;
    recentTitles: string[] } | null;
  warnings: string[];
  githubCalls: number;
  overLimit: boolean;
}

export interface Section {
  id: string;
  heading: string;
  text: string;
}

export interface Bilingual {
  zh: string | null;
  en: string | null;
}

// 整理出来的项目卡候选（后端 ProjectCard 原样序列化，reviewed 是 ZH / EN）
export interface CandidateCard {
  slug: string;
  aliases: string[];
  title: Bilingual | null;
  kind: string | null;
  period: { from: string | null; to: string | null; precision: string | null } | null;
  teamSize: number | null;
  stack: string[];
  links: { kind: string | null; url: string }[];
  sources: string[];
  zh: Section[];
  en: Section[];
}

export type DecisionKind = "accept" | "edit" | "reject" | "conflict";
export type ClaimUse = "forms_and_resume" | "interview_only" | "none";

export interface ImportCandidate {
  id: string;
  kind: "fact" | "project";
  group: string;
  key: string;
  value: string | null;
  zh: string | null;
  en: string | null;
  factClass: "A" | "B" | null;
  project: CandidateCard | null;
  evidence: string | null;
  origins: { source: string; locator: string | null }[];
  claim: string | null;
  comparison: "new" | "different" | "same";
  existing: { value?: string | null; zh?: string | null; en?: string | null } | null;
  alternatives: { source: string; value: string | null; zh: string | null; en: string | null }[];
  notes: string[];
  decision: { decision: DecisionKind | "same"; use: ClaimUse | null; at: string | null; warnings: string[] } | null;
}

export interface ImportProgress {
  message: string;
  sources_done: number;
  sources_total: number;
  model_calls: number;
  github_calls: number;
}

export interface ImportRunSummary {
  id: string;
  status: ImportRun["status"];
  created_at: string;
  updated_at: string;
  sources: string[];
  candidates: number;
  decided: number;
  error: string | null;
}

export interface ImportRun {
  id: string;
  status: "estimated" | "running" | "reviewing" | "done" | "failed";
  created_at: string;
  updated_at: string;
  sources: string[];
  progress?: ImportProgress;
  candidates?: ImportCandidate[];
  warnings?: string[];
  error?: string;
  estimate: {
    sources: { key: string; type: string; location: string; scan: SourceScan; modelCalls: number;
      githubCallsPlanned: number }[];
    chars: number;
    modelCalls: number;
    githubCallsUsed: number;
    githubCallsPlanned: number;
    warnings: string[];
    overLimit: boolean;
  } | null;
}

export interface ProfileFact {
  key: string;
  value: string | null;
  zh: string | null;
  en: string | null;
  class: "A" | "B";
  source: string | null;
  sourceDeleted: boolean;
  claim: string | null;
  reviewed: ("zh" | "en")[];
  confirmedAt: string | null;
}

export interface ProfileCard {
  slug: string;
  title: Bilingual | null;
  aliases: string[];
  kind: string | null;
  period: { from: string | null; to: string | null } | null;
  teamSize: number | null;
  stack: string[];
  links: { kind: string | null; url: string }[];
  sources: string[];
  roleClaim: string | null;
  reviewed: ("zh" | "en")[];
  zh: Section[];
  en: Section[];
}

export interface ProfileSummary {
  generatedAt: string | null;
  userEdited: string[];
  reviewed: ("zh" | "en")[];
  zh: Section[];
  en: Section[];
}

export interface Profile {
  groups: Record<string, ProfileFact[]>;
  projects: ProfileCard[];
  summary: ProfileSummary | null;
}

export interface SummaryCandidate {
  current: ProfileSummary | null;
  zh: Section[];
  en: Section[];
  suggestions: Record<string, string>;
  userEdited: string[];
  warnings: string[];
}

// 申请线程（agent-runtime.md 7.1）。Agent 在后台跑；要用户时 status 是 waiting_user，message 写着要做什么。
// 只放问题原文和选项；fieldClass 为 C 的问题不能在面板里回答，只能由用户在浏览器里亲自填
export interface ApplicationQuestion {
  id: string;
  text: string;
  options?: string[] | null;
  required?: boolean | null;
  fieldClass?: "A" | "B" | "C" | null;
  page?: string | null;
}

// 页面上的一个上传位置（简历、作品集、成绩单等）
export interface FileSlot {
  id: string;
  label: string;
  accept?: string | null;
  required?: boolean | null;
  note?: string | null;
}

export interface PlanItem {
  text: string;
  status: "todo" | "doing" | "done" | "blocked" | string;
}

// 申请状态里的一个字段（C019）。值不在侧栏显示
export interface StateField {
  id: string;
  section?: string | null;
  label: string;
  widget?: string | null;
  status?: "todo" | "filled" | "verified" | "needs_user" | "skipped" | string | null;
  note?: string | null;
}

// 申请状态（C019）：现在在哪一步、计划、字段清单、决定和站点规律
export interface ApplicationState {
  stage?: string | null;
  steps?: PlanItem[] | null;
  fields?: StateField[] | null;
  notes?: string[] | null;
}

export interface ApplicationStatus {
  job: string;
  project?: string | null;
  session?: string | null;
  status: "running" | "waiting_user" | "ready" | "failed" | string;
  message?: string | null;
  questions?: ApplicationQuestion[] | null;
  fileSlots?: FileSlot[] | null;
  assets?: Asset[] | null;
  disconnected?: boolean;
  state?: ApplicationState | null;
  usage?: { modelCalls: number; toolCalls: number; tokens: number } | null;
  prepState?: string | null;
  // 报告的状态：draft（填写中的草稿）、final（停在提交前时定稿）；还没有报告时为 null。正文用 api.report 读
  report?: "draft" | "final" | null;
  round?: number | null;
  error?: string | null;
  // 这个申请已经存下的附件（C006-fix）：上传位置也可以选它们（attachment:<id>）
  attachments?: SentAttachment[] | null;
  // 运行中插了、Agent 还没看到的话（C010）
  inserted?: string[] | null;
}

export interface ReplyBody {
  session: string;
  text?: string;
  answers?: Record<string, string>;
  // 按 fileSlots 的顺序，每个对应一个上传位置：原件键，或 attachment:<附件编号>；不选写空字符串
  files?: string[];
  // 这次回复附的文件（C006-fix），上传接口返回的编号
  attachments?: string[];
}

// 回复里附的文件、图片（C006-fix）：选中后立刻上传拿到编号，发送时只带编号
// Chrome 里一个没有主人的标签页（C006-fix-browser 第 2 节）：只给你看，你选了才交给 Agent
export interface BrowserTab {
  index: number;
  title: string;
  url: string;
}

export interface Attachment {
  id: string;
  name: string;
  size: number;
  contentType: string;
  image: boolean;
}

// 已发出的用户消息上的附件；url 用来显示缩略图、点开看原件
export interface SentAttachment {
  id: string;
  name: string;
  contentType: string;
  image: boolean;
  url: string;
}

// 单个附件的上限，和后端一致
export const MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024;

// Project / Session / Job（C005）：候选池、对话 Agent、申请工作台
export type LeadStatus = "new" | "shortlisted" | "skipped" | "closed" | "converted";

export interface JobView {
  id: string;
  project: string;
  company: string;
  title: string;
  city: string[];
  type: string | null;
  url: string | null;
  site: string | null;
  source: string | null;
  discoveredIn: string | null;
  leadStatus: LeadStatus;
  skipReason: string | null;
  phase: string | null;
  prepState: string | null;
  label: string;
  verified: "verified" | "not_found" | null;
  officialUrl: string | null;
  note: string | null;
  // 申请线程的状态：running / waiting_user / ready / failed；还没有申请线程时为 null
  application: string | null;
  // 投递以后（C007）：用户确认提交的时间、最近一次反馈的时间、下次查看的日期（yyyy-MM-dd）
  appliedAt: string | null;
  lastCheckedAt: string | null;
  nextCheckAt: string | null;
  // 申请 Agent 结束时页面已经像投递成功了，确认卡片写「确认一下」
  pageLooksSubmitted: boolean;
  // 阶段变化（转为申请、阶段变化、结束），按时间先后；只有 Project 主页和全局页的列表带，其它地方为 null
  events: PhaseEvent[] | null;
  // 下面三个由服务器算好（C006-fix 第 6 节），前端只读：「该查看了」（按服务器的今天）、分组、能不能开始申请线程
  due: boolean;
  bucket: JobBucket;
  canStart: boolean;
  // 简历排名（C008，job.md 的 resume_choice）：对话 Agent 开始申请前排的；用户说不用排时只有 skipped
  resumeChoice?: ResumeChoice | null;
  // 招聘轮次（C014-A，job.md 的 rounds），按 seq 排好；result 已由服务器按今天算过 unknown（用户未同步）
  rounds: RoundView[];
}

export interface RoundView {
  seq: number;
  name: string;
  type: string;
  scheduledAt: string | null;
  happenedAt: string | null;
  format: string | null;
  interviewerRole: string | null;
  // pending / passed / failed / cancelled / unknown
  result: string;
  resultSource: string | null;
  // 写过复盘：正文用 api.roundReview 读
  review: boolean;
  questionCount: number;
  // 复盘里每道题的主题（一题多个主题时都算）
  topics: string[];
}

export interface ResumeChoice {
  asset?: string;
  file?: string;
  ranking?: { key: string; file: string; rank: number; reason: string; gap: string }[];
  suggestions?: string[];
  skipped?: string;
  at: string;
  session?: string;
}

// 分组（服务器 JobBucket）：lead 候选池里还在考虑；preparing 准备中；waiting 准备中、要你做事（申请线程在等你 / 失败 / 中断，
// 或者 prep 是 needs_input / needs_login / handoff_user / failed）；ready 停在提交前；applied 投递以后、没结束；
// closed 跳过 / 下线 / 已结束
export type JobBucket = "lead" | "preparing" | "waiting" | "ready" | "applied" | "closed";

// to 是变化后的标签：applied、screening…、prep:selected、closed:rejected
export interface PhaseEvent {
  at: string;
  type: "converted" | "phase_changed" | "closed";
  to: string;
}

export function localDay(date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

// 停在提交前、等用户说提交了没有（C007）
export function awaitingSubmit(job: JobView): boolean {
  return job.bucket === "ready";
}

export interface SessionSummary {
  id: string;
  project: string;
  title: string;
  createdAt: string;
  tags: string[];
  jobs: string[];
  handoff: string;
  // 运行中标记（C006-agent）；没有时不显示
  status?: "running" | "idle" | "interrupted" | null;
}

export interface ProjectCounts {
  leads: number;
  shortlisted: number;
  preparing: number;
  waiting: number;
  ready: number;
  applied: number;
  // 「该查看了」的申请个数（C007）
  dueChecks: number;
}

export interface ProjectSummary {
  id: string;
  title: string;
  status: string;
  sessions: SessionSummary[];
  counts: ProjectCounts;
}

// 一次工具调用：名字、一句说明（取自参数）、结果的第一行
export type StepStatus = "running" | "done" | "failed" | "rejected";

// 现在后端只给 name / summary / 结果首行；args、完整结果、状态和时间由 C006-agent 补，有了就显示
export interface ToolStep {
  name: string;
  summary: string;
  result: string | null;
  args?: Record<string, unknown> | null;
  status?: StepStatus | null;
  startedAt?: string | null;
  endedAt?: string | null;
}

// C006-agent 的过程步骤（按时间排列）。thinking 只在模型供应商真的给了思考内容时出现
export interface ProcessStep {
  kind: "thinking" | "tool";
  text?: string | null;
  name?: string | null;
  summary?: string | null;
  args?: Record<string, unknown> | null;
  result?: string | null;
  status?: StepStatus | null;
  startedAt?: string | null;
  endedAt?: string | null;
}

export type ChatMessage =
  | { role: "user"; text: string; at: string | null; attachments?: SentAttachment[] | null }
  | { role: "assistant"; text: string | null; tools: ToolStep[]; steps?: ProcessStep[] | null; at: string | null };

// 现在在做什么（C006-agent）；没有时前端退回「对话里最后一个工具」
export interface Activity {
  kind: "thinking" | "tool";
  name?: string | null;
  summary?: string | null;
  since?: string | null;
}

export interface SessionView {
  session: SessionSummary;
  // interrupted：上一轮没跑完就断了（例如后端重启），error 写着原因
  status: "running" | "idle" | "interrupted";
  error: string | null;
  messages: ChatMessage[];
  jobs: JobView[];
  activity?: Activity | null;
  // 运行中插了、Agent 还没看到的消息（C010）
  inserted?: string[] | null;
}

export interface JobEventView {
  at: string;
  session: string | null;
  actor: string;
  type: string;
  from: string | null;
  to: string;
  reason: string | null;
}

export interface JobDetail {
  job: JobView;
  events: JobEventView[];
  application: ApplicationStatus | null;
  steps: ChatMessage[];
  activity?: Activity | null;
  // 从这个页面发消息用哪个 Session（申请线程的、发现它的；都删了时用 Project 最新的一个，C013）
  session: string | null;
  // 记录里引用、但已经删除的 Session
  deletedSessions: string[];
}

// 同时申请（C012）：两个上限和此刻在跑、排队的个数
export interface WorkerSettings {
  workers: number;
  perSite: number;
  maxWorkers: number;
  running: number;
  queued: number;
}

// 工具调用上限（C018）：申请每次运行、对话每条消息最多几次工具调用；0 = 不限。default* 是 application.yml 的默认值
export interface ToolCallSettings {
  perRun: number;
  perMessage: number;
  defaultPerRun: number;
  defaultPerMessage: number;
}

// 敏感信息（C014-D）：一个键的名字和「已设置 / 未设置」，从不带值
export interface PersonalKey {
  key: string;
  zh: string;
  en: string;
  fields: string;
  preset: boolean;
  set: boolean;
}

const MODELS = "/api/settings/models";
const PERSONAL = "/api/settings/personal";
const enc = encodeURIComponent;
const jobPath = (jobId: string) => `/api/jobs/${encodeURIComponent(jobId)}/application`;

export const api = {
  modelSettings: () => request<ModelSettings>("GET", MODELS),
  saveProvider: (id: string, body: { preset: string; format: string | null; baseUrl: string; models: string[] }) =>
    request<Provider>("PUT", `${MODELS}/providers/${encodeURIComponent(id)}`, body),
  deleteProvider: (id: string) => request<void>("DELETE", `${MODELS}/providers/${encodeURIComponent(id)}`),
  saveKey: (id: string, key: string) =>
    request<{ keyLast4: string }>("PUT", `${MODELS}/providers/${encodeURIComponent(id)}/key`, { key }),
  catalog: (id: string) => request<{ models: string[] }>("POST", `${MODELS}/providers/${encodeURIComponent(id)}/catalog`),
  test: (id: string, model: string) =>
    request<TestResult>("POST", `${MODELS}/providers/${encodeURIComponent(id)}/test`, { model }),
  assign: (main: Assignment | null, auxiliary: Assignment | null, crossCheck: Assignment | null) =>
    request<Partial<Record<Usage, Assignment>>>("PUT", `${MODELS}/assignments`, { main, auxiliary, crossCheck }),

  workerSettings: () => request<WorkerSettings>("GET", "/api/settings/workers"),
  saveWorkerSettings: (workers: number, perSite: number) =>
    request<WorkerSettings>("PUT", "/api/settings/workers", { workers, perSite }),
  toolCallSettings: () => request<ToolCallSettings>("GET", "/api/settings/tool-calls"),
  saveToolCallSettings: (perRun: number, perMessage: number) =>
    request<ToolCallSettings>("PUT", "/api/settings/tool-calls", { perRun, perMessage }),

  personalKeys: () => request<{ keys: PersonalKey[] }>("GET", PERSONAL),
  savePersonal: (key: string, value: string, label?: string) =>
    request<PersonalKey>("PUT", `${PERSONAL}/${enc(key)}`, { value, label }),
  clearPersonal: (key: string) => request<PersonalKey>("DELETE", `${PERSONAL}/${enc(key)}`),

  assets: () => request<{ assets: Asset[] }>("GET", "/api/assets"),
  upload: (file: File) => uploadFile<UploadResult>("/api/assets", file),
  describe: (key: string, description: string) =>
    request<Asset>("PATCH", `/api/assets/${encodeURIComponent(key)}`, { description }),

  sources: () => request<{ sources: Source[] }>("GET", "/api/sources"),
  registerSource: (type: "dir" | "github", location: string) =>
    request<RegisterResult>("POST", "/api/sources", { type, location }),
  browse: (path?: string) =>
    request<DirectoryListing>("GET", `/api/sources/browse${path ? `?path=${encodeURIComponent(path)}` : ""}`),
  deleteSource: (key: string) => request<{ deleted: string }>("DELETE", `/api/sources/${encodeURIComponent(key)}`),
  estimate: (sources: string[]) => request<ImportRun>("POST", "/api/import-runs", { sources }),
  importRun: (id: string) => request<ImportRun>("GET", `/api/import-runs/${encodeURIComponent(id)}`),
  importRuns: () => request<{ runs: ImportRunSummary[] }>("GET", "/api/import-runs"),
  startImport: (id: string) => request<ImportRun>("POST", `/api/import-runs/${encodeURIComponent(id)}/start`),
  decide: (id: string, body: { candidate: string; decision: DecisionKind; value?: Record<string, unknown>;
    use?: ClaimUse }) => request<ImportRun>("POST", `/api/import-runs/${encodeURIComponent(id)}/decisions`, body),

  profile: () => request<Profile>("GET", "/api/profile"),
  editFact: (key: string, body: { value?: string; zh?: string; en?: string; reviewed?: string[] }) =>
    request<ProfileFact>("PUT", `/api/profile/facts/${encodeURIComponent(key)}`, body),
  editProject: (slug: string, body: { title?: Partial<Bilingual>; zh?: Record<string, string>;
    en?: Record<string, string>; reviewed?: string[] }) =>
    request<ProfileCard>("PUT", `/api/profile/projects/${encodeURIComponent(slug)}`, body),
  generateSummary: () => request<SummaryCandidate>("POST", "/api/profile/summary"),
  confirmSummary: (body: { zh: Record<string, string>; en: Record<string, string>; userEdited: string[];
    reviewed: string[] }) => request<ProfileSummary>("PUT", "/api/profile/summary", body),

  // 浏览器连接状态（C006-fix-browser 第 1 节）；变化时全局事件流也会推 browser 事件
  browser: () => request<BrowserState>("GET", "/api/browser"),
  reconnectBrowser: () => request<BrowserState>("POST", "/api/browser/reconnect"),

  projects: () => request<{ projects: ProjectSummary[] }>("GET", "/api/projects"),
  createProject: (id: string, title: string) => request<ProjectSummary>("POST", "/api/projects", { id, title }),
  jobs: () => request<{ jobs: JobView[] }>("GET", "/api/jobs"),
  project: (id: string) => request<{ project: ProjectSummary; jobs: JobView[] }>("GET", `/api/projects/${enc(id)}`),
  createSession: (projectId: string, title?: string) =>
    request<SessionView>("POST", `/api/projects/${enc(projectId)}/sessions`, title ? { title } : {}),
  session: (id: string) => request<SessionView>("GET", `/api/sessions/${enc(id)}`),
  renameSession: (id: string, title: string) => request<SessionView>("PATCH", `/api/sessions/${enc(id)}`, { title }),
  renameProject: (id: string, title: string) => request<ProjectSummary>("PATCH", `/api/projects/${enc(id)}`, { title }),
  // 删除（C013）：挪进 storage/.trash/。Session 还在跑时先停下再挪走（status: deleting）
  deleteSession: (id: string) => request<{ id: string; status: "deleted" | "deleting" }>("DELETE", `/api/sessions/${enc(id)}`),
  deleteProject: (id: string) => request<{ id: string }>("DELETE", `/api/projects/${enc(id)}`),
  sendMessage: (id: string, text: string, attachments: string[] = []) =>
    request<SessionView>("POST", `/api/sessions/${enc(id)}/messages`,
      attachments.length > 0 ? { text, attachments } : { text }),
  sessionAttachment: (id: string, file: File) => uploadFile<Attachment>(`/api/sessions/${enc(id)}/attachments`, file),
  sessionAttachmentUrl: (id: string, attachment: string) => `/api/sessions/${enc(id)}/attachments/${enc(attachment)}`,
  stopSession: (id: string) => request<SessionView>("POST", `/api/sessions/${enc(id)}/stop`),
  // 重新生成最后一条回复（C006-agent）；已经做过的操作不会撤销
  regenerate: (id: string) => request<SessionView>("POST", `/api/sessions/${enc(id)}/regenerate`),
  job: (id: string) => request<JobDetail>("GET", `/api/jobs/${enc(id)}`),
  markLead: (id: string, status: "new" | "shortlisted" | "skipped" | "closed", reason?: string) =>
    request<JobView>("PATCH", `/api/jobs/${enc(id)}/lead`, reason ? { status, reason } : { status }),
  // 用户在 Chrome 里亲自提交了（C007）：awaiting_review → applied，用户的确认就是证据
  markSubmitted: (id: string, note?: string) =>
    request<JobView>("POST", `/api/jobs/${enc(id)}/submitted`, note ? { note } : {}),
  // 岗位下线了：候选标成 closed，已转申请的以 posting_closed 结束
  postingClosed: (id: string, note?: string) =>
    request<JobView>("POST", `/api/jobs/${enc(id)}/posting-closed`, note ? { note } : {}),
  // 还没提交（暂停）/ 不投了（giveUp，以 not_submitted 结束）（C007）
  markNotSubmitted: (id: string, giveUp: boolean, note?: string) =>
    request<JobView>("POST", `/api/jobs/${enc(id)}/not-submitted`, note ? { giveUp, note } : { giveUp }),

  startApplication: (jobId: string, body: { project: string; session: string }) =>
    request<ApplicationStatus>("POST", jobPath(jobId), body),
  application: (jobId: string) => request<ApplicationStatus>("GET", jobPath(jobId)),
  reply: (jobId: string, body: ReplyBody) => request<ApplicationStatus>("POST", `${jobPath(jobId)}/reply`, body),
  stop: (jobId: string) => request<ApplicationStatus>("POST", `${jobPath(jobId)}/stop`),
  jobAttachment: (jobId: string, file: File) => uploadFile<Attachment>(`${jobPath(jobId)}/attachments`, file),
  // 没有主人的标签页（只给界面看）；把其中一个交给这个申请：标签页变了或者有主人了时 409
  freeTabs: () => request<{ tabs: BrowserTab[] }>("GET", "/api/browser/tabs"),
  handOverTab: (jobId: string, tab: BrowserTab) =>
    request<BrowserTab>("POST", `${jobPath(jobId)}/tab`, { index: tab.index, url: tab.url }),
  // 报告是 Markdown 文本；还没有报告（404）时返回 null
  report: async (jobId: string) => {
    const response = await fetch(`${jobPath(jobId)}/report`);
    if (response.status === 404) {
      return null;
    }
    if (!response.ok) {
      return parse<never>(response);
    }
    return response.text();
  },
  // 一轮的复盘 Markdown（C014-A，含 frontmatter）
  roundReview: async (jobId: string, seq: number) => {
    const response = await fetch(`/api/jobs/${enc(jobId)}/rounds/${seq}/review`);
    if (!response.ok) {
      return parse<never>(response);
    }
    return response.text();
  },
};

export function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

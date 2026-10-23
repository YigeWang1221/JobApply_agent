// 对后端接口的薄封装。出错时把后端返回的 {"error": "..."} 原样交给页面显示。
// 写接口一律带 Content-Type: application/json（后端的本地请求防护要求这样，没有请求体时发 {}，DELETE 也一样）；上传原件除外。

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

export interface ImportRun {
  id: string;
  status: "estimated" | "running" | "reviewing" | "done" | "failed";
  created_at: string;
  updated_at: string;
  sources: string[];
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

const MODELS = "/api/settings/models";

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

  assets: () => request<{ assets: Asset[] }>("GET", "/api/assets"),
  upload: async (file: File) => {
    const form = new FormData();
    form.append("file", file);
    return parse<UploadResult>(await fetch("/api/assets", { method: "POST", body: form }));
  },
  describe: (key: string, description: string) =>
    request<Asset>("PATCH", `/api/assets/${encodeURIComponent(key)}`, { description }),

  sources: () => request<{ sources: Source[] }>("GET", "/api/sources"),
  registerSource: (type: "dir" | "github", location: string) =>
    request<RegisterResult>("POST", "/api/sources", { type, location }),
  deleteSource: (key: string) => request<{ deleted: string }>("DELETE", `/api/sources/${encodeURIComponent(key)}`),
  estimate: (sources: string[]) => request<ImportRun>("POST", "/api/import-runs", { sources }),
  importRun: (id: string) => request<ImportRun>("GET", `/api/import-runs/${encodeURIComponent(id)}`),
};

export function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

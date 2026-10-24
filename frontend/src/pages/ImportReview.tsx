import { useCallback, useEffect, useState } from "react";
import {
  api, errorText, type ClaimUse, type DecisionKind, type ImportCandidate, type ImportRun, type ImportRunSummary,
} from "../api";
import { useGlobalChanges } from "../chat/useLiveUpdates";

const RUNNING_SAFETY_MS = 15000;

const NOTICE =
  "整理时，扫描出的文字会发给你配置的模型；秘密文件按名字跳过，不会被读取。证件号、签证、薪资这类 C 级内容不提取、不写入。";

export const GROUPS: [string, string][] = [
  ["basic", "基础信息"], ["education", "教育"], ["experience", "经历"], ["projects", "项目"], ["skills", "技能"],
  ["outcomes", "成果"], ["metrics", "指标"],
];

const STATUS: Record<ImportRun["status"], string> = {
  estimated: "已估算", running: "整理中", reviewing: "待确认", done: "已处理完", failed: "失败",
};

const COMPARISON: Record<ImportCandidate["comparison"], string> = { new: "新增", different: "不同", same: "相同" };

const DECISION: Record<string, string> = {
  accept: "已确认", edit: "改后确认", reject: "已拒绝", conflict: "放进冲突", same: "相同，沿用",
};

const USES: [ClaimUse, string][] = [
  ["forms_and_resume", "填表和简历"], ["interview_only", "只用于面试"], ["none", "不用"],
];

// 「资料」页的「整理与确认」：开始整理 → 进度 → 按组确认候选（C004-A-2）
export default function ImportReview({ initialRun }: { initialRun: string | null }) {
  const [runs, setRuns] = useState<ImportRunSummary[]>([]);
  const [selected, setSelected] = useState<string | null>(initialRun);
  const [run, setRun] = useState<ImportRun | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reloadRuns = useCallback(async () => {
    try {
      const list = (await api.importRuns()).runs;
      setRuns(list);
      setSelected((current) => current ?? list[0]?.id ?? null);
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  const reloadRun = useCallback(async (id: string) => {
    try {
      setRun(await api.importRun(id));
      setError(null);
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  useEffect(() => { void reloadRuns(); }, [reloadRuns]);
  useEffect(() => { if (initialRun) { setSelected(initialRun); } }, [initialRun]);
  useEffect(() => { if (selected) { void reloadRun(selected); } }, [selected, reloadRun]);

  // 整理在后台执行：后端每写一次进度就发 changed 事件（/api/events），收到就重读当前记录和列表
  const reloadSelected = useCallback(async () => {
    if (selected) {
      await reloadRun(selected);
    }
    await reloadRuns();
  }, [selected, reloadRun, reloadRuns]);
  useGlobalChanges(reloadSelected);

  // 推送断了也不会一直停在旧进度：整理中每 15 秒保底重读一次
  const runningId = run?.status === "running" ? run.id : null;
  useEffect(() => {
    if (!runningId) {
      return;
    }
    const timer = window.setInterval(() => { void reloadRun(runningId).then(reloadRuns); }, RUNNING_SAFETY_MS);
    return () => window.clearInterval(timer);
  }, [runningId, reloadRun, reloadRuns]);

  async function start() {
    if (!run) {
      return;
    }
    try {
      setRun(await api.startImport(run.id));
      setError(null);
      await reloadRuns();
    } catch (e) {
      setError(errorText(e));
    }
  }

  return (
    <>
      <section className="card">
        <h2>导入记录</h2>
        {runs.length === 0 ? (
          <p className="muted">还没有导入记录。先在「来源」页签登记来源并估算。</p>
        ) : (
          <div className="row">
            <select value={selected ?? ""} onChange={(e) => setSelected(e.target.value)}>
              {runs.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.id} · {STATUS[r.status]} · {r.sources.join("、")}
                  {r.candidates > 0 ? ` · ${r.decided}/${r.candidates}` : ""}
                </option>
              ))}
            </select>
            <button onClick={() => { void reloadRuns(); if (selected) { void reloadRun(selected); } }}>刷新</button>
          </div>
        )}
        <p className="privacy">{NOTICE}</p>
        {error && <p className="error">{error}</p>}
      </section>
      {run && <RunView key={run.id} run={run} onStart={start} onChanged={setRun} />}
    </>
  );
}

function RunView({ run, onStart, onChanged }: {
  run: ImportRun; onStart: () => void; onChanged: (run: ImportRun) => void;
}) {
  const candidates = run.candidates ?? [];
  const decided = candidates.filter((c) => c.decision).length;
  return (
    <>
      <section className="card">
        <h2>{run.id} · {STATUS[run.status]}</h2>
        {run.estimate && (
          <div className="stats">
            <div><span className="muted small">文字量</span><strong>{run.estimate.chars.toLocaleString()} 字</strong></div>
            <div><span className="muted small">预计模型调用</span><strong>{run.estimate.modelCalls} 次</strong></div>
            {run.progress && (
              <div><span className="muted small">已调用模型</span><strong>{run.progress.model_calls} 次</strong></div>
            )}
            {candidates.length > 0 && (
              <div><span className="muted small">已处理</span><strong>{decided} / {candidates.length}</strong></div>
            )}
          </div>
        )}
        {run.status === "running" && run.progress && (
          <p>正在：{run.progress.message}（来源 {run.progress.sources_done} / {run.progress.sources_total}）</p>
        )}
        {run.error && <p className="error">{run.error}</p>}
        {(run.warnings ?? []).map((w, i) => <p key={i} className="warn">{w}</p>)}
        {(run.status === "estimated" || run.status === "failed") && (
          <div className="row">
            <button disabled={run.estimate?.overLimit} onClick={onStart}>
              {run.status === "failed" ? "重新开始整理" : "开始整理"}
            </button>
            {run.estimate?.overLimit && <span className="warn">有来源超过上限，请缩小目录后重新估算</span>}
          </div>
        )}
      </section>
      {candidates.length > 0 && GROUPS.map(([group, label]) => {
        const items = candidates.filter((c) => c.group === group);
        return items.length === 0 ? null : (
          <section key={group} className="card">
            <h2>{label}（{items.filter((c) => c.decision).length} / {items.length}）</h2>
            {items.map((candidate) => (
              // 各次导入都有 f-001 这样的编号：身份里带上导入记录 id，切换记录时草稿重新开始
              <CandidateView key={`${run.id}/${candidate.id}`} runId={run.id} candidate={candidate}
                editable={run.status === "reviewing"} onChanged={onChanged} />
            ))}
          </section>
        );
      })}
    </>
  );
}

function CandidateView({ runId, candidate, editable, onChanged }: {
  runId: string; candidate: ImportCandidate; editable: boolean; onChanged: (run: ImportRun) => void;
}) {
  const card = candidate.project;
  const original = initialValues(candidate);
  const [draft, setDraft] = useState<Record<string, string>>(original);
  const [use, setUse] = useState<ClaimUse>("forms_and_resume");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const changed = Object.keys(draft).some((k) => draft[k] !== original[k]);

  async function decide(decision: DecisionKind) {
    setBusy(true);
    try {
      const kind: DecisionKind = decision === "accept" && changed ? "edit" : decision;
      onChanged(await api.decide(runId, {
        candidate: candidate.id, decision: kind, use: candidate.claim ? use : undefined,
        value: kind === "edit" ? editedValue(candidate, draft) : undefined,
      }));
      setError(null);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const field = (name: string, label: string, multiline = false) => (
    <label key={name}>
      <span className="muted small">{label}</span>
      {multiline
        ? <textarea rows={3} value={draft[name] ?? ""} disabled={!editable || !!candidate.decision}
            onChange={(e) => setDraft({ ...draft, [name]: e.target.value })} />
        : <input value={draft[name] ?? ""} disabled={!editable || !!candidate.decision}
            onChange={(e) => setDraft({ ...draft, [name]: e.target.value })} />}
    </label>
  );

  return (
    <div className="provider">
      <div className="provider-head">
        <div>
          <strong className="mono">{candidate.key}</strong>
          {" "}<span className={candidate.comparison === "different" ? "warn small" : "muted small"}>
            {COMPARISON[candidate.comparison]}</span>
          {candidate.factClass === "B" && <span className="muted small"> · 个人信息（B 级）</span>}
          <div className="muted small">
            出处：{candidate.origins.map((o) => o.source + (o.locator ? ` ${o.locator}` : "（无定位）")).join("；")}
            {candidate.claim && ` · 论断 ${candidate.claim}`}
          </div>
          {candidate.evidence && <div className="small">依据：{candidate.evidence}</div>}
        </div>
        {candidate.decision && <span className="ok small">{DECISION[candidate.decision.decision]}</span>}
      </div>
      {candidate.notes.map((note, i) => <p key={i} className="warn small">{note}</p>)}
      {candidate.existing && (
        <p className="small">事实层现在：{[candidate.existing.value, candidate.existing.zh, candidate.existing.en]
          .filter(Boolean).join(" / ")}</p>
      )}
      {candidate.alternatives.map((a, i) => (
        <p key={i} className="small">另一种写法（{a.source}）：{[a.value, a.zh, a.en].filter(Boolean).join(" / ")}</p>
      ))}
      {card ? (
        <div className="form-grid bilingual">
          {field("title.zh", "中文名")}
          {field("title.en", "English title")}
          {(["zh", "en"] as const).map((lang) => (
            <div key={lang}>
              {card[lang].length === 0
                ? <p className="muted small">{lang === "zh" ? "中文" : "English"}：确认时由模型起草，标为未审阅</p>
                : card[lang].map((s) => field(`${lang}.${s.id}`, `${lang} · ${s.heading}`, true))}
            </div>
          ))}
          <p className="muted small wide">
            {[card.kind, card.period && `${card.period.from ?? "?"} – ${card.period.to ?? "?"}`,
              card.teamSize && `${card.teamSize} 人`, card.stack.join("、")].filter(Boolean).join(" · ")}
          </p>
        </div>
      ) : candidate.value !== null ? (
        <div className="form-grid">{field("value", "值（与语言无关）")}</div>
      ) : (
        <div className="form-grid bilingual">
          {field("zh", "中文")}
          {field("en", "English")}
          {(!candidate.zh || !candidate.en) && (
            <p className="muted small wide">缺的那种语言在确认时由模型起草，标为未审阅</p>
          )}
        </div>
      )}
      {editable && !candidate.decision && (
        <div className="row">
          {candidate.claim && (
            <select value={use} onChange={(e) => setUse(e.target.value as ClaimUse)}>
              {USES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
            </select>
          )}
          <button disabled={busy} onClick={() => void decide("accept")}>{changed ? "改后确认" : "确认"}</button>
          <button disabled={busy} onClick={() => void decide("conflict")}>放进冲突</button>
          <button disabled={busy} className="danger" onClick={() => void decide("reject")}>拒绝</button>
        </div>
      )}
      {candidate.decision && candidate.decision.decision !== "same"
        && candidate.decision.warnings.map((w, i) => <p key={i} className="warn small">{w}</p>)}
      {error && <p className="error">{error}</p>}
    </div>
  );
}

/** 候选现在的值，按输入框的名字展开。 */
function initialValues(candidate: ImportCandidate): Record<string, string> {
  const card = candidate.project;
  if (card) {
    const values: Record<string, string> = { "title.zh": card.title?.zh ?? "", "title.en": card.title?.en ?? "" };
    for (const lang of ["zh", "en"] as const) {
      card[lang].forEach((s) => { values[`${lang}.${s.id}`] = s.text; });
    }
    return values;
  }
  if (candidate.value !== null) {
    return { value: candidate.value };
  }
  return { zh: candidate.zh ?? "", en: candidate.en ?? "" };
}

/** 改过以后发给后端的 value（edit）。 */
function editedValue(candidate: ImportCandidate, draft: Record<string, string>): Record<string, unknown> {
  if (!candidate.project) {
    return candidate.value !== null ? { value: draft.value } : { zh: draft.zh, en: draft.en };
  }
  const value: Record<string, unknown> = { title: { zh: draft["title.zh"], en: draft["title.en"] } };
  for (const lang of ["zh", "en"] as const) {
    if (candidate.project[lang].length > 0) {
      const sections: Record<string, string> = {};
      candidate.project[lang].forEach((s) => { sections[s.id] = draft[`${lang}.${s.id}`] ?? ""; });
      value[lang] = sections;
    }
  }
  return value;
}

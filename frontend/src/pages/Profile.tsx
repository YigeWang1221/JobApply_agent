import { useCallback, useEffect, useState } from "react";
import {
  api, errorText, type Profile as ProfileData, type ProfileCard, type ProfileFact, type Section,
  type SummaryCandidate,
} from "../api";
import { GROUPS } from "./ImportReview";

const SECTION_IDS = ["directions", "skills", "highlights", "positioning"];

// 「资料」页的「事实与画像」：事实层按组浏览，项目卡中英对照修改，生成和确认画像（C004-A-2）
export default function Profile() {
  const [profile, setProfile] = useState<ProfileData | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      setProfile(await api.profile());
      setError(null);
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  useEffect(() => { void reload(); }, [reload]);

  if (!profile) {
    return <section className="card">{error ? <p className="error">{error}</p> : <p className="muted">读取中…</p>}</section>;
  }
  const empty = GROUPS.every(([g]) => (profile.groups[g] ?? []).length === 0) && profile.projects.length === 0;
  return (
    <>
      {error && <p className="error">{error}</p>}
      {empty && (
        <section className="card"><p className="muted">事实层还是空的。在「整理与确认」里确认候选以后，会出现在这里。</p></section>
      )}
      {GROUPS.filter(([g]) => g !== "projects").map(([group, label]) => {
        const facts = profile.groups[group] ?? [];
        return facts.length === 0 ? null : (
          <section key={group} className="card">
            <h2>{label}</h2>
            {facts.map((fact) => <FactRow key={fact.key} fact={fact} onSaved={reload} />)}
          </section>
        );
      })}
      {profile.projects.length > 0 && (
        <section className="card">
          <h2>项目卡</h2>
          {profile.projects.map((card) => <CardView key={card.slug} card={card} onSaved={reload} />)}
        </section>
      )}
      {!empty && <Summary profile={profile} onSaved={reload} />}
    </>
  );
}

function Unreviewed({ reviewed, lang, present }: { reviewed: string[]; lang: "zh" | "en"; present: boolean }) {
  return present && !reviewed.includes(lang) ? <span className="warn small"> 未审阅</span> : null;
}

function FactRow({ fact, onSaved }: { fact: ProfileFact; onSaved: () => void }) {
  const [zh, setZh] = useState(fact.zh ?? "");
  const [en, setEn] = useState(fact.en ?? "");
  const [value, setValue] = useState(fact.value ?? "");
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { setZh(fact.zh ?? ""); setEn(fact.en ?? ""); setValue(fact.value ?? ""); }, [fact]);
  const changed = fact.value !== null ? value !== fact.value : zh !== (fact.zh ?? "") || en !== (fact.en ?? "");
  const unreviewed = fact.value === null && ((!!fact.zh && !fact.reviewed.includes("zh"))
    || (!!fact.en && !fact.reviewed.includes("en")));

  async function save(body: Parameters<typeof api.editFact>[1]) {
    try {
      await api.editFact(fact.key, body);
      setError(null);
      onSaved();
    } catch (e) {
      setError(errorText(e));
    }
  }

  return (
    <div className="provider">
      <div className="provider-head">
        <div>
          <strong className="mono">{fact.key}</strong>
          {fact.class === "B" && <span className="muted small"> · 个人信息（B 级）</span>}
          <div className="muted small">
            {fact.source ?? "—"}{fact.sourceDeleted && "（来源已删除）"}
            {fact.claim && ` · 论断 ${fact.claim}`}{fact.confirmedAt && ` · ${fact.confirmedAt} 确认`}
          </div>
        </div>
      </div>
      {fact.value !== null ? (
        <div className="row"><input value={value} onChange={(e) => setValue(e.target.value)} /></div>
      ) : (
        <div className="form-grid bilingual">
          <label><span className="muted small">中文<Unreviewed reviewed={fact.reviewed} lang="zh" present={!!fact.zh} /></span>
            <input value={zh} onChange={(e) => setZh(e.target.value)} /></label>
          <label><span className="muted small">English<Unreviewed reviewed={fact.reviewed} lang="en" present={!!fact.en} /></span>
            <input value={en} onChange={(e) => setEn(e.target.value)} /></label>
        </div>
      )}
      <div className="row">
        {changed && (
          <button onClick={() => void save(fact.value !== null ? { value } : { zh, en })}>保存</button>
        )}
        {!changed && unreviewed && (
          <button onClick={() => void save({ reviewed: [fact.zh && "zh", fact.en && "en"].filter(Boolean) as string[] })}>
            标为已审阅
          </button>
        )}
      </div>
      {error && <p className="error">{error}</p>}
    </div>
  );
}

function CardView({ card, onSaved }: { card: ProfileCard; onSaved: () => void }) {
  const initial = useCallback(() => {
    const values: Record<string, string> = { "title.zh": card.title?.zh ?? "", "title.en": card.title?.en ?? "" };
    for (const lang of ["zh", "en"] as const) {
      card[lang].forEach((s) => { values[`${lang}.${s.id}`] = s.text; });
    }
    return values;
  }, [card]);
  const [draft, setDraft] = useState<Record<string, string>>(initial);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => setDraft(initial()), [initial]);
  const base = initial();
  const changed = Object.keys(draft).some((k) => draft[k] !== base[k]);
  const ids = (card.zh.length > 0 ? card.zh : card.en).map((s) => s.id);
  const heading = (lang: "zh" | "en", id: string) => card[lang].find((s) => s.id === id)?.heading ?? id;

  async function save(markReviewed: boolean) {
    const body: Parameters<typeof api.editProject>[1] = {
      title: { zh: draft["title.zh"], en: draft["title.en"] },
    };
    for (const lang of ["zh", "en"] as const) {
      const sections: Record<string, string> = {};
      ids.forEach((id) => {
        if ((draft[`${lang}.${id}`] ?? "") !== (base[`${lang}.${id}`] ?? "")) {
          sections[id] = draft[`${lang}.${id}`] ?? "";
        }
      });
      if (Object.keys(sections).length > 0) {
        body[lang] = sections;
      }
    }
    if (markReviewed) {
      body.reviewed = ["zh", "en"];
    }
    try {
      await api.editProject(card.slug, body);
      setError(null);
      onSaved();
    } catch (e) {
      setError(errorText(e));
    }
  }

  const set = (name: string) => (e: { target: { value: string } }) => setDraft({ ...draft, [name]: e.target.value });
  return (
    <div className="provider">
      <div className="provider-head">
        <div>
          <strong className="mono">{card.slug}</strong>
          <div className="muted small">
            {[card.kind, card.period && `${card.period.from ?? "?"} – ${card.period.to ?? "?"}`,
              card.teamSize && `${card.teamSize} 人`, card.stack.join("、"), card.sources.join("、")]
              .filter(Boolean).join(" · ")}
            {card.roleClaim && ` · 论断 ${card.roleClaim}`}
          </div>
        </div>
      </div>
      <div className="form-grid bilingual">
        <label><span className="muted small">中文名</span><input value={draft["title.zh"]} onChange={set("title.zh")} /></label>
        <label><span className="muted small">English title</span><input value={draft["title.en"]} onChange={set("title.en")} /></label>
        {ids.map((id) => (["zh", "en"] as const).map((lang) => (
          <label key={`${lang}.${id}`}>
            <span className="muted small">{lang} · {heading(lang, id)}
              <Unreviewed reviewed={card.reviewed} lang={lang} present={card[lang].length > 0} /></span>
            <textarea rows={3} value={draft[`${lang}.${id}`] ?? ""} onChange={set(`${lang}.${id}`)} />
          </label>
        )))}
      </div>
      <div className="row">
        {changed && <button onClick={() => void save(false)}>保存</button>}
        <button onClick={() => void save(true)}>{changed ? "保存并标为已审阅" : "标为已审阅"}</button>
      </div>
      {error && <p className="error">{error}</p>}
    </div>
  );
}

function texts(sections: Section[]): Record<string, string> {
  const values: Record<string, string> = {};
  sections.forEach((s) => { values[s.id] = s.text; });
  return values;
}

function Summary({ profile, onSaved }: { profile: ProfileData; onSaved: () => void }) {
  const current = profile.summary;
  const [candidate, setCandidate] = useState<SummaryCandidate | null>(null);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function generate() {
    setBusy(true);
    try {
      const result = await api.generateSummary();
      const values: Record<string, string> = {};
      for (const lang of ["zh", "en"] as const) {
        result[lang].forEach((s) => { values[`${lang}.${s.id}`] = s.text; });
      }
      setCandidate(result);
      setDraft(values);
      setError(null);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function confirm() {
    if (!candidate) {
      return;
    }
    const generated: Record<string, string> = {};
    for (const lang of ["zh", "en"] as const) {
      candidate[lang].forEach((s) => { generated[`${lang}.${s.id}`] = s.text; });
    }
    // 用户这次手改过的小节，加上以前标过、这次没有采用建议的小节，以后重新生成时不覆盖
    const edited = new Set(candidate.userEdited);
    Object.keys(draft).forEach((mark) => {
      if (draft[mark] !== generated[mark]) {
        edited.add(mark);
      }
      if (candidate.suggestions[mark] !== undefined && draft[mark] === candidate.suggestions[mark]) {
        edited.delete(mark);
      }
    });
    const pick = (lang: "zh" | "en") => Object.fromEntries(SECTION_IDS.map((id) => [id, draft[`${lang}.${id}`] ?? ""]));
    setBusy(true);
    try {
      await api.confirmSummary({ zh: pick("zh"), en: pick("en"), userEdited: [...edited], reviewed: ["zh", "en"] });
      setCandidate(null);
      setError(null);
      onSaved();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const currentTexts = current ? { zh: texts(current.zh), en: texts(current.en) } : null;
  return (
    <section className="card">
      <h2>画像</h2>
      <p className="muted small">画像只根据事实层生成（不读来源原文），中英双语四节；你手改过的小节重新生成时不覆盖，只给建议。</p>
      {current && !candidate && (
        <div className="form-grid bilingual">
          {(["zh", "en"] as const).map((lang) => (
            <div key={lang}>
              {current[lang].map((s) => (
                <div key={s.id}>
                  <strong>{s.heading}</strong>
                  {current.userEdited.includes(`${lang}.${s.id}`) && <span className="muted small"> · 用户编辑</span>}
                  <p className="small" style={{ whiteSpace: "pre-wrap" }}>{s.text || "—"}</p>
                </div>
              ))}
            </div>
          ))}
          <p className="muted small wide">{current.generatedAt} 确认</p>
        </div>
      )}
      {candidate && (
        <>
          {candidate.warnings.map((w, i) => <p key={i} className="warn">{w}</p>)}
          <div className="form-grid bilingual">
            {SECTION_IDS.map((id) => (["zh", "en"] as const).map((lang) => {
              const mark = `${lang}.${id}`;
              const heading = candidate[lang].find((s) => s.id === id)?.heading ?? id;
              const before = currentTexts?.[lang][id];
              return (
                <label key={mark}>
                  <span className="muted small">{lang} · {heading}
                    {candidate.userEdited.includes(mark) && " · 用户编辑（保留）"}</span>
                  <textarea rows={4} value={draft[mark] ?? ""}
                    onChange={(e) => setDraft({ ...draft, [mark]: e.target.value })} />
                  {before !== undefined && before !== draft[mark] && (
                    <span className="muted small" style={{ whiteSpace: "pre-wrap" }}>现在：{before || "—"}</span>
                  )}
                  {candidate.suggestions[mark] !== undefined && (
                    <span className="small">
                      建议：<span style={{ whiteSpace: "pre-wrap" }}>{candidate.suggestions[mark]}</span>{" "}
                      <button onClick={() => setDraft({ ...draft, [mark]: candidate.suggestions[mark] })}>采用建议</button>
                    </span>
                  )}
                </label>
              );
            }))}
          </div>
        </>
      )}
      <div className="row">
        <button disabled={busy} onClick={() => void generate()}>{busy && !candidate ? "生成中…" : "生成画像"}</button>
        {candidate && <button disabled={busy} onClick={() => void confirm()}>确认替换</button>}
        {candidate && <button disabled={busy} onClick={() => setCandidate(null)}>放弃</button>}
      </div>
      {error && <p className="error">{error}</p>}
    </section>
  );
}

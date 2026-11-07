import { useCallback, useEffect, useRef, useState } from "react";
import { api, errorText, type Assignment, type ModelSettings as Settings, type Preset, type Provider,
  type ProviderHeader, type TestResult, type Thinking, type Usage } from "../api";
import { toast } from "../toast";

const USAGES: { usage: Usage; label: string; hint: string }[] = [
  { usage: "main", label: "主模型", hint: "对话、规划、填表循环；必须通过工具调用测试" },
  { usage: "auxiliary", label: "辅助模型", hint: "长页面摘要、资料的一句说明等；便宜、快的模型" },
  { usage: "cross_check", label: "交叉核验（可选）", hint: "复核匹配评分；最好和主模型不是同一家" },
];

const PRIVACY =
  "填表与对话时，A / B 级信息（简历内容、联系方式等）会发送给所选的模型供应商；C 级信息（证件、工作授权、薪资等）永不发送。";

const USAGE_LABEL: Record<Usage, string> = { main: "主模型", auxiliary: "辅助模型", cross_check: "交叉核验" };
const usageNames = (usages: Usage[]) => usages.map((u) => USAGE_LABEL[u] ?? u).join("、");

type Format = "openai" | "anthropic";

// 思考强度（C027 / C027-fix）：值为空 = 默认，不发参数。按模型给档位，没有档位的模型不显示下拉
type ThinkingOption = { value: Thinking | ""; label: string };
const DEEPSEEK_THINKING: ThinkingOption[] = [
  { value: "", label: "默认（模型自己的，现在是高）" },
  { value: "off", label: "关（最快）" },
  { value: "low", label: "低" },
  { value: "high", label: "高" },
  { value: "max", label: "最高" },
];
// Gemini 2.5 Flash / Flash-Lite 能关（reasoning_effort=none）；3.x 和 2.5 Pro 关不掉，低就是最快
const GEMINI_25_FLASH_THINKING: ThinkingOption[] = [
  { value: "", label: "默认（模型自己的）" },
  { value: "off", label: "关（最快）" },
  { value: "low", label: "低" },
  { value: "medium", label: "中" },
  { value: "high", label: "高" },
];
const GEMINI_THINKING: ThinkingOption[] = [
  { value: "", label: "默认（模型自己的）" },
  { value: "low", label: "低（最快）" },
  { value: "medium", label: "中" },
  { value: "high", label: "高" },
];
const FORMAT_LABEL: Record<Format, string> = { openai: "OpenAI 兼容", anthropic: "Anthropic" };

export default function ModelSettings() {
  const [settings, setSettings] = useState<Settings | null>(null);
  const [error, setError] = useState<string | null>(null);
  // 刚因删除提供方 / 模型而清掉的用途（C025-fix）：用途分配里提示重新选
  const [cleared, setCleared] = useState<Usage[]>([]);

  const reload = useCallback(async () => {
    try {
      setSettings(await api.modelSettings());
      setError(null);
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  useEffect(() => {
    void reload();
  }, [reload]);

  if (!settings) {
    return <section className="card">{error ? <p className="error">{error}</p> : <p>加载中…</p>}</section>;
  }
  return (
    <>
      {error && <p className="error">{error}</p>}
      <section className="card">
        <h2>模型提供方</h2>
        {settings.providers.length === 0 && <p className="muted">还没有提供方。先在下面添加一个。</p>}
        {settings.providers.map((provider) => (
          <ProviderCard key={provider.id} provider={provider} presets={settings.presets}
            assignments={settings.assignments} onChanged={reload}
            onCleared={(usages) => setCleared((old) => [...new Set([...old, ...usages])])} />
        ))}
      </section>
      <AddProvider presets={settings.presets} existing={settings.providers.map((p) => p.id)} onAdded={reload} />
      <Assignments settings={settings} cleared={cleared}
        onSaved={() => { setCleared([]); void reload(); }} />
    </>
  );
}

function AddProvider({ presets, existing, onAdded }: { presets: Preset[]; existing: string[]; onAdded: () => void }) {
  const [presetId, setPresetId] = useState(presets[0]?.id ?? "custom");
  const preset = presets.find((p) => p.id === presetId);
  const [id, setId] = useState(presets[0] && presets[0].id !== "custom" ? `${presets[0].id}-main` : "");
  const [format, setFormat] = useState<Format>("openai");
  const [baseUrl, setBaseUrl] = useState(preset?.baseUrl ?? "");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const fixedFormat = preset?.format ?? null;

  function choose(next: string) {
    setPresetId(next);
    const chosen = presets.find((p) => p.id === next);
    setBaseUrl(chosen?.baseUrl ?? "");
    if (chosen?.format) {
      setFormat(chosen.format);
    }
    if (!id || presets.some((p) => `${p.id}-main` === id)) {
      setId(next === "custom" ? "" : `${next}-main`);
    }
  }

  async function add() {
    setBusy(true);
    try {
      await api.saveProvider(id.trim(), {
        preset: presetId,
        format: fixedFormat ?? format,
        baseUrl: baseUrl.trim(),
        models: [],
      });
      setError(null);
      toast(`已添加 ${id.trim()}`);
      setId(presetId === "custom" ? "" : `${presetId}-main`);
      onAdded();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <h2>添加提供方</h2>
      <div className="form-grid">
        <label>
          预设
          <select value={presetId} onChange={(e) => choose(e.target.value)}>
            {presets.map((p) => (
              <option key={p.id} value={p.id}>{p.label}</option>
            ))}
          </select>
        </label>
        <div className="ms-field">
          <span>接口格式</span>
          {fixedFormat ? (
            <span><span className="chip">{FORMAT_LABEL[fixedFormat]}</span></span>
          ) : (
            <div className="seg ms-seg" role="group" aria-label="接口格式">
              {(["openai", "anthropic"] as Format[]).map((f) => (
                <button key={f} type="button" aria-pressed={format === f} onClick={() => setFormat(f)}>
                  {f === "openai" ? "OpenAI 兼容" : "Anthropic"}
                </button>
              ))}
            </div>
          )}
        </div>
        <label className="wide">
          Base URL（也可以写占位符 {"${变量名}"}）
          <input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} />
        </label>
      </div>
      {preset?.note && <p className="muted small">{preset.note}</p>}
      <details className="ms-advanced">
        <summary>高级 · id：<span className="mono">{id.trim() || "（未填）"}</span></summary>
        <label>
          id（小写字母、数字、-；按预设自动生成）
          <input value={id} placeholder="例如 deepseek-main" onChange={(e) => setId(e.target.value)} />
        </label>
        {existing.includes(id.trim()) && <p className="muted small">这个 id 已经存在，会修改它的设置。</p>}
      </details>
      <div className="ms-actions">
        <button className="primary" disabled={busy || !id.trim()} onClick={add}>添加</button>
        <span className="muted small">添加后再填 Key、拉取模型列表。</span>
      </div>
      {error && <p className="error">{error}</p>}
    </section>
  );
}

function ProviderCard({ provider, presets, assignments, onChanged, onCleared }: {
  provider: Provider;
  presets: Preset[];
  assignments: Settings["assignments"];
  onChanged: () => void;
  onCleared: (usages: Usage[]) => void;
}) {
  const preset = presets.find((p) => p.id === provider.preset);
  const [key, setKey] = useState("");
  const [catalog, setCatalog] = useState<string[] | null>(null);
  const [selected, setSelected] = useState<string[]>(provider.models);
  const [search, setSearch] = useState("");
  const [tests, setTests] = useState<Record<string, TestResult>>({});
  const [testing, setTesting] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => setSelected(provider.models), [provider.models]);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    try {
      await action();
      setError(null);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  const saveKey = () =>
    run(async () => {
      await api.saveKey(provider.id, key);
      setKey(""); // 保存后清空，页面上只显示末四位
      toast("Key 已保存");
      onChanged();
    });

  const loadCatalog = () =>
    run(async () => {
      const models = (await api.catalog(provider.id)).models;
      setCatalog(models);
      toast(`拉到 ${models.length} 个模型`);
    });

  // 指向这个提供方、某个模型的用途
  const usagesOf = (model: string) =>
    (Object.keys(USAGE_LABEL) as Usage[]).filter((u) => assignments[u]?.provider === provider.id && assignments[u]?.model === model);
  const providerUsages = provider.usedBy as Usage[];

  // 保存模型选择；去掉的模型正被用途使用时，后端会清掉那条分配（C025-fix），这里先说一声
  const persistModels = (models: string[], message: string) =>
    run(async () => {
      const lost = provider.models.filter((m) => !models.includes(m)).flatMap(usagesOf);
      if (lost.length > 0 && !window.confirm(`会一起清掉用途分配里的${usageNames(lost)}，之后要重新分配。继续？`)) {
        setSelected(provider.models);
        return;
      }
      await api.saveProvider(provider.id, {
        preset: provider.preset,
        format: provider.format,
        baseUrl: provider.baseUrl,
        models,
      });
      if (lost.length > 0) {
        onCleared(lost);
      }
      toast(message);
      onChanged();
    });

  const saveModels = () => persistModels(selected, "模型选择已保存");

  // 已保存的模型点 ×：立即保存，免得只在页面上消失、刷新后又回来
  function removeModel(model: string) {
    const next = selected.filter((m) => m !== model);
    if (provider.models.includes(model)) {
      void persistModels(next, `已移除 ${model}`); // 成功后重新加载；失败时模型留在列表里，错误显示在删除按钮旁
    } else {
      setSelected(next);
    }
  }

  const remove = () =>
    run(async () => {
      const also = providerUsages.length > 0 ? `\n用途分配里的${usageNames(providerUsages)}也会清空，之后要重新分配。` : "";
      if (window.confirm(`删除提供方 ${provider.id}？它在 .env 里的 Key 也会一起删掉。${also}`)) {
        await api.deleteProvider(provider.id);
        onCleared(providerUsages);
        toast(`已删除 ${provider.id}`);
        onChanged();
      }
    });

  async function test(model: string) {
    setTesting(model);
    try {
      const result = await api.test(provider.id, model);
      setTests((old) => ({ ...old, [model]: result }));
      setError(null);
      onChanged();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setTesting(null);
    }
  }

  function toggle(model: string) {
    setSelected((old) => (old.includes(model) ? old.filter((m) => m !== model) : [...old, model]));
  }

  function addModel(raw: string) {
    const name = raw.trim();
    if (name) {
      setSelected((old) => (old.includes(name) ? old : [...old, name]));
    }
    setSearch("");
  }

  function onSearch(value: string) {
    // 从 datalist 里选中一项时，值和目录里的某个模型完全一致：直接加入
    if (catalog?.includes(value) && !selected.includes(value)) {
      addModel(value);
    } else {
      setSearch(value);
    }
  }

  const dirty = selected.join("\n") !== provider.models.join("\n");
  const listId = `ms-catalog-${provider.id}`;
  const keyNotNeeded = !!preset && !preset.keyRequired;

  return (
    <div className="provider">
      <div className="provider-head">
        <div>
          <strong>{provider.id}</strong>{" "}
          <span className="chip muted">{preset?.label ?? provider.preset}</span>{" "}
          <span className="chip">{FORMAT_LABEL[provider.format]}</span>{" "}
          {providerUsages.length > 0 && <span className="chip good">用作 {usageNames(providerUsages)}</span>}
          <div className="mono muted small">{provider.baseUrl}</div>
        </div>
        <div className="ms-head-actions">
          {error && <span className="error small">{error}</span>}
          <button className="danger btn-xs" disabled={busy} onClick={remove}>删除</button>
        </div>
      </div>

      <div className="row">
        <span>
          Key{" "}
          {provider.keySet ? <span className="chip good">已设置 ····{provider.keyLast4}</span>
            : <span className="chip muted">{keyNotNeeded ? "本地不需要" : "未设置"}</span>}
        </span>
        <input type="password" autoComplete="off" value={key} placeholder={`写进 storage/settings/.env 的 ${provider.keyVariable}`}
          onChange={(e) => setKey(e.target.value)} />
        <button className={key.trim() ? "primary" : undefined} disabled={busy || !key.trim()} onClick={saveKey}>保存 Key</button>
      </div>

      <div className="row">
        <button disabled={busy} onClick={loadCatalog}>拉取模型列表</button>
        <input list={listId} value={search} spellCheck={false} autoComplete="off"
          placeholder={catalog ? "搜索模型，回车或选中即加入" : "手填模型名，回车加入（拉到列表后可搜索）"}
          onChange={(e) => onSearch(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              addModel(search);
            }
          }} />
        <datalist id={listId}>
          {(catalog ?? []).filter((m) => !selected.includes(m)).map((m) => <option key={m} value={m} />)}
        </datalist>
        <button disabled={!search.trim()} onClick={() => addModel(search)}>加入</button>
      </div>

      {catalog && <CatalogPanel catalog={catalog} selected={selected} onToggle={toggle} />}

      {selected.length > 0 && (
        <ul className="models ms-selected">
          {selected.map((model) => {
            const saved = provider.models.includes(model);
            return (
              <li key={model}>
                <span className="mono">{model}</span>
                {saved ? (
                  <button className="btn-xs" disabled={testing !== null} onClick={() => test(model)}>
                    {testing === model ? "测试中…" : "测试"}
                  </button>
                ) : <span className="chip inp">未保存</span>}
                <TestSummary result={tests[model]} record={provider.tested.find((t) => t.model === model)} />
                {saved && usagesOf(model).length > 0 && <span className="chip">用作 {usageNames(usagesOf(model))}</span>}
                <button className="ghost btn-xs ms-remove" title="移除" aria-label={`移除 ${model}`} disabled={busy}
                  onClick={() => removeModel(model)}>×</button>
              </li>
            );
          })}
        </ul>
      )}
      {(dirty || provider.models.length > 0) && (
        <button className={dirty ? "primary" : undefined} disabled={busy || !dirty} onClick={saveModels}>保存模型选择</button>
      )}

      <details className="ms-advanced">
        <summary>高级 · 自定义请求头{provider.headers.length > 0 ? `（${provider.headers.length}）` : ""}</summary>
        <HeadersEditor provider={provider} onChanged={onChanged} />
      </details>
    </div>
  );
}

// 拉到的模型目录：默认收起的下拉面板，展开后可筛选、勾选；点面板外或再点面板头收起
function CatalogPanel({ catalog, selected, onToggle }: { catalog: string[]; selected: string[]; onToggle: (m: string) => void }) {
  const [open, setOpen] = useState(false);
  const [filter, setFilter] = useState("");
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) {
      return;
    }
    const close = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, [open]);

  const all = Array.from(new Set([...selected, ...catalog]));
  const q = filter.trim().toLowerCase();
  const shown = all
    .filter((m) => !q || m.toLowerCase().includes(q))
    .sort((a, b) => Number(selected.includes(b)) - Number(selected.includes(a)));

  return (
    <div className="ms-panel" ref={ref}>
      <button type="button" className="ms-panel-head" aria-expanded={open} onClick={() => setOpen(!open)}>
        <span>已选 {selected.length} 个 · 共 {catalog.length} 个</span>
        <span className="muted">{open ? "▴" : "▾"}</span>
      </button>
      {open && (
        <div className="ms-panel-body">
          <input value={filter} autoFocus spellCheck={false} placeholder="筛选" onChange={(e) => setFilter(e.target.value)} />
          <ul className="ms-panel-list">
            {shown.map((model) => (
              <li key={model}>
                <label>
                  <input type="checkbox" checked={selected.includes(model)} onChange={() => onToggle(model)} />
                  <span className="mono">{model}</span>
                </label>
              </li>
            ))}
            {shown.length === 0 && <li className="muted small">没有匹配的模型</li>}
          </ul>
        </div>
      )}
    </div>
  );
}

interface HeaderRow {
  name: string;
  value: string;
  secret: boolean;
  set: boolean;
}

function rowsOf(headers: ProviderHeader[]): HeaderRow[] {
  return headers.map((h) => ({ name: h.name, value: h.secret ? "" : h.value ?? "", secret: h.secret, set: h.set }));
}

function HeadersEditor({ provider, onChanged }: { provider: Provider; onChanged: () => void }) {
  const [rows, setRows] = useState<HeaderRow[]>(() => rowsOf(provider.headers));
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => setRows(rowsOf(provider.headers)), [provider.headers]);

  const strip = (list: HeaderRow[]) => JSON.stringify(list.map(({ name, value, secret }) => [name, value, secret]));
  const dirty = strip(rows) !== strip(rowsOf(provider.headers));

  function update(index: number, patch: Partial<HeaderRow>) {
    setRows((old) => old.map((row, i) => (i === index ? { ...row, ...patch } : row)));
  }

  async function save() {
    setBusy(true);
    try {
      const headers = rows
        .filter((r) => r.name.trim() || r.value.trim())
        .map((r) => ({ name: r.name.trim(), value: r.value, secret: r.secret }));
      await api.saveHeaders(provider.id, headers);
      setError(null);
      toast("请求头已保存");
      onChanged();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="ms-headers">
      {rows.length > 0 && (
        <table className="ms-headers-table">
          <thead>
            <tr><th>名字</th><th>值</th><th>密钥</th><th /></tr>
          </thead>
          <tbody>
            {rows.map((row, i) => (
              <tr key={i}>
                <td>
                  <input className="mono" value={row.name} spellCheck={false} autoComplete="off" placeholder="X-Header-Name"
                    onChange={(e) => update(i, { name: e.target.value })} />
                </td>
                <td>
                  <input type={row.secret ? "password" : "text"} value={row.value} spellCheck={false} autoComplete="off"
                    placeholder={row.secret ? (row.set ? "已设置（留空不改）" : "未设置") : "值或 ${变量名}"}
                    onChange={(e) => update(i, { value: e.target.value })} />
                </td>
                <td>
                  <input type="checkbox" checked={row.secret} aria-label="密钥"
                    onChange={(e) => update(i, { secret: e.target.checked })} />
                </td>
                <td>
                  <button className="ghost btn-xs" title="删除这一行" aria-label="删除这一行"
                    onClick={() => setRows((old) => old.filter((_, j) => j !== i))}>×</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <div className="ms-actions">
        <button className="btn-xs" onClick={() => setRows((old) => [...old, { name: "", value: "", secret: false, set: false }])}>
          添加一行
        </button>
        <button className={dirty ? "primary btn-xs" : "btn-xs"} disabled={busy || !dirty} onClick={save}>保存请求头</button>
      </div>
      <p className="muted small">
        值可以写 {"${变量名}"}，运行时和 Key 一样从 .env 解析；勾「密钥」的值写进 storage/settings/.env，models.yaml 里只留占位符。
      </p>
      {error && <p className="error">{error}</p>}
    </div>
  );
}

function TestSummary({ result, record }: { result?: TestResult; record?: Provider["tested"][number] }) {
  if (result) {
    if (!result.ok) {
      return (
        <>
          <span className="chip bad">失败</span>
          <span className="error small ms-test-error">{result.error}</span>
        </>
      );
    }
    return (
      <span className={result.supportsTools ? "chip good" : "chip wait"}
        title={result.promptTokens != null ? `${result.model} · ${result.promptTokens} / ${result.completionTokens} tokens` : result.model}>
        {result.supportsTools ? "支持工具调用" : "不支持工具调用"} · {result.latencyMs} ms
      </span>
    );
  }
  if (record) {
    const cls = !record.ok ? "chip bad" : record.tools ? "chip good" : "chip wait";
    return (
      <span className={cls} title="上次测试的结果">
        上次：{record.ok ? (record.tools ? "支持工具调用" : "不支持") : "失败"} · {record.latencyMs} ms
      </span>
    );
  }
  return null;
}

function Assignments({ settings, cleared, onSaved }: { settings: Settings; cleared: Usage[]; onSaved: () => void }) {
  const [values, setValues] = useState<Record<Usage, string>>(() => initial(settings));
  const [thinking, setThinking] = useState<Record<Usage, Thinking | "">>(() => initialThinking(settings));
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    setValues(initial(settings));
    setThinking(initialThinking(settings));
  }, [settings]);

  // DeepSeek / Gemini（OpenAI 格式、模型名含 deepseek / gemini）才有「思考」下拉；其它模型保存时不带 thinking
  const thinkingOptions = (value: string): ThinkingOption[] | null => {
    const parsed = parse(value);
    const provider = parsed && settings.providers.find((p) => p.id === parsed.provider);
    if (!parsed || provider?.format !== "openai") {
      return null;
    }
    const model = parsed.model.toLowerCase();
    if (model.includes("deepseek")) {
      return DEEPSEEK_THINKING;
    }
    if (model.includes("gemini")) {
      return model.includes("gemini-2.5-flash") ? GEMINI_25_FLASH_THINKING : GEMINI_THINKING;
    }
    return null;
  };
  // 存的值不在当前模型的档位里（比如从 DeepSeek「最高」换到 Gemini）就当默认，保存时不带
  const thinkingValue = (usage: Usage): Thinking | "" => {
    const choices = thinkingOptions(values[usage]);
    return choices?.some((option) => option.value === thinking[usage]) ? thinking[usage] : "";
  };

  const assignment = (usage: Usage): Assignment | null => {
    const parsed = parse(values[usage]);
    const value = thinkingValue(usage);
    if (!parsed || !value) {
      return parsed;
    }
    return { ...parsed, thinking: value };
  };

  const options = settings.providers.flatMap((provider) =>
    provider.models.map((model) => {
      const test = provider.tested.find((t) => t.model === model);
      return { value: `${provider.id}\n${model}`, label: `${provider.id} / ${model}`, tools: !!test?.ok && !!test.tools };
    }),
  );

  async function save() {
    setBusy(true);
    try {
      await api.assign(assignment("main"), assignment("auxiliary"), assignment("cross_check"));
      setError(null);
      toast("已保存，立即生效");
      onSaved();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <h2>用途分配</h2>
      <div className="form-grid">
        {USAGES.map(({ usage, label, hint }) => (
          <div key={usage} className="usage">
            <label>
              {label}
              <select value={values[usage]} onChange={(e) => setValues({ ...values, [usage]: e.target.value })}>
                <option value="">（不分配）</option>
                {options.map((option) => (
                  <option key={option.value} value={option.value} disabled={usage === "main" && !option.tools}>
                    {option.label}{usage === "main" && !option.tools ? "（没有通过工具调用测试）" : ""}
                  </option>
                ))}
              </select>
              <span className="muted small">{hint}</span>
              {cleared.includes(usage) && !settings.assignments[usage] && (
                <span className="error small">随删除清空了，请重新选一个再保存</span>
              )}
            </label>
            {thinkingOptions(values[usage]) && (
              <label>
                思考
                <select value={thinkingValue(usage)}
                  onChange={(e) => setThinking({ ...thinking, [usage]: e.target.value as Thinking | "" })}>
                  {thinkingOptions(values[usage])!.map((option) => (
                    <option key={option.value} value={option.value}>{option.label}</option>
                  ))}
                </select>
              </label>
            )}
          </div>
        ))}
      </div>
      <div className="ms-actions">
        <button className="primary" disabled={busy} onClick={save}>保存分配</button>
      </div>
      {error && <p className="error">{error}</p>}
      <p className="muted small ms-footnote">
        思考过程：OpenAI 兼容的推理模型（例如 DeepSeek）返回了思考内容时，会显示在对话的「过程」里；Anthropic 格式暂不开启思考（现用的
        Spring AI 版本没法在工具调用的多轮对话里把思考原样发回给模型）。{PRIVACY}
      </p>
    </section>
  );
}

function initial(settings: Settings): Record<Usage, string> {
  const value = (a?: Assignment) => (a ? `${a.provider}\n${a.model}` : "");
  return {
    main: value(settings.assignments.main),
    auxiliary: value(settings.assignments.auxiliary),
    cross_check: value(settings.assignments.cross_check),
  };
}

function initialThinking(settings: Settings): Record<Usage, Thinking | ""> {
  const value = (a?: Assignment) => a?.thinking ?? "";
  return {
    main: value(settings.assignments.main),
    auxiliary: value(settings.assignments.auxiliary),
    cross_check: value(settings.assignments.cross_check),
  };
}

function parse(value: string): Assignment | null {
  if (!value) {
    return null;
  }
  const [provider, model] = value.split("\n");
  return { provider, model };
}

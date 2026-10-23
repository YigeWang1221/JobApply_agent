import { useCallback, useEffect, useState } from "react";
import { api, errorText, type Assignment, type ModelSettings as Settings, type Preset, type Provider,
  type TestResult, type Usage } from "../api";

const USAGES: { usage: Usage; label: string; hint: string }[] = [
  { usage: "main", label: "主模型", hint: "对话、规划、填表循环；必须通过工具调用测试" },
  { usage: "auxiliary", label: "辅助模型", hint: "长页面摘要、资料的一句说明等；便宜、快的模型" },
  { usage: "cross_check", label: "交叉核验（可选）", hint: "复核匹配评分；最好和主模型不是同一家" },
];

const PRIVACY =
  "填表与对话时，A / B 级信息（简历内容、联系方式等）会发送给所选的模型供应商；C 级信息（证件、工作授权、薪资等）永不发送。";

export default function ModelSettings() {
  const [settings, setSettings] = useState<Settings | null>(null);
  const [error, setError] = useState<string | null>(null);

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
          <ProviderCard key={provider.id} provider={provider} presets={settings.presets} onChanged={reload} />
        ))}
      </section>
      <AddProvider presets={settings.presets} existing={settings.providers.map((p) => p.id)} onAdded={reload} />
      <Assignments settings={settings} onSaved={reload} />
    </>
  );
}

function AddProvider({ presets, existing, onAdded }: { presets: Preset[]; existing: string[]; onAdded: () => void }) {
  const [presetId, setPresetId] = useState(presets[0]?.id ?? "custom");
  const preset = presets.find((p) => p.id === presetId);
  const [id, setId] = useState("");
  const [format, setFormat] = useState<"openai" | "anthropic">("openai");
  const [baseUrl, setBaseUrl] = useState(preset?.baseUrl ?? "");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

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
        format: preset?.format ?? format,
        baseUrl: baseUrl.trim(),
        models: [],
      });
      setError(null);
      setId("");
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
        <label>
          id（小写字母、数字、-）
          <input value={id} placeholder="例如 deepseek-main" onChange={(e) => setId(e.target.value)} />
        </label>
        <label>
          接口格式
          <select value={preset?.format ?? format} disabled={!!preset?.format}
            onChange={(e) => setFormat(e.target.value as "openai" | "anthropic")}>
            <option value="openai">OpenAI 兼容（Chat Completions）</option>
            <option value="anthropic">Anthropic（Messages）</option>
          </select>
        </label>
        <label className="wide">
          Base URL（也可以写占位符 {"${变量名}"}）
          <input value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} />
        </label>
      </div>
      {preset?.note && <p className="muted">{preset.note}</p>}
      {existing.includes(id.trim()) && <p className="muted">这个 id 已经存在，会修改它的设置。</p>}
      <button disabled={busy || !id.trim()} onClick={add}>添加</button>
      <p className="muted">添加后再填 Key、拉取模型列表。</p>
      {error && <p className="error">{error}</p>}
    </section>
  );
}

function ProviderCard({ provider, presets, onChanged }: { provider: Provider; presets: Preset[]; onChanged: () => void }) {
  const preset = presets.find((p) => p.id === provider.preset);
  const [key, setKey] = useState("");
  const [catalog, setCatalog] = useState<string[] | null>(null);
  const [selected, setSelected] = useState<string[]>(provider.models);
  const [manual, setManual] = useState("");
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
      onChanged();
    });

  const loadCatalog = () =>
    run(async () => {
      setCatalog((await api.catalog(provider.id)).models);
    });

  const saveModels = () =>
    run(async () => {
      await api.saveProvider(provider.id, {
        preset: provider.preset,
        format: provider.format,
        baseUrl: provider.baseUrl,
        models: selected,
      });
      onChanged();
    });

  const remove = () =>
    run(async () => {
      if (window.confirm(`删除提供方 ${provider.id}？它在 .env 里的 Key 也会一起删掉。`)) {
        await api.deleteProvider(provider.id);
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

  function addManual() {
    const name = manual.trim();
    if (name && !selected.includes(name)) {
      setSelected((old) => [...old, name]);
    }
    setManual("");
  }

  const choices = Array.from(new Set([...(catalog ?? []), ...selected]));
  const dirty = selected.join("\n") !== provider.models.join("\n");

  return (
    <div className="provider">
      <div className="provider-head">
        <div>
          <strong>{provider.id}</strong>{" "}
          <span className="muted">{preset?.label ?? provider.preset} · {provider.format === "openai" ? "OpenAI 兼容" : "Anthropic"}</span>
          <div className="mono muted">{provider.baseUrl}</div>
        </div>
        <button className="danger" disabled={busy} onClick={remove}>删除</button>
      </div>

      <div className="row">
        <span>
          Key：{provider.keySet ? <span className="ok">已设置 ····{provider.keyLast4}</span>
            : <span className="muted">{preset && !preset.keyRequired ? "本地模型不需要" : "未设置"}</span>}
        </span>
        <input type="password" autoComplete="off" value={key} placeholder={`写进 storage/settings/.env 的 ${provider.keyVariable}`}
          onChange={(e) => setKey(e.target.value)} />
        <button disabled={busy || !key.trim()} onClick={saveKey}>保存 Key</button>
      </div>

      <div className="row">
        <button disabled={busy} onClick={loadCatalog}>拉取模型列表</button>
        <input value={manual} placeholder="拉不到时手填模型名" onChange={(e) => setManual(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && addManual()} />
        <button disabled={!manual.trim()} onClick={addManual}>加入</button>
      </div>

      {choices.length > 0 && (
        <ul className="models">
          {choices.map((model) => {
            const saved = provider.models.includes(model);
            const result = tests[model];
            const record = provider.tested.find((t) => t.model === model);
            return (
              <li key={model}>
                <label>
                  <input type="checkbox" checked={selected.includes(model)} onChange={() => toggle(model)} />
                  <span className="mono">{model}</span>
                </label>
                {saved && (
                  <button disabled={testing !== null} onClick={() => test(model)}>
                    {testing === model ? "测试中…" : "测试"}
                  </button>
                )}
                <TestSummary result={result} record={record} />
              </li>
            );
          })}
        </ul>
      )}
      {dirty && <button disabled={busy} onClick={saveModels}>保存模型选择</button>}
      {error && <p className="error">{error}</p>}
    </div>
  );
}

function TestSummary({ result, record }: { result?: TestResult; record?: Provider["tested"][number] }) {
  if (result) {
    if (!result.ok) {
      return <span className="error">失败：{result.error}</span>;
    }
    return (
      <span className={result.supportsTools ? "ok" : "warn"}>
        {result.latencyMs} ms · {result.model} · {result.supportsTools ? "支持工具调用" : "不支持工具调用"}
        {result.promptTokens != null && ` · ${result.promptTokens} / ${result.completionTokens} tokens`}
      </span>
    );
  }
  if (record) {
    return (
      <span className="muted">
        上次测试：{record.ok ? (record.tools ? "支持工具调用" : "不支持工具调用") : "失败"} · {record.latencyMs} ms
      </span>
    );
  }
  return null;
}

function Assignments({ settings, onSaved }: { settings: Settings; onSaved: () => void }) {
  const [values, setValues] = useState<Record<Usage, string>>(() => initial(settings));
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    setValues(initial(settings));
  }, [settings]);

  const assignment = (usage: Usage) => parse(values[usage]);

  const options = settings.providers.flatMap((provider) =>
    provider.models.map((model) => {
      const test = provider.tested.find((t) => t.model === model);
      return { value: `${provider.id}\n${model}`, label: `${provider.id} / ${model}`, tools: !!test?.ok && !!test.tools };
    }),
  );

  async function save() {
    try {
      await api.assign(assignment("main"), assignment("auxiliary"), assignment("cross_check"));
      setError(null);
      setSaved(true);
      onSaved();
    } catch (e) {
      setSaved(false);
      setError(errorText(e));
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
              <select value={values[usage]} onChange={(e) => { setSaved(false); setValues({ ...values, [usage]: e.target.value }); }}>
                <option value="">（不分配）</option>
                {options.map((option) => (
                  <option key={option.value} value={option.value} disabled={usage === "main" && !option.tools}>
                    {option.label}{usage === "main" && !option.tools ? "（没有通过工具调用测试）" : ""}
                  </option>
                ))}
              </select>
              <span className="muted small">{hint}</span>
            </label>
          </div>
        ))}
      </div>
      <p className="muted small">
        思考过程：OpenAI 兼容的推理模型（例如 DeepSeek）返回了思考内容时，会显示在对话的「过程」里。
        Anthropic 格式暂时不开启思考：现在用的 Spring AI 版本没法在工具调用的多轮对话里把思考原样发回给模型。
      </p>
      <button onClick={save}>保存分配</button>
      {saved && <span className="ok"> 已保存，立即生效</span>}
      {error && <p className="error">{error}</p>}
      <p className="privacy">{PRIVACY}</p>
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

function parse(value: string): Assignment | null {
  if (!value) {
    return null;
  }
  const [provider, model] = value.split("\n");
  return { provider, model };
}

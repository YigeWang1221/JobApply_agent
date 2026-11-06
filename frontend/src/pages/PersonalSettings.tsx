import { useEffect, useState } from "react";
import { api, errorText, type PersonalKey } from "../api";
import { toast } from "../toast";

const KEY_PATTERN = /^[A-Z_][A-Z0-9_]*$/;

// 56 个民族（汉族在前），给「民族」做建议
const ETHNIC_GROUPS = [
  "汉族", "蒙古族", "回族", "藏族", "维吾尔族", "苗族", "彝族", "壮族", "布依族", "朝鲜族",
  "满族", "侗族", "瑶族", "白族", "土家族", "哈尼族", "哈萨克族", "傣族", "黎族", "傈僳族",
  "佤族", "畲族", "高山族", "拉祜族", "水族", "东乡族", "纳西族", "景颇族", "柯尔克孜族", "土族",
  "达斡尔族", "仫佬族", "羌族", "布朗族", "撒拉族", "毛南族", "仡佬族", "锡伯族", "阿昌族", "普米族",
  "塔吉克族", "怒族", "乌孜别克族", "俄罗斯族", "鄂温克族", "德昂族", "保安族", "裕固族", "京族", "塔塔尔族",
  "独龙族", "鄂伦春族", "赫哲族", "门巴族", "珞巴族", "基诺族",
];

// 预设键的值建议（C025）：只是建议，仍可手填。这些字段输入时不遮罩（选项本身不敏感），保存后照样不回显
const SUGGESTIONS: Record<string, string[]> = {
  ID_TYPE: ["居民身份证", "护照", "港澳居民来往内地通行证", "台湾居民来往大陆通行证", "外国人永久居留身份证"],
  NEEDS_SPONSORSHIP: ["是", "否"],
  CRIMINAL_RECORD: ["无", "有"],
  DISABILITY_STATUS: ["否", "是", "不愿透露"],
  VETERAN_STATUS: ["否", "是", "不愿透露"],
  HEALTH_STATUS: ["健康", "良好", "一般"],
  ETHNICITY: [...ETHNIC_GROUPS, "不愿透露"],
  RELIGION: ["无", "不愿透露"],
};

const DATE_KEYS = new Set(["PASSPORT_EXPIRY"]);

// 不是密码框：密码框在 macOS 上会开「安全输入」，输入法用不了。这里用普通文本框 + CSS 圆点遮罩
const NO_FILL = {
  autoComplete: "off",
  spellCheck: false,
  autoCapitalize: "off",
  autoCorrect: "off",
  "data-1p-ignore": true,
  "data-lpignore": "true",
} as const;

// 名字里有 ASCII 字母数字就拼成 KEY，拼不出来用 CUSTOM_<n>
function deriveKey(label: string, existing: string[]): string {
  const ascii = label.replace(/[^A-Za-z0-9]+/g, "_").replace(/^_+|_+$/g, "").toUpperCase();
  if (ascii) return /^[0-9]/.test(ascii) ? `_${ascii}` : ascii;
  let n = 1;
  while (existing.includes(`CUSTOM_${n}`)) n++;
  return `CUSTOM_${n}`;
}

// 设置页「敏感信息」（C014-D）：证件号、签证、薪资这类 C 级的值。只能写，不显示：页面只知道已设置 / 未设置
export default function PersonalSettings() {
  const [keys, setKeys] = useState<PersonalKey[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  function replace(row: PersonalKey) {
    setKeys((current) => {
      const list = current ?? [];
      return list.some((k) => k.key === row.key) ? list.map((k) => (k.key === row.key ? row : k)) : [...list, row];
    });
  }

  useEffect(() => {
    api.personalKeys().then((view) => setKeys(view.keys), (e) => setError(errorText(e)));
  }, []);

  if (!keys) {
    return (
      <section className="card">
        <h2>敏感信息</h2>
        {error ? <p className="error">{error}</p> : <p>加载中…</p>}
      </section>
    );
  }
  return (
    <section className="card">
      <h2>敏感信息</h2>
      <p className="muted small">
        值只存在这台电脑的 storage/settings/personal.env 里（权限 600），保存后页面不再显示；可以写中文和任何字符，只是不能换行。
        资料里登记的是占位符{" {KEY}"}，模型只看到占位符，系统把填表动作发给浏览器的那一刻才换成真实值。没设置的字段照旧由你在浏览器里亲手填；
        法律声明、承诺勾选也由你亲手勾。
      </p>
      <div className="pkeys">
        {keys.map((row) => <Row key={row.key} row={row} onSaved={replace} />)}
      </div>
      <AddCustom existing={keys.map((k) => k.key)} onSaved={replace} />
    </section>
  );
}

// 值输入：日期 / 带建议（不遮罩）/ 其余遮罩 + 眼睛按钮
function ValueInput({ id, keyName, value, onChange, placeholder }: {
  id: string;
  keyName?: string;
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
}) {
  const [shown, setShown] = useState(false);
  const options = keyName ? SUGGESTIONS[keyName] : undefined;

  if (keyName && DATE_KEYS.has(keyName)) {
    return (
      <input className="pkey-input" type="date" {...NO_FILL} value={value} onChange={(e) => onChange(e.target.value)} />
    );
  }
  if (options) {
    const listId = `${id}-opts`;
    return (
      <>
        <input className="pkey-input" type="text" list={listId} {...NO_FILL} value={value} placeholder={placeholder}
          onChange={(e) => onChange(e.target.value)} />
        <datalist id={listId}>
          {options.map((o) => <option key={o} value={o} />)}
        </datalist>
      </>
    );
  }
  return (
    <span className="pkey-secret">
      <input className={`pkey-input${shown ? "" : " masked"}`} type="text" {...NO_FILL} value={value}
        placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />
      <button type="button" className="ghost btn-xs pkey-eye" aria-label={shown ? "隐藏" : "显示"} aria-pressed={shown}
        title={shown ? "隐藏" : "显示"} onClick={() => setShown((s) => !s)}>
        <EyeIcon open={!shown} />
      </button>
    </span>
  );
}

function EyeIcon({ open }: { open: boolean }) {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" />
      <circle cx="12" cy="12" r="3" />
      {!open && <line x1="3" y1="3" x2="21" y2="21" />}
    </svg>
  );
}

function Row({ row, onSaved }: { row: PersonalKey; onSaved: (row: PersonalKey) => void }) {
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);

  async function run(action: () => Promise<PersonalKey>, done: string) {
    setBusy(true);
    try {
      onSaved(await action());
      setValue("");
      toast(done);
    } catch (e) {
      toast(errorText(e), "error");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="pkey">
      <div className="pkey-name" title={row.fields}>
        <strong>{row.zh}</strong>
        {row.en && <span className="muted">{row.en}</span>}
        <span className="mono pkey-code">{`{${row.key}}`}</span>
      </div>
      <span className={`chip ${row.set ? "good" : "muted"}`}>{row.set ? "已设置" : "未设置"}</span>
      <div className="pkey-edit">
        <ValueInput id={`pk-${row.key}`} keyName={row.key} value={value} onChange={setValue}
          placeholder={row.set ? "填新的值会覆盖" : "填写真实值"} />
        <button className="primary btn-xs" disabled={busy || !value.trim()}
          onClick={() => run(() => api.savePersonal(row.key, value), `${row.zh} 已保存`)}>
          保存
        </button>
        {row.set && (
          <button className="danger btn-xs" disabled={busy}
            onClick={() => run(() => api.clearPersonal(row.key), `${row.zh} 已清除`)}>
            清除
          </button>
        )}
      </div>
    </div>
  );
}

// 自己加一项：名字（可中文）、KEY（可留空，按名字生成）、值
function AddCustom({ existing, onSaved }: { existing: string[]; onSaved: (row: PersonalKey) => void }) {
  const [key, setKey] = useState("");
  const [label, setLabel] = useState("");
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const typed = key.trim().toUpperCase();
  const name = typed || (label.trim() ? deriveKey(label.trim(), existing) : "");
  const valid = KEY_PATTERN.test(name);

  async function save() {
    setBusy(true);
    try {
      onSaved(await api.savePersonal(name, value, label.trim() || undefined));
      setKey("");
      setLabel("");
      setValue("");
      toast(`${name} 已保存`);
    } catch (e) {
      toast(errorText(e), "error");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="pkey-add">
      <h3>自己加一项</h3>
      <div className="pkey-add-row">
        <input value={label} placeholder="名字（例如 户口所在地）" onChange={(e) => setLabel(e.target.value)} />
        <input className="mono" {...NO_FILL} value={key} placeholder="KEY（可留空，按名字生成）"
          onChange={(e) => setKey(e.target.value)} />
        <ValueInput id="pk-new" value={value} onChange={setValue} placeholder="真实值" />
        <button className="primary btn-xs" disabled={busy || !valid || !value.trim()} onClick={save}>添加</button>
      </div>
      {name && (
        <p className="small">
          <span className="muted">占位符：</span><span className="mono">{`{${name}}`}</span>
        </p>
      )}
      <p className="muted small">
        KEY 只能用大写字母、数字和下划线（不以数字开头），因为它是模型原样抄进填表动作的占位符 {"{KEY}"}；名字可以写中文。
      </p>
      {typed && !valid && <p className="error small">KEY 只能用大写字母、数字和下划线，不以数字开头。</p>}
      {valid && existing.includes(name) && <p className="muted small">{name} 已经有了，添加会覆盖它的值。</p>}
    </div>
  );
}

import { useEffect, useState } from "react";
import { api, errorText, type PersonalKey } from "../api";
import { toast } from "../toast";

const KEY_PATTERN = /^[A-Z_][A-Z0-9_]*$/;

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
        值只存在这台电脑的 storage/settings/personal.env 里（权限 600），保存后页面不再显示。资料里登记的是占位符
        {" {KEY}"}，模型只看到占位符，系统把填表动作发给浏览器的那一刻才换成真实值。没设置的字段照旧由你在浏览器里亲手填；
        法律声明、承诺勾选也由你亲手勾。
      </p>
      <table className="assets">
        <thead>
          <tr>
            <th>中文名</th>
            <th>English</th>
            <th>KEY</th>
            <th>状态</th>
            <th>新的值</th>
          </tr>
        </thead>
        <tbody>
          {keys.map((row) => <Row key={row.key} row={row} onSaved={replace} />)}
        </tbody>
      </table>
      <AddCustom existing={keys.map((k) => k.key)} onSaved={replace} />
    </section>
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
    <tr>
      <td title={row.fields}>{row.zh}</td>
      <td>{row.en}</td>
      <td className="mono">{row.key}</td>
      <td>{row.set ? <span className="ok">已设置</span> : <span className="muted">未设置</span>}</td>
      <td>
        <div className="row nowrap">
          <input type="password" autoComplete="off" value={value} placeholder={row.set ? "填新的值会覆盖" : "填写真实值"}
            onChange={(e) => setValue(e.target.value)} />
          <button disabled={busy || !value.trim()}
            onClick={() => run(() => api.savePersonal(row.key, value), `${row.zh} 已保存`)}>
            保存
          </button>
          {row.set && (
            <button className="danger" disabled={busy}
              onClick={() => run(() => api.clearPersonal(row.key), `${row.zh} 已清除`)}>
              清除
            </button>
          )}
        </div>
      </td>
    </tr>
  );
}

// 自己加一个键：键名、名字、值
function AddCustom({ existing, onSaved }: { existing: string[]; onSaved: (row: PersonalKey) => void }) {
  const [key, setKey] = useState("");
  const [label, setLabel] = useState("");
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const name = key.trim().toUpperCase();
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
    <>
      <h3>自己加一项</h3>
      <div className="row">
        <input value={key} placeholder="KEY（大写字母、数字、下划线）" onChange={(e) => setKey(e.target.value)} />
        <input value={label} placeholder="名字（例如 户口所在地）" onChange={(e) => setLabel(e.target.value)} />
        <input type="password" autoComplete="off" value={value} placeholder="真实值"
          onChange={(e) => setValue(e.target.value)} />
        <button disabled={busy || !valid || !value.trim()} onClick={save}>添加</button>
      </div>
      {key.trim() && !valid && <p className="muted small">KEY 只能用大写字母、数字和下划线，不以数字开头。</p>}
      {valid && existing.includes(name) && <p className="muted small">{name} 已经有了，添加会覆盖它的值。</p>}
    </>
  );
}

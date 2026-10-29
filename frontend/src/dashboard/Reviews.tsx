import type { JobView, RoundView } from "../api";
import { applications } from "./metrics";

const TOP = 8;

// ⑦ 复盘概览（tracking.md Dashboard ⑦，C014-A）：常考主题 Top N（复盘里每道题的主题计 1 次），
// 和挂在第几轮的分布（结果是「未通过」的轮次）。数据是 JobView 里的轮次，全局页和 Project 主页同一个口径
export function Reviews({ jobs }: { jobs: JobView[] }) {
  const rounds = applications(jobs).flatMap((j) => j.rounds ?? []);
  const topics = new Map<string, number>();
  for (const topic of rounds.flatMap((r) => r.topics)) {
    topics.set(topic, (topics.get(topic) ?? 0) + 1);
  }
  const top = [...topics.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).slice(0, TOP);
  const failed = new Map<number, RoundView[]>();
  for (const r of rounds.filter((r) => r.result === "failed")) {
    failed.set(r.seq, [...(failed.get(r.seq) ?? []), r]);
  }
  const fails = [...failed.entries()].sort((a, b) => a[0] - b[0]);
  const questions = rounds.reduce((n, r) => n + r.questionCount, 0);
  return (
    <div className="panel">
      <h2>复盘概览</h2>
      <p className="sub">{rounds.length} 轮记录，复盘了 {questions} 道题；题库在 storage/review/questions.md</p>
      {rounds.length === 0 ? <p className="empty">还没有记录过笔试或面试。在申请工作台的后续段里告诉 Agent 面试情况就会记下。</p> : (
        <>
          <strong className="small">常考主题</strong>
          {top.length === 0 ? <p className="empty">还没有写过复盘。</p> : <Bars rows={top} unit="题" fill="--s1" />}
          <strong className="small">挂在第几轮</strong>
          {fails.length === 0 ? <p className="empty">还没有挂掉的轮次。</p> : (
            <Bars rows={fails.map(([seq, list]) => [`第 ${seq} 轮`, list.length,
              [...new Set(list.map((r) => r.name))].join("、")])} unit="次" fill="--s2" />
          )}
        </>
      )}
    </div>
  );
}

// 横条：标签 | 条 | 数；第三项是悬停说明
function Bars({ rows, unit, fill }: { rows: [string, number, string?][]; unit: string; fill: string }) {
  const max = Math.max(1, ...rows.map((r) => r[1]));
  return (
    <div className="funnel">
      {rows.map(([label, value, tip]) => (
        <div key={label} className="frow" title={tip ? `${label}：${tip}` : undefined}>
          <span className="lab">{label}</span>
          <div className="ftrack">
            <div className="fbar" style={{ width: `${(value / max) * 100}%`, background: `var(${fill})` }} />
          </div>
          <span className="val">{value} {unit}</span>
        </div>
      ))}
    </div>
  );
}

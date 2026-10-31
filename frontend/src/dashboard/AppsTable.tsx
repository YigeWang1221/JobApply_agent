import type { JobView } from "../api";
import { go } from "../route";
import { PHASE, phaseText, RoundProgress, RunChip } from "../status";
import { applications, lane, md } from "./metrics";

// 申请总表按阶段分组的顺序（示意页：在做的在上，结束的在下）；最后一组是 Failures 里的投递失败 / 不投了（C024-fix）
const ORDER = ["prep", "interview", "assessment", "screening", "applied", "offer", "closed", "dropped"];
const GROUP: Record<string, string> = { ...PHASE, dropped: "Failures · 投递失败 / 不投了" };

function groupOf(job: JobView): string {
  return lane(job) === "dropped" ? "dropped" : job.phase ?? "";
}

// 申请总表：每个转为申请的岗位一行
export function AppsTable({ jobs, showProject, onPostingClosed }: {
  jobs: JobView[];
  showProject?: boolean;
  onPostingClosed: (job: JobView) => void;
}) {
  const apps = applications(jobs);
  if (apps.length === 0) {
    return <div className="panel"><p className="empty">还没有转为申请的岗位。在候选池里点「开始申请」。</p></div>;
  }
  const cols = ["#", "公司", "岗位", ...(showProject ? ["Project"] : []), "城市", "类型", "投递日", "阶段", "进度", "最近查看",
    "下次查看", "链接", ""];
  let n = 0;
  return (
    <div className="tbl">
      <table className="apps">
        <thead><tr>{cols.map((c, i) => <th key={i}>{c}</th>)}</tr></thead>
        <tbody>
          {ORDER.map((phase) => {
            const rows = apps.filter((j) => groupOf(j) === phase);
            if (rows.length === 0) {
              return null;
            }
            return [
              <tr key={`g-${phase}`} className="grp"><td colSpan={cols.length}>{GROUP[phase]} · {rows.length}</td></tr>,
              ...rows.map((job) => {
                n++;
                return (
                  <tr key={job.id}>
                    <td className="num">{n}</td>
                    <td>{job.company}</td>
                    <td><button className="linkbtn" onClick={() => go({ kind: "job", id: job.id })}>{job.title}</button></td>
                    {showProject && <td className="num">{job.project}</td>}
                    <td>{job.city.join("、") || "—"}</td>
                    <td>{job.type || "—"}</td>
                    <td className="num">{md(job.appliedAt)}</td>
                    <td>
                      {phaseText(job)}{" "}
                      {job.phase === "closed" && job.outcomeReason && (
                        <span className="endreason" title={job.outcomeReason}>· {job.outcomeReason}</span>
                      )}{" "}
                      {job.due && <span className="chip wait">该查看了</span>}{" "}
                      <RunChip job={job} />
                    </td>
                    <td><RoundProgress rounds={job.rounds ?? []} /></td>
                    <td className="num">{md(job.lastCheckedAt)}</td>
                    <td className="num">{job.phase === "closed" ? "—" : md(job.nextCheckAt)}</td>
                    <td>
                      {job.url ? <a href={job.url} target="_blank" rel="noreferrer">岗位</a> : "—"}
                      {job.officialUrl && job.officialUrl !== job.url
                        && <> · <a href={job.officialUrl} target="_blank" rel="noreferrer">官方</a></>}
                    </td>
                    <td className="acts">
                      <button className="linkbtn" onClick={() => go({ kind: "job", id: job.id })}>工作台</button>
                      {job.phase !== "closed" && (
                        <button className="linkbtn" title="岗位已下线，结束这个申请" onClick={() => onPostingClosed(job)}>
                          岗位下线
                        </button>
                      )}
                    </td>
                  </tr>
                );
              }),
            ];
          })}
        </tbody>
      </table>
    </div>
  );
}

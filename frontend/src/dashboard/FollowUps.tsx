import { localDay, type JobView } from "../api";
import { writeDraft } from "../chat/draft";
import { go } from "../route";
import { labelText, PHASE } from "../status";
import { applications, dayOf, daysBetween, md } from "./metrics";

// 待追踪（C007「该查看了」）：已投递、到了下次查看日子的申请。「我看了」打开它的工作台，后续段的第一句已经填好（C022）
export function FollowUps({ jobs, showProject }: { jobs: JobView[]; showProject?: boolean }) {
  const today = localDay();
  const due = applications(jobs).filter((j) => j.due);
  const since = (job: JobView) => dayOf(job.lastCheckedAt) ?? dayOf(job.appliedAt);
  due.sort((a, b) => (since(a) ?? "").localeCompare(since(b) ?? ""));
  function open(job: JobView) {
    // 和 JobPage 后续段输入框的草稿同一个键
    writeDraft(`followup.${job.id}`, `我看了 ${job.company} · ${job.title} 的状态：`);
    go({ kind: "job", id: job.id });
  }
  return (
    <div className="panel">
      <div className="ph">
        <h2>待追踪</h2>
        <span className="chip wait">到了下次查看的日子</span>
      </div>
      {due.length === 0
        ? <p className="empty">没有该查看的申请。</p>
        : (
          <div className="list">
            {due.map((job) => {
              const from = since(job);
              return (
                <div key={job.id} className="li tm">
                  <time>{from ? `${daysBetween(from, today)} 天` : "—"}</time>
                  <div className="t">
                    <div>{job.company} · {job.title}</div>
                    <small>
                      {[showProject ? job.project : null,
                        job.lastCheckedAt ? `上次查看 ${md(job.lastCheckedAt)}` : `投递 ${md(job.appliedAt)} · 还没查看过`,
                        PHASE[job.phase ?? ""] ?? labelText(job.label)].filter(Boolean).join(" · ")}
                    </small>
                  </div>
                  <button className="linkbtn" title="去邮箱或官网看看，看完在它的工作台里告诉 Agent"
                    onClick={() => open(job)}>我看了</button>
                </div>
              );
            })}
          </div>
        )}
    </div>
  );
}

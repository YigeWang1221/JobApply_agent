import { useEffect, useRef, useState } from "react";
import { api, errorText, type JobView, type LeadGroups } from "../api";
import { Icon } from "../chat/icons";
import { LEAD, LeadChip, VerifiedChip } from "../status";

export type LeadMark = "new" | "shortlisted" | "skipped" | "closed";

const NO_CITY = "地点未写";

const uniq = (names: string[]) => [...new Set(names.filter((n) => n && n.trim()))].sort();

// 候选池：调研出来、还没转为申请的岗位，按公司组折叠，公司内有 ≥2 个地点时再按地点分小节（C023-C）。
// 全局页最外面再按 Project 分一层；分组永远在一个 Project 内。公司组和地点组由后端用模型归并（原文 → 组名），
// 没归并的名字按原文分组。想投 / 跳过（填原因，跳过的挪到 Dashboard 的 Failures，C024-fix）/ 已下线 / 重新开放 / 开始申请
export function LeadPool({ leads, applications, showProject, projectTitle, sessionTitle, onMark, onClosed, onStart }: {
  leads: JobView[];
  applications: JobView[];
  showProject?: boolean;
  projectTitle?: (id: string) => string | undefined;
  sessionTitle: (id: string) => string | undefined;
  onMark: (job: JobView, status: LeadMark, reason?: string) => Promise<boolean>;
  onClosed: (job: JobView) => Promise<boolean>;
  onStart: (job: JobView) => void;
}) {
  // 正在填「跳过」原因的那个岗位
  const [skipping, setSkipping] = useState<string | null>(null);
  const [reason, setReason] = useState("");
  // 展开着的 Project（p:<id>）和公司组（c:<project>:<组名>）；默认都收起
  const [open, setOpen] = useState<Set<string>>(() => new Set());
  const [groups, setGroups] = useState<LeadGroups>({ companies: {}, cities: {}, error: null });
  const [groupError, setGroupError] = useState<string | null>(null);
  const [grouping, setGrouping] = useState(false);
  // 已经问过后端的名字：同一个名字只在第一次出现时问一次，重试时再全部问
  const asked = useRef<Set<string>>(new Set());

  const companyNames = uniq([...leads, ...applications].map((j) => j.company));
  const cityNames = uniq(leads.flatMap((j) => j.city));
  const namesKey = JSON.stringify([companyNames, cityNames]);

  async function resolve(force: boolean) {
    const fresh = [...companyNames.map((n) => `c:${n}`), ...cityNames.map((n) => `l:${n}`)]
      .filter((k) => force || !asked.current.has(k));
    if (fresh.length === 0) {
      return;
    }
    fresh.forEach((k) => asked.current.add(k));
    setGrouping(true);
    try {
      const result = await api.leadGroups(companyNames, cityNames);
      setGroups(result);
      setGroupError(result.error);
    } catch (e) {
      setGroupError(errorText(e));
    } finally {
      setGrouping(false);
    }
  }

  useEffect(() => {
    void resolve(false);
    // 只在出现新名字时问后端；namesKey 变了才重跑
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [namesKey]);

  if (leads.length === 0) {
    return <p className="empty">候选池是空的。新建一个 Session，让 Agent 去调研。</p>;
  }

  const coOf = (job: JobView) => groups.companies[job.company] ?? job.company;
  const cityOf = (city: string) => groups.cities[city] ?? city;
  const placeOf = (job: JobView) => {
    const places = [...new Set(job.city.filter((c) => c.trim()).map(cityOf))].sort((a, b) => a.localeCompare(b, "zh"));
    return places.length > 0 ? places.join("、") : NO_CITY;
  };
  const active = (j: JobView) => j.leadStatus !== "skipped" && j.leadStatus !== "closed";
  const unmapped = companyNames.filter((n) => !(n in groups.companies)).length
    + cityNames.filter((n) => !(n in groups.cities)).length;

  // 一个 Project 内的公司组：想投 + 新发现多的公司排前面，已下线多的沉到下面
  const companiesIn = (rows: JobView[]) => {
    const names = [...new Set(rows.map(coOf))];
    const weight = (co: string) => rows.filter((j) => coOf(j) === co && active(j)).length;
    return names.sort((a, b) => weight(b) - weight(a));
  };
  const projectIds = [...new Set(leads.map((j) => j.project))];
  const weightOfProject = (id: string) => leads.filter((j) => j.project === id && active(j)).length;
  projectIds.sort((a, b) => weightOfProject(b) - weightOfProject(a));
  const allKeys = projectIds.flatMap((pid) => {
    const keys = companiesIn(leads.filter((j) => j.project === pid)).map((co) => `c:${pid}:${co}`);
    return showProject ? [`p:${pid}`, ...keys] : keys;
  });
  const companyCount = projectIds.reduce((n, pid) => n + companiesIn(leads.filter((j) => j.project === pid)).length, 0);
  const toggle = (key: string) => {
    const next = new Set(open);
    if (next.has(key)) {
      next.delete(key);
    } else {
      next.add(key);
    }
    setOpen(next);
  };
  const allOpen = allKeys.every((k) => open.has(k));

  const leadRow = (job: JobView) => {
    // 范围内找不到的 Session 是删掉的（C013）：Job 留着，只标一句
    const from = job.discoveredIn ? sessionTitle(job.discoveredIn) ?? "会话已删除" : null;
    return (
      <div key={job.id} className="lead">
        <div className="nm">
          <div>
            {job.url ? <a href={job.url} target="_blank" rel="noreferrer">{job.title}</a> : job.title}
          </div>
          <small>
            {/* 公司和城市照原文写：同一组里看得出是哪个部门、页面上怎么写的 */}
            {[job.company, job.city.join("、"), job.type,
              from ? `发现于「${from}」` : null,
              job.resumeChoice?.file ? `拟用简历：${job.resumeChoice.file}` : null].filter(Boolean).join(" · ")}
            {job.officialUrl && <> · <a href={job.officialUrl} target="_blank" rel="noreferrer">官方链接</a></>}
            {job.skipReason && ` · 跳过：${job.skipReason}`}
            {job.note && ` · ${job.note}`}
          </small>
        </div>
        <div className="chips">
          <VerifiedChip verified={job.verified} />
          <LeadChip status={job.leadStatus} />
        </div>
        <div className="acts">
          {job.leadStatus === "new" && <button className="linkbtn" onClick={() => void onMark(job, "shortlisted")}>想投</button>}
          {job.leadStatus === "shortlisted" && <button className="linkbtn" onClick={() => void onMark(job, "new")}>取消想投</button>}
          {(job.leadStatus === "new" || job.leadStatus === "shortlisted") && (
            <>
              <button className="linkbtn" title="跳过这个岗位（写原因），它会挪到 Dashboard 的 Failures" onClick={() => { setSkipping(job.id); setReason(""); }}>跳过</button>
              <button className="linkbtn" title="岗位已下线 / 停止招聘" onClick={() => void onClosed(job)}>已下线</button>
              <button className="linkbtn" onClick={() => onStart(job)}>开始申请</button>
            </>
          )}
          {job.leadStatus === "closed" && (
            <button className="linkbtn" title="岗位又开放了" onClick={() => void onMark(job, "new")}>重新开放</button>
          )}
        </div>
        {skipping === job.id && (
          <form className="skipform" onSubmit={(e) => {
            e.preventDefault();
            if (reason.trim()) {
              void onMark(job, "skipped", reason.trim()).then((ok) => ok && setSkipping(null));
            }
          }}>
            <input autoFocus value={reason} placeholder="跳过的原因，例如：要求博士"
              onChange={(e) => setReason(e.target.value)}
              onKeyDown={(e) => e.key === "Escape" && setSkipping(null)} />
            <button className="primary" type="submit" disabled={!reason.trim()}>确定</button>
            <button type="button" onClick={() => setSkipping(null)}>取消</button>
          </form>
        )}
      </div>
    );
  };

  // 一家公司（组）：头一行是数量；展开后有 ≥2 个地点时按地点分小节，否则直接列岗位
  const companyGroup = (pid: string, co: string, projectLeads: JobView[]) => {
    const key = `c:${pid}:${co}`;
    const rows = projectLeads.filter((j) => coOf(j) === co);
    const applied = applications.filter((j) => j.project === pid && coOf(j) === co);
    const inflight = applied.filter((j) => j.phase !== "closed").length;
    const expanded = open.has(key);
    const counts = (["shortlisted", "new", "skipped", "closed"] as const)
      .map((status) => [status, rows.filter((j) => j.leadStatus === status).length] as const)
      .filter(([, n]) => n > 0)
      .map(([status, n]) => `${LEAD[status]} ${n}`);
    const raw = [...new Set(rows.map((j) => j.company))];
    const places = [...new Set(rows.map(placeOf))].sort((a, b) =>
      a === NO_CITY ? 1 : b === NO_CITY ? -1 : rows.filter((j) => placeOf(j) === b).length - rows.filter((j) => placeOf(j) === a).length);
    return (
      <div key={key} className="cogrp">
        <button className="cohead" aria-expanded={expanded} onClick={() => toggle(key)}
          title={raw.length > 1 || raw[0] !== co ? `页面上的写法：${raw.join("、")}` : undefined}>
          <span className={`chev${expanded ? " open" : ""}`}><Icon name="chevron" size={13} /></span>
          <b>{co}</b>
          <span>
            {[`候选 ${rows.length}`, ...counts,
              applied.length > 0 ? `已申请 ${applied.length}（进行中 ${inflight}）` : null].filter(Boolean).join(" · ")}
          </span>
        </button>
        {expanded && (places.length < 2 ? rows.map(leadRow) : places.map((place) => {
          const here = rows.filter((j) => placeOf(j) === place);
          return (
            <div key={place} className="locsec">
              <div className="lochead">{place}<span>{here.length}</span></div>
              {here.map(leadRow)}
            </div>
          );
        }))}
      </div>
    );
  };

  const companiesOf = (pid: string) => {
    const projectLeads = leads.filter((j) => j.project === pid);
    return companiesIn(projectLeads).map((co) => companyGroup(pid, co, projectLeads));
  };

  return (
    <div>
      <div className="cotools">
        <span className="muted small">
          {showProject ? `${projectIds.length} 个 Project · ` : ""}{companyCount} 家公司 · {leads.length} 个岗位
          {grouping && " · 正在归并公司名和地点…"}
        </span>
        <button className="linkbtn" onClick={() => setOpen(allOpen ? new Set() : new Set(allKeys))}>
          {allOpen ? "全部收起" : "全部展开"}
        </button>
      </div>
      {groupError && unmapped > 0 && (
        <p className="groupnote">
          有 {unmapped} 个公司名 / 地点没有归并（按原文分组）：{groupError}
          <button className="linkbtn" disabled={grouping} onClick={() => void resolve(true)}>重试</button>
        </p>
      )}
      {showProject ? projectIds.map((pid) => {
        const key = `p:${pid}`;
        const expanded = open.has(key);
        const projectLeads = leads.filter((j) => j.project === pid);
        return (
          <div key={key} className="cogrp projgrp">
            <button className="cohead projhead" aria-expanded={expanded} onClick={() => toggle(key)} title={pid}>
              <span className={`chev${expanded ? " open" : ""}`}><Icon name="chevron" size={13} /></span>
              <b>{projectTitle?.(pid) ?? pid}</b>
              <span>{companiesIn(projectLeads).length} 家公司 · 候选 {projectLeads.length}</span>
            </button>
            {expanded && <div className="projbody">{companiesOf(pid)}</div>}
          </div>
        );
      }) : companiesOf(projectIds[0])}
    </div>
  );
}

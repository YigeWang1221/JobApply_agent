package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jobapply.domain.Evidence;
import jobapply.domain.Job;
import jobapply.domain.JobPosting;
import jobapply.domain.JobStatus;
import jobapply.domain.LeadStatus;
import jobapply.domain.Outcome;
import jobapply.domain.Phase;
import jobapply.domain.PrepState;

/**
 * 读写 {@code jobs/<job-id>/job.md}。frontmatter 是 Job 当前状态的单一事实来源；正文更新时原样保留。
 */
public final class JobRepository {

    /** 本段建模的键，按 plan/master/memory.md「Job 记录」的顺序写出。其余键进 {@link Job#extra()}。 */
    private static final List<String> KNOWN_KEYS = List.of(
            "id", "project", "company", "discovered_in", "source", "lead_status", "skip_reason", "site",
            "recruitment_project", "title", "job_id", "type", "city", "url", "history_url",
            "phase", "prep_state", "outcome", "evidence");
    private static final Set<String> KNOWN = Set.copyOf(KNOWN_KEYS);

    private final StorageLayout layout;

    public JobRepository(StorageLayout layout) {
        this.layout = layout;
    }

    public boolean exists(String projectId, String jobId) {
        return Files.exists(layout.jobFile(projectId, jobId));
    }

    public Job load(String projectId, String jobId) {
        Path file = layout.jobFile(projectId, jobId);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Job 不存在：" + projectId + "/" + jobId);
        }
        return fromFrontmatter(FrontmatterFile.read(file).frontmatter(), file.toString());
    }

    /** 本 Project 的全部 Job，按 id 排序。 */
    public List<Job> list(String projectId) {
        List<Job> jobs = new ArrayList<>();
        for (String jobId : StorageLayout.childDirectoryNames(layout.jobsDir(projectId))) {
            if (exists(projectId, jobId)) {
                jobs.add(load(projectId, jobId));
            }
        }
        return jobs;
    }

    /** 所有 Project 下已有的 job id（用于全局编号）。 */
    public List<String> allJobIds() {
        List<String> ids = new ArrayList<>();
        for (String projectId : layout.projectIds()) {
            ids.addAll(StorageLayout.childDirectoryNames(layout.jobsDir(projectId)));
        }
        return ids;
    }

    Path create(Job job, String jdSnapshot) {
        Path file = layout.jobFile(job.project(), job.id());
        if (Files.exists(file)) {
            throw new IllegalStateException("Job 已存在：" + file);
        }
        FrontmatterFile.write(file, new FrontmatterFile.Document(toFrontmatter(job, Map.of()), body(job, jdSnapshot)));
        return file;
    }

    Path update(Job job) {
        Path file = layout.jobFile(job.project(), job.id());
        FrontmatterFile.Document current = FrontmatterFile.read(file);
        FrontmatterFile.write(file, new FrontmatterFile.Document(toFrontmatter(job, current.frontmatter()), current.body()));
        return file;
    }

    /**
     * 在已有 frontmatter 上覆盖本段建模的键：已有键保持原来的位置，新键追加在后面。
     */
    static Map<String, Object> toFrontmatter(Job job, Map<String, Object> existing) {
        JobPosting posting = job.posting();
        JobStatus status = job.status();
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("id", job.id());
        known.put("project", job.project());
        known.put("company", posting.company());
        known.put("discovered_in", job.discoveredIn());
        known.put("source", posting.source());
        known.put("lead_status", status.leadStatus().value());
        known.put("skip_reason", status.skipReason());
        known.put("site", posting.site());
        known.put("recruitment_project", posting.recruitmentProject());
        known.put("title", posting.title());
        known.put("job_id", posting.jobId());
        known.put("type", posting.type());
        known.put("city", new ArrayList<>(posting.city()));
        known.put("url", posting.url());
        known.put("history_url", posting.historyUrl());
        known.put("phase", status.phase() == null ? null : status.phase().value());
        known.put("prep_state", status.prepState() == null ? null : status.prepState().value());
        known.put("outcome", status.outcome() == null ? null : status.outcome().value());
        known.put("evidence", status.evidence() == null ? null : Map.of("submitted", evidenceToMap(status.evidence())));

        Map<String, Object> merged = new LinkedHashMap<>(existing);
        merged.putAll(known);
        merged.putAll(job.extra());
        return merged;
    }

    static Job fromFrontmatter(Map<String, Object> fm, String source) {
        try {
            JobPosting posting = new JobPosting(
                    string(fm, "company"),
                    string(fm, "recruitment_project"),
                    string(fm, "title"),
                    string(fm, "job_id"),
                    string(fm, "type"),
                    cities(fm.get("city")),
                    string(fm, "url"),
                    string(fm, "history_url"),
                    string(fm, "site"),
                    string(fm, "source"));
            Object evidence = fm.get("evidence") instanceof Map<?, ?> map ? map.get("submitted") : null;
            JobStatus status = new JobStatus(
                    LeadStatus.of(string(fm, "lead_status")),
                    string(fm, "skip_reason"),
                    fm.get("phase") == null ? null : Phase.of(string(fm, "phase")),
                    fm.get("prep_state") == null ? null : PrepState.of(string(fm, "prep_state")),
                    fm.get("outcome") == null ? null : Outcome.of(string(fm, "outcome")),
                    evidenceFromMap(evidence));
            Map<String, Object> extra = new LinkedHashMap<>();
            fm.forEach((key, value) -> {
                if (!KNOWN.contains(key)) {
                    extra.put(key, value);
                }
            });
            return new Job(string(fm, "id"), string(fm, "project"), string(fm, "discovered_in"), posting, status, extra);
        } catch (RuntimeException e) {
            throw new StorageFormatException(source, "job.md 的 frontmatter 不合法：" + e.getMessage(), e);
        }
    }

    static Map<String, Object> evidenceToMap(Evidence evidence) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", evidence.kind());
        map.put("text", evidence.text());
        map.put("official_time", evidence.officialTime());
        map.put("local_time", evidence.localTime());
        map.put("verified_at", evidence.verifiedAt());
        return map;
    }

    static Evidence evidenceFromMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        return new Evidence(
                asString(map.get("kind")),
                asString(map.get("text")),
                asString(map.get("official_time")),
                asString(map.get("local_time")),
                asString(map.get("verified_at")));
    }

    private static String body(Job job, String jdSnapshot) {
        String jd = jdSnapshot == null || jdSnapshot.isBlank() ? "" : jdSnapshot.strip() + "\n\n";
        return "\n# " + job.posting().title() + " · " + job.posting().company() + "\n\n"
                + "## JD 快照\n\n" + jd
                + "## 资格映射\n\n"
                + "## 本次答案\n\n"
                + "## 授权\n\n"
                + "## 证据\n";
    }

    private static List<String> cities(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of(value.toString());
    }

    private static String string(Map<String, Object> fm, String key) {
        return asString(fm.get(key));
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}

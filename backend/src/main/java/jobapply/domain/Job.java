package jobapply.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个岗位：候选 → 申请 → 结束。对应 {@code job.md} 的 frontmatter。
 * {@code extra} 保存本段没有建模的字段（评分、材料、授权、轮次摘要、手工加的键等），写回时原样保留。
 */
public record Job(
        String id,
        String project,
        String discoveredIn,
        JobPosting posting,
        JobStatus status,
        Map<String, Object> extra) {

    public Job {
        extra = Collections.unmodifiableMap(new LinkedHashMap<>(extra == null ? Map.of() : extra));
    }

    public Job withStatus(JobStatus next) {
        return new Job(id, project, discoveredIn, posting, next, extra);
    }

    /** 覆盖 {@code extra} 里的几个键（值为 null 时写成空值）。 */
    public Job withExtra(Map<String, Object> changes) {
        Map<String, Object> next = new LinkedHashMap<>(extra);
        next.putAll(changes);
        return new Job(id, project, discoveredIn, posting, status, next);
    }

    public String extraString(String key) {
        Object value = extra.get(key);
        return value == null ? null : value.toString();
    }
}

package jobapply.domain;

import java.util.List;

/**
 * {@code session.md}：一次对话。{@code tags} 与 {@code jobs} 由本 Session 的事件推导。
 */
public record SessionRecord(
        String id,
        String project,
        String title,
        String createdAt,
        List<String> tags,
        List<String> jobs,
        String handoff) {

    public SessionRecord {
        tags = tags == null ? List.of() : List.copyOf(tags);
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
    }
}

package jobapply.domain;

import java.util.List;

/**
 * {@code session.md}：一次对话。{@code tags} 与 {@code jobs} 由本 Session 的事件推导。
 * {@code lastStatus}、{@code lastError} 是对话 Agent 上一次运行的状态（{@code running} / {@code idle}）和错误，
 * 应用重启后用来认出被中断的运行（C006-agent）；没有记录时为 null。
 */
public record SessionRecord(
        String id,
        String project,
        String title,
        String createdAt,
        List<String> tags,
        List<String> jobs,
        String handoff,
        String lastStatus,
        String lastError) {

    public SessionRecord {
        tags = tags == null ? List.of() : List.copyOf(tags);
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
    }
}

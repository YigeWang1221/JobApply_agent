package jobapply.domain;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 招聘的一轮（C014-A，tracking.md「轮次（可变）」）。{@code job.md} 的 {@code rounds} 里一轮一条摘要，详情在
 * {@code rounds/<seq>-<type>.md}。{@code result} 只存用户说的（pending / passed / failed / cancelled）；
 * {@code unknown} 不存，生成台账和视图时按 {@link #effectiveResult} 算。
 *
 * @param review        复盘文件，相对 Job 目录（{@code rounds/1-technical.md}）；还没写复盘时为 null
 * @param questionCount 复盘里记了几道题
 * @param topics        复盘里每道题的主题（一题多个主题时都算），Dashboard「常考主题」用
 */
public record HiringRound(
        int seq,
        String name,
        String type,
        String scheduledAt,
        String happenedAt,
        String format,
        String interviewerRole,
        String result,
        String resultAt,
        String resultSource,
        String review,
        int questionCount,
        List<String> topics) {

    public static final Set<String> TYPES = Set.of("screen-call", "oa", "written-test", "assessment", "technical",
            "coding", "system-design", "behavioral", "manager", "hr", "final", "offer-call", "other");
    /** 能记下的结果；{@code unknown} 是算出来的，不能记。 */
    public static final Set<String> RESULTS = Set.of("pending", "passed", "failed", "cancelled");
    public static final Set<String> SOURCES = Set.of("portal", "email", "recruiter", "user");
    public static final String UNKNOWN = "unknown";
    /** 约定时间（没有时用实际时间）过了这么多天还是 pending，就是「用户未同步」（tracking.md「unknown 规则」）。 */
    public static final int UNKNOWN_AFTER_DAYS = 7;
    private static final Set<String> WRITTEN = Set.of("oa", "written-test", "assessment");

    public HiringRound {
        if (seq < 1) {
            throw new IllegalArgumentException("seq 从 1 开始");
        }
        if (type == null || !TYPES.contains(type)) {
            throw new IllegalArgumentException("type 只能是 " + String.join(" / ", TYPES.stream().sorted().toList()));
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("一轮要有 name（公司的叫法，例如「一面」）");
        }
        if (result == null || !RESULTS.contains(result)) {
            throw new IllegalArgumentException("result 只能是 pending / passed / failed / cancelled");
        }
        if (resultSource != null && !SOURCES.contains(resultSource)) {
            throw new IllegalArgumentException("result_source 只能是 portal / email / recruiter / user");
        }
        topics = topics == null ? List.of() : List.copyOf(topics);
    }

    /** 用户还没同步时是 {@code unknown}，否则就是记下的结果。 */
    public String effectiveResult(LocalDate today) {
        if (!"pending".equals(result)) {
            return result;
        }
        LocalDate at = day(scheduledAt != null ? scheduledAt : happenedAt);
        return at != null && at.plusDays(UNKNOWN_AFTER_DAYS).isBefore(today) ? UNKNOWN : result;
    }

    /** 这一轮的日期：实际时间，没有时用约定时间。 */
    public String date() {
        return happenedAt != null ? happenedAt : scheduledAt;
    }

    public HiringRound withReview(String path, int count, List<String> questionTopics) {
        return new HiringRound(seq, name, type, scheduledAt, happenedAt, format, interviewerRole, result, resultAt,
                resultSource, path, count, questionTopics);
    }

    /** 写进 job.md 的摘要（空的键不写）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("seq", seq);
        map.put("name", name);
        map.put("type", type);
        put(map, "scheduled_at", scheduledAt);
        put(map, "happened_at", happenedAt);
        put(map, "format", format);
        put(map, "interviewer_role", interviewerRole);
        map.put("result", result);
        put(map, "result_at", resultAt);
        put(map, "result_source", resultSource);
        put(map, "review", review);
        if (review != null) {
            map.put("question_count", questionCount);
            map.put("topics", new ArrayList<>(topics));
        }
        return map;
    }

    public static HiringRound fromMap(Map<?, ?> map) {
        Object seq = map.get("seq");
        Object count = map.get("question_count");
        List<String> topics = map.get("topics") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        return new HiringRound(seq instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(seq).strip()),
                string(map.get("name")), string(map.get("type")), string(map.get("scheduled_at")),
                string(map.get("happened_at")), string(map.get("format")), string(map.get("interviewer_role")),
                map.get("result") == null ? "pending" : string(map.get("result")), string(map.get("result_at")),
                string(map.get("result_source")), string(map.get("review")),
                count instanceof Number n ? n.intValue() : 0, topics);
    }

    /** 一个 Job 的全部轮次，按 seq 排好；job.md 里没有或写坏了的条目跳过。 */
    public static List<HiringRound> of(Job job) {
        List<HiringRound> rounds = new ArrayList<>();
        if (job.extra().get("rounds") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    try {
                        rounds.add(fromMap(map));
                    } catch (RuntimeException e) {
                        // 手改坏了的一条不挡住别的
                    }
                }
            }
        }
        rounds.sort(Comparator.comparingInt(HiringRound::seq));
        return rounds;
    }

    /**
     * 按轮次推出的阶段（tracking.md「阶段由轮次自动推导」）：最新一轮（seq 最大）failed 是 {@code closed}；
     * 出现过 Offer 轮是 {@code offer}；最新一轮是笔试类（oa / written-test / assessment）是 {@code assessment}；
     * 其它是 {@code interview}。没有轮次时为 null。
     */
    public static Phase phaseOf(List<HiringRound> rounds) {
        if (rounds.isEmpty()) {
            return null;
        }
        HiringRound latest = rounds.stream().max(Comparator.comparingInt(HiringRound::seq)).orElseThrow();
        if ("failed".equals(latest.result())) {
            return Phase.CLOSED;
        }
        if (rounds.stream().anyMatch(round -> "offer-call".equals(round.type()))) {
            return Phase.OFFER;
        }
        return WRITTEN.contains(latest.type()) ? Phase.ASSESSMENT : Phase.INTERVIEW;
    }

    /** {@code 2026-10-18...} 的日期部分；写法不对时为 null。 */
    public static LocalDate day(String value) {
        if (value == null || value.strip().length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip().substring(0, 10));
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static void put(Map<String, Object> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String string(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().strip();
        return text.isEmpty() ? null : text;
    }
}

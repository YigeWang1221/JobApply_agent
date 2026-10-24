package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jobapply.domain.Job;
import jobapply.domain.HiringRound;

/**
 * 一轮的复盘文件 {@code jobs/<job-id>/rounds/<seq>-<type>.md}（C014-A，格式见 tracking.md「轮次复盘文件格式」）：
 * 对话 Agent 按用户的话写，用户可以手改；题库和按公司汇总从这里读题目（{@link #questions}），只认
 * {@code ## 题目} 下的 {@code ### } 标题和 {@code topic}、{@code canonical}、{@code 复习状态} 三行。
 */
public final class RoundReviews {

    public static final Set<String> REVIEW_STATES = Set.of("todo", "reviewed", "mastered");
    private static final String QUESTIONS = "## 题目";
    private static final Pattern NUMBERED = Pattern.compile("^Q\\d+\\s*[·.:：、-]?\\s*");
    private static final Pattern FIELD = Pattern.compile("^-\\s*(topic|canonical|复习状态)\\s*[:：]\\s*(.*)$");

    /**
     * 一道题。{@code topics}、{@code canonical} 用于归并到题库；{@code status} 是复习状态（todo / reviewed / mastered）。
     */
    public record Question(String title, List<String> topics, String canonical, String answer, String selfReview,
                           String followUp, String status) {

        public Question {
            topics = topics == null ? List.of() : List.copyOf(topics);
        }
    }

    /** 复盘的正文：经过、题目、面试官反馈、教训。 */
    public record Review(String summary, List<Question> questions, String feedback, List<String> lessons) {

        public Review {
            questions = questions == null ? List.of() : List.copyOf(questions);
            lessons = lessons == null ? List.of() : List.copyOf(lessons);
        }

        /** 每道题的主题（一题多个时都算），写进 job.md 的轮次摘要。 */
        public List<String> topics() {
            return questions.stream().flatMap(q -> q.topics().stream()).toList();
        }
    }

    private RoundReviews() {
    }

    /** 整个文件：frontmatter 是这一轮的摘要，正文按 tracking.md 的格式。 */
    static FrontmatterFile.Document render(Job job, HiringRound round, String session, Review review) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("job", job.id());
        fm.put("session", session);
        fm.put("company", job.posting().company());
        Map<String, Object> which = new LinkedHashMap<>();
        which.put("seq", round.seq());
        which.put("name", round.name());
        which.put("type", round.type());
        fm.put("round", which);
        fm.put("happened_at", round.happenedAt());
        fm.put("format", round.format());
        fm.put("interviewer_role", round.interviewerRole());
        fm.put("result", round.result());
        fm.put("result_source", round.resultSource());
        Set<String> tags = new LinkedHashSet<>();
        tags.add("jobapply/round");
        review.topics().forEach(topic -> tags.add("topic/" + topic));
        fm.put("tags", new ArrayList<>(tags));
        fm.values().removeIf(value -> value == null);

        StringBuilder body = new StringBuilder("\n# ").append(job.posting().company()).append(" · ")
                .append(round.name()).append(" 复盘\n\n## 经过\n\n");
        appendText(body, review.summary());
        body.append(QUESTIONS).append("\n\n");
        int n = 1;
        for (Question q : review.questions()) {
            body.append("### Q").append(n++).append(" · ").append(oneLine(q.title())).append('\n');
            body.append("- topic: [").append(String.join(", ", q.topics())).append("]\n");
            line(body, "canonical: ", q.canonical());
            line(body, "我的作答：", q.answer());
            line(body, "自评：", q.selfReview());
            line(body, "追问：", q.followUp());
            body.append("- 复习状态：").append(q.status() == null ? "todo" : q.status()).append("\n\n");
        }
        body.append("## 面试官反馈\n\n");
        appendText(body, review.feedback());
        body.append("## 教训（候选）\n\n");
        for (String lesson : review.lessons()) {
            body.append("- ").append(oneLine(lesson)).append('\n');
        }
        return new FrontmatterFile.Document(fm, body.toString());
    }

    /** 复盘文件里的题目；文件不在时为空。 */
    public static List<Question> questions(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<Question> out = new ArrayList<>();
        boolean inQuestions = false;
        String title = null;
        List<String> topics = List.of();
        String canonical = null;
        String status = null;
        for (String raw : FrontmatterFile.read(file).body().split("\n")) {
            String line = raw.strip();
            if (line.startsWith("## ")) {
                if (title != null) {
                    out.add(new Question(title, topics, canonical, null, null, null, status));
                    title = null;
                }
                inQuestions = line.equals(QUESTIONS);
                continue;
            }
            if (!inQuestions) {
                continue;
            }
            if (line.startsWith("### ")) {
                if (title != null) {
                    out.add(new Question(title, topics, canonical, null, null, null, status));
                }
                title = NUMBERED.matcher(line.substring(4).strip()).replaceFirst("");
                topics = List.of();
                canonical = null;
                status = null;
                continue;
            }
            Matcher field = FIELD.matcher(line);
            if (title == null || !field.matches()) {
                continue;
            }
            String value = withoutComment(field.group(2));
            switch (field.group(1)) {
                case "topic" -> topics = topicList(value);
                case "canonical" -> canonical = value.isEmpty() ? null : value;
                default -> status = value.isEmpty() ? null : value;
            }
        }
        if (title != null) {
            out.add(new Question(title, topics, canonical, null, null, null, status));
        }
        return out;
    }

    /** 主题统一成小写、空格换成连字符，例如 {@code System Design} → {@code system-design}。 */
    public static String topic(String value) {
        return value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    private static List<String> topicList(String value) {
        String inner = value.startsWith("[") && value.endsWith("]") ? value.substring(1, value.length() - 1) : value;
        List<String> topics = new ArrayList<>();
        for (String part : inner.split("[,，、]")) {
            if (!part.isBlank()) {
                topics.add(topic(part));
            }
        }
        return topics;
    }

    private static String withoutComment(String value) {
        int hash = value.indexOf(" #");
        return (hash >= 0 ? value.substring(0, hash) : value).strip();
    }

    private static void appendText(StringBuilder body, String text) {
        if (text != null && !text.isBlank()) {
            body.append(text.strip()).append("\n\n");
        }
    }

    private static void line(StringBuilder body, String label, String value) {
        if (value != null && !value.isBlank()) {
            body.append("- ").append(label).append(value.strip().replace("\n", "\n  ")).append('\n');
        }
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s*\\n\\s*", " ");
    }
}

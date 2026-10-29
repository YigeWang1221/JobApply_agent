package jobapply.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import jobapply.domain.Job;
import jobapply.domain.HiringRound;

/**
 * 跨 Project 的复盘生成文件（C014-A，tracking.md「面试复盘与常考题」）：{@code review/questions.md}（常考题库，按
 * canonical 归并、按出现次数排序）和 {@code review/companies/<company>.md}（这家公司历次各轮问过什么）。
 * 题目从各轮的复盘文件读（{@link RoundReviews#questions}），轮次从 job.md 的 {@code rounds} 读。
 */
final class ReviewWriter {

    private static final String NO_TOPIC = "未分类";

    private final StorageLayout layout;
    private final Clock clock;

    ReviewWriter(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.clock = clock;
    }

    /** 一道题出现的一次：哪个 Job 的哪一轮。 */
    private record Asked(Job job, HiringRound round, RoundReviews.Question question) {

        String key() {
            String canonical = question.canonical();
            return canonical != null ? canonical : question.title().strip().toLowerCase(Locale.ROOT);
        }
    }

    /** 重新生成题库和按公司汇总；{@code all} 是所有 Project 的 Job。 */
    List<Path> write(List<Job> all) {
        List<Asked> asked = new ArrayList<>();
        for (Job job : all) {
            for (HiringRound round : HiringRound.of(job)) {
                for (RoundReviews.Question q : questions(job, round)) {
                    asked.add(new Asked(job, round, q));
                }
            }
        }
        List<Path> written = new ArrayList<>();
        AtomicFiles.write(layout.reviewQuestions(), questionsMarkdown(asked));
        written.add(layout.reviewQuestions());
        written.addAll(writeCompanies(all));
        StorageWrites.changed(written);
        return written;
    }

    private List<RoundReviews.Question> questions(Job job, HiringRound round) {
        if (round.review() == null) {
            return List.of();
        }
        Path file = layout.jobDir(job.project(), job.id()).resolve(round.review()).normalize();
        if (!file.startsWith(layout.roundsDir(job.project(), job.id()))) {
            return List.of();
        }
        return RoundReviews.questions(file);
    }

    private String questionsMarkdown(List<Asked> asked) {
        // 先按 canonical（没有时按题目）归并，再按第一次出现时的第一个主题分组
        Map<String, List<Asked>> byQuestion = new LinkedHashMap<>();
        for (Asked a : asked) {
            byQuestion.computeIfAbsent(a.key(), k -> new ArrayList<>()).add(a);
        }
        Map<String, List<List<Asked>>> byTopic = new LinkedHashMap<>();
        for (List<Asked> group : byQuestion.values()) {
            List<String> topics = group.getFirst().question().topics();
            byTopic.computeIfAbsent(topics.isEmpty() ? NO_TOPIC : topics.getFirst(), k -> new ArrayList<>()).add(group);
        }
        List<Map.Entry<String, List<List<Asked>>>> topics = new ArrayList<>(byTopic.entrySet());
        topics.sort(Comparator.comparingInt((Map.Entry<String, List<List<Asked>>> e) -> -count(e.getValue()))
                .thenComparing(Map.Entry::getKey));

        StringBuilder out = new StringBuilder(TrackerWriter.GENERATED).append("\n# 常考题库\n\n");
        if (asked.isEmpty()) {
            return out.append("还没有复盘过的题目。\n").toString();
        }
        out.append("共 ").append(byQuestion.size()).append(" 道题，被问过 ").append(asked.size())
                .append(" 次。主题按出现次数排序，同一道题（canonical 相同）归并在一起。\n");
        for (Map.Entry<String, List<List<Asked>>> topic : topics) {
            out.append("\n## ").append(topic.getKey()).append("（").append(count(topic.getValue())).append(" 次）\n\n");
            out.append("| 题目 | 主题 | canonical | 次数 | 出处 | 复习状态 |\n| --- | --- | --- | --- | --- | --- |\n");
            List<List<Asked>> groups = new ArrayList<>(topic.getValue());
            groups.sort(Comparator.comparingInt((List<Asked> g) -> -g.size())
                    .thenComparing(g -> g.getFirst().question().title()));
            for (List<Asked> group : groups) {
                Set<String> allTopics = new LinkedHashSet<>();
                group.forEach(a -> allTopics.addAll(a.question().topics()));
                List<String> sources = group.stream().map(a -> link(a.job().posting().company() + " · "
                        + a.round().name(), "../" + reviewPath(a)) + "（" + a.job().project() + "）").toList();
                Asked latest = group.stream().max(Comparator.comparing(a -> dateOf(a.round()))).orElseThrow();
                out.append("| ").append(cell(group.getFirst().question().title()))
                        .append(" | ").append(cell(String.join(", ", allTopics)))
                        .append(" | ").append(cell(group.getFirst().question().canonical()))
                        .append(" | ").append(group.size())
                        .append(" | ").append(String.join("；", sources))
                        .append(" | ").append(cell(latest.question().status() == null ? "todo" : latest.question().status()))
                        .append(" |\n");
            }
        }
        return out.toString();
    }

    private List<Path> writeCompanies(List<Job> all) {
        Map<String, List<Job>> byCompany = new LinkedHashMap<>();
        all.stream().filter(job -> !HiringRound.of(job).isEmpty())
                .forEach(job -> byCompany.computeIfAbsent(StorageLayout.companySlug(job.posting().company()),
                        k -> new ArrayList<>()).add(job));
        List<Path> written = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);
        for (List<Job> jobs : byCompany.values()) {
            Path file = layout.reviewCompany(jobs.getFirst().posting().company());
            AtomicFiles.write(file, companyMarkdown(jobs, today));
            written.add(file);
        }
        removeStale(written);
        return written;
    }

    private String companyMarkdown(List<Job> jobs, LocalDate today) {
        StringBuilder out = new StringBuilder(TrackerWriter.GENERATED).append("\n# ")
                .append(jobs.getFirst().posting().company()).append(" · 历次各轮\n");
        for (Job job : jobs) {
            out.append("\n## ").append(link(job.posting().title(),
                    "../../projects/" + job.project() + "/jobs/" + job.id() + "/job.md"))
                    .append("（").append(job.project()).append("）\n");
            for (HiringRound round : HiringRound.of(job)) {
                out.append("\n### ").append(round.seq()).append(". ").append(round.name()).append(" · ")
                        .append(round.type());
                String day = TrackerWriter.shortDate(round.date());
                if (day != null) {
                    out.append(" · ").append(day);
                }
                out.append(" · ").append(round.effectiveResult(today)).append('\n');
                if (round.format() != null) {
                    out.append("- 形式：").append(round.format()).append('\n');
                }
                if (round.interviewerRole() != null) {
                    out.append("- 面试官：").append(round.interviewerRole()).append('\n');
                }
                for (RoundReviews.Question q : questions(job, round)) {
                    out.append("- 题目：").append(q.title());
                    if (!q.topics().isEmpty()) {
                        out.append("（").append(String.join(", ", q.topics())).append("）");
                    }
                    out.append('\n');
                }
                if (round.review() != null) {
                    out.append("- ").append(link("复盘", "../../projects/" + job.project() + "/jobs/" + job.id()
                            + "/" + round.review())).append('\n');
                }
            }
        }
        return out.toString();
    }

    /** 公司已经没有轮次了（Job 删了等）：删掉以前生成的那份；不是生成文件的不动。 */
    private void removeStale(List<Path> written) {
        Path dir = layout.reviewCompaniesDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        Set<Path> keep = new HashSet<>(written);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".md") && !keep.contains(f)).toList()) {
                if (AtomicFiles.read(file).startsWith(TrackerWriter.GENERATED)) {
                    Files.deleteIfExists(file);
                    StorageWrites.changed(file);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String reviewPath(Asked a) {
        return "projects/" + a.job().project() + "/jobs/" + a.job().id() + "/" + a.round().review();
    }

    private static String dateOf(HiringRound round) {
        return round.date() == null ? "" : round.date();
    }

    private static int count(List<List<Asked>> groups) {
        return groups.stream().mapToInt(List::size).sum();
    }

    private static String cell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private static String link(String text, String target) {
        return "[" + cell(text).replace("]", "\\]") + "](" + target.replace(" ", "%20") + ")";
    }
}

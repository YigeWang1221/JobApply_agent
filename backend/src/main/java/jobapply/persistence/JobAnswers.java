package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import jobapply.domain.Fact;
import jobapply.domain.FactClass;
import jobapply.domain.Timestamps;
import jobapply.harness.policies.FieldClassGuard;

/**
 * {@code job.md} 正文里的「本次答案」一节：用户在 {@code wait_input} 里回答的问题。只追加，job.md 的其它内容
 * （frontmatter 原文、其它各节）一个字节都不动。
 * <p>
 * 每条答案有编号（{@code ans-NNN}），模型在填写调用里用它作 {@code fact}；{@link #facts} 把答案当作
 * {@code ProfileResolver} 的 Session 层（键就是编号）。
 * C 级的问题（{@link FieldClassGuard#isClassC}）不能回答，那一项只能由用户在浏览器里亲自填：整批拒绝，什么都不写。
 * <p>
 * 一条答案在文件里的写法（值的每一行前面加 {@code > }，所以值里有 {@code #} 也不会拆坏这一节）：
 * <pre>
 * ### ans-001
 * - 问题：毕业论文题目
 * - session：s-2026-10-22-001
 * - 时间：2026-10-22T10:00:00-04:00
 *
 * &gt; 值的第一行
 * &gt; 值的第二行
 * </pre>
 */
public final class JobAnswers {

    static final String SECTION = "## 本次答案";
    static final String PREFIX = "ans-";
    private static final String QUESTION = "- 问题：";
    private static final String SESSION = "- session：";
    private static final String AT = "- 时间：";

    /** 要写入的一条回答。 */
    public record NewAnswer(String question, String value) {
    }

    /** 已经写下的一条答案。 */
    public record Answer(String id, String question, String value, String session, String at) {

        /** 作为 Session 层的事实条目：键是答案编号，A 级。 */
        public Fact toFact() {
            return Fact.of(id, value, FactClass.A, "session:" + session, at);
        }
    }

    /** 回答了 C 级的问题。HTTP 层返回 400。 */
    public static final class ClassCQuestionException extends IllegalArgumentException {

        private final String question;

        public ClassCQuestionException(String question) {
            super("「" + question + "」是 C 级字段，只能由用户在浏览器里亲自填写");
            this.question = question;
        }

        public String question() {
            return question;
        }
    }

    private final StorageLayout layout;

    private final Clock clock;

    public JobAnswers(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.clock = clock;
    }

    /**
     * 追加一批答案，返回写下的答案（带编号）。所有校验都在写文件之前完成。
     *
     * @throws ClassCQuestionException 其中有 C 级的问题
     */
    public synchronized List<Answer> append(String projectId, String jobId, String sessionId,
                                            List<NewAnswer> answers) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("缺少 session");
        }
        if (answers == null || answers.isEmpty()) {
            throw new IllegalArgumentException("没有要写入的答案");
        }
        for (NewAnswer answer : answers) {
            if (answer == null || answer.question() == null || answer.question().isBlank()) {
                throw new IllegalArgumentException("答案缺少问题原文");
            }
            if (FieldClassGuard.isClassC(answer.question())) {
                throw new ClassCQuestionException(oneLine(answer.question()));
            }
            if (answer.value() == null || answer.value().isBlank()) {
                throw new IllegalArgumentException("「" + oneLine(answer.question()) + "」没有回答");
            }
        }
        Path file = requireJob(projectId, jobId);
        synchronized (StorageLock.WRITES) {
            String content = AtomicFiles.read(file);
            String body = FrontmatterFile.parse(content, file.toString()).body();
            String head = content.substring(0, content.length() - body.length());

            List<String> ids = new ArrayList<>(parse(body).stream().map(Answer::id).toList());
            String at = Timestamps.now(clock);
            List<Answer> written = new ArrayList<>();
            StringBuilder block = new StringBuilder();
            for (NewAnswer answer : answers) {
                String id = IdSequence.next(PREFIX, ids);
                ids.add(id);
                Answer next = new Answer(id, oneLine(answer.question()), answer.value().strip(), sessionId, at);
                written.add(next);
                block.append(render(next));
            }
            AtomicFiles.write(file, head + insert(body, block.toString()));
            return List.copyOf(written);
        }
    }

    /** 「本次答案」里的全部答案，按写入顺序。 */
    public List<Answer> list(String projectId, String jobId) {
        Path file = requireJob(projectId, jobId);
        return parse(FrontmatterFile.read(file).body());
    }

    /** 作为 {@code ProfileResolver} Session 层的事实条目。 */
    public List<Fact> facts(String projectId, String jobId) {
        return list(projectId, jobId).stream().map(Answer::toFact).toList();
    }

    private Path requireJob(String projectId, String jobId) {
        Path file = layout.jobFile(projectId, jobId);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Job 不存在：" + projectId + "/" + jobId);
        }
        return file;
    }

    private static String render(Answer answer) {
        StringBuilder out = new StringBuilder();
        out.append("### ").append(answer.id()).append('\n');
        out.append(QUESTION).append(answer.question()).append('\n');
        out.append(SESSION).append(answer.session()).append('\n');
        out.append(AT).append(answer.at()).append("\n\n");
        for (String line : answer.value().split("\\r?\\n", -1)) {
            out.append(line.isEmpty() ? ">" : "> " + line).append('\n');
        }
        return out.append('\n').toString();
    }

    /** 把新答案放在「本次答案」一节的末尾；没有这一节时加在正文末尾。 */
    static String insert(String body, String block) {
        List<String> lines = new ArrayList<>(body.lines().toList());
        int start = sectionStart(lines);
        if (start < 0) {
            String separator = body.isEmpty() || body.endsWith("\n\n") ? "" : body.endsWith("\n") ? "\n" : "\n\n";
            return body + separator + SECTION + "\n\n" + block;
        }
        int end = sectionEnd(lines, start);
        int last = end;
        while (last > start + 1 && lines.get(last - 1).isBlank()) {
            last--;
        }
        List<String> out = new ArrayList<>(lines.subList(0, last));
        out.add("");
        out.addAll(block.lines().toList());
        out.addAll(lines.subList(end, lines.size()));
        String joined = String.join("\n", out);
        return body.endsWith("\n") || end < lines.size() ? joined + "\n" : joined;
    }

    static List<Answer> parse(String body) {
        List<String> lines = body.lines().toList();
        int start = sectionStart(lines);
        if (start < 0) {
            return List.of();
        }
        int end = sectionEnd(lines, start);
        List<Answer> answers = new ArrayList<>();
        String id = null;
        String question = null;
        String session = null;
        String at = null;
        List<String> value = new ArrayList<>();
        for (int i = start + 1; i <= end; i++) {
            String line = i < end ? lines.get(i) : null;
            if (line == null || line.startsWith("### ")) {
                if (id != null) {
                    answers.add(new Answer(id, question, String.join("\n", value), session, at));
                }
                if (line == null) {
                    break;
                }
                id = line.substring(4).strip();
                question = null;
                session = null;
                at = null;
                value = new ArrayList<>();
            } else if (id != null) {
                if (line.startsWith(QUESTION)) {
                    question = line.substring(QUESTION.length()).strip();
                } else if (line.startsWith(SESSION)) {
                    session = line.substring(SESSION.length()).strip();
                } else if (line.startsWith(AT)) {
                    at = line.substring(AT.length()).strip();
                } else if (line.startsWith(">")) {
                    value.add(line.startsWith("> ") ? line.substring(2) : line.substring(1));
                }
            }
        }
        return answers;
    }

    private static int sectionStart(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().equals(SECTION)) {
                return i;
            }
        }
        return -1;
    }

    /** 下一个同级或更高级标题所在的行；没有时为行数。 */
    private static int sectionEnd(List<String> lines, int start) {
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("## ") || line.startsWith("# ")) {
                return i;
            }
        }
        return lines.size();
    }

    private static String oneLine(String text) {
        return text.strip().replaceAll("\\s*[\\r\\n]+\\s*", " ");
    }
}

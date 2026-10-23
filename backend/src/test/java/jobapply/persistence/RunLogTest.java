package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import jobapply.domain.Actor;
import jobapply.persistence.RunLog.Element;
import jobapply.persistence.RunLog.Entry;
import jobapply.persistence.RunLog.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunLogTest {

    private static final String PROJECT = "fall-2026-v1";
    private static final List<String> KEYS = List.of("ts", "session", "step", "kind", "tool", "ref", "element",
            "decision", "purpose", "evidence", "page_title", "url_path", "outcome", "note");

    /** 虚构的资料：填进页面的值。日志里一个都不能出现。 */
    private static final Map<String, String> FILLED = Map.of(
            "姓名", "林小雨",
            "手机", "13800001234",
            "邮箱", "xiaoyu.lin@example.com",
            "专业", "计算机科学与技术",
            "毕业论文题目", "虚构的分布式缓存研究");

    @TempDir
    Path root;

    private TestStorage storage;
    private RunLog log;
    private String session;
    private String jobId;

    @BeforeEach
    void setUp() {
        storage = TestStorage.create(root);
        storage.project(PROJECT, "live");
        session = storage.session(PROJECT);
        jobId = storage.ledger().addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-001"),
                null).id();
        log = new RunLog(storage.layout, storage.clock);
    }

    @Test
    void everyLineHasTheFixedKeysInOrder() {
        Page page = new Page("基本信息 - 示例公司甲招聘", "https://user:pw@jobs.example.com:8443/apply/step1?token=abc#top");
        log.append(PROJECT, jobId, Entry.toolCall(session, 3, "fill_form", "e12", new Element("textbox", " 姓名 "),
                null, null, page, "ok"));

        List<Map<String, Object>> lines = log.read(PROJECT, jobId);
        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.keySet()).containsExactlyElementsOf(KEYS);
            assertThat(line).containsEntry("ts", "2026-10-22T10:00:00-04:00").containsEntry("session", session)
                    .containsEntry("step", 3).containsEntry("kind", "tool_call").containsEntry("tool", "fill_form")
                    .containsEntry("ref", "e12").containsEntry("page_title", "基本信息 - 示例公司甲招聘")
                    .containsEntry("url_path", "https://jobs.example.com:8443/apply/step1")
                    .containsEntry("outcome", "ok").containsEntry("note", null);
            assertThat(line.get("element")).isEqualTo(Map.of("role", "textbox", "name", "姓名"));
        });
    }

    @Test
    void appendsAcrossInstancesInsteadOfOverwriting() {
        log.append(PROJECT, jobId, Entry.modelCall(session, 1, 1200, 80));
        new RunLog(storage.layout, storage.clock).append(PROJECT, jobId, Entry.skillLoaded(session, 1, "apply-form"));
        log.append(PROJECT, jobId, Entry.budget(session, 2, "warn_80", 480, 2_400_000));

        List<Map<String, Object>> lines = log.read(PROJECT, jobId);
        assertThat(lines).extracting(line -> line.get("kind"))
                .containsExactly("model_call", "skill_loaded", "budget");
        assertThat(lines.get(0)).containsEntry("note", "input_tokens=1200 output_tokens=80");
        assertThat(lines.get(1)).containsEntry("note", "apply-form");
        assertThat(lines.get(2)).containsEntry("decision", "warn_80")
                .containsEntry("note", "tool_calls=480 tokens=2400000");
        assertThat(storage.read(log.path(PROJECT, jobId)).lines()).hasSize(3);
    }

    @Test
    void userSignalsRecordOnlyTheNameExceptRequestChangesNote() {
        log.append(PROJECT, jobId, Entry.userSignal(session, 5, "answers"));
        log.append(PROJECT, jobId, Entry.requestChanges(session, 9, "毕业时间改成到岗时间那一栏"));

        List<Map<String, Object>> lines = log.read(PROJECT, jobId);
        assertThat(lines.get(0)).containsEntry("kind", "user_signal").containsEntry("decision", "answers")
                .containsEntry("note", null);
        assertThat(lines.get(1)).containsEntry("kind", "user_signal").containsEntry("decision", "request_changes")
                .containsEntry("note", "毕业时间改成到岗时间那一栏");
    }

    @Test
    void urlPathDropsQueryFragmentAndCredentials() {
        assertThat(RunLog.urlPath("https://jobs.example.com/apply?id=1&email=a@b.c")).isEqualTo(
                "https://jobs.example.com/apply");
        assertThat(RunLog.urlPath("https://jobs.example.com#/form/step2")).isEqualTo("https://jobs.example.com/");
        assertThat(RunLog.urlPath("not a url?x=1")).isEqualTo("not a url");
        assertThat(RunLog.urlPath(null)).isNull();
    }

    @Test
    void entriesCanOnlyBeBuiltThroughFactoriesThatHaveNoValueParameter() {
        assertThat(Entry.class.getDeclaredConstructors()).allSatisfy(constructor ->
                assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue());
        List<String> parameterNames = Arrays.stream(Entry.class.getDeclaredMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()) && Modifier.isPublic(method.getModifiers()))
                .map(Method::getParameters)
                .flatMap(Arrays::stream)
                .map(parameter -> parameter.getName().toLowerCase())
                .toList();
        assertThat(parameterNames).noneMatch(name -> name.contains("value") || name.contains("snapshot")
                || name.contains("message") || name.contains("args"));
        assertThat(Element.class.getRecordComponents()).extracting(component -> component.getName())
                .containsExactly("role", "name");
        assertThatThrownBy(() -> Entry.modelCall(" ", 1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFullFillRunLogsNoneOfTheFilledValues() {
        Page page1 = new Page("基本信息", "https://jobs.example.com/apply/1?name=" + FILLED.get("姓名"));
        Page page2 = new Page("教育经历", "https://jobs.example.com/apply/2#" + FILLED.get("邮箱"));
        int step = 0;
        log.append(PROJECT, jobId, Entry.modelCall(session, ++step, 3000, 200));
        log.append(PROJECT, jobId, Entry.skillLoaded(session, step, "components/date-picker"));
        for (String label : List.of("姓名", "手机", "邮箱")) {
            log.append(PROJECT, jobId, Entry.toolCall(session, ++step, "fill_form", "e" + step,
                    new Element("textbox", label), null, null, page1, "ok"));
        }
        log.append(PROJECT, jobId, Entry.guard(session, ++step, "fill_form", "e20", new Element("textbox", "身份证号"),
                "handoff", page1, "C 级字段由用户亲自填写（「身份证号」）"));
        log.append(PROJECT, jobId, Entry.toolCall(session, ++step, "click", "e30", new Element("button", "下一步"),
                "进入下一页", "按钮在表单底部，页面还有第 2 / 3 步", page1, "page_changed"));
        log.append(PROJECT, jobId, Entry.toolCall(session, ++step, "select_option", "e41",
                new Element("combobox", "专业"), null, null, page2, "ok"));
        log.append(PROJECT, jobId, Entry.loopDetected(session, ++step, "same_result", "type", "e42",
                new Element("textbox", "毕业论文题目"), page2));
        log.append(PROJECT, jobId, Entry.waiting(session, step, "wait_input", page2, "1 个必填项资料里没有"));
        log.append(PROJECT, jobId, Entry.userSignal(session, ++step, "answers"));
        log.append(PROJECT, jobId, Entry.guard(session, ++step, "click", "e50", new Element("button", "提交申请"),
                "reject", page2, "硬边界：提交类按钮"));
        log.append(PROJECT, jobId, Entry.waiting(session, step, "wait_review", page2, null));

        List<String> lines = storage.read(log.path(PROJECT, jobId)).lines().toList();
        assertThat(lines).hasSize(13);
        for (String line : lines) {
            for (String value : FILLED.values()) {
                assertThat(line).as("过程日志不能含填进去的值").doesNotContain(value);
            }
        }
    }
}

package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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
}

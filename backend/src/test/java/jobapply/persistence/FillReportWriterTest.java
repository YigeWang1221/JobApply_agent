package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import jobapply.domain.Actor;
import jobapply.domain.FactClass;
import jobapply.domain.Language;
import jobapply.memory.ResolvedFact;
import jobapply.persistence.FillReportWriter.Attachment;
import jobapply.persistence.FillReportWriter.Confidence;
import jobapply.persistence.FillReportWriter.Filled;
import jobapply.persistence.FillReportWriter.GuardNote;
import jobapply.persistence.FillReportWriter.GuardOutcome;
import jobapply.persistence.FillReportWriter.Report;
import jobapply.persistence.FillReportWriter.UserFilled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FillReportWriterTest {

    private static final String PROJECT = "fall-2026-v1";

    /** 虚构的资料。 */
    private static final ResolvedFact NAME = new ResolvedFact("name", "林小雨", "林小雨", FactClass.B,
            ResolvedFact.Layer.BASE, "live", null, Language.ZH, false);
    private static final ResolvedFact MAJOR = new ResolvedFact("education.major", "计算机科学与技术", "计算机科学与技术",
            FactClass.A, ResolvedFact.Layer.PROJECT, PROJECT, "2026-10-13", Language.ZH, false);
    private static final ResolvedFact GRADUATION = new ResolvedFact("education.graduation", "2027-06", "2027年6月",
            FactClass.A, ResolvedFact.Layer.BASE, "live", null, null, false);
    private static final ResolvedFact SUMMARY_EN = new ResolvedFact("summary", "Fictional summary text",
            "Fictional summary text", FactClass.A, ResolvedFact.Layer.BASE, "live", null, Language.EN, true);

    @TempDir
    Path root;

    private TestStorage storage;
    private FillReportWriter writer;
    private String jobId;

    @BeforeEach
    void setUp() {
        storage = TestStorage.create(root);
        storage.project(PROJECT, "live");
        String session = storage.session(PROJECT);
        jobId = storage.ledger().addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-001"),
                null).id();
        writer = new FillReportWriter(storage.layout, storage.clock);
    }

    private static Report report(int round) {
        return new Report("示例公司甲", "Backend Engineer", round, List.of(
                Filled.fromFact("基本信息", "姓名", "林小雨", NAME, Confidence.HIGH, Language.ZH),
                Filled.fromFact("教育经历", "专业", "计算机科学与技术", MAJOR, Confidence.HIGH, Language.ZH)
                        .replacing("计算机"),
                Filled.fromFact("教育经历", "毕业时间", "2027/06", GRADUATION, Confidence.MEDIUM, Language.ZH),
                Filled.fromFact("Profile", "Summary", "Fictional summary text", SUMMARY_EN, Confidence.MEDIUM,
                        Language.EN),
                Filled.inferred("其它", "如何得知", "官网", Confidence.LOW, FactClass.A, Language.ZH),
                new UserFilled("基本信息", "身份证号")),
                List.of(new Attachment("resume.pdf", "resume-demo", 2048, "ab12cd", "页面显示 resume.pdf")),
                List.of("「最早到岗时间」：资料里没有，已在集中问答里问过"),
                List.of(new GuardNote("提交申请", GuardOutcome.REJECTED),
                        new GuardNote("下一步", GuardOutcome.CONFIRMED_SAFE)));
    }

    /** 申请状态（C019）只存报告的单元格：变回报告行后写出来要一字不差。 */
    @Test
    void filledRowsKeptAsCellsRenderTheSame() {
        Report original = report(1);
        Report cells = new Report(original.company(), original.title(), original.round(), original.fields().stream()
                .map(field -> field instanceof Filled filled ? FillReportWriter.asRecorded(filled) : field).toList(),
                original.attachments(), original.unresolved(), original.guardNotes());

        assertThat(FillReportWriter.render(cells, true, "2026-10-25T10:00:00-04:00"))
                .isEqualTo(FillReportWriter.render(original, true, "2026-10-25T10:00:00-04:00"));
    }

    @Test
    void draftHasHeaderMarkedDraftAndAllSections() {
        Path file = writer.writeDraft(PROJECT, jobId, report(1));

        assertThat(file).isEqualTo(storage.layout.fillReport(PROJECT, jobId));
        String text = writer.read(PROJECT, jobId).orElseThrow();
        assertThat(text).startsWith(
                "# 填写报告 · 示例公司甲 · Backend Engineer（第 1 轮，2026-10-22T10:00:00-04:00，草稿）\n\n## 字段\n\n"
                        + "| 页面 | 字段 | 值 | 来源层 | 语言 | 置信度 | 分级 |\n");
        assertThat(text).contains("| 基本信息 | 姓名 | 林小雨 | 来自 base（pin live） | zh | high | **B** |\n");
        assertThat(text).contains("| 教育经历 | 专业 | 计算机科学与技术（页面原值：计算机，已改正） "
                + "| 来自 Project fall-2026-v1 / overrides，2026-10-13 确认 | zh | high | A |\n");
        assertThat(text).contains("| 教育经历 | 毕业时间 | 2027/06（资料原值：2027年6月） | 来自 base（pin live） | zh | medium | A |\n");
        assertThat(text).contains("| Profile | Summary | Fictional summary text | 来自 base（pin live）；en 文字未审阅 | en "
                + "| medium | A |\n");
        assertThat(text).contains("| 其它 | 如何得知 | 官网 | 模型推断 | zh | low | A |\n");
        assertThat(text).contains("| 基本信息 | 身份证号 | （用户已填） | 用户亲填 | — | — | C |\n");
        assertThat(text).contains("## 附件\n\n| 文件 | 原件键 | 大小 | SHA-256 | 页面回读 |\n| --- | --- | --- | --- | --- |\n"
                + "| resume.pdf | resume-demo | 2048 B | ab12cd | 页面显示 resume.pdf |\n");
        assertThat(text).contains("## 未解决\n\n- 「最早到岗时间」：资料里没有，已在集中问答里问过\n- 1 个字段用了未审阅的译文\n");
        assertThat(text).endsWith("## 守卫记录\n\n- 「提交申请」：被守卫拒绝\n- 「下一步」：用户确认：不会提交\n");
        assertThat(writer.isDraft(PROJECT, jobId)).contains(true);
    }

    @Test
    void draftIsRewrittenAndNotCommittedFinalIsCommittedWithRunLog() {
        writer.writeDraft(PROJECT, jobId, report(1));
        writer.writeDraft(PROJECT, jobId, report(1));

        RunLog runLog = new RunLog(storage.layout, storage.clock);
        runLog.append(PROJECT, jobId, RunLog.Entry.skillLoaded("s-2026-10-22-001", 1, "apply-form"));
        writer.writeFinal(PROJECT, jobId, report(1));

        String text = writer.read(PROJECT, jobId).orElseThrow();
        assertThat(text).startsWith("# 填写报告 · 示例公司甲 · Backend Engineer（第 1 轮，2026-10-22T10:00:00-04:00）\n");
        assertThat(text.split("# 填写报告", -1)).hasSize(2);
        assertThat(writer.isDraft(PROJECT, jobId)).contains(false);
        String dir = storage.layout.relative(storage.layout.jobDir(PROJECT, jobId));
    }

    @Test
    void noReportYetReadsEmpty() {
        assertThat(writer.read(PROJECT, jobId)).isEmpty();
        assertThat(writer.isDraft(PROJECT, jobId)).isEmpty();
    }

    @Test
    void emptySectionsSayNone() {
        writer.writeDraft(PROJECT, jobId, new Report("示例公司甲", "Backend Engineer", 2, null, null, null, null));

        String text = writer.read(PROJECT, jobId).orElseThrow();
        assertThat(text).contains("（第 2 轮，").contains("## 未解决\n\n- 无\n").endsWith("## 守卫记录\n\n- 无\n");
    }

    @Test
    void cellsWithPipesAndNewlinesDoNotBreakTheTable() {
        writer.writeDraft(PROJECT, jobId, new Report("示例公司甲", "Backend Engineer", 1, List.of(
                Filled.inferred("其它", "补充说明", "第一行\n第二行 | 带竖线", Confidence.LOW, FactClass.A, null)),
                null, null, null));

        assertThat(writer.read(PROJECT, jobId).orElseThrow())
                .contains("| 其它 | 补充说明 | 第一行<br>第二行 \\| 带竖线 | 模型推断 | — | low | A |\n");
    }

    @Test
    void reportReadsBackToTheSameTextSoRowsSurviveARestart() {
        for (Report original : List.of(report(1), report(3),
                new Report("示例公司甲", "Backend Engineer", 2, null, null, null, null),
                new Report(null, "带 · 分隔号的岗位", 1, List.of(
                        Filled.inferred("其它", "补充说明", "第一行\n第二行 | 带竖线 \\ 反斜杠", Confidence.LOW,
                                FactClass.A, null),
                        new UserFilled(null, "期望薪资")), null, null, null))) {
            String once = FillReportWriter.render(original, true, TestStorage.NOW);
            Report parsed = FillReportWriter.parse(once);

            assertThat(FillReportWriter.render(parsed, true, TestStorage.NOW)).isEqualTo(once);
            assertThat(FillReportWriter.render(parsed, false, TestStorage.NOW))
                    .isEqualTo(FillReportWriter.render(original, false, TestStorage.NOW));
            assertThat(parsed.round()).isEqualTo(original.round());
            assertThat(parsed.fields()).hasSameSizeAs(original.fields());
            assertThat(parsed.guardNotes()).isEqualTo(original.guardNotes());
            assertThat(parsed.attachments()).isEqualTo(original.attachments());
        }
    }

    @Test
    void loadReadsTheWrittenFileAndKeepsClassCRowsWithoutValues() {
        assertThat(writer.load(PROJECT, jobId)).isEmpty();
        writer.writeDraft(PROJECT, jobId, report(2));

        Report loaded = writer.load(PROJECT, jobId).orElseThrow();

        assertThat(loaded.company()).isEqualTo("示例公司甲");
        assertThat(loaded.title()).isEqualTo("Backend Engineer");
        assertThat(loaded.round()).isEqualTo(2);
        assertThat(loaded.fields()).contains(new UserFilled("基本信息", "身份证号"));
        assertThat(loaded.fields().getFirst()).isEqualTo(new FillReportWriter.Recorded("基本信息", "姓名", "林小雨",
                "来自 base（pin live）", "zh", "high", "**B**"));
        assertThat(loaded.unresolved()).containsExactly("「最早到岗时间」：资料里没有，已在集中问答里问过");
        // 加一条守卫记录后再写，原来的行都还在
        writer.writeDraft(PROJECT, jobId, loaded.withGuardNote(new GuardNote("继续", GuardOutcome.CONFIRMED_UNSAFE)));
        String text = writer.read(PROJECT, jobId).orElseThrow();
        assertThat(text).contains("| 基本信息 | 姓名 | 林小雨 |").contains("- 1 个字段用了未审阅的译文\n")
                .endsWith("- 「继续」：用户确认：会提交，已停下\n");
    }

    @Test
    void aFileInAnotherFormatIsNotSilentlyReadAsEmpty() {
        storage.write(storage.layout.fillReport(PROJECT, jobId), "随手写的笔记\n");

        assertThatThrownBy(() -> writer.load(PROJECT, jobId)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("标题行");
    }

    @Test
    void classCCannotCarryAValue() {
        assertThatThrownBy(() -> Filled.inferred("基本信息", "身份证号", "虚构", Confidence.HIGH, FactClass.C, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(UserFilled.class.getRecordComponents()).extracting(component -> component.getName())
                .containsExactly("page", "label");
    }

    @Test
    void factClassComesFromTheResolvedFact() {
        Filled filled = new Filled("基本信息", "姓名", "林小雨", NAME, Confidence.HIGH, FactClass.A, null, null);

        assertThat(filled.factClass()).isEqualTo(FactClass.B);
        assertThat(filled.language()).isEqualTo(Language.ZH);
        assertThat(filled.original()).isNull();
    }
}

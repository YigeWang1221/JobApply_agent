package jobapply.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jobapply.domain.ImportCandidate;
import jobapply.domain.ImportRun;
import jobapply.domain.Language;
import jobapply.domain.ProjectCard.Section;
import jobapply.persistence.ProfileSummaryFile;
import jobapply.persistence.ProfileSummaryFile.ProfileSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 画像：只读事实层；固定四节；用户编辑过的小节不被覆盖。模型是本机假服务端，数据全部虚构。
 */
class ProfileSummaryBuilderTest {

    @TempDir
    Path dir;

    /** 整理并确认全部候选，得到一个有事实和项目卡的事实层。 */
    private static void confirmAll(ImportFixture fixture) {
        ImportRun run = fixture.reviewing();
        for (ImportCandidate candidate : ImportRunner.candidates(run)) {
            fixture.runner.decide(run.id(), candidate.id(), FactWriter.ACCEPT, null, null);
        }
    }

    private static Map<String, String> texts(List<Section> sections) {
        Map<String, String> texts = new LinkedHashMap<>();
        sections.forEach(section -> texts.put(section.id(), section.text()));
        return texts;
    }

    @Test
    void readsOnlyTheFactLayerAndReturnsFourSectionsInBothLanguages() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            confirmAll(fixture);
            int before = fixture.server.requests().size();

            ProfileSummaryBuilder.Candidate candidate = fixture.summaryBuilder().generate();

            assertThat(candidate.current()).isNull();
            assertThat(candidate.zh()).extracting(Section::id).containsExactlyElementsOf(ProfileSummaryFile.SECTIONS);
            assertThat(candidate.zh()).extracting(Section::heading).containsExactly("方向", "技能分组", "亮点", "定位");
            assertThat(candidate.en()).extracting(Section::heading)
                    .containsExactly("Directions", "Skills", "Highlights", "Positioning");
            assertThat(candidate.warnings()).isEmpty();
            String request = fixture.server.requests().get(before).body();
            assertThat(request).contains("可以提出估算、量化口径、技能定位和措辞的候选写法", "由用户确认采用",
                    "缺少原文数字、出处或测试条件不阻止候选起草", "新增细节作为待确认候选")
                    .doesNotContain("只用给出的事实和项目卡", "不要编造经历、数字和技能");
            // 事实层（facts.md、项目卡）在请求里；来源原文、清单卡、文件名不在
            assertThat(request).contains("林虚构").contains("projects/demo-recsys").contains("负责召回和压测");
            assertThat(request).doesNotContain("单机，1k QPS").doesNotContain("一个虚构的推荐服务课程项目")
                    .doesNotContain("README.md");
            // 生成不写文件
            assertThat(fixture.summaries.read()).isEmpty();
        }
    }

    @Test
    void confirmReplacesTheFileAndUserEditedSectionsAreNotOverwritten() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            confirmAll(fixture);
            ProfileSummaryBuilder builder = fixture.summaryBuilder();
            ProfileSummaryBuilder.Candidate first = builder.generate();
            Map<String, String> zh = texts(first.zh());
            zh.put("positioning", "我自己写的定位。");

            ProfileSummary saved = builder.confirm(zh, texts(first.en()), List.of("zh.positioning"),
                    Set.of(Language.ZH));
            assertThat(fixture.summaries.read()).contains(saved);
            assertThat(saved.userEdited()).containsExactly("zh.positioning");

            ProfileSummaryBuilder.Candidate second = builder.generate();

            assertThat(second.current()).isEqualTo(saved);
            assertThat(texts(second.zh())).containsEntry("positioning", "我自己写的定位。");
            assertThat(second.suggestions()).containsExactly(Map.entry("zh.positioning", "新的定位。"));
            assertThat(texts(second.en())).containsEntry("positioning", "New positioning.");
        }
    }

    @Test
    void unknownReferencesAreReportedNotRefused() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            confirmAll(fixture);
            fixture.server.respond(body -> ImportFixture.PROFILE.replace("projects/demo-recsys", "projects/ghost")
                    .replace("Java（", "Java（c-0099，"));

            ProfileSummaryBuilder.Candidate candidate = fixture.summaryBuilder().generate();

            assertThat(candidate.warnings()).singleElement().asString().contains("projects/ghost").contains("c-0099");
        }
    }

    @Test
    void anEmptyFactLayerAndBadSectionsAreRefused() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            ProfileSummaryBuilder builder = fixture.summaryBuilder();
            assertThatThrownBy(builder::generate).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("事实层还是空的");
            assertThatThrownBy(() -> builder.confirm(Map.of("summary", "x"), Map.of(), List.of(), Set.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> builder.confirm(Map.of(), Map.of(), List.of("fr.skills"), Set.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> builder.confirm(Map.of(), null, List.of(), Set.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void emptySectionsAreReported() {
        try (ImportFixture fixture = ImportFixture.create(dir, false)) {
            confirmAll(fixture);
            // 真实模型检查里遇到过：有的小节返回空字符串
            fixture.server.respond(body -> ImportFixture.PROFILE.replace("\"- 后端开发\"", "\"\""));

            ProfileSummaryBuilder.Candidate candidate = fixture.summaryBuilder().generate();

            assertThat(candidate.warnings()).singleElement().asString().contains("zh").contains("方向");
        }
    }
}


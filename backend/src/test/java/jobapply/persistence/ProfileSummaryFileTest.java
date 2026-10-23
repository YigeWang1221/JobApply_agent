package jobapply.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import jobapply.domain.Language;
import jobapply.domain.ProjectCard.Section;
import jobapply.persistence.ProfileSummaryFile.ProfileSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileSummaryFileTest {

    @TempDir
    Path root;

    @Test
    void roundTripsAndKeepsUnknownContent() {
        TestStorage storage = TestStorage.create(root, true);
        ProfileSummaryFile file = new ProfileSummaryFile(storage.layout, storage.clock);
        storage.write(storage.layout.baseProfile(), """
                ---
                generated_at: 2026-10-22T10:00:00-04:00
                user_edited: [zh.highlights]
                reviewed: [zh]
                theme: 自己加的键
                ---

                # 画像

                ## zh

                ### 方向 {#directions}
                - 后端
                - AI Infra

                ### 亮点 {#highlights}
                - 示例项目

                ## en

                ### Directions {#directions}
                - Backend
                - AI infrastructure

                ### Highlights {#highlights}
                - Demo project

                ## 草稿
                还没想好的。
                """);

        ProfileSummary summary = file.read().orElseThrow();
        assertThat(summary.userEdited()).containsExactly("zh.highlights");
        assertThat(summary.reviewed()).containsExactly(Language.ZH);
        assertThat(summary.zh()).extracting(Section::id).containsExactly("directions", "highlights");
        assertThat(summary.extra()).containsEntry("theme", "自己加的键");
        assertThat(summary.preamble()).contains("# 画像");
        assertThat(summary.trailing()).contains("## 草稿").contains("还没想好的。");

        file.save(summary);
        assertThat(file.read()).contains(summary);
        assertThat(storage.read(storage.layout.baseProfile())).contains("## 草稿");
        assertThat(ProfileSummaryFile.SECTIONS).containsExactly("directions", "skills", "highlights", "positioning");
    }

    @Test
    void languagesMustCorrespond() {
        assertThatThrownBy(() -> new ProfileSummary(null, null, Set.of(),
                List.of(new Section("directions", "方向", "a")), List.of(new Section("skills", "Skills", "b")),
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不对应");
    }
}

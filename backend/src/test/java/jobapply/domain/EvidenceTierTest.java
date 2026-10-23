package jobapply.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import jobapply.persistence.FactsFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 键前缀只影响分组；用户确认的求职内容不需要关联论断（2026-10-22 用户修订）。数据是虚构的。
 */
class EvidenceTierTest {

    @TempDir
    Path root;

    @Test
    void prefixesOnlyChooseTheGroup() {
        assertThat(EvidenceTier.of("experience.internship-1.role")).isEqualTo(EvidenceTier.EXPERIENCE);
        assertThat(EvidenceTier.of("skills.languages")).isEqualTo(EvidenceTier.SKILLS);
        assertThat(EvidenceTier.of("outcomes.latency")).isEqualTo(EvidenceTier.OUTCOMES);
        assertThat(EvidenceTier.of("metrics.qps")).isEqualTo(EvidenceTier.METRICS);
        assertThat(EvidenceTier.of("projects.demo.title")).isEqualTo(EvidenceTier.PROJECTS);
        assertThat(EvidenceTier.of("education.school")).isEqualTo(EvidenceTier.EDUCATION);
        assertThat(EvidenceTier.of("contact.email")).isEqualTo(EvidenceTier.BASIC);
        assertThat(EvidenceTier.of("links.github")).isEqualTo(EvidenceTier.BASIC);
    }

    @Test
    void userConfirmedJobContentWithoutClaimsCanBeSaved() {
        List<Fact> facts = List.of(
                Fact.bilingual("experience.internship-1.role", "后端开发实习生", "Backend Engineering Intern",
                        FactClass.A, "user-confirmed", "2026-10-22", Set.of(Language.ZH, Language.EN)),
                Fact.bilingual("skills.backend", "Java、Spring Boot", "Java, Spring Boot", FactClass.A,
                        "user-confirmed", "2026-10-22", Set.of(Language.ZH)),
                Fact.bilingual("outcomes.order-service", "主导重构订单服务，接口延迟降低 40%",
                        "Led the order service rewrite, cutting latency by 40%", FactClass.A, "user-confirmed",
                        "2026-10-22", Set.of(Language.ZH)),
                Fact.of("metrics.order-service.qps", "12000", FactClass.A, "user-confirmed", "2026-10-22"));

        assertThatCode(() -> EvidenceTier.requireKnownClaims(facts, Set.of())).doesNotThrowAnyException();
        Path file = root.resolve("facts.md");
        FactsFile.write(file, "个人基础事实", facts);
        assertThat(FactsFile.read(file)).isEqualTo(facts);
    }

    @Test
    void claimsThatAreGivenMustExist() {
        List<Fact> facts = List.of(
                Fact.of("metrics.qps", "12000", FactClass.A, "user-confirmed", null).withClaim("c-0001"),
                Fact.of("skills.go", "Go", FactClass.A, "user-confirmed", null).withClaim("c-0009"));

        assertThatCode(() -> EvidenceTier.requireKnownClaims(facts.subList(0, 1), Set.of("c-0001")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> EvidenceTier.requireKnownClaims(facts, Set.of("c-0001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skills.go → c-0009")
                .hasMessageNotContaining("metrics.qps");
    }
}

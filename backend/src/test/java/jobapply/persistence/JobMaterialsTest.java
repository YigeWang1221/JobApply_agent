package jobapply.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import jobapply.domain.Actor;
import jobapply.domain.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Job 和原件全部虚构。
 */
class JobMaterialsTest {

    private static final String PROJECT = "fall-2026-v1";

    @TempDir
    Path root;

    private TestStorage storage;
    private AssetRegistry assets;
    private JobMaterials materials;
    private Job job;

    @BeforeEach
    void setUp() {
        storage = TestStorage.create(root);
        storage.project(PROJECT, "live");
        String session = storage.session(PROJECT);
        job = storage.ledger().addLead(PROJECT, session, Actor.AGENT, TestStorage.posting("示例公司甲", "JD-001"),
                "职位描述（虚构）");
        assets = new AssetRegistry(storage.layout, storage.clock);
        materials = new JobMaterials(storage.layout, assets, storage.clock);
    }

    @Test
    void writesTheChosenAssetsAndKeepsTheRestOfJobMd() {
        Path file = storage.layout.jobFile(PROJECT, job.id());
        FrontmatterFile.Document before = FrontmatterFile.read(file);
        AssetRegistry.Asset resume = assets.register("resume-demo.pdf", "application/pdf",
                "虚构简历".getBytes(UTF_8)).asset();
        AssetRegistry.Asset portfolio = assets.register("portfolio-demo.pdf", "application/pdf",
                "虚构作品集".getBytes(UTF_8)).asset();

        List<JobMaterials.Material> written = materials.write(PROJECT, job.id(), List.of(
                new JobMaterials.Selection("简历", resume.key()),
                new JobMaterials.Selection(null, portfolio.key())));

        assertThat(written).containsExactly(
                new JobMaterials.Material("简历", resume.key(), "resume-demo.pdf", resume.sha256()),
                new JobMaterials.Material(null, portfolio.key(), "portfolio-demo.pdf", portfolio.sha256()));
        FrontmatterFile.Document after = FrontmatterFile.read(file);
        assertThat(after.body()).isEqualTo(before.body());
        Map<String, Object> fm = after.frontmatter();
        before.frontmatter().forEach((key, value) -> assertThat(fm).containsEntry(key, value));
        assertThat(fm.get("materials")).isEqualTo(List.of(
                Map.of("slot", "简历", "asset", resume.key(), "upload_name", "resume-demo.pdf",
                        "sha256", resume.sha256()),
                Map.of("asset", portfolio.key(), "upload_name", "portfolio-demo.pdf", "sha256", portfolio.sha256())));
        assertThat(materials.read(PROJECT, job.id())).isEqualTo(written);
        // 之后台账的写操作不会丢掉 materials
        assertThat(new JobRepository(storage.layout).load(PROJECT, job.id()).extra()).containsKey("materials");
    }

    @Test
    void replacesThePreviousChoice() {
        AssetRegistry.Asset first = assets.register("resume-v1.pdf", "application/pdf", "v1".getBytes(UTF_8)).asset();
        AssetRegistry.Asset second = assets.register("resume-v2.pdf", "application/pdf", "v2".getBytes(UTF_8)).asset();
        materials.write(PROJECT, job.id(), List.of(new JobMaterials.Selection("简历", first.key())));

        materials.write(PROJECT, job.id(), List.of(new JobMaterials.Selection("简历", second.key())));

        assertThat(materials.read(PROJECT, job.id())).extracting(JobMaterials.Material::asset)
                .containsExactly(second.key());
    }

    @Test
    void unknownAssetsWriteNothing() {
        Path file = storage.layout.jobFile(PROJECT, job.id());
        String before = storage.read(file);

        assertThatThrownBy(() -> materials.write(PROJECT, job.id(),
                List.of(new JobMaterials.Selection("简历", "a-2026-10-22-999"))))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> materials.write(PROJECT, job.id(),
                List.of(new JobMaterials.Selection("简历", "../resume.pdf"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> materials.read(PROJECT, "2026-10-22-099"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(storage.read(file)).isEqualTo(before);
        assertThat(materials.read(PROJECT, job.id())).isEmpty();
    }
}

package jobapply.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import jobapply.domain.JobPosting;
import jobapply.domain.ProjectSpec;

/**
 * 测试用的临时 storage。数据全部是虚构的。
 */
public final class TestStorage {

    public static final String NOW = "2026-10-22T14:00:00Z";

    public final StorageLayout layout;
    public final TestClock clock;

    private TestStorage(Path root, TestClock clock) {
        this.layout = new StorageLayout(root);
        this.clock = clock;
    }

    public static TestStorage create(Path root) {
        TestStorage storage = new TestStorage(root, TestClock.at(NOW));
        new StorageInitializer(storage.layout, storage.clock).initialize();
        return storage;
    }

    /** 打开一个已经初始化过的临时 storage。 */
    public static TestStorage open(Path root) {
        return new TestStorage(root, TestClock.at(NOW));
    }

    public ProjectRepository projects() {
        return new ProjectRepository(layout, clock);
    }

    public SessionRepository sessions() {
        return new SessionRepository(layout, clock);
    }

    public JobLedger ledger() {
        return new JobLedger(layout, clock);
    }

    public ProjectSpec project(String id, String basePin, String... parents) {
        List<String> inherits = new ArrayList<>(List.of(ProjectSpec.BASE));
        for (String parent : parents) {
            inherits.add("projects/" + parent);
        }
        return projects().create(new ProjectSpec(id, "示例 Project " + id, inherits, basePin, "resume-demo", null));
    }

    public String session(String projectId) {
        return sessions().create(projectId, "示例对话").id();
    }

    public static JobPosting posting(String company, String jobId) {
        return new JobPosting(company, "2027 校园招聘", "Backend Engineer", jobId, "campus-fulltime",
                List.of("上海"), "https://jobs.example.com/" + jobId + "?from=search", null, "self-campus",
                "user-link");
    }

    public String read(Path path) {
        try {
            return Files.readString(path, UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 所有文件的内容快照。 */
    public Map<String, String> snapshot() {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(layout.root())) {
            walk.filter(Files::isRegularFile)
                    .forEach(path -> files.put(layout.relative(path), read(path)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }
}

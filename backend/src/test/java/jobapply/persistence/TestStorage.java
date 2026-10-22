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
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

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

    public static TestStorage create(Path root, boolean git) {
        TestStorage storage = new TestStorage(root, TestClock.at(NOW));
        new StorageInitializer(storage.layout, storage.clock).initialize(git);
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

    public StorageGit git() {
        return new StorageGit(layout, clock);
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

    /** 除 .git 之外所有文件的内容快照。 */
    public Map<String, String> snapshot() {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(layout.root())) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> !layout.relative(path).startsWith(".git/"))
                    .forEach(path -> files.put(layout.relative(path), read(path)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    public int commitCount() {
        try (Git git = Git.open(layout.root().toFile())) {
            if (git.getRepository().resolve("HEAD") == null) {
                return 0;
            }
            int count = 0;
            for (RevCommit ignored : git.log().call()) {
                count++;
            }
            return count;
        } catch (IOException | GitAPIException e) {
            throw new IllegalStateException(e);
        }
    }

    public String headMessage() {
        try (Git git = Git.open(layout.root().toFile())) {
            return git.log().setMaxCount(1).call().iterator().next().getFullMessage();
        } catch (IOException | GitAPIException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 工作区里没有未提交的改动，也没有未跟踪的文件（被 .gitignore 忽略的除外）。 */
    public boolean isClean() {
        try (Git git = Git.open(layout.root().toFile())) {
            return git.status().call().isClean();
        } catch (IOException | GitAPIException e) {
            throw new IllegalStateException(e);
        }
    }

    /** HEAD 提交树里的全部文件。 */
    public List<String> trackedFiles() {
        try (Git git = Git.open(layout.root().toFile()); RevWalk revWalk = new RevWalk(git.getRepository())) {
            ObjectId head = git.getRepository().resolve("HEAD");
            List<String> files = new ArrayList<>();
            if (head == null) {
                return files;
            }
            try (TreeWalk walk = new TreeWalk(git.getRepository())) {
                walk.addTree(revWalk.parseCommit(head).getTree());
                walk.setRecursive(true);
                while (walk.next()) {
                    files.add(walk.getPathString());
                }
            }
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

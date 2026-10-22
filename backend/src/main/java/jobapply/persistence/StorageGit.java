package jobapply.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.StatusCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/**
 * {@code storage/.git}：可选的本地版本记录。{@code .git} 存在即视为开启；没有开启时提交是空操作。
 * 只提交调用方给出的路径（等同 {@code git commit --only}）：用户自己暂存的其它文件不会被带进来，提交后仍留在暂存区。不设远程。
 */
public final class StorageGit {

    private static final String AUTHOR_NAME = "JobApply";
    private static final String AUTHOR_EMAIL = "jobapply@localhost";

    private final StorageLayout layout;
    private final Clock clock;

    public StorageGit(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return Files.isDirectory(layout.gitDir());
    }

    void init() {
        try (Git ignored = Git.init().setDirectory(layout.root().toFile()).setInitialBranch("main").call()) {
            // 只需要建仓库
        } catch (GitAPIException e) {
            throw new IllegalStateException("git init 失败：" + layout.root(), e);
        }
    }

    /**
     * 提交给出的路径。没有开启 git、或这些路径没有变化时返回 false。
     */
    public boolean commit(String message, Collection<Path> paths) {
        if (!isEnabled() || paths.isEmpty()) {
            return false;
        }
        List<String> relative = paths.stream().map(layout::relative).distinct().toList();
        try (Git git = Git.open(layout.root().toFile())) {
            AddCommand add = git.add();
            StatusCommand statusOfOurs = git.status();
            for (String path : relative) {
                add.addFilepattern(path);
                statusOfOurs.addPath(path);
            }
            add.call();
            if (!hasStagedChanges(statusOfOurs.call())) {
                return false;
            }
            CommitCommand commit = newCommit(git, message);
            relative.forEach(commit::setOnly);
            commit.call();
            return true;
        } catch (IOException | GitAPIException e) {
            throw new IllegalStateException("git 提交失败：" + message, e);
        }
    }

    /** 提交工作区里所有未被忽略的文件。只在开启 git 时用一次。 */
    boolean commitAll(String message) {
        try (Git git = Git.open(layout.root().toFile())) {
            git.add().addFilepattern(".").call();
            if (!hasStagedChanges(git.status().call())) {
                return false;
            }
            newCommit(git, message).call();
            return true;
        } catch (IOException | GitAPIException e) {
            throw new IllegalStateException("git 提交失败：" + message, e);
        }
    }

    public Optional<String> head() {
        if (!isEnabled()) {
            return Optional.empty();
        }
        try (Git git = Git.open(layout.root().toFile())) {
            ObjectId head = git.getRepository().resolve("HEAD");
            return Optional.ofNullable(head).map(ObjectId::name);
        } catch (IOException e) {
            throw new IllegalStateException("读取 HEAD 失败", e);
        }
    }

    /** 读取某个提交里的文件内容。提交或文件不存在时返回空。 */
    public Optional<String> readAtCommit(String revision, String relativePath) {
        try (Git git = Git.open(layout.root().toFile())) {
            Repository repository = git.getRepository();
            ObjectId id = repository.resolve(revision + "^{commit}");
            if (id == null) {
                return Optional.empty();
            }
            try (RevWalk walk = new RevWalk(repository)) {
                return read(repository, walk.parseCommit(id), relativePath);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取历史版本失败：" + revision, e);
        }
    }

    /** 读取某天结束前（按时钟所在时区）最后一个提交里的文件内容。 */
    public Optional<String> readAtEndOfDay(LocalDate day, String relativePath) {
        Instant endExclusive = day.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
        try (Git git = Git.open(layout.root().toFile())) {
            Repository repository = git.getRepository();
            if (repository.resolve("HEAD") == null) {
                return Optional.empty();
            }
            for (RevCommit commit : git.log().call()) {
                if (commit.getCommitterIdent().getWhenAsInstant().isBefore(endExclusive)) {
                    return read(repository, commit, relativePath);
                }
            }
            return Optional.empty();
        } catch (IOException | GitAPIException e) {
            throw new IllegalStateException("读取历史版本失败：" + day, e);
        }
    }

    private static boolean hasStagedChanges(Status status) {
        return !status.getAdded().isEmpty() || !status.getChanged().isEmpty() || !status.getRemoved().isEmpty();
    }

    private CommitCommand newCommit(Git git, String message) {
        PersonIdent ident = new PersonIdent(AUTHOR_NAME, AUTHOR_EMAIL, clock.instant(), clock.getZone());
        return git.commit()
                .setMessage(message)
                .setAuthor(ident)
                .setCommitter(ident)
                .setSign(false)
                .setNoVerify(true);
    }

    private static Optional<String> read(Repository repository, RevCommit commit, String relativePath)
            throws IOException {
        try (TreeWalk walk = TreeWalk.forPath(repository, relativePath, commit.getTree())) {
            if (walk == null) {
                return Optional.empty();
            }
            return Optional.of(new String(repository.open(walk.getObjectId(0)).getBytes(), UTF_8));
        }
    }
}

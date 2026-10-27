package jobapply.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * 把仓库里预置的初始 skill（classpath 的 {@code playbook/skills/}）复制到 {@code storage/playbook/skills/}。
 * 缺少的文件补上；已有的文件只在预置的 {@code version}（frontmatter）比它新时覆盖（C016，用户 2026-10-24 定：
 * 改写过的预置 skill 要能送到用户本机）。本机那份的 version 相同或更高、没有 version、读不出来时一律不动
 * （用户改过的正文要留着）。用户删掉的预置 skill，下次会被补回来。
 * 预置文件逐个列在 {@link #BUNDLED} 里（打包成 jar 后不方便列 classpath 目录），测试会核对清单和资源目录一致。
 */
public final class PlaybookSeeder {

    static final String RESOURCE_ROOT = "playbook/skills/";

    /** 预置 skill 的相对路径。 */
    static final List<String> BUNDLED = List.of(
            "apply-form/SKILL.md",
            "components/cascader-city/SKILL.md",
            "components/date-picker/SKILL.md",
            "components/readonly-select/SKILL.md",
            "components/file-upload/SKILL.md",
            "components/resume-parse/SKILL.md",
            "components/repeatable-entries/SKILL.md",
            "components/cover-letter/SKILL.md",
            "memory-update/SKILL.md",
            "research/broad-search/SKILL.md",
            "research/verify-official/SKILL.md");

    private final StorageLayout layout;

    public PlaybookSeeder(StorageLayout layout, Clock clock) {
        this.layout = layout;
    }

    /**
     * 复制缺少的、和比本机新的预置 skill，返回这次写下的文件。都不用写时什么也不写。
     *
     * @throws IllegalStateException 打包里缺少某个预置文件
     */
    public synchronized List<Path> seed() {
        List<Path> written = new ArrayList<>();
        for (String relative : BUNDLED) {
            Path target = layout.playbookSkills().resolve(relative);
            String content = bundled(relative);
            if (Files.exists(target) && !newer(content, AtomicFiles.read(target), relative)) {
                continue;
            }
            AtomicFiles.write(target, content);
            written.add(target);
        }
        return List.copyOf(written);
    }

    /** 预置的 version 比本机那份高。本机那份没有 version 或读不出来时为 false（不动）。 */
    static boolean newer(String bundled, String local, String relative) {
        BigDecimal mine = version(bundled, relative);
        BigDecimal theirs;
        try {
            theirs = version(local, relative);
        } catch (RuntimeException e) {
            return false;
        }
        return mine != null && theirs != null && mine.compareTo(theirs) > 0;
    }

    private static BigDecimal version(String content, String relative) {
        Object version = FrontmatterFile.parse(content, relative).frontmatter().get("version");
        try {
            return version == null ? null : new BigDecimal(version.toString().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 读一个预置文件的内容。 */
    static String bundled(String relative) {
        String resource = RESOURCE_ROOT + relative;
        try (InputStream in = PlaybookSeeder.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("打包里缺少预置 skill：" + resource);
            }
            return new String(in.readAllBytes(), UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取预置 skill 失败：" + resource, e);
        }
    }
}

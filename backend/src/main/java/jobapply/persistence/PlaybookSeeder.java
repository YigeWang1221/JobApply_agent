package jobapply.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * 把仓库里预置的初始 skill（classpath 的 {@code playbook/skills/}）复制到 {@code storage/playbook/skills/}。
 * 只补缺少的文件，已有的一律不覆盖（用户或以后的候选流程改过的正文要留着）。用户删掉的预置 skill，下次会被补回来。
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
            "research/broad-search/SKILL.md",
            "research/verify-official/SKILL.md");

    private final StorageLayout layout;

    public PlaybookSeeder(StorageLayout layout, Clock clock) {
        this.layout = layout;
    }

    /**
     * 复制缺少的预置 skill，返回这次写下的文件。没有缺少的文件时什么也不写、不提交。
     *
     * @throws IllegalStateException 打包里缺少某个预置文件
     */
    public synchronized List<Path> seed() {
        List<Path> written = new ArrayList<>();
        for (String relative : BUNDLED) {
            Path target = layout.playbookSkills().resolve(relative);
            if (Files.exists(target)) {
                continue;
            }
            AtomicFiles.write(target, bundled(relative));
            written.add(target);
        }
        if (!written.isEmpty()) {
        }
        return List.copyOf(written);
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

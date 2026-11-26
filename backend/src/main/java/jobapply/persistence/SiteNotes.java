package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import jobapply.domain.Timestamps;

/**
 * {@code playbook/sites/<host>.md}：一个站点的事实记录（不是 skill，C004-B「skill 体系」）。
 * frontmatter 有 {@code host}、{@code platform}（猜测）；正文是观察记录。
 * 读旧文件时如仍有历史残留的 {@code confirmed_buttons} 等未知 frontmatter 键，静默忽略。
 */
public final class SiteNotes {

    static final String HOST = "host";
    static final String PLATFORM = "platform";
    private static final int MAX_NAME = 200;

    /**
     * 读出来的站点记录。
     *
     * @param platform 平台猜测，没有时为空字符串
     * @param notes    正文（观察记录）
     */
    public record Site(String host, String platform, String notes) {
    }

    private final StorageLayout layout;
    private final Clock clock;

    public SiteNotes(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.clock = clock;
    }

    /** 读一个站点的记录；还没有文件时返回空。文件格式不对时报错（带文件名）。 */
    public Optional<Site> read(String host) {
        Path file = layout.playbookSite(host);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return Optional.of(parse(host, FrontmatterFile.read(file), layout.relative(file)));
    }

    /** 记下（或更新）平台猜测。这是猜测，不是确认记录，可以被后来的猜测替换。 */
    public synchronized void notePlatform(String host, String platform) {
        String value = requireLine(platform, "平台");
        update(host, (frontmatter, body) -> {
            frontmatter.put(PLATFORM, value);
            return body;
        }, "playbook：" + host + " 平台猜测 " + value);
    }

    /** 在正文末尾追加一条带时间的观察记录。已有的正文不动。 */
    public synchronized void appendObservation(String host, String observation) {
        if (observation == null || observation.isBlank()) {
            throw new IllegalArgumentException("观察记录不能为空");
        }
        String line = "- " + Timestamps.now(clock) + " " + observation.strip().replaceAll("\\s*\\R\\s*", " ");
        update(host, (frontmatter, body) -> {
            String trimmed = body.stripTrailing();
            if (trimmed.isEmpty()) {
                return "\n# 观察记录\n\n" + line + "\n";
            }
            String last = trimmed.substring(trimmed.lastIndexOf('\n') + 1);
            return trimmed + (last.startsWith("- ") ? "\n" : "\n\n") + line + "\n";
        }, "playbook：" + host + " 追加观察记录");
    }

    private interface Change {
        String apply(Map<String, Object> frontmatter, String body);
    }

    private void update(String host, Change change, String message) {
        Path file = layout.playbookSite(host);
        Map<String, Object> frontmatter = new LinkedHashMap<>();
        String body = "\n# 观察记录\n";
        if (Files.exists(file)) {
            FrontmatterFile.Document existing = FrontmatterFile.read(file);
            parse(host, existing, layout.relative(file));
            frontmatter.putAll(existing.frontmatter());
            body = existing.body();
        } else {
            frontmatter.put(HOST, host);
            frontmatter.put(PLATFORM, "");
        }
        String newBody = change.apply(frontmatter, body);
        FrontmatterFile.write(file, new FrontmatterFile.Document(frontmatter, newBody));
    }

    private static Site parse(String host, FrontmatterFile.Document document, String source) {
        Map<String, Object> fm = document.frontmatter();
        Object fileHost = fm.get(HOST);
        if (fileHost != null && !host.equals(fileHost)) {
            throw new StorageFormatException(source, "host 和文件名不一致：" + fileHost);
        }
        Object platform = fm.get(PLATFORM);
        if (platform != null && !(platform instanceof String)) {
            throw new StorageFormatException(source, "platform 必须是字符串");
        }
        return new Site(host, platform == null ? "" : (String) platform, document.body());
    }

    private static String requireLine(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + "不能为空");
        }
        String trimmed = value.strip();
        if (trimmed.length() > MAX_NAME || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(what + "不能含换行，也不能超过 " + MAX_NAME + " 个字符");
        }
        return trimmed;
    }
}

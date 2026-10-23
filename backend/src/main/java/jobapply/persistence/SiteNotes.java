package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import jobapply.domain.Ids;
import jobapply.domain.Timestamps;
import jobapply.harness.policies.SubmitWords;

/**
 * {@code playbook/sites/<host>.md}：一个站点的事实记录（不是 skill，C004-B「skill 体系」）。
 * frontmatter 有 {@code host}、{@code platform}（猜测）、{@code confirmed_buttons}（{@code {name, verdict, at, session}}）；
 * 正文是观察记录。
 * <p>
 * 确认记录只追加，不删减、不改写。同一个按钮名字（按 {@link SubmitWords#normalize} 比较）以最后一条记录为准，
 * 所以用户后来答「不安全」就收回之前的放行，旧记录仍然留着。
 * <p>
 * {@code safe} 记录只能放行软边界按钮：名字命中硬边界词表（{@link SubmitWords#isHard}）的，写入时拒绝；
 * 文件被手工改过、里面有这样的 {@code safe} 记录时，读出放行名单时也会去掉。硬边界词表在代码里，这个文件改不了它。
 */
public final class SiteNotes {

    static final String HOST = "host";
    static final String PLATFORM = "platform";
    static final String CONFIRMED_BUTTONS = "confirmed_buttons";
    private static final int MAX_NAME = 200;

    /** 用户对一个软边界按钮的回答。 */
    public enum Verdict {
        SAFE, UNSAFE;

        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Verdict parse(String value, String source) {
            for (Verdict verdict : values()) {
                if (verdict.value().equals(value)) {
                    return verdict;
                }
            }
            throw new StorageFormatException(source, "verdict 只能是 safe 或 unsafe：" + value);
        }
    }

    /**
     * 一条确认记录。
     *
     * @param at      记录时间（ISO-8601，带偏移）
     * @param session 回答发生在哪个申请线程（job id）或 Session（session id）
     */
    public record ButtonConfirmation(String name, Verdict verdict, String at, String session) {
    }

    /**
     * 读出来的站点记录。
     *
     * @param platform 平台猜测，没有时为空字符串
     * @param notes    正文（观察记录）
     */
    public record Site(String host, String platform, List<ButtonConfirmation> confirmedButtons, String notes) {

        public Site {
            confirmedButtons = List.copyOf(confirmedButtons);
        }

        /**
         * 当前放行的按钮名字（交给 {@code SubmitGuard.click} 的 {@code confirmedSafe}）：每个名字取最后一条记录，
         * 是 {@code safe} 且不命中硬边界词表的才算。
         */
        public Set<String> confirmedSafe() {
            Map<String, ButtonConfirmation> latest = new LinkedHashMap<>();
            for (ButtonConfirmation record : confirmedButtons) {
                latest.put(SubmitWords.normalize(record.name()), record);
            }
            Set<String> safe = new LinkedHashSet<>();
            for (ButtonConfirmation record : latest.values()) {
                if (record.verdict() == Verdict.SAFE && !SubmitWords.isHard(record.name())) {
                    safe.add(record.name());
                }
            }
            return Set.copyOf(safe);
        }
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

    /** 这个站点上用户确认过「安全」的软边界按钮名字；没有记录时为空集合。 */
    public Set<String> confirmedSafe(String host) {
        return read(host).map(Site::confirmedSafe).orElse(Set.of());
    }

    /**
     * 追加一条用户对软边界按钮的回答。
     *
     * @param session 申请线程的 job id，或 Session id
     * @throws IllegalArgumentException 名字为空、含换行、过长；或者要把硬边界按钮记成 {@code safe}
     */
    public synchronized ButtonConfirmation recordButton(String host, String buttonName, Verdict verdict,
                                                        String session) {
        String name = requireLine(buttonName, "按钮名字");
        if (verdict == null) {
            throw new IllegalArgumentException("缺少 verdict");
        }
        if (verdict == Verdict.SAFE && SubmitWords.isHard(name)) {
            throw new IllegalArgumentException("硬边界按钮不能记成 safe，只能由用户自己点：" + name);
        }
        if (session == null || !(Ids.isJobId(session) || Ids.isSessionId(session))) {
            throw new IllegalArgumentException("session 必须是 job id 或 session id：" + session);
        }
        ButtonConfirmation record = new ButtonConfirmation(name, verdict, Timestamps.now(clock), session);
        update(host, (frontmatter, body) -> {
            List<Object> buttons = new ArrayList<>(rawButtons(frontmatter, host));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", record.name());
            entry.put("verdict", record.verdict().value());
            entry.put("at", record.at());
            entry.put("session", record.session());
            buttons.add(entry);
            frontmatter.put(CONFIRMED_BUTTONS, buttons);
            return body;
        }, "playbook：" + host + " 记下按钮「" + name + "」为 " + verdict.value());
        return record;
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
            frontmatter.put(CONFIRMED_BUTTONS, new ArrayList<>());
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
        List<ButtonConfirmation> buttons = new ArrayList<>();
        for (Object item : rawButtons(fm, source)) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new StorageFormatException(source, "confirmed_buttons 的每一项都必须是键值对");
            }
            String name = string(map.get("name"), "name", source);
            Verdict verdict = Verdict.parse(string(map.get("verdict"), "verdict", source), source);
            buttons.add(new ButtonConfirmation(name, verdict, optionalString(map.get("at")),
                    optionalString(map.get("session"))));
        }
        return new Site(host, platform == null ? "" : (String) platform, buttons, document.body());
    }

    private static List<?> rawButtons(Map<String, Object> fm, String source) {
        Object value = fm.get(CONFIRMED_BUTTONS);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new StorageFormatException(source, "confirmed_buttons 必须是列表");
        }
        return list;
    }

    private static String string(Object value, String key, String source) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new StorageFormatException(source, "confirmed_buttons 的记录缺少 " + key);
        }
        return text;
    }

    private static String optionalString(Object value) {
        return value == null ? "" : value.toString();
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

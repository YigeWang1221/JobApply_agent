package jobapply.domain;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@code project.md} 的 frontmatter：一份资料版本 + 继承关系。
 */
public record ProjectSpec(
        String id,
        String title,
        List<String> inherits,
        String basePin,
        String defaultResume,
        String status) {

    public static final String BASE = "base";
    public static final String LIVE = "live";
    private static final String PARENT_PREFIX = "projects/";
    private static final Pattern ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{7,40}");

    public ProjectSpec {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("project id 只允许小写字母、数字和 -：" + id);
        }
        inherits = inherits == null ? List.of() : List.copyOf(inherits);
        if (!inherits.contains(BASE)) {
            throw new IllegalArgumentException("inherits 必须包含 base：" + id);
        }
        for (String entry : inherits) {
            if (!entry.equals(BASE)
                    && !(entry.startsWith(PARENT_PREFIX) && ID.matcher(entry.substring(PARENT_PREFIX.length())).matches())) {
                throw new IllegalArgumentException("inherits 只能是 base 或 projects/<id>：" + entry);
            }
        }
        basePin = basePin == null ? LIVE : basePin;
        if (!isLivePin(basePin) && !isCommitPin(basePin) && !isDatePin(basePin)) {
            throw new IllegalArgumentException("base_pin 只能是 live、commit 或 YYYY-MM-DD：" + basePin);
        }
        status = status == null ? "active" : status;
    }

    /** 父 Project 的 id，按 inherits 顺序，不含 base。 */
    public List<String> parentIds() {
        return inherits.stream()
                .filter(entry -> !entry.equals(BASE))
                .map(entry -> entry.substring(PARENT_PREFIX.length()))
                .toList();
    }

    public boolean pinsLive() {
        return isLivePin(basePin);
    }

    public boolean pinsDate() {
        return isDatePin(basePin);
    }

    private static boolean isLivePin(String pin) {
        return LIVE.equals(pin);
    }

    private static boolean isCommitPin(String pin) {
        return COMMIT.matcher(pin).matches();
    }

    private static boolean isDatePin(String pin) {
        try {
            LocalDate.parse(pin);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}

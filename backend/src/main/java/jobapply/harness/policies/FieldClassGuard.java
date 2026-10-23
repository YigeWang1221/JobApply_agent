package jobapply.harness.policies;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jobapply.tools.SnapshotIndex;

/**
 * C 级字段守卫：字段的名字，或它所在分组（祖先节点）的名字命中 C 级词表时，交给用户亲自填写。
 * 宁可多拦：误判的代价只是多问用户一次。词表写在代码里，只能由开发代理修改。
 * 同一份词表也用来在发给模型之前去掉文字里的 C 级内容（{@link #withoutClassC(String)}）。
 */
public final class FieldClassGuard {

    static final String REASON = "C 级字段由用户亲自填写";

    static final List<String> C_ZH = List.of(
            "身份证", "证件", "护照", "签证", "工作授权", "工作许可", "居留", "期望薪资", "期望薪酬", "薪资", "薪酬", "工资",
            "年薪", "月薪", "民族", "宗教", "信仰", "残障", "残疾", "健康", "病史", "犯罪记录", "犯罪", "违法记录", "声明",
            "承诺");
    static final List<String> C_EN = List.of(
            "sponsorship", "sponsor", "work authorization", "authorized to work", "authorization to work",
            "right to work", "work permit", "visa", "passport", "social security", "ssn", "national id",
            "identity card", "id number", "salary", "compensation", "pay expectation", "expected pay", "ethnicity",
            "race", "religio", "disability", "disabled", "veteran", "health", "medical", "criminal", "conviction",
            "i certify", "i attest", "i declare", "i hereby");

    private static final List<Pattern> C_EN_PATTERNS = C_EN.stream()
            .map(word -> Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word)))
            .toList();

    private FieldClassGuard() {
    }

    /**
     * 写一个字段之前检查。
     *
     * @param index      最新快照的索引
     * @param ref        要写的字段
     * @param givenLabel 模型在调用里给的字段名（例如 {@code fill_form} 的 {@code name}），可以为 null
     */
    public static GuardDecision check(SnapshotIndex index, String ref, String givenLabel) {
        List<String> labels = new ArrayList<>();
        if (givenLabel != null) {
            labels.add(givenLabel);
        }
        Optional<SnapshotIndex.Node> node = index == null ? Optional.empty() : index.find(ref);
        node.ifPresent(found -> {
            labels.add(found.name());
            found.ancestors().forEach(ancestor -> labels.add(ancestor.name()));
        });
        for (String label : labels) {
            if (isClassC(label)) {
                return new GuardDecision.Handoff(REASON + "（「" + label.strip() + "」）");
            }
        }
        return GuardDecision.allow();
    }

    /** 去掉 C 级值以后的文字，以及去掉了几处。 */
    public record Redacted(String text, int removed) {
    }

    public static final String REMOVED = "[已省略]";

    /** 身份证号（18 位）和美国 SSN 的号码格式。 */
    private static final List<Pattern> C_NUMBERS = List.of(
            Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?![\\dA-Za-z])"),
            Pattern.compile("(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)"));

    /** 没有冒号时，「证件名 + 号码」的写法（号码里要有数字），例如 {@code Passport No. X1234567}、{@code 护照号 E12345678}。 */
    private static final Pattern C_DOCUMENT_NUMBER = Pattern.compile(
            "(?i)(passport|visa|ssn|social security|national id|id card|身份证|护照|签证|证件)"
                    + "(\\s*(no\\.?|number|num|#|号码|号))?\\s*[:：#]?\\s*((?=[A-Z0-9-]*\\d)[A-Z0-9][A-Z0-9-]{4,})");

    /**
     * 发给模型之前去掉 C 级的值，其它文字保留：
     * <ul>
     *   <li>「字段名：值」里字段名命中 C 级词表的（例如「期望薪资：…」「Visa status: …」），只去掉值；</li>
     *   <li>证件号码格式（18 位身份证号、SSN）本身；</li>
     *   <li>没有冒号时，「证件名 + 号码」写法里的号码。</li>
     * </ul>
     * 普通文字里出现 C 级词（例如项目名「健康管理平台」）不处理。
     */
    public static Redacted withoutClassC(String text) {
        if (text == null || text.isEmpty()) {
            return new Redacted(text == null ? "" : text, 0);
        }
        int[] removed = {0};
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            String result = line;
            int colon = firstColon(result);
            if (colon > 0 && isClassC(result.substring(0, colon)) && !result.substring(colon + 1).isBlank()) {
                result = result.substring(0, colon + 1) + " " + REMOVED;
                removed[0]++;
            }
            Matcher document = C_DOCUMENT_NUMBER.matcher(result);
            StringBuilder replaced = new StringBuilder();
            while (document.find()) {
                document.appendReplacement(replaced, Matcher.quoteReplacement(
                        result.substring(document.start(), document.start(4)) + REMOVED));
                removed[0]++;
            }
            document.appendTail(replaced);
            result = replaced.toString();
            for (Pattern number : C_NUMBERS) {
                Matcher matcher = number.matcher(result);
                if (matcher.find()) {
                    result = matcher.replaceAll(REMOVED);
                    removed[0]++;
                }
            }
            out.append(result).append('\n');
        }
        out.setLength(out.length() - 1);
        return new Redacted(out.toString(), removed[0]);
    }

    private static int firstColon(String line) {
        int ascii = line.indexOf(':');
        int full = line.indexOf('：');
        if (ascii < 0) {
            return full;
        }
        return full < 0 ? ascii : Math.min(ascii, full);
    }

    public static boolean isClassC(String label) {
        String normalized = SubmitWords.normalize(label);
        if (normalized.isEmpty()) {
            return false;
        }
        String compact = SubmitWords.compact(normalized);
        return C_ZH.stream().anyMatch(compact::contains)
                || C_EN_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
    }
}

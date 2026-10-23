package jobapply.harness.policies;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import jobapply.tools.SnapshotIndex;

/**
 * C 级字段守卫：字段的名字，或它所在分组（祖先节点）的名字命中 C 级词表时，交给用户亲自填写。
 * 宁可多拦：误判的代价只是多问用户一次。词表写在代码里，只能由开发代理修改。
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

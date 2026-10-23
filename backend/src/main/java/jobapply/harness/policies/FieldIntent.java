package jobapply.harness.policies;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 模型写一个字段时多给的参数（{@link SubmitGuard.ClickIntent} 的字段版本）。系统用它们写报告和日志，
 * 发给 MCP 之前用 {@link #strip(Map)} 去掉：白名单不接受这些键。
 *
 * @param ref        字段的 ref（{@code target}）；没有时为 null
 * @param label      模型给的字段名（{@code name} 或 {@code element}）；没有时为 null
 * @param fact       资料键、答案编号，或 {@code none}（模型推断）；没写时为 null
 * @param confidence {@code high} / {@code medium} / {@code low}；没写时为 null
 * @param language   {@code zh} / {@code en}：指明用另一种语言的值；没有时为 null（按页面语言）
 */
public record FieldIntent(String ref, String label, String fact, String confidence, String language) {

    public static final String FACT = "fact";
    public static final String CONFIDENCE = "confidence";
    public static final String LANGUAGE = "language";
    public static final String NONE = "none";
    static final Set<String> KEYS = Set.of(FACT, CONFIDENCE, LANGUAGE);
    public static final Set<String> CONFIDENCES = Set.of("high", "medium", "low");
    public static final Set<String> LANGUAGES = Set.of("zh", "en");

    /** 从一个字段的参数里取出（不检查取值；检查用 {@link #problem()}）。 */
    public static FieldIntent from(Map<String, Object> field) {
        Map<String, Object> arguments = field == null ? Map.of() : field;
        Object label = arguments.get("name") instanceof String name ? name : arguments.get("element");
        return new FieldIntent(
                arguments.get("target") instanceof String target ? target : null,
                label instanceof String text ? text : null,
                arguments.get(FACT) instanceof String fact ? fact.strip() : null,
                arguments.get(CONFIDENCE) instanceof String confidence ? confidence.strip() : null,
                arguments.get(LANGUAGE) instanceof String language ? language.strip() : null);
    }

    /** 去掉 {@code fact}、{@code confidence}、{@code language} 之后的字段参数，发给 MCP。 */
    public static Map<String, Object> strip(Map<String, Object> field) {
        Map<String, Object> rest = new LinkedHashMap<>(field == null ? Map.of() : field);
        rest.keySet().removeAll(KEYS);
        return rest;
    }

    /** 取值不对时的说明（交给模型）；没有问题时返回 null。{@code fact}、{@code confidence} 都可以不写。 */
    public String problem() {
        if (language != null && !LANGUAGES.contains(language)) {
            return "字段的 language 只能是 zh 或 en";
        }
        return null;
    }

    /** 没有写来源，或者写了 {@code none}（模型推断的值）。 */
    public boolean inferred() {
        return fact == null || fact.isEmpty() || NONE.equals(fact);
    }
}

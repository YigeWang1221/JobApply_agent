package jobapply.harness.runtime;

import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 模型给的工具参数（申请 Agent 和对话 Agent 共用）：JSON 对象和其中的文字。 */
final class ToolArgs {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private ToolArgs() {
    }

    /** 参数的 JSON 对象；空的时为空 Map。不是 JSON 对象时抛出 {@link IllegalArgumentException}（原因交回模型）。 */
    static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JSON.readValue(json, MAP);
            return parsed == null ? Map.of() : parsed;
        } catch (JacksonException e) {
            throw new IllegalArgumentException("参数不是 JSON 对象");
        }
    }

    /** 不是空白的字符串（去掉首尾空白）；否则为 null。 */
    static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }
}

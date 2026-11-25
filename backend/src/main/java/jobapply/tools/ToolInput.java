package jobapply.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jobapply.domain.AgentRun;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 模型的一次工具调用和它的参数。参数只在用到时解析一次；不是 JSON 对象时抛 {@link BadArguments}，原因交回模型。
 */
public final class ToolInput {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    /** 参数不是 JSON 对象。 */
    public static final class BadArguments extends IllegalArgumentException {

        BadArguments() {
            super("参数不是 JSON 对象");
        }
    }

    private final AgentRun.ToolCall call;
    private Map<String, Object> args;

    public ToolInput(AgentRun.ToolCall call) {
        this.call = call;
    }

    public AgentRun.ToolCall call() {
        return call;
    }

    public String name() {
        return call.name();
    }

    /** 原样的参数 JSON（浏览器工具自己翻译）。 */
    public String json() {
        return call.arguments();
    }

    /** 参数的 JSON 对象；空的时为空 Map。 */
    public Map<String, Object> args() {
        if (args == null) {
            args = parse(call.arguments());
        }
        return args;
    }

    /** 不是空白的字符串参数（去掉首尾空白）；否则为 null。 */
    public String text(String key) {
        return text(args().get(key));
    }

    /** 字符串列表参数（单个字符串也算一项）；去掉空白项。 */
    public List<String> strings(String key) {
        return strings(args().get(key));
    }

    /** 对象列表参数。 */
    public List<Map<?, ?>> maps(String key) {
        return maps(args().get(key));
    }

    public static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JSON.readValue(json, MAP);
            return parsed == null ? Map.of() : parsed;
        } catch (JacksonException e) {
            throw new BadArguments();
        }
    }

    public static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }

    public static List<String> strings(Object value) {
        if (value instanceof String single && !single.isBlank()) {
            return List.of(single.strip());
        }
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(item -> item instanceof String s && !s.isBlank())
                .map(item -> ((String) item).strip()).toList();
    }

    public static List<Map<?, ?>> maps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<?, ?>> maps = new ArrayList<>();
        list.forEach(item -> {
            if (item instanceof Map<?, ?> map) {
                maps.add(map);
            }
        });
        return maps;
    }
}

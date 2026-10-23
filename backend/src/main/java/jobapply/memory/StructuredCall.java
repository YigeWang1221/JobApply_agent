package jobapply.memory;

import java.util.List;
import jobapply.config.ModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 资料整理里的一次模型调用：要求按 JSON Schema 返回（{@link ModelFactory#withSchema}），解析成 JSON。
 * 返回的不是合法 JSON（或不是对象）时<b>重试一次</b>，再失败就抛出 {@link StepFailed}，不猜。模型调用本身失败时不在这里重试
 * （SDK 已经重试过一次）。日志和错误信息只有步骤名和异常类型，不带请求或返回的内容。
 */
public final class StructuredCall {

    /** Anthropic 格式的 {@code max_tokens}（默认的 4096 不够放抽取结果）。 */
    static final int MAX_TOKENS = 16_000;

    private static final Logger log = LoggerFactory.getLogger(StructuredCall.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 一步失败了；{@code getMessage()} 给页面看，不含材料内容。 */
    public static final class StepFailed extends RuntimeException {

        StepFailed(String message) {
            super(message);
        }
    }

    /** 一次调用的结果和实际发出的请求数（解析失败重试时是 2）。 */
    record Result(JsonNode json, int requests) {
    }

    private StructuredCall() {
    }

    static Result call(ChatModel model, String step, String system, String user, String schema) {
        Prompt prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(user)),
                ModelFactory.withSchema(model, schema, MAX_TOKENS));
        for (int attempt = 1; ; attempt++) {
            String text;
            try {
                ChatResponse response = model.call(prompt);
                text = response.getResult() == null ? null : response.getResult().getOutput().getText();
            } catch (RuntimeException e) {
                log.warn("{}：模型调用失败（{}）", step, e.getClass().getSimpleName());
                throw new StepFailed(step + "：模型调用失败（" + e.getClass().getSimpleName() + summary(e) + "）");
            }
            JsonNode json = parse(text);
            if (json != null) {
                return new Result(json, attempt);
            }
            log.warn("{}：模型返回的 JSON 解析失败（第 {} 次）", step, attempt);
            if (attempt == 2) {
                throw new StepFailed(step + "：模型返回的 JSON 解析失败，已经重试一次");
            }
        }
    }

    /** 解析成 JSON 对象；去掉模型偶尔加上的 Markdown 代码块。不是对象时返回 null。 */
    static JsonNode parse(String text) {
        if (text == null) {
            return null;
        }
        String body = text.strip();
        if (body.startsWith("```")) {
            int firstLine = body.indexOf('\n');
            int fence = body.lastIndexOf("```");
            body = firstLine < 0 || fence <= firstLine ? "" : body.substring(firstLine + 1, fence).strip();
        }
        try {
            JsonNode json = JSON.readTree(body);
            return json != null && json.isObject() ? json : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    /** 提供方的错误说明只取第一行的前 160 个字（例如状态码和「额度不足」），不会是材料内容。 */
    private static String summary(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "";
        }
        String line = message.strip().lines().findFirst().orElse("");
        return "：" + (line.length() > 160 ? line.substring(0, 160) + "…" : line);
    }
}

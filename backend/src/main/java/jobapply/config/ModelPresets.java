package jobapply.config;

import java.util.List;
import java.util.Optional;

/**
 * ui.md「预设」表。选了预设就自动填好接口格式和 Base URL，用户只需要填 Key 和选模型。
 * Base URL 按各厂商的官方文档核对（2026-10-22，来源见 plan/coding/C004-A-model-guard.md「实际结果」）。
 * OpenAI 兼容格式的 Base URL 带版本路径（例如 {@code /v1}），SDK 在后面接 {@code chat/completions}；
 * Anthropic 格式的 Base URL 不带 {@code /v1}，SDK 自己接 {@code v1/messages}。
 */
public final class ModelPresets {

    /**
     * @param format      {@code openai} 或 {@code anthropic}；自定义时为 null（用户自己选）
     * @param baseUrl     默认 Base URL；自定义时为 null
     * @param keyRequired 本地模型不需要 Key
     * @param note        给用户看的提示，可以为 null
     */
    public record Preset(String id, String label, String format, String baseUrl, boolean keyRequired, String note) {
    }

    public static final String CUSTOM = "custom";

    public static final List<Preset> ALL = List.of(
            new Preset("openai", "OpenAI", "openai", "https://api.openai.com/v1", true, null),
            new Preset("anthropic", "Anthropic", "anthropic", "https://api.anthropic.com", true, null),
            new Preset("gemini", "Google Gemini", "openai",
                    "https://generativelanguage.googleapis.com/v1beta/openai/", true, "使用 Gemini 的 OpenAI 兼容端点"),
            new Preset("deepseek", "DeepSeek", "openai", "https://api.deepseek.com", true, null),
            new Preset("dashscope", "通义千问（DashScope）", "openai",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1", true,
                    "北京地域；新加坡地域改成 https://dashscope-intl.aliyuncs.com/compatible-mode/v1，Key 不能跨地域使用"),
            new Preset("moonshot", "Moonshot / Kimi", "openai", "https://api.moonshot.cn/v1", true, null),
            new Preset("zhipu", "智谱 GLM", "openai", "https://open.bigmodel.cn/api/paas/v4/", true, null),
            new Preset("openrouter", "OpenRouter", "openai", "https://openrouter.ai/api/v1", true, "一个 Key 接很多模型"),
            new Preset("ollama", "Ollama / LM Studio（本地）", "openai", "http://localhost:11434/v1", false,
                    "不需要 Key，数据不出本机；LM Studio 的默认地址是 http://localhost:1234/v1"),
            new Preset(CUSTOM, "自定义", null, null, true, "公司内网网关、自建代理等；两种接口格式任选"));

    private ModelPresets() {
    }

    public static Optional<Preset> find(String id) {
        return ALL.stream().filter(preset -> preset.id().equals(id)).findFirst();
    }
}

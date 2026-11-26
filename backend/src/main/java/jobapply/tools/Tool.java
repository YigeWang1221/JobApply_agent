package jobapply.tools;

import java.util.function.Function;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 交给模型的一个工具：定义（名字、说明、参数）和执行在一起（C030 Slice 2）。工具在一次运行开始时由
 * {@code ToolRegistry} 按 Mode 建出来，建的时候拿到这次运行的上下文（{@link RunContext}），执行时只看模型给的参数。
 * 结果（做成 / 失败 / 被拒绝、调用之后的页面、要不要结束这次运行）由工具自己定，不靠结果的开头去猜。
 */
public interface Tool {

    ToolDefinition definition();

    /** 执行一次调用。参数不是 JSON 对象时 {@link ToolInput#args()} 抛 {@link ToolInput.BadArguments}，由调用方交回模型。 */
    ToolOutcome run(ToolInput input);

    /** 是否放进发给模型的工具清单；false 时只接受旧对话里的调用（例如 C019 之前的 {@code update_plan}）。 */
    default boolean advertised() {
        return true;
    }

    default String name() {
        return definition().name();
    }

    static Tool of(ToolDefinition definition, Function<ToolInput, ToolOutcome> run) {
        return new Simple(definition, run, true);
    }

    /** 不放进工具清单、只接受旧调用的工具。 */
    static Tool hidden(ToolDefinition definition, Function<ToolInput, ToolOutcome> run) {
        return new Simple(definition, run, false);
    }

    static ToolDefinition definition(String name, String description, String schema) {
        return ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
    }

    record Simple(ToolDefinition definition, Function<ToolInput, ToolOutcome> runner, boolean advertised)
            implements Tool {

        @Override
        public ToolOutcome run(ToolInput input) {
            return runner.apply(input);
        }
    }
}

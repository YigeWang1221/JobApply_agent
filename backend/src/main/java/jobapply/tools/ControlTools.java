package jobapply.tools;

import java.util.List;
import java.util.Set;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 交给模型的控制工具（agent-runtime.md 7.2）。和 {@link BrowserTools} 一样只有定义，{@code call()} 一律抛异常，
 * 由 Agent 循环在本地处理。
 * <ul>
 *   <li>{@link #ASK_USER}：要用户的地方都用它（登录、验证码、不知道的必填项、选上传文件、C 级字段、拿不准、卡住）；
 *       结束这一次运行，用户的回复作为下一条消息接上</li>
 *   <li>{@link #UPDATE_PLAN}：模型自己的计划和进度</li>
 *   <li>{@link #READ_SKILL}：读一个 skill 的正文</li>
 *   <li>{@link #FINISH}：能填的都填了，停在最终提交前</li>
 * </ul>
 */
public final class ControlTools {

    public static final String ASK_USER = "ask_user";
    public static final String UPDATE_PLAN = "update_plan";
    public static final String READ_SKILL = "read_skill";
    public static final String FINISH = "finish";

    private static final Set<String> NAMES = Set.of(ASK_USER, UPDATE_PLAN, READ_SKILL, FINISH);

    private static final ToolDefinition ASK = definition(ASK_USER,
            "Hand control to the user and wait for their reply. Use it when only the user can act or decide: log in, "
                    + "solve a captcha / verification, answer required questions the profile does not cover, pick files "
                    + "for upload slots, fill class C fields (ID, visa, salary, demographics, declarations) themselves, "
                    + "decide whether an ambiguous button submits, or help when you are stuck after a few tries. "
                    + "Collect everything you need from the user on this page into one call. message is shown to the "
                    + "user as is: say what to do and how to reply (e.g. \"请在 Chrome 里登录，登录好后回复「好了」\"). "
                    + "Ends this run; the reply comes back as the next user message, answers as ids ans-NNN, files as "
                    + "file numbers for browser_file_upload. Do not ask about things you can find in the profile or on "
                    + "the page.",
            """
            {"type":"object","properties":{\
            "message":{"type":"string","description":"What the user should do now, written to the user"},\
            "questions":{"type":"array","items":{"type":"object","properties":{\
            "text":{"type":"string","description":"The field's question, as written on the page"},\
            "options":{"type":"array","items":{"type":"string"},"description":"Options as written on the page"},\
            "required":{"type":"boolean"},\
            "page":{"type":"string","description":"Which page or section the field is on"}},\
            "required":["text"]}},\
            "files":{"type":"array","description":"Upload slots the user should pick files for","items":{\
            "type":"object","properties":{"label":{"type":"string"},\
            "accept":{"type":"string","description":"e.g. PDF, max 10MB"},\
            "required":{"type":"boolean"},"note":{"type":"string"}},"required":["label"]}}},\
            "required":["message"]}""");

    private static final ToolDefinition PLAN = definition(UPDATE_PLAN,
            "Write down your plan for this application and keep it current: the pages or sections of the form and "
                    + "their status, what is still missing. Replaces the previous plan. Use it at the start and when "
                    + "you move to a new page; skip it for single field changes.",
            """
            {"type":"object","properties":{"items":{"type":"array","minItems":1,"items":{"type":"object",\
            "properties":{"text":{"type":"string"},\
            "status":{"type":"string","enum":["todo","doing","done","blocked"]}},\
            "required":["text","status"]}}},"required":["items"]}""");

    private static final ToolDefinition SKILL = definition(READ_SKILL,
            "Read the full text of a skill from the catalog (experience with a kind of control or a job site). Read "
                    + "it before working on a control or site the skill covers.",
            """
            {"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""");

    private static final ToolDefinition DONE = definition(FINISH,
            "Everything you can fill is filled, saved where the site has a save step, and read back. The form now "
                    + "stands just before the final submit. Ends the run; the user reviews the report and the page and "
                    + "submits it themselves. summary is shown to the user. Set page_shows_submitted from the latest "
                    + "snapshot: true only if the page says the application was already submitted (e.g. a Next button "
                    + "turned out to submit it).",
            """
            {"type":"object","properties":{\
            "summary":{"type":"string","description":"For the user: what was filled, what to check before submitting"},\
            "unresolved":{"type":"array","items":{"type":"string"},\
            "description":"Open points with the reason, one per item"},\
            "page_shows_submitted":{"type":"boolean",\
            "description":"The latest page says the application has already been submitted"}},\
            "required":["summary","page_shows_submitted"]}""");

    private ControlTools() {
    }

    public static List<ToolCallback> definitions() {
        return List.of(callback(ASK), callback(PLAN), callback(SKILL), callback(DONE));
    }

    public static boolean isControlTool(String name) {
        return NAMES.contains(name);
    }

    private static ToolDefinition definition(String name, String description, String schema) {
        return ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
    }

    private static ToolCallback callback(ToolDefinition definition) {
        return new DefinitionOnly(definition);
    }

    /** 只有定义的工具：由 Agent 循环在本地处理，{@code call()} 被用到说明调用方式错了。 */
    private record DefinitionOnly(ToolDefinition definition) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException("控制工具 " + definition.name() + " 由 Agent 循环处理，不应被 SDK 调用");
        }
    }
}

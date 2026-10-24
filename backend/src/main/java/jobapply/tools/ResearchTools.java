package jobapply.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import jobapply.harness.policies.BrowserToolPolicy;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 交给对话 Agent（调研、管理候选池、开始申请）的工具（C005）。和 {@link ControlTools} 一样只有定义，
 * 由 {@link jobapply.harness.runtime.SessionLoop} 在本地执行。浏览器只给读和翻页用的那几个（快照、查找、导航、点击、
 * 输入、按键、等待），不给填表和上传。
 */
public final class ResearchTools {

    public static final String WEB_SEARCH = "web_search";
    public static final String WEB_FETCH = "web_fetch";
    public static final String LIST_JOBS = "list_jobs";
    public static final String ADD_LEAD = "add_lead";
    public static final String UPDATE_LEAD = "update_lead";
    public static final String START_APPLICATION = "start_application";
    public static final String MARK_SUBMITTED = "mark_submitted";
    public static final String RECORD_STATUS = "record_status";
    public static final String ASK_USER = ControlTools.ASK_USER;
    public static final String READ_SKILL = ControlTools.READ_SKILL;

    /** 调研时可以用的浏览器工具（导航另有说明）。 */
    static final Set<String> BROWSER = Set.of(BrowserToolPolicy.SNAPSHOT, BrowserToolPolicy.FIND,
            BrowserToolPolicy.CLICK, BrowserToolPolicy.TYPE, BrowserToolPolicy.PRESS_KEY, BrowserToolPolicy.WAIT_FOR);

    private static final List<ToolDefinition> OWN = List.of(
            definition(WEB_SEARCH,
                    "Search the web. Returns up to 5 results with title, url and description. provider picks the "
                            + "source: duckduckgo (free), searxng (local, if configured), brave (if a key is set), or auto "
                            + "(default: free first, falling back on rate limits / no results). Use several focused "
                            + "queries (company + role + city + 校招 / campus / careers) rather than one broad query. "
                            + "This is one way to search: you can also web_fetch or open in the browser any search "
                            + "engine or job site directly. Results are leads to read, not facts.",
                    """
                    {"type":"object","properties":{"query":{"type":"string"},\
                    "provider":{"type":"string","enum":["auto","duckduckgo","searxng","brave"]}},\
                    "required":["query"]}"""),
            definition(WEB_FETCH,
                    "Read a page as text: a job description, a careers list, or a public ATS API such as "
                            + "boards-api.greenhouse.io/v1/boards/<company>/jobs, api.lever.co/v0/postings/<company>, "
                            + "api.ashbyhq.com/posting-api/job-board/<company>. Pages that render in the browser are read "
                            + "in the user's Chrome automatically. Long pages are cut to the beginning and end.",
                    """
                    {"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}"""),
            definition(BrowserToolPolicy.NAVIGATE,
                    "Open a public URL in your own research tab in the user's Chrome (the tab is created on first use; "
                            + "other tabs are not touched). Use it for official careers sites where you need to search, "
                            + "page through lists and open job details. Read only: never fill application forms.",
                    """
                    {"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}"""),
            definition(LIST_JOBS,
                    "List the jobs of this project: leads in the pool and applications, with id, company, title, city, "
                            + "status, verification and links; applied jobs also show when they were submitted, last "
                            + "checked and next check (marked 该查看了 when due). Call it before adding leads to avoid "
                            + "duplicates, and when the user refers to jobs.",
                    """
                    {"type":"object","properties":{"status":{"type":"string",\
                    "enum":["lead","new","shortlisted","skipped","closed","applying","applied","check_due","all"],\
                    "description":"Filter; default all"}}}"""),
            definition(ADD_LEAD,
                    "Put a job into the lead pool as new and not verified, after reading its own detail page (not "
                            + "only a search snippet or list page). url must be a link you saw. note: why it fits, the "
                            + "key requirements (degree, graduation year, direction, city, deadline) and whether it is "
                            + "still hiring. Don't add postings the page says are closed. "
                            + "Calling it again for the same company + link adds nothing and returns the existing id. "
                            + "The page you read (web_fetch or browser, in any earlier message too) is kept as the JD "
                            + "snapshot; if you read the details at a different address than url, give it as source.",
                    """
                    {"type":"object","properties":{\
                    "company":{"type":"string"},"title":{"type":"string"},"url":{"type":"string"},\
                    "source":{"type":"string","description":"optional: the address of the page you read the details \
                    from, when it is not url"},\
                    "city":{"type":"array","items":{"type":"string"}},\
                    "type":{"type":"string","description":"e.g. 校招·全职, 实习, 社招"},\
                    "note":{"type":"string"}},"required":["company","title","url"]}"""),
            definition(UPDATE_LEAD,
                    "Update a lead: status shortlisted (user wants it) / skipped (needs note as reason) / closed (the "
                            + "posting is taken down or no longer hiring, per its page or the user) / new; record an "
                            + "official-site check with verified = verified | not_found and official_url; replace note.",
                    """
                    {"type":"object","properties":{"job":{"type":"string","description":"Job id"},\
                    "status":{"type":"string","enum":["new","shortlisted","skipped","closed"]},\
                    "verified":{"type":"string","enum":["verified","not_found"]},\
                    "official_url":{"type":"string"},"note":{"type":"string"}},"required":["job"]}"""),
            definition(START_APPLICATION,
                    "Turn a lead into an application and hand it to the application agent, which opens the job page in "
                            + "Chrome and fills the form up to (not including) the final submit. Only for jobs the user "
                            + "asked to apply to. One application runs at a time. If the job already has an application thread it "
                            + "is not restarted; the result says so (with its status).",
                    """
                    {"type":"object","properties":{"job":{"type":"string","description":"Job id"}},\
                    "required":["job"]}"""),
            definition(MARK_SUBMITTED,
                    "Record that the user has submitted this application themselves in Chrome (they said so in the "
                            + "chat). The user's word is the evidence; the job moves to applied and the next status check "
                            + "is scheduled. note: the user's words. Only when the user clearly said it was submitted and "
                            + "it is clear which job; otherwise ask first. Works whatever the application was doing (a "
                            + "running application agent is stopped first); a job already applied returns the recorded "
                            + "time and records nothing new.",
                    """
                    {"type":"object","properties":{"job":{"type":"string","description":"Job id"},\
                    "note":{"type":"string","description":"What the user said"}},"required":["job"]}"""),
            definition(RECORD_STATUS,
                    "Record what the user found when checking an applied job (email / careers site). No phase and no "
                            + "outcome: checked, nothing new. phase: screening / assessment / interview / offer when it "
                            + "moved on. outcome: rejected / withdrawn / ghosted / accepted / declined / posting_closed (the posting "
                            + "was taken down) when it ended. A job still being prepared (not submitted yet) can only end: "
                            + "outcome posting_closed, or not_submitted when the user no longer wants to apply. "
                            + "Repeating the same call records nothing new. "
                            + "note: the user's words. next_check_in_days: when to remind again (default from "
                            + "preferences, usually 4); with a deadline or interview date, set it to the day before.",
                    """
                    {"type":"object","properties":{"job":{"type":"string","description":"Job id"},\
                    "phase":{"type":"string","enum":["screening","assessment","interview","offer"]},\
                    "outcome":{"type":"string","enum":["rejected","withdrawn","ghosted","accepted","declined","posting_closed","not_submitted"]},\
                    "note":{"type":"string"},"next_check_in_days":{"type":"integer","minimum":1,"maximum":90}},\
                    "required":["job"]}"""),
            definition(ASK_USER,
                    "Hand control to the user and end this turn: e.g. ask them to log in to a careers site in Chrome, "
                            + "to paste a link you cannot reach, or to choose which jobs to apply to. message is shown "
                            + "to the user as is; say what to do and how to reply.",
                    """
                    {"type":"object","properties":{"message":{"type":"string"}},"required":["message"]}"""),
            definition(READ_SKILL,
                    "Read the full text of a skill from the catalog (how to come up with search queries, how to verify "
                            + "jobs on official campus sites).",
                    """
                    {"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""));

    private static final Set<String> NAMES = Set.of(WEB_SEARCH, WEB_FETCH, LIST_JOBS, ADD_LEAD, UPDATE_LEAD,
            START_APPLICATION, MARK_SUBMITTED, RECORD_STATUS, ASK_USER, READ_SKILL);

    private ResearchTools() {
    }

    /** 对话 Agent 的全部工具：自己的工具 + 浏览器里读和翻页的那几个。 */
    public static List<ToolCallback> definitions() {
        List<ToolCallback> tools = new ArrayList<>();
        OWN.forEach(definition -> tools.add(new DefinitionOnly(definition)));
        BrowserTools.definitions().stream()
                .filter(tool -> BROWSER.contains(tool.getToolDefinition().name()))
                .forEach(tools::add);
        return List.copyOf(tools);
    }

    /** 由对话 Agent 自己处理的工具（不含浏览器工具和导航）。 */
    public static boolean isOwnTool(String name) {
        return NAMES.contains(name);
    }

    /** 对话 Agent 能用的浏览器工具（含导航）。 */
    public static boolean isBrowserTool(String name) {
        return BROWSER.contains(name) || BrowserToolPolicy.NAVIGATE.equals(name);
    }

    private static ToolDefinition definition(String name, String description, String schema) {
        return ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
    }

    /** 只有定义的工具：由循环在本地处理。 */
    private record DefinitionOnly(ToolDefinition definition) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException("工具 " + definition.name() + " 由对话 Agent 的循环处理，不应被 SDK 调用");
        }
    }
}

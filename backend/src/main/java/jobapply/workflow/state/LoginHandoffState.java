package jobapply.workflow.state;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bsc.langgraph4j.state.AgentState;

/**
 * 登录接力线程（thread id = job id）的状态。只放简单的值；快照全文不放进来（可能有个人信息）。
 * 空值一律写成空字符串，因为 LangGraph4j 的状态 Map 不接受 null。
 */
public class LoginHandoffState extends AgentState {

    public static final String PROJECT = "project";
    public static final String SESSION = "session";
    public static final String JOB = "job";
    public static final String URL = "url";
    public static final String ACCOUNT_HINT = "account_hint";
    /** 当前停在哪里的说明，给用户看。 */
    public static final String REASON = "reason";
    /** {@code LOGIN | CHALLENGE | NONE}。 */
    public static final String DETECTED = "detected";
    public static final String PAGE_URL = "page_url";
    public static final String PAGE_TITLE = "page_title";
    /** {@code matched | unverified | user_confirmed}。 */
    public static final String IDENTITY = "identity";
    public static final String LAST_ERROR = "last_error";
    /** 用户最近一次发的信号。 */
    public static final String SIGNAL = "signal";
    public static final String OVERRIDE = "override";
    /** 节点给出的下一步，条件边按它路由。 */
    public static final String NEXT = "next";

    public LoginHandoffState(Map<String, Object> data) {
        super(data);
    }

    /** 一次新的运行：所有键都给初值，同一线程上次运行留下的值不会混进来。 */
    public static Map<String, Object> initial(String project, String session, String job, String url,
            String accountHint) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(PROJECT, project);
        values.put(SESSION, session);
        values.put(JOB, job);
        values.put(URL, url);
        values.put(ACCOUNT_HINT, accountHint == null ? "" : accountHint);
        for (String key : new String[] {REASON, DETECTED, PAGE_URL, PAGE_TITLE, IDENTITY, LAST_ERROR, SIGNAL, NEXT}) {
            values.put(key, "");
        }
        values.put(OVERRIDE, false);
        return values;
    }

    public String text(String key) {
        return this.<Object>value(key).map(Object::toString).orElse("");
    }

    public boolean flag(String key) {
        return this.<Object>value(key).map(value -> Boolean.TRUE.equals(value)).orElse(false);
    }
}

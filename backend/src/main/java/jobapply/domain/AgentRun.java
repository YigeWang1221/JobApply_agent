package jobapply.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次申请的运行状态（agent-runtime.md 7.1）：和模型的对话、申请状态（字段清单和计划，C019）、现在给用户的话，以及用量。
 * 每轮之后存一次；用户回复时读回，把回答追加成下一条消息，接着跑。对话里不存快照全文，只存工具结果的说明。
 *
 * @param status       {@link #RUNNING} / {@link #WAITING_USER} / {@link #READY} / {@link #FAILED}
 * @param message      给用户看的话（要用户做什么、做完怎么回复）；运行中为 null
 * @param questions    {@code ask_user} 带的问题（保留页面原文和选项）
 * @param fileSlots    {@code ask_user} 带的上传位置
 *                     （浏览器断开不再存在这里，由全局的连接状态推出来，C006-fix-browser 第 1 节；旧文件里的
 *                     {@code disconnected} 键读的时候忽略）
 * @param state        申请状态（C019）：现在在哪一步、计划、字段清单、决定；旧记录里只有 {@code plan}，读的时候由
 *                     {@code AgentRunStore} 换成 {@code state.steps}
 * @param files        已经复制到 {@code uploads/<job-id>/} 的文件，编号从 1 开始
 * @param round        报告第几轮（定稿后用户要求修改时加一）
 * @param error        失败的原因；没有时为 null
 * @param lastPage     浏览器那边的状态（C006-fix-runtime-2）：最后的页面地址（接着做时按它认回原页）、用户批准过的一次点击、
 *                     结束时页面是否像已经投递；旧记录里没有，为 null
 */
public record AgentRun(
        String project,
        String job,
        String session,
        String company,
        String title,
        String url,
        String status,
        String message,
        List<Question> questions,
        List<FileSlot> fileSlots,
        ApplicationState state,
        List<StagedFile> files,
        int round,
        Usage usage,
        String error,
        List<Turn> conversation,
        LastPage lastPage) {

    public static final String RUNNING = "running";
    public static final String WAITING_USER = "waiting_user";
    public static final String READY = "ready";
    public static final String FAILED = "failed";
    /**
     * 只出现在对外的状态里，不写进文件（C006-fix-2）：排着队等浏览器（文件里是 {@link #RUNNING}），
     * 和文件写着 {@link #RUNNING}、这个进程里却没有在跑（应用重启前被中断，{@link LiveRuns}）。
     */
    public static final String QUEUED = "queued";
    public static final String INTERRUPTED = "interrupted";
    /** 也只在对外的状态里：用户已经定下了（已提交、结束了），这个申请线程不再接着做（C006-fix-2 回归第 1 条）。 */
    public static final String DONE = "done";

    public AgentRun {
        questions = questions == null ? List.of() : List.copyOf(questions);
        fileSlots = fileSlots == null ? List.of() : List.copyOf(fileSlots);
        state = state == null ? ApplicationState.empty() : state;
        files = files == null ? List.of() : List.copyOf(files);
        conversation = conversation == null ? List.of() : List.copyOf(conversation);
        usage = usage == null ? new Usage(0, 0, 0) : usage;
        round = Math.max(1, round);
    }

    /** 新开始的一次申请：对话里只有一条开始的说明。 */
    public static AgentRun start(String project, String job, String session, String company, String title,
                                 String url, String opening) {
        return new AgentRun(project, job, session, company, title, url, RUNNING, null, null, null, null,
                null, 1, null, null, List.of(Turn.user(opening)), null);
    }

    /**
     * 开始跑：清掉给用户的话、问题和上传位置，对话追加 {@code reply}（为 null 时不追加）。「页面像已投递」是上一次
     * 结束时的判断，也清掉；页面地址和批准留着。
     */
    public AgentRun resumed(Turn reply, List<StagedFile> files, int round) {
        List<Turn> turns = new ArrayList<>(conversation);
        if (reply != null) {
            turns.add(reply);
        }
        return new AgentRun(project, job, session, company, title, url, RUNNING, null, null, null,
                state, files, round, usage, null, turns, lastPage == null ? null : lastPage.submitted(false));
    }

    public AgentRun withProgress(ApplicationState state, Usage usage, List<Turn> conversation) {
        return new AgentRun(project, job, session, company, title, url, status, message, questions, fileSlots,
                state, files, round, usage, error, conversation, lastPage);
    }

    public AgentRun withState(ApplicationState state) {
        return new AgentRun(project, job, session, company, title, url, status, message, questions, fileSlots,
                state, files, round, usage, error, conversation, lastPage);
    }

    public AgentRun waiting(String message, List<Question> questions, List<FileSlot> fileSlots) {
        return new AgentRun(project, job, session, company, title, url, WAITING_USER, message, questions, fileSlots,
                state, files, round, usage, null, conversation, lastPage);
    }

    public AgentRun ready(String message) {
        return new AgentRun(project, job, session, company, title, url, READY, message, null, null, state,
                files, round, usage, null, conversation, lastPage);
    }

    /** 停在提交前；{@code pageShowsSubmitted}：模型看最新页面判断已经像投递成功了。 */
    public AgentRun ready(String message, boolean pageShowsSubmitted) {
        LastPage page = lastPage == null ? new LastPage(null, null, false) : lastPage;
        return withLastPage(page.submitted(pageShowsSubmitted)).ready(message);
    }

    public AgentRun failed(String error) {
        return new AgentRun(project, job, session, company, title, url, FAILED, null, null, null, state,
                files, round, usage, error, conversation, lastPage);
    }

    /** 记下浏览器那边的状态（每轮存盘前由申请循环更新）。 */
    public AgentRun withLastPage(LastPage lastPage) {
        return new AgentRun(project, job, session, company, title, url, status, message, questions, fileSlots,
                state, files, round, usage, error, conversation, lastPage);
    }

    public AgentRun withFiles(List<StagedFile> files) {
        return new AgentRun(project, job, session, company, title, url, status, message, questions, fileSlots,
                state, files, round, usage, error, conversation, lastPage);
    }

    /** 结束时页面像已经投递成功了（模型在 {@code finish} 里判断）。 */
    public boolean pageShowsSubmitted() {
        return lastPage != null && lastPage.showsSubmitted();
    }

    /**
     * 浏览器那边的状态。
     *
     * @param url            最后一次看到的页面地址；接着做时只用来告诉模型上次做到哪（不按它认回标签页，C006-fix-browser 第 2 节）
     * @param approved       用户批准过的一次点击（这一页、这一个按钮）；用过或者翻到别的网址就清掉。没有时为 null
     * @param showsSubmitted 模型 {@code finish} 时判断页面已经像投递成功了
     */
    public record LastPage(String url, ApprovedClick approved, boolean showsSubmitted) {

        LastPage submitted(boolean shows) {
            return new LastPage(url, approved, shows);
        }
    }

    /** 用户批准过的一次点击：页面地址、快照里的 ref、按钮名字。 */
    public record ApprovedClick(String url, String ref, String button) {
    }

    /**
     * 对话里的一条。
     *
     * @param role    {@code user}（用户的回复或系统的说明）、{@code note}（只给模型看的系统说明，界面上不显示）、
     *                {@code page}（系统自己取了页面）、{@code assistant}、{@code tool}
     * @param text    文字；{@code tool} 时为 null
     * @param calls   {@code assistant} 的工具调用
     * @param results  {@code tool} 的结果（不含快照全文）
     * @param thinking {@code assistant} 时供应商真的给出的思考原文（C006-agent）；给界面看，OpenAI 兼容格式下同一次运行里
     *                 还作为 {@code reasoning_content} 发回给模型（{@link jobapply.harness.context.Conversation}）。没有时为 null
     * @param attachments {@code user} 时用户附的文件（C006 附件）；旧记录里没有，为空
     */
    public record Turn(String role, String text, List<ToolCall> calls, List<ToolResult> results, String thinking,
                       List<Attachment> attachments) {

        public static final String USER = "user";
        public static final String NOTE = "note";
        public static final String PAGE = "page";
        public static final String ASSISTANT = "assistant";
        public static final String TOOL = "tool";

        public Turn {
            calls = calls == null ? List.of() : List.copyOf(calls);
            results = results == null ? List.of() : List.copyOf(results);
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        public Turn(String role, String text, List<ToolCall> calls, List<ToolResult> results, String thinking) {
            this(role, text, calls, results, thinking, null);
        }

        public Turn(String role, String text, List<ToolCall> calls, List<ToolResult> results) {
            this(role, text, calls, results, null, null);
        }

        public static Turn user(String text) {
            return new Turn(USER, text, null, null);
        }

        public static Turn user(String text, List<Attachment> attachments) {
            return new Turn(USER, text, null, null, null, attachments);
        }

        public static Turn note(String text) {
            return new Turn(NOTE, text, null, null);
        }

        public static Turn page(String text) {
            return new Turn(PAGE, text, null, null);
        }

        public static Turn assistant(String text, List<ToolCall> calls) {
            return new Turn(ASSISTANT, text, calls, null);
        }

        public static Turn assistant(String text, List<ToolCall> calls, String thinking) {
            return new Turn(ASSISTANT, text, calls, null, thinking == null || thinking.isBlank() ? null : thinking);
        }

        public static Turn tool(List<ToolResult> results) {
            return new Turn(TOOL, null, null, results);
        }
    }

    public record ToolCall(String id, String name, String arguments) {
    }

    /**
     * 一次工具调用的结果（C006-agent 加了状态和用时，旧记录里没有，为 null）。
     *
     * @param status    {@link #DONE} / {@link #FAILED} / {@link #REJECTED}（被守卫拒绝或没有执行）
     * @param startedAt 开始执行的时间（ISO 字符串）
     * @param ms        执行用了多少毫秒
     */
    public record ToolResult(String id, String name, String text, String status, String startedAt, Long ms) {

        public static final String DONE = "done";
        public static final String FAILED = "failed";
        public static final String REJECTED = "rejected";

        public ToolResult(String id, String name, String text) {
            this(id, name, text, null, null, null);
        }

        /** 产生结果的地方就定下状态（C006-fix）：{@link #DONE} / {@link #FAILED} / {@link #REJECTED}。 */
        public ToolResult(String id, String name, String text, String status) {
            this(id, name, text, status, null, null);
        }

        /** 执行完了：记下开始时间和用时。状态是产生结果时定下的；没定的才按结果的开头猜（{@link #statusOf}）。 */
        public ToolResult timed(String startedAt, long ms) {
            return new ToolResult(id, name, text, status != null ? status : statusOf(text), startedAt, ms);
        }

        /** 按结果的开头猜状态：只给没有记状态的旧记录、没有定状态的结果兜底，不作为新结果的依据。 */
        public static String statusOf(String text) {
            String head = text == null ? "" : text.strip();
            if (head.startsWith("被拒绝") || head.startsWith("没有点") || head.startsWith("没有执行")) {
                return REJECTED;
            }
            return head.startsWith("失败") || head.startsWith("没有开始") ? FAILED : DONE;
        }
    }

    /** 问用户的一个问题。{@code fieldClass} 为 {@code C} 时只能由用户在浏览器里亲自填。 */
    public record Question(String id, String text, List<String> options, boolean required, String fieldClass,
                           String page) {

        public Question {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /** 页面上的一个上传位置。 */
    public record FileSlot(String id, String label, String accept, boolean required, String note) {
    }

    /**
     * 复制到上传区的文件：模型只看到编号和 {@code uploadName}。{@code slot} 是用户为哪个上传位置选的。
     *
     * @param attachment 用户在回复里附的文件（C006 附件）时是附件编号，{@code assetKey} 为 null；资料库的原件时为 null
     */
    public record StagedFile(String assetKey, String uploadName, String slot, long size, String sha256,
                             String attachment) {

        public StagedFile(String assetKey, String uploadName, String slot, long size, String sha256) {
            this(assetKey, uploadName, slot, size, sha256, null);
        }
    }

    /**
     * 用户附在一条消息里的文件（C006 附件）。
     *
     * @param file 申请 Agent 里 {@code browser_file_upload} 用的文件编号（同 {@link AgentRun#files()}）；Session 里为 null
     */
    public record Attachment(String id, String name, String contentType, Integer file) {
    }

    /** 累计用量。 */
    public record Usage(long modelCalls, long toolCalls, long tokens) {
    }
}

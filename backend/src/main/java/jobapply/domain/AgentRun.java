package jobapply.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次申请的运行状态（agent-runtime.md 7.1）：和模型的对话、模型的计划、现在给用户的话，以及用量。
 * 每轮之后存一次；用户回复时读回，把回答追加成下一条消息，接着跑。对话里不存快照全文，只存工具结果的说明。
 *
 * @param status       {@link #RUNNING} / {@link #WAITING_USER} / {@link #READY} / {@link #FAILED}
 * @param message      给用户看的话（要用户做什么、做完怎么回复）；运行中为 null
 * @param questions    {@code ask_user} 带的问题（保留页面原文和选项）
 * @param fileSlots    {@code ask_user} 带的上传位置
 * @param disconnected 浏览器断开了：用户回复时先重连
 * @param files        已经复制到 {@code uploads/<job-id>/} 的文件，编号从 1 开始
 * @param round        报告第几轮（定稿后用户要求修改时加一）
 * @param error        失败的原因；没有时为 null
 * @param askedButton  模型最近一次请用户确认的软边界按钮（「下一步」这类）；用户回复后，模型再点它时放行
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
        boolean disconnected,
        List<PlanItem> plan,
        List<StagedFile> files,
        int round,
        Usage usage,
        String error,
        List<Turn> conversation,
        String askedButton) {

    public static final String RUNNING = "running";
    public static final String WAITING_USER = "waiting_user";
    public static final String READY = "ready";
    public static final String FAILED = "failed";

    public AgentRun {
        questions = questions == null ? List.of() : List.copyOf(questions);
        fileSlots = fileSlots == null ? List.of() : List.copyOf(fileSlots);
        plan = plan == null ? List.of() : List.copyOf(plan);
        files = files == null ? List.of() : List.copyOf(files);
        conversation = conversation == null ? List.of() : List.copyOf(conversation);
        usage = usage == null ? new Usage(0, 0, 0) : usage;
        round = Math.max(1, round);
    }

    /** 新开始的一次申请：对话里只有一条开始的说明。 */
    public static AgentRun start(String project, String job, String session, String company, String title,
                                 String url, String opening) {
        return new AgentRun(project, job, session, company, title, url, RUNNING, null, null, null, false, null,
                null, 1, null, null, List.of(Turn.user(opening)), null);
    }

    /** 开始跑：清掉给用户的话、问题和上传位置，对话追加 {@code reply}（为 null 时不追加）。 */
    public AgentRun resumed(Turn reply, List<StagedFile> files, int round) {
        List<Turn> turns = new ArrayList<>(conversation);
        if (reply != null) {
            turns.add(reply);
        }
        return new AgentRun(project, job, session, company, title, url, RUNNING, null, null, null, disconnected,
                plan, files, round, usage, null, turns, askedButton);
    }

    /** 浏览器重新连上了。 */
    public AgentRun reconnected() {
        return new AgentRun(project, job, session, company, title, url, status, message, questions, fileSlots, false,
                plan, files, round, usage, error, conversation, askedButton);
    }

    public AgentRun withProgress(List<PlanItem> plan, Usage usage, List<Turn> conversation) {
        return new AgentRun(project, job, session, company, title, url, status, message, questions, fileSlots,
                disconnected, plan, files, round, usage, error, conversation, askedButton);
    }

    public AgentRun waiting(String message, List<Question> questions, List<FileSlot> fileSlots, boolean disconnected) {
        return new AgentRun(project, job, session, company, title, url, WAITING_USER, message, questions, fileSlots,
                disconnected, plan, files, round, usage, null, conversation, askedButton);
    }

    public AgentRun ready(String message) {
        return new AgentRun(project, job, session, company, title, url, READY, message, null, null, false, plan,
                files, round, usage, null, conversation, askedButton);
    }

    public AgentRun failed(String error) {
        return new AgentRun(project, job, session, company, title, url, FAILED, null, null, null, disconnected, plan,
                files, round, usage, error, conversation, askedButton);
    }

    /** 记下模型请用户确认的软边界按钮（随 {@code ask_user} 一起交给用户）。 */
    public AgentRun withAskedButton(String button) {
        return new AgentRun(project, job, session, company, title, url, status, message, questions, fileSlots,
                disconnected, plan, files, round, usage, error, conversation, button);
    }

    public AgentRun withFiles(List<StagedFile> files) {
        return new AgentRun(project, job, session, company, title, url, status, message, questions, fileSlots,
                disconnected, plan, files, round, usage, error, conversation, askedButton);
    }

    /**
     * 对话里的一条。
     *
     * @param role    {@code user}（用户的回复或系统的说明）、{@code note}（只给模型看的系统说明，界面上不显示）、
     *                {@code page}（系统自己取了页面）、{@code assistant}、{@code tool}
     * @param text    文字；{@code tool} 时为 null
     * @param calls   {@code assistant} 的工具调用
     * @param results  {@code tool} 的结果（不含快照全文）
     * @param thinking {@code assistant} 时供应商真的给出的思考原文（C006-agent）；只给界面看，不发回给模型。没有时为 null
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

        /** 执行完了：记下开始时间和用时，状态按结果的开头判断（各个工具的结果统一以「失败：」「被拒绝：」开头）。 */
        public ToolResult timed(String startedAt, long ms) {
            return new ToolResult(id, name, text, statusOf(text), startedAt, ms);
        }

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

    /** 模型计划里的一项。{@code status}：{@code todo} / {@code doing} / {@code done} / {@code blocked}。 */
    public record PlanItem(String text, String status) {
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

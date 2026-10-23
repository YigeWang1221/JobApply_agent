package jobapply.harness.runtime;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import jobapply.agents.ApplyAgent;
import jobapply.config.HarnessProperties;
import jobapply.config.ModelFactory;
import jobapply.domain.AgentRun;
import jobapply.domain.Fact;
import jobapply.domain.FactClass;
import jobapply.domain.Language;
import jobapply.domain.Timestamps;
import jobapply.harness.context.Conversation;
import jobapply.harness.context.FillContext;
import jobapply.harness.context.SkillCatalog;
import jobapply.harness.policies.BrowserToolPolicy;
import jobapply.harness.policies.FieldClassGuard;
import jobapply.harness.policies.FieldIntent;
import jobapply.harness.policies.GuardDecision;
import jobapply.harness.policies.SnapshotRedactor;
import jobapply.harness.policies.SubmitGuard;
import jobapply.harness.policies.SubmitWords;
import jobapply.harness.policies.ToolNotAllowedException;
import jobapply.memory.ProfileResolver;
import jobapply.memory.ResolvedFact;
import jobapply.persistence.AttachmentStore;
import jobapply.persistence.FillReportWriter;
import jobapply.persistence.JobAnswers;
import jobapply.persistence.LedgerFile;
import jobapply.persistence.PlaybookSeeder;
import jobapply.persistence.RunLog;
import jobapply.persistence.SiteNotes;
import jobapply.persistence.StorageLayout;
import jobapply.tools.BrowserException;
import jobapply.tools.BrowserSession;
import jobapply.tools.BrowserTools;
import jobapply.tools.ControlTools;
import jobapply.tools.PageSnapshot;
import jobapply.tools.SnapshotIndex;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Agent 循环（agent-runtime.md 7.1）：拼消息 → 调主模型 → 依次执行工具调用 → 结果放回对话 → 下一轮。
 * 流程上的事（登录、下一步做什么、卡住了怎么办、要不要问用户）都由模型决定。
 * <p>
 * 一次运行在这些时候结束，结束时返回更新过的 {@link AgentRun}：
 * <ul>
 *   <li>模型调 {@code ask_user}，或者只回了文字：等用户回复；</li>
 *   <li>模型调 {@code finish}：停在最终提交前（{@link AgentRun#READY}）；</li>
 *   <li>浏览器断开：等用户回复后重连；</li>
 *   <li>用户要求停止；</li>
 *   <li>这一次运行的工具调用到了上限：让模型总结做到哪了，交给用户决定要不要继续。</li>
 * </ul>
 * 代码只拒绝提交类按钮（{@link SubmitGuard}；拿不准会不会提交的「下一步」类按钮要模型先问用户）、C 级字段（{@link FieldClassGuard}）和白名单以外的操作
 * （{@link BrowserToolPolicy}），拒绝的原因作为工具结果交回模型。重复操作、页面出现验证或弹窗都只在结果里提醒。
 * <p>
 * 填过什么记在报告草稿里（{@link FillReportWriter#writeDraft}）；过程日志只记工具、元素的角色和名字、页面标题和地址、
 * 守卫的判断和 token 数，不记值、快照和对话。
 */
public final class HarnessLoop {

    static final String STOPPED = "已按你的要求停下。回复后接着做。";

    private static final Set<String> TEXT_INPUTS = Set.of("textbox", "searchbox", "spinbutton");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ChatModel model;
    private final BrowserSession browser;
    private final StorageLayout layout;
    private final Clock clock;
    private final HarnessProperties limits;
    private final ProfileResolver resolver;
    private final JobAnswers answers;
    private final FillReportWriter reports;
    private final RunLog runLog;
    private final SiteNotes sites;
    private final LedgerFile ledger;
    private final PlaybookSeeder seeder;
    private volatile boolean seeded;

    public HarnessLoop(ChatModel model, BrowserSession browser, StorageLayout layout, Clock clock,
                       HarnessProperties limits) {
        this.model = model;
        this.browser = browser;
        this.layout = layout;
        this.clock = clock;
        this.limits = limits == null ? HarnessProperties.defaults() : limits;
        this.resolver = new ProfileResolver(layout, clock);
        this.answers = new JobAnswers(layout, clock);
        this.reports = new FillReportWriter(layout, clock);
        this.runLog = new RunLog(layout, clock);
        this.sites = new SiteNotes(layout, clock);
        this.ledger = new LedgerFile(layout, clock);
        this.seeder = new PlaybookSeeder(layout, clock);
    }

    /**
     * 跑到模型或系统结束这一次运行。开始时系统先确认标签页是这个岗位的（不是就打开岗位链接），再取一次快照。
     *
     * @param stop     用户要求停止时返回 true（每次调用模型和工具之前检查）
     * @param progress 每轮之后调用一次，交给调用方存盘
     * @throws RuntimeException 主模型出错、重试也不行时（调用方记为失败）
     */
    public AgentRun run(AgentRun run, BooleanSupplier stop, Consumer<AgentRun> progress) {
        return run(run, stop, progress, LoopObserver.NONE);
    }

    /** 同上；{@code observer} 收到「正在做什么」（调模型、执行工具）和每一步的结果。 */
    public AgentRun run(AgentRun run, BooleanSupplier stop, Consumer<AgentRun> progress, LoopObserver observer) {
        if (!seeded) {
            seeder.seed();
            seeded = true;
        }
        return new Round(run, stop, progress, observer == null ? LoopObserver.NONE : observer).go();
    }

    /** 一个 Job 的报告草稿：按「页面 + 字段」去重，后填的覆盖先填的。 */
    private static final class Draft {

        private String company;
        private String title;
        private int round = 1;
        private final Map<String, FillReportWriter.Field> fields = new LinkedHashMap<>();
        private final Map<String, FillReportWriter.Attachment> attachments = new LinkedHashMap<>();
        private final Set<String> unresolved = new LinkedHashSet<>();
        private final Set<FillReportWriter.GuardNote> guardNotes = new LinkedHashSet<>();

        void field(FillReportWriter.Field field) {
            String key = field.page() + "\u0000" + field.label();
            fields.remove(key);
            fields.put(key, field);
        }

        void attach(FillReportWriter.Attachment attachment) {
            attachments.put(attachment.fileName(), attachment);
        }

        /** 从读回的报告开始累积（字段行是 {@link FillReportWriter.Recorded}，原样保留）。 */
        void restore(FillReportWriter.Report report) {
            report.fields().forEach(this::field);
            report.attachments().forEach(this::attach);
            unresolved.addAll(report.unresolved());
            guardNotes.addAll(report.guardNotes());
        }

        FillReportWriter.Report report() {
            return new FillReportWriter.Report(company, title, round, List.copyOf(fields.values()),
                    List.copyOf(attachments.values()), List.copyOf(unresolved), List.copyOf(guardNotes));
        }
    }

    /**
     * 一次工具调用的结果。
     *
     * @param page 调用之后的页面（只有最新的一份会原样发给模型）；没有时为 null
     * @param end  结束这一次运行：拿到当前状态，返回结束时的状态；不结束时为 null
     */
    private record Step(AgentRun.ToolResult result, PageSnapshot page, UnaryOperator<AgentRun> end) {
    }

    /** 一次运行的内存状态。 */
    private final class Round {

        private final AgentRun run;
        private final BooleanSupplier stop;
        private final Consumer<AgentRun> progress;
        private final LoopObserver observer;
        private final LoopDetector detector;
        private final List<AgentRun.Turn> turns;
        private final Draft draft = new Draft();
        private List<AgentRun.PlanItem> plan;
        private long modelCalls;
        private long toolCalls;
        private long tokens;
        private int callsThisRun;
        private List<JobAnswers.Answer> sessionAnswers;
        private Map<String, ResolvedFact> profileZh;
        private Map<String, ResolvedFact> profileEn;
        private SkillCatalog catalog;
        private PageSnapshot latest;
        private Conversation.LatestPage latestPage;
        /** 这次运行里守卫要求先问用户的软边界按钮；随 {@code ask_user} 交给用户。 */
        private String pendingButton;

        Round(AgentRun run, BooleanSupplier stop, Consumer<AgentRun> progress, LoopObserver observer) {
            this.run = run;
            this.stop = stop == null ? () -> false : stop;
            this.progress = progress == null ? ignored -> {
            } : progress;
            this.observer = observer;
            this.detector = new LoopDetector(limits);
            this.turns = new ArrayList<>(run.conversation());
            this.plan = run.plan();
            this.modelCalls = run.usage().modelCalls();
            this.toolCalls = run.usage().toolCalls();
            this.tokens = run.usage().tokens();
            // 每次运行都从文件读回草稿：应用重启后前面的行还在
            reports.load(run.project(), run.job()).ifPresent(draft::restore);
            draft.company = run.company();
            draft.title = run.title();
            draft.round = run.round();
        }

        AgentRun go() {
            catalog = SkillCatalog.scan(layout);
            sessionAnswers = answers.list(run.project(), run.job());
            List<Fact> answerFacts = sessionAnswers.stream().map(JobAnswers.Answer::toFact).toList();
            profileZh = FillContext.sendable(resolver.resolve(run.project(), answerFacts, Language.ZH), ledger::find);
            profileEn = FillContext.sendable(resolver.resolve(run.project(), answerFacts, Language.EN), ledger::find);

            try {
                if (!browser.ownsTab(run.job())) {
                    latest = browser.open(run.url(), run.job());
                    turns.add(AgentRun.Turn.page("系统打开了岗位链接 " + run.url() + "。"));
                } else {
                    try {
                        latest = browser.snapshot(run.job());
                        turns.add(AgentRun.Turn.page("系统取了一次当前页面。"));
                    } catch (BrowserException.ToolFailed e) {
                        // 原来的标签页读不到了（被关掉、换了页面）：在新标签页重新打开岗位链接，不去问用户
                        latest = browser.open(run.url(), run.job());
                        turns.add(AgentRun.Turn.page("原来的标签页读不到了（" + e.getMessage() + "），系统在新标签页重新打开了岗位链接 "
                                + run.url() + "。"));
                    }
                }
            } catch (BrowserException.Disconnected e) {
                return disconnected(e.getMessage());
            } catch (BrowserException.ToolFailed e) {
                return waiting("读不到当前页面（" + e.getMessage() + "）。确认 Chrome 里这个页面开着，然后回复，我再试一次。");
            }
            latestPage = new Conversation.LatestPage(latest, turns.size() - 1, null);
            detector.startAt(pageText(latest));
            SubmitGuard.pageWarning(latest).ifPresent(warning -> turns.add(AgentRun.Turn.user("系统提醒：" + warning)));

            while (true) {
                if (stop.getAsBoolean()) {
                    return waiting(STOPPED);
                }
                if (callsThisRun >= limits.toolCallsPerRun()) {
                    return limitReached();
                }
                ChatResponse response = call();
                if (stop.getAsBoolean()) {
                    return waiting(STOPPED);
                }
                AssistantMessage message = ModelCaller.message(response);
                String thinking = ModelCaller.thinking(response);
                String text = message == null ? null : message.getText();
                if (message == null || !message.hasToolCalls()) {
                    // 只回了文字：当成对用户说的话，等用户回复（OpenMAIC 的自动 cue）
                    reply(AgentRun.Turn.assistant(text, List.of(), thinking));
                    return waiting(text == null || text.isBlank() ? "我没有想好下一步。请看一下页面，告诉我接下来做什么。"
                            : text.strip());
                }
                List<AgentRun.ToolCall> calls = message.getToolCalls().stream()
                        .map(call -> new AgentRun.ToolCall(call.id(), call.name(), call.arguments())).toList();
                reply(AgentRun.Turn.assistant(text, calls, thinking));
                int toolTurn = turns.size();
                List<AgentRun.ToolResult> results = new ArrayList<>();
                UnaryOperator<AgentRun> end = null;
                for (AgentRun.ToolCall call : calls) {
                    String startedAt = Timestamps.now(clock);
                    if (end != null || stop.getAsBoolean()) {
                        AgentRun.ToolResult skipped = new AgentRun.ToolResult(call.id(), call.name(),
                                "没有执行：这一次运行已经结束。").timed(startedAt, 0);
                        results.add(skipped);
                        observer.done(call, skipped);
                        continue;
                    }
                    callsThisRun++;
                    toolCalls++;
                    observer.tool(call);
                    long started = System.nanoTime();
                    Step step = handle(call);
                    AgentRun.ToolResult result = step.result().timed(startedAt, (System.nanoTime() - started) / 1_000_000);
                    results.add(result);
                    observer.done(call, result);
                    if (step.page() != null) {
                        latestPage = new Conversation.LatestPage(step.page(), toolTurn, call.id());
                    }
                    end = step.end();
                }
                turns.add(AgentRun.Turn.tool(results));
                progress.accept(current());
                if (end != null) {
                    return end.apply(current());
                }
            }
        }

        /** 调主模型（{@link ModelCaller}：暂时出错时等一会儿再试）。 */
        private ChatResponse call() {
            observer.thinking();
            ChatResponse response = ModelCaller.call(model,
                    new Prompt(FillContext.messages(inputs(), turns, latestPage,
                            new AttachmentStore(layout.jobAttachmentsDir(run.job()))), options()), stop);
            recordUsage(response);
            return response;
        }

        /** 模型回的一条：记下，再告诉观察的一方。 */
        private void reply(AgentRun.Turn turn) {
            turns.add(turn);
            observer.replied(turn);
        }

        private ChatOptions options() {
            return ModelFactory.withTools(model, ApplyAgent.tools());
        }

        private void recordUsage(ChatResponse response) {
            modelCalls++;
            long in = 0;
            long out = 0;
            Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
            if (usage != null) {
                in = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                out = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            }
            tokens += in + out;
            log(RunLog.Entry.modelCall(run.session(), step(), in, out));
        }

        private FillContext.Inputs inputs() {
            List<String> suggested = catalog.autoInclude(latest.url()).stream().map(SkillCatalog.Skill::name).toList();
            String host = host(latest);
            String notes = host == null ? null : sites.read(host).map(SiteNotes.Site::notes).orElse(null);
            return new FillContext.Inputs(profileZh, profileEn, sessionAnswers,
                    run.files().stream().map(AgentRun.StagedFile::uploadName).toList(), catalog.skills(), suggested,
                    notes, plan);
        }

        /** 到了这一次运行的上限：让模型总结做到哪了（不再执行工具），交给用户决定要不要继续。 */
        private AgentRun limitReached() {
            log(RunLog.Entry.budget(run.session(), step(), "run_limit", toolCalls, tokens));
            turns.add(AgentRun.Turn.user("系统：这一次运行已经用了 " + callsThisRun + " 次工具调用，到了上限。"
                    + "不要再调用工具，用几句话告诉用户：做到哪了、还剩什么、卡在哪里。"));
            ChatResponse response = call();
            AssistantMessage message = ModelCaller.message(response);
            String text = message == null || message.getText() == null ? "" : message.getText().strip();
            reply(AgentRun.Turn.assistant(text, List.of(), ModelCaller.thinking(response)));
            return waiting((text.isEmpty() ? "这一次运行做了很多步，到了上限。" : text) + "\n\n回复「继续」我接着做。");
        }

        // ---- 工具调用 ----

        private Step handle(AgentRun.ToolCall call) {
            String name = call.name();
            if (ControlTools.isControlTool(name)) {
                return control(call);
            }
            if (!BrowserTools.isBrowserTool(name)) {
                return result(call, "失败：没有这个工具：" + name + "。");
            }
            BrowserTools.Call translated;
            try {
                translated = BrowserTools.translate(name, call.arguments(), stagedPaths());
            } catch (IllegalArgumentException e) {
                return failed(call, name, targetOf(call.arguments()), call.arguments(), true, e.getMessage());
            }
            return browser(call, translated);
        }

        private Step browser(AgentRun.ToolCall call, BrowserTools.Call translated) {
            String tool = translated.tool();
            String ref = translated.args().get("target") instanceof String target ? target : null;
            PageSnapshot before = latest;

            if (BrowserToolPolicy.CLICK.equals(tool)) {
                switch (SubmitGuard.click(latest, ref, run.askedButton())) {
                    case GuardDecision.Reject reject -> {
                        return rejected(call, translated, ref, reject.reason(), buttonName(latest, ref));
                    }
                    case GuardDecision.AskUser ask -> {
                        pendingButton = ask.button();
                        log(RunLog.Entry.guard(run.session(), step(), tool, ref, element(latest, ref), "ask_user",
                                page(latest), ask.reason()));
                        return result(call, "没有点：" + ask.reason() + "。先用 ask_user 问用户「" + ask.button()
                                + "」能不能点（说明你的依据）；用户同意后再点它，系统就会放行。");
                    }
                    default -> {
                    }
                }
            }
            if (!translated.fields().isEmpty()) {
                Step classC = classC(call, translated);
                if (classC != null) {
                    return classC;
                }
            }

            BrowserToolPolicy.Context policy = BrowserToolPolicy.Context.model(SnapshotIndex.of(before),
                    siteUrls(), layout.uploadsDir(run.job()));
            if (BrowserToolPolicy.FIND.equals(tool)) {
                return find(call, translated, policy);
            }
            PageSnapshot after;
            try {
                after = browser.act(run.job(), tool, translated.args(), policy);
            } catch (ToolNotAllowedException e) {
                return rejected(call, translated, ref, e.getMessage(), null);
            } catch (BrowserException.Disconnected e) {
                return ended(call, current -> disconnectedRun(current, e.getMessage()));
            } catch (BrowserException.ToolFailed e) {
                return failed(call, tool, ref, translated.args().toString(), translated.write(), e.getMessage());
            } catch (IllegalStateException e) {
                return ended(call, current -> disconnectedRun(current, e.getMessage()));
            }

            latest = after;
            boolean changedPage = !(after.url() + after.title()).equals(before.url() + before.title());
            if (translated.write()) {
                recordFields(translated, before);
                recordUploads(translated, after);
            }
            SubmitGuard.ClickIntent click = translated.click();
            log(RunLog.Entry.toolCall(run.session(), step(), tool, ref, element(before, ref),
                    click == null ? null : click.purpose(), click == null ? null : click.evidence(), page(after),
                    changedPage ? "page_changed" : "ok"));

            StringBuilder text = new StringBuilder(resultText(translated));
            SubmitGuard.pageWarning(after).ifPresent(warning -> text.append("\n系统提醒：").append(warning));
            detector.record(new LoopDetector.Action(tool, ref, translated.args().toString(), translated.write(), false,
                    pageText(after))).ifPresent(reminder -> {
                        log(RunLog.Entry.loopDetected(run.session(), step(), "repeat", tool, ref, element(before, ref),
                                page(after)));
                        text.append('\n').append(reminder);
                    });
            return new Step(new AgentRun.ToolResult(call.id(), call.name(), text.toString()), after, null);
        }

        /** 有 C 级字段时拒绝整次调用（不发给浏览器），报告里记为「用户亲填」；没有时为 null。 */
        private Step classC(AgentRun.ToolCall call, BrowserTools.Call translated) {
            String tool = translated.tool();
            SnapshotIndex index = SnapshotIndex.of(latest);
            List<String> labels = new ArrayList<>();
            for (FieldIntent field : translated.fields()) {
                if (FieldClassGuard.check(index, field.ref(), field.label()) instanceof GuardDecision.Handoff) {
                    String label = fieldLabel(field);
                    if (label == null) {
                        label = index.find(field.ref()).map(SnapshotIndex.Node::name).filter(name -> !name.isBlank())
                                .orElse(field.ref() == null ? "（未命名字段）" : field.ref());
                    }
                    labels.add(label);
                    log(RunLog.Entry.guard(run.session(), step(), tool, field.ref(), element(latest, field.ref()),
                            "handoff", page(latest), "C 级字段由用户亲自填写"));
                    draft.field(new FillReportWriter.UserFilled(latest.title(), label));
                    draft.guardNotes.add(new FillReportWriter.GuardNote(label, FillReportWriter.GuardOutcome.HANDOFF));
                }
            }
            if (labels.isEmpty()) {
                return null;
            }
            writeDraft();
            return result(call, "被拒绝，这次调用没有执行：「" + String.join("」「", labels) + "」是 C 级字段，由用户亲自填写。"
                    + "把其它字段去掉再填；最后用 ask_user 请用户在浏览器里填这些字段。");
        }

        private Step find(AgentRun.ToolCall call, BrowserTools.Call translated, BrowserToolPolicy.Context policy) {
            String found;
            try {
                found = browser.find(run.job(), translated.args(), policy);
            } catch (ToolNotAllowedException e) {
                return rejected(call, translated, null, e.getMessage(), null);
            } catch (BrowserException.Disconnected | IllegalStateException e) {
                return ended(call, current -> disconnectedRun(current, e.getMessage()));
            } catch (BrowserException.ToolFailed e) {
                return failed(call, translated.tool(), null, translated.args().toString(), false, e.getMessage());
            }
            String redacted = SnapshotRedactor.redact(found == null ? "" : found);
            log(RunLog.Entry.toolCall(run.session(), step(), translated.tool(), null, null, null, null, page(latest),
                    "ok"));
            StringBuilder text = new StringBuilder("找到的内容：\n").append(redacted);
            detector.record(new LoopDetector.Action(translated.tool(), null, translated.args().toString(), false, false,
                    pageText(latest) + "\n" + redacted)).ifPresent(reminder -> text.append('\n').append(reminder));
            return result(call, text.toString());
        }

        private Step rejected(AgentRun.ToolCall call, BrowserTools.Call translated, String ref, String reason,
                              String button) {
            String tool = translated.tool();
            log(RunLog.Entry.guard(run.session(), step(), tool, ref, element(latest, ref), "reject", page(latest),
                    reason));
            if (button != null && !button.isBlank()) {
                draft.guardNotes.add(new FillReportWriter.GuardNote(button.strip(),
                        FillReportWriter.GuardOutcome.REJECTED));
                writeDraft();
            }
            StringBuilder text = new StringBuilder("被拒绝：").append(reason);
            detector.record(new LoopDetector.Action(tool, ref, translated.args().toString(), translated.write(), true,
                    reason)).ifPresent(reminder -> text.append('\n').append(reminder));
            return result(call, text.toString());
        }

        private Step failed(AgentRun.ToolCall call, String tool, String ref, String action, boolean write,
                            String message) {
            log(RunLog.Entry.toolCall(run.session(), step(), tool, ref, element(latest, ref), null, null,
                    page(latest), "error"));
            StringBuilder text = new StringBuilder("失败：").append(message);
            detector.record(new LoopDetector.Action(tool, ref, action, write, true, message))
                    .ifPresent(reminder -> text.append('\n').append(reminder));
            return result(call, text.toString());
        }

        // ---- 控制工具 ----

        private Step control(AgentRun.ToolCall call) {
            Map<String, Object> args;
            try {
                args = parse(call.arguments());
            } catch (IllegalArgumentException e) {
                return result(call, "失败：" + e.getMessage());
            }
            return switch (call.name()) {
                case ControlTools.ASK_USER -> askUser(call, args);
                case ControlTools.UPDATE_PLAN -> updatePlan(call, args);
                case ControlTools.READ_SKILL -> readSkill(call, text(args.get("name")));
                case ControlTools.FINISH -> finish(call, args);
                default -> result(call, "失败：没有这个工具：" + call.name());
            };
        }

        private Step askUser(AgentRun.ToolCall call, Map<String, Object> args) {
            String message = text(args.get("message"));
            if (message == null) {
                return result(call, "失败：ask_user 要写 message（直接写给用户：要用户做什么、做完怎么回复）");
            }
            List<AgentRun.Question> questions = new ArrayList<>();
            for (Map<?, ?> item : maps(args.get("questions"))) {
                String question = text(item.get("text"));
                if (question != null) {
                    questions.add(new AgentRun.Question("q" + (questions.size() + 1), question,
                            strings(item.get("options")), !Boolean.FALSE.equals(item.get("required")),
                            FieldClassGuard.isClassC(question) ? FactClass.C.name() : null, text(item.get("page"))));
                }
            }
            List<AgentRun.FileSlot> slots = new ArrayList<>();
            for (Map<?, ?> item : maps(args.get("files"))) {
                String label = text(item.get("label"));
                if (label != null) {
                    slots.add(new AgentRun.FileSlot("slot-" + (slots.size() + 1), label, text(item.get("accept")),
                            !Boolean.FALSE.equals(item.get("required")), text(item.get("note"))));
                }
            }
            log(RunLog.Entry.waiting(run.session(), step(), "ask_user", page(latest), null));
            return ended(call, "已经交给用户，等用户回复。",
                    current -> current.waiting(message, questions, slots, false)
                            .withAskedButton(pendingButton != null ? pendingButton : run.askedButton()));
        }

        private Step updatePlan(AgentRun.ToolCall call, Map<String, Object> args) {
            List<AgentRun.PlanItem> items = new ArrayList<>();
            for (Map<?, ?> item : maps(args.get("items"))) {
                String text = text(item.get("text"));
                if (text != null) {
                    String status = text(item.get("status"));
                    items.add(new AgentRun.PlanItem(text, status == null ? "todo" : status));
                }
            }
            if (items.isEmpty()) {
                return result(call, "失败：update_plan 至少要有一项");
            }
            plan = List.copyOf(items);
            return result(call, "计划已更新。");
        }

        private Step readSkill(AgentRun.ToolCall call, String name) {
            SkillCatalog.LoadedSkill skill;
            try {
                skill = catalog.load(name == null ? "" : name);
            } catch (RuntimeException e) {
                return result(call, "失败：" + e.getMessage());
            }
            log(RunLog.Entry.skillLoaded(run.session(), step(), skill.name()));
            return result(call, "skill「" + skill.name() + "」：\n\n" + skill.body());
        }

        private Step finish(AgentRun.ToolCall call, Map<String, Object> args) {
            draft.unresolved.addAll(strings(args.get("unresolved")));
            writeDraft();
            String summary = text(args.get("summary"));
            log(RunLog.Entry.waiting(run.session(), step(), "finish", page(latest), null));
            // 页面已经像是投递成功了（例如「下一步」直接完成了投递）：不再说「最终提交由你自己点」
            String tail = latest != null && SubmitWords.looksSubmitted(latest.visibleText())
                    ? SubmitWords.SUBMITTED_NOTICE + "请在 Chrome 里核对投递状态和填写报告。"
                    : "请在 Chrome 里核对页面和填写报告，最终提交由你自己点。要修改时直接回复要改什么。";
            String message = (summary == null ? "能填的都填好了。" : summary) + "\n\n" + tail;
            return ended(call, "已结束，交给用户审阅。", current -> current.ready(message));
        }

        // ---- 报告 ----

        private void recordFields(BrowserTools.Call call, PageSnapshot before) {
            if (call.fields().isEmpty()) {
                return;
            }
            List<String> values = values(call);
            SnapshotIndex index = SnapshotIndex.of(before);
            // 页面原值只从遮掉 C 级值以后的快照里取
            SnapshotIndex shown = SnapshotIndex.of(SnapshotRedactor.redact(before));
            for (int i = 0; i < call.fields().size(); i++) {
                FieldIntent field = call.fields().get(i);
                String value = i < values.size() ? values.get(i) : "";
                String label = fieldLabel(field);
                if (label == null) {
                    label = index.find(field.ref()).map(SnapshotIndex.Node::name).filter(n -> !n.isBlank())
                            .orElse(field.ref() == null ? "（未命名字段）" : field.ref());
                }
                Language language = field.language() == null ? null : Language.of(field.language());
                FillReportWriter.Confidence confidence = confidence(field.confidence());
                ResolvedFact fact = field.inferred() ? null : fact(field.fact(), language, value);
                FillReportWriter.Filled row = fact == null
                        ? FillReportWriter.Filled.inferred(before.title(), label, value, confidence, FactClass.A,
                        language)
                        : FillReportWriter.Filled.fromFact(before.title(), label, value, fact, confidence,
                        fact.language() != null ? fact.language() : language);
                String replaced = replacedValue(shown, field.ref(), value);
                draft.field(replaced == null ? row : row.replacing(replaced));
            }
            writeDraft();
        }

        /** 模型写的资料键或答案编号对应的资料：指明了语言就取那种写法，否则取和填进去的值一样的那种。 */
        private ResolvedFact fact(String key, Language language, String value) {
            ResolvedFact zh = profileZh.get(key);
            ResolvedFact en = profileEn.get(key);
            if (language == Language.EN && en != null) {
                return en;
            }
            if (language == Language.ZH && zh != null) {
                return zh;
            }
            if (en != null && value.strip().equals(en.display() == null ? en.value() : en.display())) {
                return en;
            }
            return zh != null ? zh : en;
        }

        private void recordUploads(BrowserTools.Call call, PageSnapshot after) {
            if (call.fileNumbers().isEmpty()) {
                return;
            }
            for (int number : call.fileNumbers()) {
                AgentRun.StagedFile file = run.files().get(number - 1);
                String readBack = after.snapshot().contains(file.uploadName()) ? "页面显示了文件名" : "页面上没看到文件名";
                draft.attach(new FillReportWriter.Attachment(file.uploadName(), file.assetKey(), file.size(),
                        file.sha256(), readBack));
            }
            writeDraft();
        }

        private void writeDraft() {
            reports.writeDraft(run.project(), run.job(), draft.report());
        }

        // ---- 结束一次运行 ----

        private Step result(AgentRun.ToolCall call, String text) {
            return new Step(new AgentRun.ToolResult(call.id(), call.name(), text), null, null);
        }

        private Step ended(AgentRun.ToolCall call, UnaryOperator<AgentRun> end) {
            return ended(call, "失败：浏览器连接断开了。", end);
        }

        private Step ended(AgentRun.ToolCall call, String text, UnaryOperator<AgentRun> end) {
            return new Step(new AgentRun.ToolResult(call.id(), call.name(), text), null, end);
        }

        private AgentRun waiting(String message) {
            log(RunLog.Entry.waiting(run.session(), step(), "user", latest == null ? null : page(latest), null));
            return current().waiting(message, List.of(), List.of(), false);
        }

        private AgentRun disconnected(String reason) {
            return disconnectedRun(current(), reason);
        }

        private AgentRun disconnectedRun(AgentRun current, String reason) {
            log(RunLog.Entry.waiting(run.session(), step(), "reconnect", null, null));
            return current.waiting("浏览器连接断开了（" + reason + "）。请确认 Chrome 开着、chrome://inspect/#remote-debugging 里的远程调试开关已经打开，然后回复，"
                    + "我会重新连接并接着做。", List.of(), List.of(), true);
        }

        private AgentRun current() {
            return run.withProgress(plan, new AgentRun.Usage(modelCalls, toolCalls, tokens), turns);
        }

        private int step() {
            return (int) modelCalls;
        }

        private List<Path> stagedPaths() {
            return run.files().stream().map(file -> file.attachment() == null
                    ? layout.uploadsDir(run.job()).resolve(file.uploadName())
                    : layout.jobAttachmentsDir(run.job()).resolve(file.attachment()).resolve(file.uploadName())).toList();
        }

        private List<String> siteUrls() {
            List<String> urls = new ArrayList<>();
            if (run.url() != null) {
                urls.add(run.url());
            }
            if (latest != null) {
                urls.add(latest.url());
            }
            return urls;
        }

        private void log(RunLog.Entry entry) {
            runLog.append(run.project(), run.job(), entry);
        }
    }

    // ---- 小工具 ----

    /** 交给重复检测比较的页面：遮掉 C 级值以后的地址、标题、快照和弹出状态。 */
    private static String pageText(PageSnapshot page) {
        PageSnapshot redacted = SnapshotRedactor.redact(page);
        return redacted.url() + "\n" + redacted.title() + "\n" + redacted.modalState() + "\n" + redacted.snapshot();
    }

    private static String resultText(BrowserTools.Call call) {
        if (!call.write()) {
            return "完成。";
        }
        if (!call.fileNumbers().isEmpty()) {
            return "已上传文件 " + call.fileNumbers() + "。系统取了新的快照：";
        }
        return "完成。系统取了新的快照：";
    }

    private static FillReportWriter.Confidence confidence(String code) {
        try {
            return code == null ? null : FillReportWriter.Confidence.of(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 每个字段填进去的值，顺序同 {@link BrowserTools.Call#fields()}。只在内存里用来写报告。 */
    private static List<String> values(BrowserTools.Call call) {
        Map<String, Object> args = call.args();
        return switch (call.tool()) {
            case BrowserToolPolicy.TYPE -> List.of(String.valueOf(args.getOrDefault("text", "")));
            case BrowserToolPolicy.SELECT_OPTION -> List.of(args.get("values") instanceof List<?> list
                    ? String.join("、", list.stream().map(String::valueOf).toList()) : "");
            case BrowserToolPolicy.FILL_FORM -> {
                List<String> values = new ArrayList<>();
                if (args.get("fields") instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            values.add(String.valueOf(map.get("value") == null ? "" : map.get("value")));
                        }
                    }
                }
                yield values;
            }
            default -> List.of();
        };
    }

    /**
     * 文本输入框里原来有一个不同的值（例如上传简历后页面解析写错的专业、日期），系统把它改正了：返回原来的值，
     * 报告里记下改正前后。空的输入框、值相同、不是文本输入框（下拉框的「请选择」、勾选框）时为 null。
     */
    private static String replacedValue(SnapshotIndex index, String ref, String value) {
        return index.find(ref)
                .filter(node -> TEXT_INPUTS.contains(node.role()))
                .map(node -> node.text().strip())
                .filter(text -> !text.isEmpty() && !text.equals(value.strip()))
                .orElse(null);
    }

    private static String fieldLabel(FieldIntent field) {
        return field.label() == null || field.label().isBlank() ? null : field.label().strip();
    }

    /** 页面的主机名（小写）；解析不了时为 null。 */
    private static String host(PageSnapshot page) {
        try {
            String host = page == null ? null : URI.create(page.url()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String buttonName(PageSnapshot page, String ref) {
        if (page == null || ref == null) {
            return null;
        }
        return SnapshotIndex.of(page).find(ref)
                .map(node -> node.name().isBlank() ? node.text() : node.name())
                .orElse(null);
    }

    private static RunLog.Element element(PageSnapshot page, String ref) {
        if (page == null || ref == null) {
            return null;
        }
        return SnapshotIndex.of(page).find(ref).map(node -> new RunLog.Element(node.role(), node.name())).orElse(null);
    }

    private static RunLog.Page page(PageSnapshot page) {
        return page == null ? null : new RunLog.Page(page.title(), page.url());
    }

    private static String targetOf(String argumentsJson) {
        try {
            return parse(argumentsJson).get("target") instanceof String target ? target : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, Object> parse(String json) {
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

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(item -> item instanceof String text && !text.isBlank())
                .map(item -> ((String) item).strip()).toList();
    }

    private static List<Map<?, ?>> maps(Object value) {
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

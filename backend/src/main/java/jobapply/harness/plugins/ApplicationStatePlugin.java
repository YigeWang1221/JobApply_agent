package jobapply.harness.plugins;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jobapply.domain.AgentRun;
import jobapply.domain.ApplicationState;
import jobapply.domain.FactClass;
import jobapply.domain.Language;
import jobapply.harness.context.FillContext;
import jobapply.harness.context.SkillCatalog;
import jobapply.harness.context.StateView;
import jobapply.harness.policies.BrowserToolPolicy;
import jobapply.harness.policies.FieldIntent;
import jobapply.harness.policies.SnapshotRedactor;
import jobapply.harness.runtime.Loop;
import jobapply.harness.runtime.LoopEnd;
import jobapply.harness.runtime.LoopEvent;
import jobapply.memory.ResolvedFact;
import jobapply.persistence.FillReportWriter;
import jobapply.persistence.FrontmatterFile;
import jobapply.persistence.JobThreadStore;
import jobapply.persistence.MemoryNotes;
import jobapply.tools.PlaywrightMcpClient;
import jobapply.persistence.RunLog;
import jobapply.persistence.SiteNotes;
import jobapply.persistence.StorageLayout;
import jobapply.tools.BrowserFillTools;
import jobapply.tools.BrowserTools;
import jobapply.tools.Ending;
import jobapply.tools.FillEvent;
import jobapply.tools.PageSnapshot;
import jobapply.tools.SnapshotIndex;
import jobapply.tools.ToolOutcome;
import jobapply.tools.Workbench;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * 申请状态插件（ApplicationState，C019；原来散在 {@code HarnessLoop} 里）：
 * <ul>
 *   <li>注入：每一步的资料和上下文（{@link FillContext}），申请状态（{@link StateView}）接在最后一条消息末尾；</li>
 *   <li>自动记录：浏览器填写工具报告的 {@link FillEvent}（填了、核对上了、没填的原因、见过的选项、C 级交给用户、
 *       被拒绝的提交类按钮）记进申请状态和报告草稿；</li>
 *   <li>用量和每轮存盘；结束时按原因写出这次运行的 {@link AgentRun}（等用户、停在提交前、浏览器断开、停止、上限）。</li>
 * </ul>
 * 过程日志只记工具、元素的角色和名字、页面标题和地址、守卫的判断和 token 数，不记值、快照和对话。
 */
public final class ApplicationStatePlugin implements Plugin {

    public static final String STOPPED = "已按你的要求停下。回复后接着做。";
    /** 模型只回了空文字时给用户的话。 */
    public static final String BLANK_REPLY = "我没有想好下一步。请看一下页面，告诉我接下来做什么。";

    private static final Set<String> TEXT_INPUTS = Set.of("textbox", "searchbox", "spinbutton");
    /** 选下拉没选中时结果里的一个选项（{@code PlaywrightMcpClient.choices} 的写法）。 */
    private static final Pattern OPTION_LINE = Pattern.compile("^\\s*- option \"(.*)\"\\s*$");

    private final Workbench bench;
    private final StorageLayout layout;
    private final SiteNotes sites;
    private final MemoryNotes memoryNotes;
    private final Predicate<SkillCatalog.Skill> skills;
    private final String limitNote;
    private AgentRun result;

    /**
     * @param skills    这个 Mode 的 skill 范围
     * @param limitNote 到了上限时的系统说明，{@code {calls}} 换成已经用掉的次数
     */
    public ApplicationStatePlugin(Workbench bench, StorageLayout layout, java.time.Clock clock,
                                  Predicate<SkillCatalog.Skill> skills, String limitNote) {
        this.bench = bench;
        this.layout = layout;
        this.sites = new SiteNotes(layout, clock);
        this.memoryNotes = new MemoryNotes(layout);
        this.skills = skills;
        this.limitNote = limitNote;
    }

    /** 这次运行结束时的运行记录；还没结束时为 null。 */
    public AgentRun result() {
        return result;
    }

    // ---- 注入 ----

    @Override
    public void context(ContextView view) {
        AgentRun run = bench.run();
        view.context(FillContext.context(inputs()));
        view.tail(StateView.render(goal(run), bench.state()));
        view.attachments(JobThreadStore.attachments(layout, run.project(), run.job(), true));
    }

    private static String goal(AgentRun run) {
        return run.company() + " · " + run.title() + "：替用户填好这份申请，停在最终提交前";
    }

    private FillContext.Inputs inputs() {
        AgentRun run = bench.run();
        PageSnapshot latest = bench.latest();
        String currentUrl = latest != null ? latest.url() : run.url();
        SkillCatalog catalog = bench.catalog();
        List<String> suggested = catalog.autoInclude(currentUrl).stream().map(SkillCatalog.Skill::name).toList();
        String host = latest != null ? Workbench.host(latest) : Workbench.hostOf(run.url());
        String notes = host == null ? null : sites.read(host).map(SiteNotes.Site::notes).orElse(null);
        Path profileFile = layout.baseProfile();
        String profileSummary = Files.exists(profileFile) ? FrontmatterFile.read(profileFile).body().strip() : null;
        if (profileSummary != null && profileSummary.isEmpty()) {
            profileSummary = null;
        }
        String jobNote = bench.jobs().exists(run.project(), run.job())
                ? bench.jobs().load(run.project(), run.job()).extraString("lead_note") : null;
        if (jobNote != null && jobNote.isBlank()) {
            jobNote = null;
        }
        return new FillContext.Inputs(bench.profileZh(), bench.profileEn(), bench.answers(),
                bench.files().stream().map(AgentRun.StagedFile::uploadName).toList(),
                catalog.skills().stream().filter(skills).toList(), suggested,
                notes, goal(run), bench.state(),
                memoryNotes.company(run.company()).orElse(null), memoryNotes.lessons().orElse(null),
                profileSummary, jobNote);
    }

    /** 每次调模型之后记用量。 */
    @Override
    public void response(ChatResponse response) {
        long in = 0;
        long out = 0;
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage != null) {
            in = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            out = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        }
        bench.used(in, out);
        bench.log(RunLog.Entry.modelCall(bench.run().session(), bench.step(), in, out));
    }

    // ---- 自动记录 ----

    @Override
    public ToolOutcome toolAfter(AgentRun.ToolCall call, ToolOutcome outcome, Loop loop) {
        if (outcome.fills().isEmpty()) {
            return outcome;
        }
        boolean draft = false;
        for (FillEvent event : outcome.fills()) {
            switch (event) {
                case FillEvent.Filled filled -> {
                    if (!filled.call().fields().isEmpty()) {
                        recordFields(filled.call(), filled.before(), filled.after(), filled.notice());
                        draft = true;
                    }
                    if (!filled.call().fileNumbers().isEmpty()) {
                        recordUploads(filled.call(), filled.before(), filled.after());
                        draft = true;
                    }
                }
                case FillEvent.NotFilled notFilled -> notFilled(notFilled.call(), notFilled.page(), notFilled.message());
                case FillEvent.Skipped skipped -> noteField(skipped.field(), skipped.page(), skipped.note());
                case FillEvent.HandedOff handed -> {
                    // 申请状态里记成要用户填，报告里是「用户亲填」（C019）
                    ApplicationState.ReportRow userFilled = new ApplicationState.ReportRow(handed.pageTitle(), null,
                            null, null, null, FactClass.C.name());
                    ApplicationState.Located at = bench.state().locate(handed.field().field(), handed.label());
                    bench.setState(at.state().update(at.id(), f -> f.filled(null, null, null,
                            ApplicationState.NEEDS_USER, "C 级：由用户在浏览器里亲自填", userFilled)));
                    bench.draft().guard(new FillReportWriter.GuardNote(handed.label(),
                            FillReportWriter.GuardOutcome.HANDOFF));
                    draft = true;
                }
                case FillEvent.Refused refused -> {
                    bench.draft().guard(new FillReportWriter.GuardNote(refused.button(),
                            FillReportWriter.GuardOutcome.REJECTED));
                    draft = true;
                }
            }
        }
        if (draft) {
            bench.writeDraft();
        }
        return outcome;
    }

    /**
     * 填写成功：每个字段记进申请状态（C019）——值、来源、控件、状态和报告那一行。新快照里输入框的文字和填的值一样、
     * 选下拉 / 设日期读回对得上时记成「已核对」，否则「已填」。
     */
    private void recordFields(BrowserTools.Call call, PageSnapshot before, PageSnapshot after, String notice) {
        List<String> values = BrowserFillTools.values(call);
        List<String> widgets = widgets(call);
        SnapshotIndex index = SnapshotIndex.of(before);
        // 页面原值只从遮掉 C 级值以后的快照里取
        SnapshotIndex shown = SnapshotIndex.of(SnapshotRedactor.redact(before));
        SnapshotIndex now = SnapshotIndex.of(after);
        boolean readBack = PlaywrightMcpClient.readBackMatched(notice);
        for (int i = 0; i < call.fields().size(); i++) {
            FieldIntent field = call.fields().get(i);
            String value = i < values.size() ? values.get(i) : "";
            String label = label(field, index);
            Language language = field.language() == null ? null : Language.of(field.language());
            FillReportWriter.Confidence confidence = confidence(field.confidence());
            String replaced = replacedValue(shown, field.ref(), value);
            FillReportWriter.Recorded row;
            if (FieldIntent.RESUME.equals(field.fact())) {
                // 取自简历原文：来源写「简历原文」（C006-fix-real-run 第 3 条，报告写明每个值的来源）
                row = new FillReportWriter.Recorded(before.title(), label,
                        replaced == null ? value : value + "（页面原值：" + replaced + "，已改正）",
                        FillReportWriter.RESUME_SOURCE, language == null ? null : language.code(),
                        confidence == null ? null : confidence.code(), FactClass.A.name());
            } else if (FieldIntent.COVER_LETTER.equals(field.fact())) {
                // Agent 按 JD、简历和资料起草的 Cover Letter（C014）：来源写「Cover Letter：Agent 起草」
                row = new FillReportWriter.Recorded(before.title(), label, value,
                        FillReportWriter.COVER_LETTER_SOURCE, language == null ? null : language.code(),
                        confidence == null ? null : confidence.code(), FactClass.A.name());
            } else {
                ResolvedFact fact = field.inferred() ? null : fact(field.fact(), language, value);
                FillReportWriter.Filled filled = fact == null
                        ? FillReportWriter.Filled.inferred(before.title(), label, value, confidence, FactClass.A,
                        language)
                        : FillReportWriter.Filled.fromFact(before.title(), label, value, fact, confidence,
                        fact.language() != null ? fact.language() : language);
                row = FillReportWriter.asRecorded(replaced == null ? filled : filled.replacing(replaced));
            }
            String widget = i < widgets.size() ? widgets.get(i) : null;
            boolean verified = BrowserToolPolicy.SELECT_OPTION.equals(call.tool())
                    || BrowserToolPolicy.SET_DATE.equals(call.tool()) ? readBack
                    : ("text".equals(widget) && now.find(field.ref()).filter(node -> TEXT_INPUTS.contains(node.role()))
                    .map(node -> node.text().strip().equals(value.strip())).orElse(false));
            ApplicationState.ReportRow cells = new ApplicationState.ReportRow(row.page(), row.value(), row.source(),
                    row.language(), row.confidence(), row.grade());
            String source = field.inferred() ? FieldIntent.NONE : field.fact();
            ApplicationState.Located at = bench.state().locate(field.field(), label);
            bench.setState(at.state().update(at.id(), f -> f.filled(widget, value, source,
                    verified ? ApplicationState.VERIFIED : ApplicationState.FILLED,
                    replaced == null ? null : "页面原来的值不对，已改正", cells)));
        }
    }

    /** 模型写的资料键或答案编号对应的资料：指明了语言就取那种写法，否则取和填进去的值一样的那种。 */
    private ResolvedFact fact(String key, Language language, String value) {
        ResolvedFact zh = bench.profileZh().get(key);
        ResolvedFact en = bench.profileEn().get(key);
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

    private void recordUploads(BrowserTools.Call call, PageSnapshot before, PageSnapshot after) {
        List<AgentRun.StagedFile> files = bench.files();
        List<String> names = new ArrayList<>();
        boolean allShown = true;
        for (int number : call.fileNumbers()) {
            AgentRun.StagedFile file = files.get(number - 1);
            boolean shown = after.snapshot().contains(file.uploadName());
            allShown &= shown;
            names.add(file.uploadName());
            bench.draft().attach(new FillReportWriter.Attachment(file.uploadName(), file.assetKey(), file.size(),
                    file.sha256(), shown ? "页面显示了文件名" : "页面上没看到文件名"));
        }
        // 申请状态里一个上传字段（C019）：报告的附件表照旧，字段行不重复记
        String target = call.args().get("target") instanceof String ref ? ref : null;
        String label = Optional.ofNullable(Workbench.buttonName(before, target)).filter(name -> !name.isBlank())
                .orElse("上传文件");
        String value = String.join("、", names);
        boolean verified = allShown;
        ApplicationState.Located at = bench.state().locate(null, label);
        bench.setState(at.state().update(at.id(), f -> f.filled("file", value, null,
                verified ? ApplicationState.VERIFIED : ApplicationState.FILLED,
                verified ? null : "页面上没看到文件名，看新快照核对", f.report())));
    }

    /** 这些字段这次没有填进去：原因记在申请状态的字段上（C019）。 */
    private void noteField(FieldIntent field, PageSnapshot page, String note) {
        ApplicationState.Located at = bench.state().locate(field.field(), label(field, SnapshotIndex.of(page)));
        String line = firstLine(note);
        bench.setState(at.state().update(at.id(), f -> f.withNote(line)));
    }

    /**
     * 填写工具失败：每个字段记下原因；选下拉没选中时记下结果里列出的选项原文；日期 {@code need_day} 记成要问用户（C019）。
     */
    private void notFilled(BrowserTools.Call call, PageSnapshot page, String message) {
        List<String> options = new ArrayList<>();
        if (BrowserToolPolicy.SELECT_OPTION.equals(call.tool())) {
            message.lines().map(OPTION_LINE::matcher).filter(Matcher::matches)
                    .forEach(m -> options.add(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\")));
        }
        boolean needDay = BrowserToolPolicy.SET_DATE.equals(call.tool()) && message.contains("need_day");
        for (FieldIntent field : call.fields()) {
            ApplicationState.Located at = bench.state().locate(field.field(), label(field, SnapshotIndex.of(page)));
            String line = firstLine(message);
            bench.setState(at.state().update(at.id(), f -> {
                ApplicationState.Field noted = needDay
                        ? f.withStatus(ApplicationState.NEEDS_USER, "need_day：要精确到日，问用户是哪一天")
                        : f.withNote(line);
                return options.isEmpty() ? noted : noted.withOptions(options);
            }));
        }
    }

    // ---- 循环里的事 ----

    @Override
    public void on(LoopEvent event, Loop loop) {
        switch (event) {
            // 每轮之后、用户插话加进对话后都交给调用方存盘，工作台马上看得到（C010）
            case LoopEvent.RoundDone done -> bench.saveProgress(loop.turns(), loop.calls());
            case LoopEvent.Heard heard -> bench.saveProgress(loop.turns(), loop.calls());
            case LoopEvent.LimitReached limit -> {
                bench.log(RunLog.Entry.budget(bench.run().session(), bench.step(), "run_limit",
                        bench.toolCalls(loop.calls()), bench.tokens()));
                loop.add(AgentRun.Turn.user(ConversationEndingPlugin.limitText(limitNote, limit.calls())));
            }
            case LoopEvent.Finished finished -> result = finished(finished.end(), loop);
            default -> {
            }
        }
    }

    private AgentRun finished(LoopEnd end, Loop loop) {
        return switch (end) {
            case LoopEnd.Answered answered -> {
                // 只回了文字：当成对用户说的话，等用户回复（OpenMAIC 的自动 cue）
                loop.reply(AgentRun.Turn.assistant(answered.text(), List.of(), answered.thinking()));
                String text = answered.text();
                yield text == null || text.isBlank() ? told(waiting(loop, BLANK_REPLY), BLANK_REPLY)
                        : waiting(loop, text.strip());
            }
            case LoopEnd.Stopped stopped -> told(waiting(loop, STOPPED), STOPPED);
            case LoopEnd.Summarized summary -> {
                loop.reply(AgentRun.Turn.assistant(summary.text(), List.of(), summary.thinking()));
                yield waiting(loop, (summary.text().isEmpty() ? "这一次运行做了很多步，到了上限。" : summary.text())
                        + "\n\n回复「继续」我接着做。" + ConversationEndingPlugin.RAISE_LIMIT);
            }
            case LoopEnd.Ended ended -> switch (ended.ending()) {
                case Ending.AskUser ask -> current(loop).waiting(ask.message(), ask.questions(), ask.files());
                case Ending.Finish finish -> told(current(loop).ready(finish.message(), finish.submitted()),
                        finish.note());
                case Ending.Disconnected disconnected -> disconnected(loop, disconnected.reason());
                case Ending.Waiting waiting -> told(waiting(loop, waiting.message()), waiting.message());
            };
        };
    }

    private AgentRun current(Loop loop) {
        return bench.current(loop.turns(), loop.calls());
    }

    private AgentRun waiting(Loop loop, String message) {
        bench.log(RunLog.Entry.waiting(bench.run().session(), bench.step(), "user",
                bench.latest() == null ? null : Workbench.page(bench.latest()), null));
        return current(loop).waiting(message, List.of(), List.of());
    }

    /**
     * 申请遇到浏览器断开：停下等你（对话类 Mode 是这一步失败、接着做，两边的应对有意不同）。断开本身记在全局的连接状态
     * 里，这里不另存；自动重连已经在下一次使用时试过一次了。
     */
    private AgentRun disconnected(Loop loop, String reason) {
        bench.log(RunLog.Entry.waiting(bench.run().session(), bench.step(), "reconnect", null, null));
        String message = "浏览器断开：" + reason + "。请确认 Chrome 开着、chrome://inspect/#remote-debugging 里的远程调试开关已经打开，"
                + "然后回复（或者先点页面顶部的「重新连接」），我会接着做。";
        return told(current(loop).waiting(message, List.of(), List.of()), message);
    }

    /** 这次运行不是以模型的文字结束的：对话末尾补一句现在的状态（C006-fix-final-reply），刷新后也在。 */
    private static AgentRun told(AgentRun ended, String status) {
        return ended.noted(AgentRun.Turn.status(status));
    }

    // ---- 小工具 ----

    /** 字段名：模型给的名字，没有时取快照里的名字，再没有时是 ref。 */
    private static String label(FieldIntent field, SnapshotIndex index) {
        String label = BrowserFillTools.fieldLabel(field);
        if (label != null) {
            return label;
        }
        return index.find(field.ref()).map(SnapshotIndex.Node::name).filter(n -> !n.isBlank())
                .orElse(field.ref() == null ? "（未命名字段）" : field.ref());
    }

    private static FillReportWriter.Confidence confidence(String code) {
        try {
            return code == null ? null : FillReportWriter.Confidence.of(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 每个字段的控件（申请状态里的 widget），顺序同 {@link BrowserTools.Call#fields()}。 */
    private static List<String> widgets(BrowserTools.Call call) {
        return switch (call.tool()) {
            case BrowserToolPolicy.TYPE -> List.of("text");
            case BrowserToolPolicy.SET_DATE -> List.of("date");
            case BrowserToolPolicy.SELECT_OPTION -> List.of("select");
            case BrowserToolPolicy.FILL_FORM -> {
                List<String> widgets = new ArrayList<>();
                if (call.args().get("fields") instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof java.util.Map<?, ?> map) {
                            Object type = map.get("type");
                            widgets.add("textbox".equals(type) ? "text" : "combobox".equals(type) ? "select"
                                    : type == null ? null : String.valueOf(type));
                        }
                    }
                }
                yield widgets;
            }
            default -> List.of();
        };
    }

    /** 一段说明的第一行（截到 160 个字），记在申请状态的字段上。 */
    private static String firstLine(String text) {
        String line = text == null ? "" : text.strip().split("\n", 2)[0].strip();
        return line.length() > 160 ? line.substring(0, 160) + "…" : line;
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
}

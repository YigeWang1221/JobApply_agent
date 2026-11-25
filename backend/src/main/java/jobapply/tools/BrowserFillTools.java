package jobapply.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import jobapply.domain.AgentRun;
import jobapply.domain.Placeholder;
import jobapply.harness.policies.BrowserToolPolicy;
import jobapply.harness.policies.FieldClassGuard;
import jobapply.harness.policies.FieldIntent;
import jobapply.harness.policies.GuardDecision;
import jobapply.harness.policies.SnapshotRedactor;
import jobapply.harness.policies.SubmitGuard;
import jobapply.harness.policies.ToolNotAllowedException;
import jobapply.persistence.PersonalEnv;
import jobapply.persistence.RunLog;

/**
 * 申请 Mode 的浏览器工具（BrowserFill，原来在 {@code HarnessLoop} 里）：快照、查找、导航、点击、键入、填表、选下拉、
 * 设日期、上传、按键、等待，和 {@code browser_tabs}。浏览器的底线都在这里面，插件和上层碰不到 C 级原值：
 * <ul>
 *   <li>硬提交守卫：提交类按钮（含确认提交对话框里的确认按钮）由 {@link SubmitGuard} 拒绝；选下拉、设日期的 target
 *       是按钮时同样判断；文本框回车 / {@code submit: true} / 空格提交由 {@link BrowserToolPolicy} 拒绝；</li>
 *   <li>C 级字段：没登记占位符的明文由 {@link FieldClassGuard} 拒绝、交给用户；登记了的 {@code {KEY}} 只在发给浏览器
 *       MCP 的这一刻换成 personal.env 的真实值（{@link #withRealValues}），结果、日志、报告、对话里都是占位符；</li>
 *   <li>页面和出错说明交出去之前过 {@link SnapshotRedactor}；白名单以外的操作由 {@link BrowserToolPolicy} 拒绝。</li>
 * </ul>
 * 填写的结果作为 {@link FillEvent} 交给申请状态插件去记，打转比较用的 {@link ToolOutcome.BrowserAction} 交给打转提醒插件。
 * 开始时打开岗位页（{@link #start}）也在这里，由 {@code JobPagePlugin} 在循环开始时调用。
 */
public final class BrowserFillTools {

    private final RunContext ctx;
    private final Workbench bench;
    private final BrowserSession browser;
    private final PersonalEnv personal;

    private BrowserFillTools(RunContext ctx) {
        this.ctx = ctx;
        this.bench = ctx.slot(Workbench.class);
        this.browser = ctx.services().browser();
        this.personal = bench.personal();
    }

    /** 这次运行的浏览器填写工具（同一次运行里只有一份）。 */
    public static BrowserFillTools of(RunContext ctx) {
        return ctx.slot(BrowserFillTools.class, () -> new BrowserFillTools(ctx));
    }

    /** 全部浏览器工具 + 标签页。 */
    public static List<Tool> create(RunContext ctx) {
        BrowserFillTools tools = of(ctx);
        List<Tool> out = new ArrayList<>();
        BrowserTools.definitions().forEach(definition -> out.add(Tool.of(definition, tools::browser)));
        out.add(Tool.of(BrowserTools.tabsDefinition(), tools::tabs));
        return out;
    }

    private String job() {
        return bench.run().job();
    }

    private String session() {
        return bench.run().session();
    }

    // ---- 开始时的页面 ----

    /**
     * 本次启动是否先不打开 Chrome 页面（C021）：刚开工作台、上传区还没有简历、没有已认领的标签页、且开场提示要求先在
     * 工作台里匹配简历。
     */
    public boolean deferred() {
        AgentRun run = bench.run();
        if (!bench.fresh() || !bench.files().isEmpty() || browser.ownsTab(run.job())) {
            return false;
        }
        if (bench.jobs().exists(run.project(), run.job())
                && bench.resumes().current(bench.jobs().load(run.project(), run.job()))) {
            return false;
        }
        return run.conversation().stream().anyMatch(turn -> turn.text() != null && turn.text().contains("匹配简历"));
    }

    /**
     * 开始时的页面：用户刚转交了一个标签页就切过去；否则正在用的标签页还认得出就用它；否则新开岗位链接，告诉模型
     * 上次做到的页面，由它决定要不要用 {@code browser_tabs} 找回去。说明作为 {@code page} 条加进对话。
     */
    public PageSnapshot start() {
        AgentRun run = bench.run();
        BrowserSession.TabInfo handed = browser.takeHandover(run.job());
        if (handed != null) {
            try {
                PageSnapshot page = browser.select(run.job(), handed.index());
                ctx.add(AgentRun.Turn.page(handedText(handed) + "系统切过去了，下面是它的快照。"));
                return page;
            } catch (IllegalArgumentException e) {
                ctx.add(AgentRun.Turn.page(handedText(handed) + "但它现在已经不在了（可能被关掉），系统没有读它。"));
            } catch (BrowserException.ToolFailed e) {
                ctx.add(AgentRun.Turn.page(handedText(handed) + "系统切过去时出错（" + e.getMessage() + "），"
                        + "要在它上面做，用 browser_tabs select " + handed.index() + " 切过去。"));
            }
        }
        if (browser.ownsTab(run.job())) {
            try {
                PageSnapshot page = browser.snapshot(run.job());
                ctx.add(AgentRun.Turn.page("系统取了一次当前页面。"));
                return page;
            } catch (BrowserSession.TabLost e) {
                // 认不出了：新开岗位链接
            }
        }
        String lastUrl = run.lastPage() == null ? null : run.lastPage().url();
        PageSnapshot opened = browser.open(run.url(), run.job());
        if (lastUrl == null) {
            ctx.add(AgentRun.Turn.page("系统打开了岗位链接 " + run.url() + "。"));
        } else {
            ctx.add(AgentRun.Turn.page("系统打开了岗位链接 " + run.url() + "。上次做到的页面是 " + lastUrl
                    + "（应用重启、浏览器重连或者那一页被关掉后，系统不再记得它在哪个标签页）。那一页要是还开着、"
                    + "上面有填了一半的内容，用 browser_tabs list 找到它（网址对得上的那页），select 过去接着做。"));
        }
        return opened;
    }

    /** 「用户把标签页 N（标题、网址）交给你了。」 */
    private static String handedText(BrowserSession.TabInfo tab) {
        return "用户把标签页 " + tab.index() + "（" + (tab.title() == null || tab.title().isBlank() ? "无标题"
                : tab.title()) + "，" + tab.url() + "）交给你了。";
    }

    /** 运行中用户转交了一个标签页：结果后面附一句，由模型决定要不要 {@code browser_tabs select} 过去。 */
    private String withHandover(String text) {
        BrowserSession.TabInfo handed = browser.takeHandover(job());
        return handed == null ? text : text + "\n系统：" + handedText(handed) + "要在它上面做，用 browser_tabs select "
                + handed.index() + " 切过去。";
    }

    // ---- 浏览器工具 ----

    private ToolOutcome browser(ToolInput input) {
        AgentRun.ToolCall call = input.call();
        String name = call.name();
        BrowserTools.Call translated;
        try {
            translated = BrowserTools.translate(name, call.arguments(), bench.stagedPaths());
        } catch (IllegalArgumentException e) {
            return failed(call, name, targetOf(call.arguments()), call.arguments(), true, e.getMessage());
        }
        String baseline = null;
        if (bench.latest() == null) {
            EnsuredPage ensured = ensurePage(call, translated);
            if (ensured.outcome() != null) {
                return ensured.outcome();
            }
            baseline = ensured.baseline();
        }
        ToolOutcome out = act(call, translated);
        if (baseline != null && out.action() != null) {
            ToolOutcome.BrowserAction a = out.action();
            out = out.observed(new ToolOutcome.BrowserAction(a.tool(), a.target(), a.action(), a.write(), a.failed(),
                    a.observed(), baseline, a.repeated()));
        }
        return out;
    }

    /** 守卫、C 级字段、跳过不能键入的字段，然后发给浏览器；没填的字段原因跟着结果交出去。 */
    private ToolOutcome act(AgentRun.ToolCall call, BrowserTools.Call translated) {
        String tool = translated.tool();
        PageSnapshot latest = bench.latest();
        String ref = translated.args().get("target") instanceof String target ? target : null;

        if (BrowserToolPolicy.CLICK.equals(tool)
                && SubmitGuard.click(latest, ref) instanceof GuardDecision.Reject reject) {
            return rejected(call, translated, ref, reject.reason(), Workbench.buttonName(latest, ref));
        }
        String targetRole = SnapshotIndex.of(latest).find(ref).map(SnapshotIndex.Node::role).orElse(null);
        if ((BrowserToolPolicy.SELECT_OPTION.equals(tool) || BrowserToolPolicy.SET_DATE.equals(tool))
                && ("button".equals(targetRole) || "generic".equals(targetRole))) {
            // 选下拉、设日期会真实点击 target 来打开它（C016）：target 是按钮或没有角色的元素时照点击守卫判断，
            // 提交类按钮一样不点（下拉框、输入框本身点了不会提交，不拦）
            if (SubmitGuard.click(latest, ref) instanceof GuardDecision.Reject reject) {
                return rejected(call, translated, ref, reject.reason(), Workbench.buttonName(latest, ref));
            }
        }
        if (!translated.fields().isEmpty()) {
            ToolOutcome classC = classC(call, translated);
            if (classC != null) {
                return classC;
            }
        }
        // 只读框、自定义下拉上键入必然等满动作超时（C016 A）：发出之前查一次，这些字段不发，其余照常
        List<FillEvent> skippedEvents = new ArrayList<>();
        String skipped = null;
        if (BrowserToolPolicy.TYPE.equals(tool) || BrowserToolPolicy.FILL_FORM.equals(tool)) {
            Map<String, String> skip = cannotFill(translated);
            if (!skip.isEmpty()) {
                String reasons = reasons(translated, skip);
                for (FieldIntent field : translated.fields()) {
                    if (skip.containsKey(field.ref())) {
                        skippedEvents.add(new FillEvent.Skipped(field, latest, "没有填：" + skip.get(field.ref())));
                    }
                }
                BrowserTools.Call rest = without(translated, skip.keySet());
                if (rest == null) {
                    return failed(call, tool, ref, translated.args().toString(), true,
                            "没有填，这次调用没有发出：" + reasons + "。").filled(skippedEvents);
                }
                translated = rest;
                skipped = "这些字段没有填：" + reasons + "。其余字段照常填了。";
            }
        }
        // 没发出的字段的原因排在这次调用的结果前面记
        ToolOutcome sent = send(call, translated, ref, skipped);
        List<FillEvent> events = new ArrayList<>(skippedEvents);
        events.addAll(sent.fills());
        return new ToolOutcome(sent.result(), sent.page(), sent.ending(), sent.pageGone(), sent.action(), events);
    }

    private ToolOutcome send(AgentRun.ToolCall call, BrowserTools.Call translated, String ref, String skipped) {
        String tool = translated.tool();
        PageSnapshot before = bench.latest();
        BrowserToolPolicy.Context policy = BrowserToolPolicy.Context.model(SnapshotIndex.of(before),
                bench.siteUrls(), ctx.services().layout().uploadsDir(job()));
        if (BrowserToolPolicy.FIND.equals(tool)) {
            return find(call, translated, policy);
        }
        PageSnapshot after;
        try {
            // 占位符只在发给浏览器 MCP 的这一刻换成真实值（C014-D）；别处（结果、日志、报告）都还是占位符
            after = browser.act(job(), tool, !translated.write() ? translated.args()
                    : withRealValues(translated, text -> Placeholder.keyOf(text).filter(bench.placeholders()::contains)
                    .flatMap(personal::value)), policy);
        } catch (ToolNotAllowedException e) {
            return rejected(call, translated, ref, e.getMessage(), null);
        } catch (BrowserException.Disconnected e) {
            return disconnected(call, e.getMessage());
        } catch (BrowserException.Stalled e) {
            return stalled(call, e.getMessage());
        } catch (BrowserException.ToolFailed e) {
            // 选下拉、设日期没成时，说明里有页面上的选项原文：照快照一样过脱敏（C016）
            String message = SnapshotRedactor.redact(e.getMessage());
            return failed(call, tool, ref, translated.args().toString(), translated.write(), message)
                    .filled(List.of(new FillEvent.NotFilled(translated, before, message)));
        } catch (BrowserSession.TabLost e) {
            return tabLost(call, e.getMessage());
        } catch (IllegalStateException e) {
            return disconnected(call, e.getMessage());
        }

        bench.setLatest(after);
        boolean changedPage = !(after.url() + after.title()).equals(before.url() + before.title());
        String notice = browser.takeNotice(job());
        SubmitGuard.ClickIntent click = translated.click();
        bench.log(RunLog.Entry.toolCall(session(), bench.step(), tool, ref, Workbench.element(before, ref),
                click == null ? null : click.purpose(), click == null ? null : click.evidence(),
                Workbench.page(after), changedPage ? "page_changed" : "ok"));

        StringBuilder text = new StringBuilder(resultText(translated));
        if (skipped != null) {
            text.append("\n系统：").append(skipped);
        }
        if (notice != null) {
            text.append("\n系统：").append(SnapshotRedactor.redact(notice));
        }
        String handed = withHandover("");
        if (!handed.isEmpty()) {
            text.append(handed);
        }
        SubmitGuard.pageWarning(after).ifPresent(warning -> text.append("\n系统提醒：").append(warning));
        // 点开了下拉、级联、日期面板：只给弹出层那一块（C016 F）；判断不了时给整页
        PageSnapshot shown = BrowserToolPolicy.CLICK.equals(tool) || BrowserToolPolicy.TYPE.equals(tool)
                ? jobapply.harness.context.PopupView.of(before, after, ref).orElse(after) : after;
        ToolOutcome out = ToolOutcome.done(call, text.toString()).withPage(shown)
                .observed(new ToolOutcome.BrowserAction(tool, ref, translated.args().toString(), translated.write(),
                        false, pageText(after), null, RunLog.Entry.loopDetected(session(), bench.step(), "repeat",
                        tool, ref, Workbench.element(before, ref), Workbench.page(after))));
        return translated.write() ? out.filled(List.of(new FillEvent.Filled(translated, before, after, notice))) : out;
    }

    /** 这次 {@code browser_type} / {@code browser_fill_form} 里键入不会生效的字段 → 原因（C016 A）；查不了时为空。 */
    private Map<String, String> cannotFill(BrowserTools.Call call) {
        SnapshotIndex index = SnapshotIndex.of(bench.latest());
        Map<String, String> fields = new LinkedHashMap<>();
        if (BrowserToolPolicy.TYPE.equals(call.tool()) && call.args().get("target") instanceof String target) {
            fields.put(target, "textbox");
        } else if (call.args().get("fields") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && map.get("target") instanceof String target
                        && map.get("type") instanceof String type) {
                    fields.putIfAbsent(target, type);
                }
            }
        }
        fields.keySet().removeIf(target -> !index.contains(target));
        try {
            return browser.cannotFill(job(), fields);
        } catch (RuntimeException e) {
            return Map.of(); // 检查不了就照常发出，出错由那次调用报
        }
    }

    /** 「名字」原因；「名字」…… */
    private String reasons(BrowserTools.Call call, Map<String, String> skip) {
        SnapshotIndex index = SnapshotIndex.of(bench.latest());
        List<String> out = new ArrayList<>();
        skip.forEach((target, reason) -> {
            String label = call.fields().stream().filter(field -> target.equals(field.ref()))
                    .map(BrowserFillTools::fieldLabel).filter(Objects::nonNull).findFirst()
                    .orElse(index.find(target).map(SnapshotIndex.Node::name).filter(name -> !name.isBlank())
                            .orElse(target));
            out.add("「" + label + "」（" + target + "）" + reason);
        });
        return String.join("；", out);
    }

    /** 有 C 级字段时拒绝整次调用（不发给浏览器），报告里记为「用户亲填」；没有时为 null。 */
    private ToolOutcome classC(AgentRun.ToolCall call, BrowserTools.Call translated) {
        String tool = translated.tool();
        PageSnapshot latest = bench.latest();
        SnapshotIndex index = SnapshotIndex.of(latest);
        List<String> labels = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<FillEvent> events = new ArrayList<>();
        List<String> values = values(translated);
        Set<String> placeholders = bench.placeholders();
        for (int i = 0; i < translated.fields().size(); i++) {
            FieldIntent field = translated.fields().get(i);
            String value = i < values.size() ? values.get(i) : null;
            if (FieldClassGuard.check(index, field.ref(), field.label(), value, placeholders, personal::has)
                    instanceof GuardDecision.Handoff) {
                Placeholder.keyOf(value).filter(placeholders::contains).filter(key -> !personal.has(key))
                        .ifPresent(key -> missing.add(FieldClassGuard.missingValue(key)));
                String label = fieldLabel(field);
                if (label == null) {
                    label = index.find(field.ref()).map(SnapshotIndex.Node::name).filter(name -> !name.isBlank())
                            .orElse(field.ref() == null ? "（未命名字段）" : field.ref());
                }
                labels.add(label);
                bench.log(RunLog.Entry.guard(session(), bench.step(), tool, field.ref(),
                        Workbench.element(latest, field.ref()), "handoff", Workbench.page(latest),
                        "C 级字段由用户亲自填写"));
                events.add(new FillEvent.HandedOff(field, label, latest.title()));
            }
        }
        if (labels.isEmpty()) {
            return null;
        }
        return ToolOutcome.rejected(call, "被拒绝，这次调用没有执行：「" + String.join("」「", labels) + "」是 C 级字段，由用户亲自填写"
                + (missing.isEmpty() ? "" : "（" + String.join("；", missing) + "）") + "。"
                + "把其它字段去掉再填；最后用 ask_user 请用户在浏览器里填这些字段。").filled(events);
    }

    private ToolOutcome find(AgentRun.ToolCall call, BrowserTools.Call translated, BrowserToolPolicy.Context policy) {
        String found;
        try {
            found = browser.find(job(), translated.args(), policy);
        } catch (ToolNotAllowedException e) {
            return rejected(call, translated, null, e.getMessage(), null);
        } catch (BrowserSession.TabLost e) {
            return tabLost(call, e.getMessage());
        } catch (BrowserException.Disconnected | IllegalStateException e) {
            return disconnected(call, e.getMessage());
        } catch (BrowserException.Stalled e) {
            return stalled(call, e.getMessage());
        } catch (BrowserException.ToolFailed e) {
            return failed(call, translated.tool(), null, translated.args().toString(), false, e.getMessage());
        }
        String redacted = SnapshotRedactor.redact(found == null ? "" : found);
        bench.log(RunLog.Entry.toolCall(session(), bench.step(), translated.tool(), null, null, null, null,
                Workbench.page(bench.latest()), "ok"));
        return ToolOutcome.done(call, "找到的内容：\n" + redacted).observed(new ToolOutcome.BrowserAction(
                translated.tool(), null, translated.args().toString(), false, false,
                pageText(bench.latest()) + "\n" + redacted, null, null));
    }

    /**
     * 阶段一（匹配简历）尚未打开页面时，模型首次调用浏览器工具（C021）：自动打开岗位链接并取初始快照。
     * 若本次调用本身就是 {@code browser_snapshot} 或打开当前岗位链接的 {@code browser_navigate}，直接返回初始快照。
     */
    private EnsuredPage ensurePage(AgentRun.ToolCall call, BrowserTools.Call translated) {
        PageSnapshot latest;
        try {
            latest = start();
        } catch (BrowserException.Disconnected e) {
            return new EnsuredPage(disconnected(call, e.getMessage()), null);
        } catch (BrowserException.Stalled e) {
            return new EnsuredPage(stalled(call, e.getMessage()), null);
        } catch (BrowserException.ToolFailed e) {
            return new EnsuredPage(failed(call, translated.tool(), null, translated.args().toString(), false,
                    e.getMessage()), null);
        }
        bench.setLatest(latest);
        String baseline = latest == null ? null : pageText(latest);
        String tool = translated.tool();
        boolean sameNav = BrowserToolPolicy.NAVIGATE.equals(tool)
                && translated.args().get("url") instanceof String url
                && (url.equals(bench.run().url()) || (latest != null && url.equals(latest.url())));
        if (BrowserToolPolicy.SNAPSHOT.equals(tool) || sameNav) {
            bench.log(RunLog.Entry.toolCall(session(), bench.step(), tool, null, null, null, null,
                    Workbench.page(latest), "ok"));
            StringBuilder text = new StringBuilder("完成。系统已打开岗位页面并取了快照：");
            SubmitGuard.pageWarning(latest).ifPresent(warning -> text.append("\n系统提醒：").append(warning));
            return new EnsuredPage(ToolOutcome.done(call, text.toString()).withPage(latest)
                    .observed(new ToolOutcome.BrowserAction(null, null, null, false, false, null, baseline, null)),
                    baseline);
        }
        return new EnsuredPage(null, baseline);
    }

    /** {@link #ensurePage} 的结果：直接交回的结果（不用再发这次调用时为 null），和刚取的页面（比较的起点）。 */
    private record EnsuredPage(ToolOutcome outcome, String baseline) {
    }

    // ---- 标签页 ----

    /**
     * 模型的 {@code browser_tabs}：{@code list} 列出所有标签页（标出正在用的、它自己开的或用户转交的），
     * {@code select} 切过去并取快照；能碰哪些由提示词约束。参数不发给 MCP，由 {@link BrowserSession} 以系统身份去列、去切。
     */
    private ToolOutcome tabs(ToolInput input) {
        AgentRun.ToolCall call = input.call();
        Object action = input.args().get("action");
        try {
            if ("list".equals(action)) {
                List<BrowserSession.TabInfo> listed = browser.tabs(job());
                bench.log(RunLog.Entry.toolCall(session(), bench.step(), BrowserTools.TABS, null, null, null, null,
                        Workbench.page(bench.latest()), "ok"));
                return ToolOutcome.done(call, withHandover(listed.isEmpty() ? "现在没有标签页。"
                        : BrowserReadTools.TABS_HEADER + BrowserReadTools.tabLines(listed)));
            }
            if ("select".equals(action)) {
                if (!(input.args().get("index") instanceof Number number) || number.intValue() < 0) {
                    return ToolOutcome.failed(call, "失败：select 要给 browser_tabs list 列出的序号 index");
                }
                PageSnapshot before = bench.latest();
                PageSnapshot after = browser.select(job(), number.intValue());
                bench.setLatest(after);
                bench.log(RunLog.Entry.toolCall(session(), bench.step(), BrowserTools.TABS, null, null, null, null,
                        Workbench.page(after), before == null || !after.url().equals(before.url()) ? "page_changed" : "ok"));
                StringBuilder text = new StringBuilder("已切到标签页 ").append(number.intValue())
                        .append("。系统取了新的快照：");
                SubmitGuard.pageWarning(after).ifPresent(warning -> text.append("\n系统提醒：").append(warning));
                return ToolOutcome.done(call, text.toString()).withPage(after);
            }
            return ToolOutcome.failed(call, "失败：browser_tabs 的 action 只能是 list 或 select");
        } catch (IllegalArgumentException e) {
            return ToolOutcome.failed(call, "失败：" + e.getMessage() + "。");
        } catch (BrowserException.Disconnected e) {
            return disconnected(call, e.getMessage());
        } catch (BrowserException.Stalled e) {
            return stalled(call, e.getMessage());
        } catch (BrowserException.ToolFailed e) {
            return ToolOutcome.failed(call, "失败：" + e.getMessage());
        }
    }

    // ---- 结果 ----

    /** 正在用的标签页认不出了（或者还没有）：这次没有操作，原话交给模型，由它 list / select 或者重新打开岗位链接。 */
    private ToolOutcome tabLost(AgentRun.ToolCall call, String reason) {
        return ToolOutcome.failed(call, withHandover("失败：" + reason + "。"));
    }

    private ToolOutcome rejected(AgentRun.ToolCall call, BrowserTools.Call translated, String ref, String reason,
                                 String button) {
        String tool = translated.tool();
        bench.log(RunLog.Entry.guard(session(), bench.step(), tool, ref, Workbench.element(bench.latest(), ref),
                "reject", Workbench.page(bench.latest()), reason));
        ToolOutcome out = ToolOutcome.rejected(call, "被拒绝：" + reason).observed(new ToolOutcome.BrowserAction(tool,
                ref, translated.args().toString(), translated.write(), true, reason, null, null));
        return button != null && !button.isBlank() ? out.filled(List.of(new FillEvent.Refused(button.strip()))) : out;
    }

    private ToolOutcome failed(AgentRun.ToolCall call, String tool, String ref, String action, boolean write,
                               String message) {
        bench.log(RunLog.Entry.toolCall(session(), bench.step(), tool, ref, Workbench.element(bench.latest(), ref),
                null, null, Workbench.page(bench.latest()), "error"));
        return ToolOutcome.failed(call, "失败：" + message)
                .observed(new ToolOutcome.BrowserAction(tool, ref, action, write, true, message, null, null));
    }

    /**
     * 浏览器断开了：这次调用失败，运行结束（申请 Mode 断开 = 停下等用户说重连，agent-runtime.md 7.1）。断开本身记在
     * 全局的连接状态里（{@link BrowserSession#state()}）。
     */
    private static ToolOutcome disconnected(AgentRun.ToolCall call, String reason) {
        return ToolOutcome.failed(call, "失败：浏览器连接断开了。").ending(new Ending.Disconnected(reason));
    }

    /**
     * 浏览器这次没回应（MCP 没回话，连接还在）：只是这次调用失败，运行照常继续，由模型决定再试、换做法还是问用户
     * （C011-fix；不停下等用户）。
     */
    private static ToolOutcome stalled(AgentRun.ToolCall call, String reason) {
        return ToolOutcome.failed(call, "失败：" + reason + "。这次操作没有完成；可以等一下再试、取一次快照核对、换个做法，"
                + "或者用 browser_navigate 重新打开页面；一直没有回应时用 ask_user 请用户看一下 Chrome。");
    }

    // ---- 小工具 ----

    /** 交给打转提醒比较的页面：遮掉 C 级值以后的地址、标题、快照和弹出状态。 */
    public static String pageText(PageSnapshot page) {
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

    /**
     * 去掉 {@code refs} 这些字段之后的 {@code browser_fill_form}（C016 A）；一个都不剩、或者不是 fill_form 时为 null。
     * 字段的 {@link FieldIntent} 和字段一一对应，一起去掉。
     */
    private static BrowserTools.Call without(BrowserTools.Call call, Set<String> refs) {
        if (!BrowserToolPolicy.FILL_FORM.equals(call.tool()) || !(call.args().get("fields") instanceof List<?> list)) {
            return null;
        }
        List<Object> kept = new ArrayList<>();
        List<FieldIntent> intents = new ArrayList<>();
        int at = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                kept.add(item);
                continue;
            }
            FieldIntent intent = at < call.fields().size() ? call.fields().get(at) : null;
            at++;
            if (!refs.contains(map.get("target"))) {
                kept.add(item);
                if (intent != null) {
                    intents.add(intent);
                }
            }
        }
        if (intents.isEmpty()) {
            return null;
        }
        Map<String, Object> args = new LinkedHashMap<>(call.args());
        args.put("fields", kept);
        return new BrowserTools.Call(call.tool(), args, intents, call.click(), call.fileNumbers());
    }

    /** 每个字段填进去的值，顺序同 {@link BrowserTools.Call#fields()}。只在内存里用来判断和写报告。 */
    public static List<String> values(BrowserTools.Call call) {
        Map<String, Object> args = call.args();
        return switch (call.tool()) {
            case BrowserToolPolicy.TYPE -> List.of(String.valueOf(args.getOrDefault("text", "")));
            case BrowserToolPolicy.SET_DATE -> List.of(String.valueOf(args.getOrDefault("date", "")));
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
     * 发给浏览器 MCP 的参数：值正好是资料里登记过的占位符 {@code {KEY}}、personal.env 里有值的，换成真实值（C014-D）。
     * 只在这一次调用里用，不存、不记日志。C 级字段能不能填已经由 {@link FieldClassGuard} 判断过。
     */
    static Map<String, Object> withRealValues(BrowserTools.Call call, Function<String, Optional<String>> realValue) {
        UnaryOperator<Object> real = value -> value instanceof String text
                ? realValue.apply(text).<Object>map(found -> found).orElse(value) : value;
        Map<String, Object> args = new LinkedHashMap<>(call.args());
        switch (call.tool()) {
            case BrowserToolPolicy.TYPE -> args.computeIfPresent("text", (key, text) -> real.apply(text));
            case BrowserToolPolicy.SET_DATE -> args.computeIfPresent("date", (key, date) -> real.apply(date));
            case BrowserToolPolicy.SELECT_OPTION -> args.computeIfPresent("values", (key, values) ->
                    values instanceof List<?> list ? list.stream().map(real).toList() : values);
            case BrowserToolPolicy.FILL_FORM -> args.computeIfPresent("fields", (key, fields) ->
                    fields instanceof List<?> list ? list.stream().map(item -> {
                        if (!(item instanceof Map<?, ?> map) || !map.containsKey("value")) {
                            return item;
                        }
                        Map<Object, Object> field = new LinkedHashMap<>(map);
                        field.put("value", real.apply(map.get("value")));
                        return field;
                    }).toList() : fields);
            default -> {
            }
        }
        return args;
    }

    public static String fieldLabel(FieldIntent field) {
        return field.label() == null || field.label().isBlank() ? null : field.label().strip();
    }

    private static String targetOf(String argumentsJson) {
        try {
            return ToolInput.parse(argumentsJson).get("target") instanceof String target ? target : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

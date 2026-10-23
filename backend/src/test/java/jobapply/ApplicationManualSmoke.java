package jobapply;

import static jobapply.api.LocalApi.path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jobapply.api.LocalApi;
import jobapply.config.ModelRegistry;
import jobapply.domain.Actor;
import jobapply.domain.Fact;
import jobapply.domain.FactClass;
import jobapply.domain.Job;
import jobapply.domain.JobPosting;
import jobapply.domain.Transition;
import jobapply.harness.policies.SubmitWords;
import jobapply.persistence.EnvFile;
import jobapply.persistence.FactsFile;
import jobapply.persistence.JobRepository;
import jobapply.persistence.ModelSettingsFile;
import jobapply.persistence.RunLog;
import jobapply.persistence.SiteRegistry;
import jobapply.persistence.StorageLayout;
import jobapply.persistence.TestStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 手动冒烟：真实的 Chrome + Playwright 扩展 + {@code npx @playwright/mcp@0.0.83 --extension}，主模型用用户配置的
 * （读 {@code storage/settings/models.yaml}，Key 由程序从 {@code .env} 解析；没有时在冒烟应用的设置页里配置）。
 * 名字不以 Test / IT 结尾，所以 {@code mvn test} / {@code mvn verify} 不会运行它。由用户本人登录和操作网站，
 * 回复由用户（或在用户告知后由代理）用 curl 发送。
 *
 * <pre>
 * # 用户指定的测试网址
 * mvn -B test -Dtest=ApplicationManualSmoke -Dsurefire.failIfNoSpecifiedTests=false -Djobapply.smoke.url=&lt;测试网址&gt;
 * # 本地 HTML 夹具（C004-B「真实 LLM 测试与冒烟」第 2 步：multi-page、submit-labels、verification、file-slots）
 * mvn -B test -Dtest=ApplicationManualSmoke -Dsurefire.failIfNoSpecifiedTests=false -Djobapply.smoke.form=multi-page
 * </pre>
 * 夹具模式在 127.0.0.1 的随机端口上提供 {@code fixtures/forms/*.html}（虚构的表单），岗位链接就是那一页；
 * storage 里放一份虚构的资料。可选：{@code -Djobapply.smoke.port=8765}、{@code -Djobapply.smoke.npx=<npx 的绝对路径>}、
 * {@code -Djobapply.smoke.profile="Profile 1"}、{@code -Djobapply.check.storage=<读模型设置的 storage>}
 * （默认 {@code ../storage}）。storage 建在 {@code backend/target/smoke-*}（被 git 忽略）。
 * <p>
 * 夹具可以用逗号分隔多个：一个应用、一条浏览器连接、每个夹具一个 Job，依次跑（和真实使用一致，只在第一次连接时同意）。
 * 每个 Job 停在提交前（{@code ready}）或失败时结束，每个 Job 最多等 15 分钟。Agent 需要用户时在终端打印「要你做的事」。结束时检查：过程日志里没有点过提交类按钮；
 * {@code storage/cache/playwright-mcp/} 里没有残留。
 */
class ApplicationManualSmoke {

    private static final String PROJECT = "smoke";
    private static final Duration LIMIT = Duration.ofMinutes(15);
    private static final Pattern FORM_NAME = Pattern.compile("[a-z0-9-]+");

    @Test
    void applicationAgainstRealChrome() throws Exception {
        String url = System.getProperty("jobapply.smoke.url");
        String formList = System.getProperty("jobapply.smoke.form");
        List<String> formNames = formList == null || formList.isBlank() ? List.of()
                : Stream.of(formList.split(",")).map(String::strip).filter(name -> !name.isEmpty()).toList();
        boolean formMode = !formNames.isEmpty();
        assumeTrue(formMode || (url != null && !url.isBlank()),
                "需要 -Djobapply.smoke.url=<测试网址> 或 -Djobapply.smoke.form=<夹具名，可用逗号分隔多个，例如 multi-page>");
        int port = Integer.parseInt(System.getProperty("jobapply.smoke.port", "8765"));
        Path root = Path.of("target", "smoke-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
                .toAbsolutePath();

        HttpServer forms = null;
        List<String> urls = new ArrayList<>();
        if (formMode) {
            for (String form : formNames) {
                assumeTrue(FORM_NAME.matcher(form).matches()
                                && ApplicationManualSmoke.class.getResource("/fixtures/forms/" + form + ".html") != null,
                        "没有夹具 fixtures/forms/" + form + ".html");
            }
            forms = serveForms();
            for (String form : formNames) {
                urls.add("http://127.0.0.1:" + forms.getAddress().getPort() + "/" + form + ".html");
            }
        } else {
            urls.add(url);
        }

        TestStorage storage = TestStorage.create(root);
        storage.project(PROJECT, "live");
        if (formMode) {
            FactsFile.write(storage.layout.baseFacts(), "个人基础事实（虚构）", List.of(
                    Fact.of("contact.name", "林示例", FactClass.B, "user-confirmed", null),
                    Fact.of("contact.email", "demo@example.com", FactClass.B, "user-confirmed", null),
                    Fact.of("contact.phone", "13800000000", FactClass.B, "user-confirmed", null),
                    Fact.of("education.school", "示例大学", FactClass.A, "user-confirmed", null),
                    Fact.of("education.major", "计算机科学与技术", FactClass.A, "user-confirmed", null),
                    Fact.of("education.graduation", "2027-06", FactClass.A, "user-confirmed", null)));
        }
        String session = storage.session(PROJECT);
        // 一个应用、一条浏览器连接、多个 Job 依次跑：和真实使用一致（只在第一次连接时要用户在 Chrome 里同意）
        List<String> jobs = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            Job lead = storage.ledger().addLead(PROJECT, session, Actor.USER, new JobPosting("冒烟测试", null,
                    formMode ? "夹具 " + formNames.get(i) : "申请线程冒烟", null, null, List.of(), urls.get(i), null, null,
                    "user-link"), null);
            storage.ledger().transition(PROJECT, lead.id(), session, Actor.USER, new Transition.Convert());
            jobs.add(lead.id());
        }

        // 用命令行参数传：优先级高于 application.yml（builder 的 properties() 只是默认值，会被 yml 盖掉）
        List<String> args = new ArrayList<>(List.of("--server.port=" + port, "--jobapply.storage.root=" + root));
        String npx = System.getProperty("jobapply.smoke.npx");
        if (npx != null && !npx.isBlank()) {
            args.add("--jobapply.browser.mcp.command=" + npx);
        }
        String profile = System.getProperty("jobapply.smoke.profile");
        if (profile != null && !profile.isBlank()) {
            args.add("--jobapply.browser.mcp.profile-dir-name=" + profile);
        }

        SpringApplicationBuilder builder = new SpringApplicationBuilder(JobApplyApplication.class);
        String models = useConfiguredModels(builder);
        Map<String, Map<String, Object>> finalStates = new java.util.LinkedHashMap<>();
        try (ConfigurableApplicationContext ignored = builder.run(args.toArray(String[]::new))) {
            StringBuilder jobLines = new StringBuilder();
            for (int i = 0; i < jobs.size(); i++) {
                jobLines.append("job：").append(jobs.get(i)).append("    岗位链接：").append(urls.get(i)).append('\n');
            }
            String base = "http://127.0.0.1:" + port + path("<job>");
            System.out.printf("""

                    ======== 申请线程冒烟 ========
                    storage：%s
                    主模型：%s
                    session：%s
                    %s
                    1. 开始一个 Job（第一次连接时，在 Chrome 里同意连接）：
                       curl -s -X POST %s -H 'Content-Type: application/json' -d '{"project":"%s","session":"%s"}'
                    2. Agent 需要你时，下面会打印「要你做的事」。做完后回复：
                       curl -s -X POST %s/reply -H 'Content-Type: application/json' -d '{"session":"%s","text":"好了"}'
                       回答问题：加 "answers":{"q1":"…"}；选文件：加 "files":["<原件键>"]（先 curl -s -F file=@<文件> http://127.0.0.1:%d/api/assets）
                    3. 停止：curl -s -X POST %s/stop    查状态：curl -s %s    报告：curl -s %s/report
                    每个 Job 停在提交前（ready）或失败时算结束；全部结束或超时（每个 Job %d 分钟）后退出。系统不会点提交。
                    ==============================

                    """, root, models, session, jobLines.toString().strip(), base, PROJECT, session, base, session, port,
                    base, base, base, LIMIT.toMinutes());

            LocalApi api = new LocalApi(port);
            Instant deadline = Instant.now().plus(LIMIT.multipliedBy(jobs.size()));
            Map<String, String> last = new java.util.HashMap<>();
            while (Instant.now().isBefore(deadline) && finalStates.size() < jobs.size()) {
                for (String job : jobs) {
                    if (finalStates.containsKey(job)) {
                        continue;
                    }
                    LocalApi.Response response = api.get(path(job));
                    if (response.status() != 200) {
                        continue;
                    }
                    Map<String, Object> state = response.json();
                    String line = state.get("status") + " " + state.get("message") + " " + state.get("questions") + " "
                            + state.get("fileSlots");
                    if (!Objects.equals(line, last.get(job))) {
                        last.put(job, line);
                        System.out.println("[" + job + "] " + state.get("status") + "  计划：" + state.get("plan"));
                        if (state.get("message") != null) {
                            System.out.println("  要你做的事：" + state.get("message"));
                        }
                        if (state.get("questions") instanceof List<?> questions && !questions.isEmpty()) {
                            System.out.println("  问题：" + questions);
                        }
                        if (state.get("fileSlots") instanceof List<?> slots && !slots.isEmpty()) {
                            System.out.println("  上传位置：" + slots);
                        }
                        if (state.get("error") != null) {
                            System.out.println("  出错：" + state.get("error"));
                        }
                    }
                    if (Set.of("ready", "failed").contains(String.valueOf(state.get("status")))) {
                        finalStates.put(job, state);
                    }
                }
                Thread.sleep(2000);
            }

            for (String job : jobs) {
                Map<String, Object> state = finalStates.getOrDefault(job, Map.of());
                List<Map<String, Object>> clicks = new RunLog(storage.layout, storage.clock).read(PROJECT, job).stream()
                        .filter(entry -> "tool_call".equals(entry.get("kind")) && "browser_click".equals(entry.get("tool")))
                        .toList();
                System.out.printf("[结果 %s] status=%s prep_state=%s 点击 %d 次%n", job, state.get("status"),
                        new JobRepository(storage.layout).load(PROJECT, job).status().label(), clicks.size());
                assertThat(clicks).as("过程日志里没有点过提交类按钮").noneMatch(entry -> entry.get("element") instanceof Map<?, ?> element
                        && SubmitWords.isHard(String.valueOf(element.get("name"))));
            }
            System.out.println("[结果] sites.md = " + new SiteRegistry(storage.layout, storage.clock).rows());
        } finally {
            if (forms != null) {
                forms.stop(0);
            }
        }
        // 连接打开期间 MCP 会往输出目录写 console 日志；关闭连接时清空，所以应用关闭以后再检查
        assertThat(leftovers(storage.layout.mcpOutputDir())).as("storage/cache/playwright-mcp/ 里没有残留").isEmpty();
        assertThat(finalStates).as("每个 Job 都结束了").hasSize(jobs.size());
        assertThat(finalStates.values()).as("没有失败的 Job").noneMatch(state -> "failed".equals(state.get("status")));
    }

    /**
     * 有用户配置的模型设置时，冒烟应用的主模型从那里取（只读；Key 由程序解析，不打印）：在配置类注册完 Bean 以后，
     * 把 {@code modelRegistry} 换成读那份设置的实例。返回打印用的说明（不含 Key）。
     */
    private static String useConfiguredModels(SpringApplicationBuilder builder) {
        Path settingsRoot = Path.of(System.getProperty("jobapply.check.storage", "../storage")).toAbsolutePath()
                .normalize();
        StorageLayout settings = new StorageLayout(settingsRoot);
        if (!Files.exists(settings.modelsYaml())) {
            return "没有 " + settings.modelsYaml() + "：请在冒烟应用的设置页分配主模型";
        }
        ModelRegistry registry = new ModelRegistry(new ModelSettingsFile(settings, Clock.systemDefaultZone()),
                new EnvFile(settings.envFile()));
        builder.initializers(context -> context.addBeanFactoryPostProcessor(beanFactory -> {
            ((BeanDefinitionRegistry) beanFactory).removeBeanDefinition("modelRegistry");
            beanFactory.registerSingleton("modelRegistry", registry);
        }));
        return "读 " + settings.modelsYaml() + " 的分配";
    }

    /** 在 127.0.0.1 的随机端口上提供 {@code fixtures/forms/<name>.html}；别的路径 404。 */
    private static HttpServer serveForms() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ApplicationManualSmoke::serveForm);
        server.start();
        return server;
    }

    private static void serveForm(HttpExchange exchange) throws IOException {
        String name = exchange.getRequestURI().getPath().replaceFirst("^/", "");
        byte[] body = null;
        if (name.endsWith(".html") && FORM_NAME.matcher(name.substring(0, name.length() - 5)).matches()) {
            try (InputStream in = ApplicationManualSmoke.class.getResourceAsStream("/fixtures/forms/" + name)) {
                body = in == null ? null : in.readAllBytes();
            }
        }
        if (body == null) {
            exchange.sendResponseHeaders(404, -1);
        } else {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private static List<Path> leftovers(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).filter(file -> !Set.of(".gitkeep").contains(
                    file.getFileName().toString())).toList();
        }
    }
}

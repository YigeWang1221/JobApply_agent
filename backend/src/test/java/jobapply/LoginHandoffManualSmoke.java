package jobapply;

import static jobapply.api.LocalApi.path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jobapply.api.LocalApi;
import jobapply.domain.Actor;
import jobapply.domain.Job;
import jobapply.domain.JobPosting;
import jobapply.domain.Transition;
import jobapply.persistence.JobRepository;
import jobapply.persistence.SiteRegistry;
import jobapply.persistence.TestStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 手动冒烟：真实的 Chrome + Playwright 扩展 + {@code npx @playwright/mcp@0.0.83 --extension}。
 * 名字不以 Test / IT 结尾，所以 {@code mvn test} / {@code mvn verify} 不会运行它。由用户本人登录和操作网站。
 *
 * <pre>
 * mvn -B test -Dtest=LoginHandoffManualSmoke -Dsurefire.failIfNoSpecifiedTests=false -Djobapply.smoke.url=&lt;测试网址&gt;
 * </pre>
 * 可选：{@code -Djobapply.smoke.port=8765}、{@code -Djobapply.smoke.npx=<npx 的绝对路径>}、
 * {@code -Djobapply.smoke.profile="Profile 1"}。storage 建在 {@code backend/target/smoke-*}（被 git 忽略）。
 */
class LoginHandoffManualSmoke {

    private static final String PROJECT = "smoke";
    private static final Duration LIMIT = Duration.ofMinutes(15);

    @Test
    void loginHandoffAgainstRealChrome() throws InterruptedException {
        String url = System.getProperty("jobapply.smoke.url");
        assumeTrue(url != null && !url.isBlank(), "需要 -Djobapply.smoke.url=<测试网址>");
        int port = Integer.parseInt(System.getProperty("jobapply.smoke.port", "8765"));
        Path root = Path.of("target", "smoke-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
                .toAbsolutePath();

        TestStorage storage = TestStorage.create(root, true);
        storage.project(PROJECT, "live");
        String session = storage.session(PROJECT);
        Job lead = storage.ledger().addLead(PROJECT, session, Actor.USER, new JobPosting("冒烟测试", null,
                "登录接力冒烟", null, null, List.of(), url, null, null, "user-link"), null);
        storage.ledger().transition(PROJECT, lead.id(), session, Actor.USER, new Transition.Convert());
        String job = lead.id();

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

        try (ConfigurableApplicationContext ignored =
                new SpringApplicationBuilder(JobApplyApplication.class).run(args.toArray(String[]::new))) {
            String base = "http://127.0.0.1:" + port + path(job);
            System.out.printf("""

                    ======== 登录接力冒烟 ========
                    storage：%s
                    job：%s    session：%s
                    1. 开始（扩展的连接页弹出后 30 秒内点同意）：
                       curl -s -X POST %s -H 'Content-Type: application/json' -d '{"project":"%s","session":"%s","accountHint":""}'
                    2. 查状态：curl -s %s
                    3. 发信号（logged_in / identity_confirmed / wrong_account / reconnected；logged_in 可加 "override":true）：
                       curl -s -X POST %s/signal -H 'Content-Type: application/json' -d '{"session":"%s","signal":"logged_in"}'
                    断开测试：在扩展里断开连接，看到 waitingFor=reconnect 后发 reconnected，再同意一次。
                    状态变成 ready 后自动结束；最多等 %d 分钟。
                    ==============================

                    """, root, job, session, base, PROJECT, session, base, base, session, LIMIT.toMinutes());

            LocalApi api = new LocalApi(port);
            Instant deadline = Instant.now().plus(LIMIT);
            String last = null;
            Map<String, Object> state = Map.of();
            while (Instant.now().isBefore(deadline)) {
                LocalApi.Response response = api.get(path(job));
                String line = response.status() + " " + response.body();
                if (!Objects.equals(line, last)) {
                    System.out.println("[状态] " + line);
                    last = line;
                }
                if (response.status() == 200) {
                    state = response.json();
                    if ("ready".equals(state.get("status")) || "failed".equals(state.get("status"))) {
                        break;
                    }
                }
                Thread.sleep(2000);
            }

            System.out.println("[结果] prep_state = " + new JobRepository(storage.layout).load(PROJECT, job).status().label());
            System.out.println("[结果] sites.md = " + new SiteRegistry(storage.layout, storage.clock).rows());
            assertThat(state.get("status")).isEqualTo("ready");
        }
    }
}

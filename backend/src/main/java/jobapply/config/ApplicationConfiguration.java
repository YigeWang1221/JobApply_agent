package jobapply.config;

import java.nio.file.Path;
import java.time.Clock;
import jobapply.persistence.EnvFile;
import jobapply.persistence.PersonalEnv;
import jobapply.persistence.StorageLayout;
import jobapply.tools.BrowserSession;
import jobapply.tools.WebFetch;
import jobapply.tools.WebSearch;
import jobapply.workflow.ApplicationRunner;
import jobapply.workflow.FollowupRunner;
import jobapply.workflow.RuntimeEvents;
import jobapply.workflow.SessionRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 申请线程用到的 Bean。浏览器连接在第一次调用接口时才建立，应用启动时不连接 Chrome、不写 storage。
 * 主模型在 Agent 循环用到时才从 {@link ModelRegistry} 取，所以启动时可以还没有配置；没有配置时申请线程的状态里会显示
 * 「还没有配置主模型」。对话 Agent（{@link SessionRunner}）同样每次运行时才取主模型。
 */
@Configuration
@EnableConfigurationProperties({StorageProperties.class, BrowserMcpProperties.class, HarnessProperties.class,
        SearchProperties.class, WebProperties.class})
public class ApplicationConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

    /** storage 根目录从哪来（命令行 / 环境变量 → app.yaml → 默认，C017 第 3 节）；启动时定下，换位置要重启。 */
    @Bean
    StorageLocation storageLocation(ConfigurableEnvironment env, StorageProperties properties) {
        return StorageLocation.resolve(env, properties);
    }

    @Bean
    StorageLayout storageLayout(StorageLocation location) {
        return new StorageLayout(location.root());
    }

    @Bean(destroyMethod = "close")
    BrowserSession browserSession(BrowserMcpProperties properties, StorageLayout layout, StorageLocation location) {
        // 启动参数里的脚本会写进 storage/cache；还没选数据位置时（C017）先写到临时目录，不在默认位置建文件夹。选完会重启
        StorageLayout scripts = location.needsChoice()
                ? new StorageLayout(Path.of(System.getProperty("java.io.tmpdir"), "jobapply-unchosen")) : layout;
        // personal.env 的真实值在快照交出去之前换回占位符（C014-D）
        return new BrowserSession(properties.toLaunch(scripts), new PersonalEnv(layout)::values);
    }

    /** 应用级事件日志（C006-fix）：两个 Runner、台账和 {@code GET /api/events} 共用一个。 */
    @Bean
    RuntimeEvents runtimeEvents(Clock clock, BrowserSession browser) {
        RuntimeEvents events = new RuntimeEvents(clock);
        // 浏览器连接状态变了发全局事件 browser（C006-fix-browser 第 1 节），界面顶部的状态条跟着它
        browser.onChange(events::browser);
        return events;
    }

    @Bean(destroyMethod = "close")
    ApplicationRunner applicationRunner(StorageLayout layout, Clock clock, BrowserSession browser,
                                        ModelRegistry models, HarnessProperties limits, RuntimeEvents events) {
        return new ApplicationRunner(layout, clock, browser, models::main, limits, events);
    }

    @Bean
    WebSearch webSearch(SearchProperties properties, StorageLayout layout) {
        EnvFile env = new EnvFile(layout.envFile());
        return new WebSearch(properties.searxngUrl(), () -> env.get(SearchProperties.BRAVE_KEY));
    }

    @Bean
    WebFetch webFetch(StorageLayout layout, WebProperties web) {
        return new WebFetch(layout.cacheDir().resolve("web"), "cache/web", web.activeBlocklist());
    }

    @Bean(destroyMethod = "close")
    SessionRunner sessionRunner(StorageLayout layout, Clock clock, BrowserSession browser, ModelRegistry models,
                                WebSearch search, WebFetch fetch, ApplicationRunner applications) {
        return new SessionRunner(layout, clock, browser, models::main, search, fetch, applications);
    }

    /** 工作台的后续段（C022）：和申请线程共用事件日志和工具调用上限。 */
    @Bean(destroyMethod = "close")
    FollowupRunner followupRunner(StorageLayout layout, Clock clock, BrowserSession browser, ModelRegistry models,
                                  WebFetch fetch, ApplicationRunner applications) {
        return new FollowupRunner(layout, clock, browser, models::main, fetch, applications);
    }
}

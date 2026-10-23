package jobapply.config;

import java.time.Clock;
import jobapply.persistence.EnvFile;
import jobapply.persistence.StorageLayout;
import jobapply.tools.BrowserSession;
import jobapply.tools.WebFetch;
import jobapply.tools.WebSearch;
import jobapply.workflow.ApplicationRunner;
import jobapply.workflow.SessionRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean
    StorageLayout storageLayout(StorageProperties properties) {
        return new StorageLayout(properties.rootPath());
    }

    @Bean(destroyMethod = "close")
    BrowserSession browserSession(BrowserMcpProperties properties, StorageLayout layout) {
        return new BrowserSession(properties.toLaunch(layout));
    }

    @Bean(destroyMethod = "close")
    ApplicationRunner applicationRunner(StorageLayout layout, Clock clock, BrowserSession browser,
                                        ModelRegistry models, HarnessProperties limits) {
        return new ApplicationRunner(layout, clock, browser, models::main, limits);
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
}

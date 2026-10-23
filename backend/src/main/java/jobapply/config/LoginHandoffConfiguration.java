package jobapply.config;

import java.time.Clock;
import jobapply.persistence.StorageLayout;
import jobapply.tools.BrowserSession;
import jobapply.workflow.graphs.LoginHandoffRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 登录接力用到的 Bean。浏览器连接和 Checkpoint 都在第一次调用接口时才建立，应用启动时不连接 Chrome、不写 storage。
 */
@Configuration
@EnableConfigurationProperties({StorageProperties.class, BrowserMcpProperties.class})
public class LoginHandoffConfiguration {

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
    LoginHandoffRunner loginHandoffRunner(StorageLayout layout, Clock clock, BrowserSession browser) {
        return new LoginHandoffRunner(layout, clock, browser);
    }
}

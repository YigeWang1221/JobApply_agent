package jobapply.config;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * macOS App 里（{@code jobapply.app.exit-with-shell}，C017-fix）：后端的标准输入是 Swift 外壳拿着的管道。外壳不管怎么退出
 * （包括强制退出），系统都会关掉写端，这里读到结尾就关掉后端，不留下占着端口的进程。
 */
@Component
@ConditionalOnProperty(name = "jobapply.app.exit-with-shell", havingValue = "true")
class ShellWatch {

    private static final Logger log = LoggerFactory.getLogger(ShellWatch.class);

    ShellWatch(ConfigurableApplicationContext context) {
        Thread watcher = new Thread(() -> {
            try {
                while (System.in.read() != -1) {
                    // 外壳不往管道里写东西，只等它关上
                }
            } catch (IOException e) {
                log.info("读外壳管道出错，当作外壳已退出：{}", e.getMessage());
            }
            log.info("外壳已退出，关闭后端");
            System.exit(SpringApplication.exit(context));
        }, "shell-watch");
        watcher.setDaemon(true);
        watcher.start();
    }
}

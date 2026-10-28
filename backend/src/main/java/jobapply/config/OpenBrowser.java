package jobapply.config;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Windows / Linux 桌面版（{@code jobapply.app.open-browser=true}）在后端启动就绪后自动调用系统默认浏览器打开应用。
 * macOS 原生外壳自带 WKWebView 窗口并自行管理展示与生命周期，不用该 Bean。
 */
@Component
@ConditionalOnProperty(name = "jobapply.app.open-browser", havingValue = "true")
public class OpenBrowser {

    private static final Logger log = LoggerFactory.getLogger(OpenBrowser.class);

    private final int port;

    public OpenBrowser(@Value("${server.port:8088}") int port) {
        this.port = port;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        String url = "http://127.0.0.1:" + port + "/";
        try {
            if (GraphicsEnvironment.isHeadless()) {
                log.info("当前处于无头环境，不自动打开浏览器：{}", url);
                return;
            }
            if (!Desktop.isDesktopSupported()) {
                log.info("当前桌面环境不支持 Desktop API，请手动在浏览器中打开：{}", url);
                return;
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                log.info("当前桌面环境不支持调用默认浏览器打开网页，请手动在浏览器中打开：{}", url);
                return;
            }
            desktop.browse(URI.create(url));
            log.info("桌面版后端已就绪，已在默认浏览器中打开：{}", url);
        } catch (Exception e) {
            log.info("尝试自动打开浏览器失败，请手动在浏览器中打开：{}（原因：{}）", url, e.getMessage());
        }
    }
}

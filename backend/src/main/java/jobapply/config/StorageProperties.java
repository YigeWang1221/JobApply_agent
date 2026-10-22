package jobapply.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code jobapply.storage.root}：storage 根目录，相对路径按启动时的工作目录解析。应用启动时不初始化，也不写入。
 * 用 String 绑定，再自己转成 Path：Web 应用里 Spring 会把字符串当成 Servlet 资源路径来转换 Path，
 * {@code ../storage} 这样的相对路径会转换失败。
 */
@ConfigurationProperties("jobapply.storage")
public record StorageProperties(String root) {

    public Path rootPath() {
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("jobapply.storage.root 不能为空");
        }
        return Path.of(root).toAbsolutePath().normalize();
    }
}

package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * {@code storage/settings/tool-calls.yaml}：设置页改过的工具调用上限（C018），0 = 不限。没有这个文件时用
 * {@code application.yml} 的 {@code jobapply.harness.tool-calls-per-run} / {@code tool-calls-per-message}。不含密钥。原子写入。
 */
public final class ToolCallsFile {

    /** 申请 Agent 一次运行、对话 Agent 每条消息最多几次工具调用；0 = 不限。 */
    public record Limits(int perRun, int perMessage) {
    }

    private final Path file;

    public ToolCallsFile(StorageLayout layout) {
        this.file = layout.toolCallsYaml();
    }

    /** 文件里的值；没有文件时为空。内容不对时抛 {@link StorageFormatException}。 */
    public Optional<Limits> read() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Object loaded;
        try {
            loaded = FrontmatterFile.yaml().load(AtomicFiles.read(file));
        } catch (YAMLException e) {
            throw new StorageFormatException(file.toString(), "不是合法的 YAML：" + e.getMessage());
        }
        if (!(loaded instanceof Map<?, ?> map)
                || !(map.get("tool_calls_per_run") instanceof Integer perRun)
                || !(map.get("tool_calls_per_message") instanceof Integer perMessage)) {
            throw new StorageFormatException(file.toString(), "需要整数 tool_calls_per_run 和 tool_calls_per_message");
        }
        return Optional.of(new Limits(perRun, perMessage));
    }

    public void write(Limits limits) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tool_calls_per_run", limits.perRun());
        root.put("tool_calls_per_message", limits.perMessage());
        AtomicFiles.write(file, "# 工具调用上限（设置页「工具调用上限」写入），0 = 不限。删掉这个文件就回到 application.yml 的值\n"
                + FrontmatterFile.yaml().dump(root));
    }

    public Path path() {
        return file;
    }
}

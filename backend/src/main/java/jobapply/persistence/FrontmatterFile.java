package jobapply.persistence;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * 「YAML frontmatter + Markdown 正文」文件。读写往返时正文、键顺序和未识别的键都不变。
 * 时间不会被解析成 {@code java.util.Date}，保持原字符串（含时区偏移）。
 */
public final class FrontmatterFile {

    private static final String FENCE = "---\n";

    public record Document(Map<String, Object> frontmatter, String body) {

        public Document {
            frontmatter = new LinkedHashMap<>(frontmatter);
            body = body == null ? "" : body;
        }
    }

    private FrontmatterFile() {
    }

    public static Document read(Path path) {
        return parse(AtomicFiles.read(path), path.toString());
    }

    public static void write(Path path, Document document) {
        AtomicFiles.write(path, render(document));
    }

    public static Document parse(String content, String source) {
        String text = content.startsWith("﻿") ? content.substring(1) : content;
        if (!text.startsWith(FENCE)) {
            return new Document(Map.of(), text);
        }
        int close = text.indexOf("\n" + FENCE, FENCE.length() - 1);
        int bodyStart;
        if (close >= 0) {
            bodyStart = close + 1 + FENCE.length();
        } else if (text.endsWith("\n---")) {
            close = text.length() - 4;
            bodyStart = text.length();
        } else {
            throw new StorageFormatException(source, "frontmatter 缺少结束的 ---");
        }
        String yamlText = close < FENCE.length() ? "" : text.substring(FENCE.length(), close + 1);
        return new Document(load(yamlText, source), text.substring(bodyStart));
    }

    public static String render(Document document) {
        String yamlText = document.frontmatter().isEmpty() ? "" : yaml().dump(document.frontmatter());
        return FENCE + yamlText + FENCE + document.body();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String yamlText, String source) {
        Object loaded;
        try {
            loaded = yaml().load(yamlText);
        } catch (YAMLException e) {
            throw new StorageFormatException(source, "frontmatter 不是合法的 YAML", e);
        }
        if (loaded == null) {
            return Map.of();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new StorageFormatException(source, "frontmatter 必须是键值对");
        }
        for (Object key : map.keySet()) {
            if (!(key instanceof String)) {
                throw new StorageFormatException(source, "frontmatter 的键必须是字符串：" + key);
            }
        }
        return (Map<String, Object>) map;
    }

    private static Yaml yaml() {
        LoaderOptions loaderOptions = new LoaderOptions();
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setIndent(2);
        dumperOptions.setIndicatorIndent(2);
        dumperOptions.setIndentWithIndicator(true);
        dumperOptions.setAllowUnicode(true);
        dumperOptions.setSplitLines(false);
        return new Yaml(new SafeConstructor(loaderOptions), new Representer(dumperOptions), dumperOptions,
                loaderOptions, new NoTimestampResolver());
    }

    /** 与 SnakeYAML 默认规则相同，只是不识别时间戳。 */
    private static final class NoTimestampResolver extends Resolver {

        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, BOOL, "yYnNtTfFoO", 10);
            addImplicitResolver(Tag.INT, INT, "-+0123456789");
            addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
            addImplicitResolver(Tag.MERGE, MERGE, "<", 10);
            addImplicitResolver(Tag.NULL, NULL, "~nN\0", 10);
            addImplicitResolver(Tag.NULL, EMPTY, null, 10);
            addImplicitResolver(Tag.YAML, YAML, "!&*", 10);
        }
    }
}

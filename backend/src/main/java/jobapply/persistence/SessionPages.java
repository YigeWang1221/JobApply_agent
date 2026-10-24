package jobapply.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话 Agent 读过的网页（C006-fix 第 6 节）：每读一页，把开头存进 {@code sessions/<id>/pages/<hash>.txt}，
 * {@code pages/index.tsv} 一行一条「规范化网址 → 文件名」。下一条消息、重定向之后的网址都能找回来，
 * {@code add_lead} 用它作 JD 快照（参考 deepagents 的 {@code _message_eviction}：大结果落盘、按路径引用）。
 * 同一个网址再读时覆盖（以最新的为准）。
 */
public final class SessionPages {

    /** 每页最多存这么多字（就是 JD 快照的长度）。 */
    public static final int MAX_CHARS = 12_000;
    private static final String INDEX = "index.tsv";
    private static final Pattern FILE = Pattern.compile("(?:^|.*/)([0-9a-f]{24}\\.txt)$");

    private final Path dir;

    public SessionPages(Path dir) {
        this.dir = dir;
    }

    /**
     * 存一页。{@code urls} 是这一页的网址（请求的和重定向之后的），都指向同一个文件；文件名按第一个网址算。
     *
     * @return 文件名（{@code <hash>.txt}）
     */
    public synchronized String save(String text, String... urls) {
        List<String> keys = Arrays.stream(urls).filter(u -> u != null && !u.isBlank())
                .map(SessionPages::normalize).distinct().toList();
        if (keys.isEmpty() || text == null) {
            return null;
        }
        String name = hash(keys.getFirst()) + ".txt";
        String kept = text.substring(0, Math.min(MAX_CHARS, text.length()));
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name), kept, StandardCharsets.UTF_8);
            StringBuilder lines = new StringBuilder();
            for (String key : keys) {
                lines.append(key).append('\t').append(name).append('\n');
            }
            Files.writeString(dir.resolve(INDEX), lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("存不下读过的网页：" + name, e);
        }
        return name;
    }

    /**
     * 找一页：{@code ref} 是网址（按规范化后比较）或者存下的文件名（{@code <hash>.txt}，可以带 {@code pages/} 这类前缀）。
     */
    public synchronized Optional<String> find(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        String value = ref.strip();
        Matcher file = FILE.matcher(value);
        if (file.matches()) {
            return read(file.group(1));
        }
        String key = normalize(value);
        List<String> index;
        try {
            index = Files.readAllLines(dir.resolve(INDEX), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("读不了读过的网页目录", e);
        }
        for (int i = index.size() - 1; i >= 0; i--) {
            String[] parts = index.get(i).split("\t", 2);
            if (parts.length == 2 && parts[0].equals(key)) {
                return read(parts[1].strip());
            }
        }
        return Optional.empty();
    }

    private Optional<String> read(String name) {
        try {
            return Optional.of(Files.readString(dir.resolve(name), StandardCharsets.UTF_8));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("读不了读过的网页：" + name, e);
        }
    }

    /**
     * 比较用的网址：scheme、host 小写，去掉默认端口、{@code #…}、{@code utm_*} 参数和结尾的 {@code /}。不是网址时原样（去空白）。
     */
    public static String normalize(String url) {
        String value = url.strip();
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return value;
        }
        if (uri.getScheme() == null || uri.getRawAuthority() == null) {
            return value;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? uri.getRawAuthority().toLowerCase(Locale.ROOT)
                : uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1 || port == 80 && scheme.equals("http") || port == 443 && scheme.equals("https");
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        StringBuilder query = new StringBuilder();
        if (uri.getRawQuery() != null) {
            for (String part : uri.getRawQuery().split("&")) {
                if (part.isEmpty() || part.toLowerCase(Locale.ROOT).startsWith("utm_")) {
                    continue;
                }
                query.append(query.isEmpty() ? "?" : "&").append(part);
            }
        }
        return scheme + "://" + host + (defaultPort ? "" : ":" + port) + path + query;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

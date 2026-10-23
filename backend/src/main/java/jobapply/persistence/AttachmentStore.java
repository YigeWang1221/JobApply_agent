package jobapply.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import jobapply.domain.AgentRun;
import jobapply.tools.SecretFileRules;

/**
 * 用户附在消息里的文件（C006 附件）：一个附件一个目录 {@code <dir>/<id>/<原文件名>}，文件名保持原样（上传到表单时网站看到的名字）。
 * 申请的放在 {@code uploads/<job-id>/attachments/}，Session 的放在 {@code sessions/<id>/attachments/}；不进资料库。
 */
public final class AttachmentStore {

    public static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Pattern ID = Pattern.compile("[0-9a-f]{12}");
    private static final Map<String, String> IMAGES = Map.of("png", "image/png", "jpg", "image/jpeg",
            "jpeg", "image/jpeg", "webp", "image/webp", "gif", "image/gif");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 存好的一个附件。 */
    public record Stored(String id, String name, long size, String contentType, Path path) {

        public boolean image() {
            return isImage(contentType);
        }

        /** 文件内容的 SHA-256（写进填写报告）。 */
        public String sha256() {
            try {
                return AssetRegistry.sha256(Files.readAllBytes(path));
            } catch (IOException e) {
                throw new UncheckedIOException("读不到附件：" + name, e);
            }
        }

        public AgentRun.Attachment attachment(Integer file) {
            return new AgentRun.Attachment(id, name, contentType, file);
        }
    }

    private final Path dir;

    public AttachmentStore(Path dir) {
        this.dir = dir;
    }

    /** 存一个附件。文件名像密钥文件、空文件、超过 20 MB 时抛出 {@link IllegalArgumentException}。 */
    public synchronized Stored save(String originalName, byte[] content) {
        String name = fileName(originalName);
        if (SecretFileRules.isSecret(name)) {
            throw new IllegalArgumentException("「" + name + "」看起来是密钥或凭证文件，不能作为附件");
        }
        if (content.length == 0) {
            throw new IllegalArgumentException("「" + name + "」是空文件");
        }
        if (content.length > MAX_BYTES) {
            throw new IllegalArgumentException("文件超过 20MB");
        }
        String id;
        do {
            byte[] random = new byte[6];
            RANDOM.nextBytes(random);
            id = HexFormat.of().formatHex(random);
        } while (Files.exists(dir.resolve(id)));
        Path file = dir.resolve(id).resolve(name);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException("保存附件失败：" + name, e);
        }
        return new Stored(id, name, content.length, contentType(name), file);
    }

    /** 按编号找附件；编号不合法或不存在时为空。 */
    public Optional<Stored> find(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            return Optional.empty();
        }
        Path folder = dir.resolve(id);
        if (!Files.isDirectory(folder)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(folder)) {
            List<Path> found = files.filter(Files::isRegularFile).toList();
            if (found.size() != 1) {
                return Optional.empty();
            }
            Path file = found.getFirst();
            String name = file.getFileName().toString();
            return Optional.of(new Stored(id, name, Files.size(file), contentType(name), file));
        } catch (IOException e) {
            throw new UncheckedIOException("读取附件失败：" + id, e);
        }
    }

    /** 按编号找附件，找不到时抛出 {@link IllegalArgumentException}（请求里写了不存在的附件）。 */
    public Stored require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("没有这个附件：" + id));
    }

    public static boolean isImage(String contentType) {
        return contentType != null && IMAGES.containsValue(contentType);
    }

    /** 按扩展名判断类型：四种图片自己判断，其它的问 JDK 的类型表，不认识的是 {@code application/octet-stream}。 */
    static String contentType(String name) {
        int dot = name.lastIndexOf('.');
        String image = dot < 0 ? null : IMAGES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (image != null) {
            return image;
        }
        String guessed = URLConnection.guessContentTypeFromName(name);
        return guessed == null ? "application/octet-stream" : guessed;
    }

    /** 只留文件名最后一段，去掉控制字符；什么都不剩时叫 {@code file}。 */
    private static String fileName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            return "file";
        }
        return name.length() > 150 ? name.substring(name.length() - 150) : name;
    }
}

package jobapply.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ChatGPT 登录的本地文件（C028）。
 * <ul>
 *   <li>{@code chatgpt-auth.json}：令牌，权限 600，先写临时文件再原子改名。按 .env 的规矩处理：内容不进日志、不返回给页面。</li>
 *   <li>{@code chatgpt-client.json}：签发的 client_id 和本机的 ext_agent_host_id。不是密钥，退出登录后保留，
 *       重新授权时复用（官方 profiles-and-sessions：退出再登录不新建 client）。</li>
 * </ul>
 */
public final class ChatGptTokenStore {

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * 一份登录。{@link #toString()} 不带令牌。
     *
     * @param expiresAt access token 的过期时间（epoch 秒）
     */
    public record Tokens(String clientId, String accessToken, String refreshToken, long expiresAt, String scope,
                         String email, String subject) {

        @Override
        public String toString() {
            return "Tokens[clientId=" + clientId + ", accessToken=****, refreshToken=****, expiresAt=" + expiresAt
                    + ", scope=" + scope + ", email=" + (email == null ? null : "****") + "]";
        }
    }

    /** 本机的注册信息：clientId 第一次登录前为 null。 */
    public record Registration(String clientId, String hostId) {
    }

    private final Path tokenFile;
    private final Path clientFile;

    public ChatGptTokenStore(StorageLayout layout) {
        this(layout.chatGptAuthFile(), layout.chatGptClientFile());
    }

    ChatGptTokenStore(Path tokenFile, Path clientFile) {
        this.tokenFile = tokenFile;
        this.clientFile = clientFile;
    }

    public synchronized Optional<Tokens> read() {
        if (!Files.exists(tokenFile)) {
            return Optional.empty();
        }
        JsonNode node = parse(tokenFile);
        String refresh = text(node, "refresh_token");
        if (refresh == null) {
            return Optional.empty();
        }
        return Optional.of(new Tokens(text(node, "client_id"), text(node, "access_token"), refresh,
                node.path("expires_at").asLong(0), text(node, "scope"), text(node, "email"), text(node, "sub")));
    }

    public synchronized void write(Tokens tokens) {
        ObjectNode node = JSON.createObjectNode();
        node.put("client_id", tokens.clientId());
        node.put("access_token", tokens.accessToken());
        node.put("refresh_token", tokens.refreshToken());
        node.put("expires_at", tokens.expiresAt());
        node.put("scope", tokens.scope());
        node.put("email", tokens.email());
        node.put("sub", tokens.subject());
        writeOwnerOnly(tokenFile, JSON.writeValueAsString(node));
    }

    /** 删掉令牌文件；注册信息保留。 */
    public synchronized void delete() {
        try {
            Files.deleteIfExists(tokenFile);
        } catch (IOException e) {
            throw new UncheckedIOException("删除失败：" + tokenFile.getFileName(), e);
        }
    }

    /** 读注册信息；还没有 host id 时生成一个 {@code urn:uuid:<v4>} 并存下来，以后一直用它。 */
    public synchronized Registration registration() {
        JsonNode node = Files.exists(clientFile) ? parse(clientFile) : JSON.createObjectNode();
        String hostId = text(node, "ext_agent_host_id");
        String clientId = text(node, "client_id");
        if (hostId == null) {
            hostId = "urn:uuid:" + UUID.randomUUID();
            saveRegistration(new Registration(clientId, hostId));
        }
        return new Registration(clientId, hostId);
    }

    /** 回调里签发的 client_id：换令牌之前先存下来。 */
    public synchronized void saveClientId(String clientId) {
        saveRegistration(new Registration(clientId, registration().hostId()));
    }

    private void saveRegistration(Registration registration) {
        ObjectNode node = JSON.createObjectNode();
        node.put("client_id", registration.clientId());
        node.put("ext_agent_host_id", registration.hostId());
        writeOwnerOnly(clientFile, JSON.writeValueAsString(node));
    }

    private static JsonNode parse(Path file) {
        try {
            return JSON.readTree(Files.readString(file, UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("读取失败：" + file.getFileName(), e);
        } catch (JacksonException e) {
            // 不带文件内容
            throw new StorageFormatException(file.getFileName().toString(), "不是合法的 JSON，删掉它后重新登录 ChatGPT");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }

    private static void writeOwnerOnly(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "." + file.getFileName(), ".tmp",
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            try {
                Files.writeString(temp, content, UTF_8);
                Files.setPosixFilePermissions(temp, OWNER_ONLY);
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入失败：" + file.getFileName(), e);
        }
    }
}

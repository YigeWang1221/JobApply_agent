package jobapply.memory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 经验检索的本地嵌入模型（C022）：multilingual-e5-small 的量化 ONNX 和分词器，放在 {@code storage/model/RAG/}。
 * 不随 App 打包；第一次用到时（检索、或设置页点「下载」）在后台下载，带进度，下载完校验 SHA-256。已经下载过就直接读本地
 * 文件，不再联网。下载失败时状态是 {@code failed}，原因留在 {@link Status#message()}，检索照常退回关键词检索。
 * <p>
 * 分词器和 ONNX 会话加载后约占 700MB 本机内存（C029 实测），所以只在真要算向量时加载，闲置 {@code idle} 后关掉，
 * 下次用到再加载。
 */
public final class ExperienceModel implements AutoCloseable {

    /** 模型的四种状态，{@code GET /api/rag/status} 原样返回。 */
    public enum State {
        ABSENT, DOWNLOADING, READY, FAILED;

        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** {@code progress} 是 0–1；{@code message} 给人看。 */
    public record Status(String state, double progress, String message) {
    }

    /** 一个要下载的文件：存成 {@code name}，大小和 SHA-256 用来校验。 */
    private record ModelFile(String name, String path, long size, String sha256) {
    }

    public static final String MODEL_NAME = "multilingual-e5-small";
    /** Hugging Face 上的 ONNX 版本，固定到一个提交，文件不会悄悄变。 */
    public static final String DEFAULT_BASE_URL =
            "https://huggingface.co/Xenova/multilingual-e5-small/resolve/761b726dd34fb83930e26aab4e9ac3899aa1fa78/";
    private static final List<ModelFile> FILES = List.of(
            new ModelFile("model_quantized.onnx", "onnx/model_quantized.onnx", 118_308_185L,
                    "f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193"),
            new ModelFile("tokenizer.json", "tokenizer.json", 17_082_730L,
                    "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39"));
    private static final long TOTAL = FILES.stream().mapToLong(ModelFile::size).sum();
    private static final Logger log = LoggerFactory.getLogger(ExperienceModel.class);

    private final Path dir;
    private final String baseUrl;
    private final Duration idle;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "rag-model");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong downloaded = new AtomicLong();
    private final List<Runnable> readyListeners = new CopyOnWriteArrayList<>();
    private State state;
    private String message;
    private final LazyEmbedding embedding = new LazyEmbedding();

    /** 加载、使用、卸下分词器和会话都在这把锁里，正在算向量时不会被卸下。 */
    private final Object useLock = new Object();
    private E5Embedding loaded;
    private ScheduledFuture<?> unload;

    /** {@code idle}：最后一次算向量后多久卸下模型。 */
    public ExperienceModel(Path dir, String baseUrl, Duration idle) {
        this.dir = dir;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.idle = idle;
        if (present()) {
            state = State.READY;
            message = MODEL_NAME + " 已在本地";
        } else {
            state = State.ABSENT;
            message = "还没有下载 " + MODEL_NAME + "（约 " + TOTAL / 1_000_000 + " MB），第一次检索时自动下载";
        }
    }

    public synchronized Status status() {
        double progress = switch (state) {
            case READY -> 1;
            case DOWNLOADING, FAILED -> Math.min(1, (double) downloaded.get() / TOTAL);
            case ABSENT -> 0;
        };
        String text = state == State.DOWNLOADING
                ? "正在下载 " + MODEL_NAME + "：" + downloaded.get() / 1_000_000 + " / " + TOTAL / 1_000_000 + " MB"
                : message;
        return new Status(state.value(), progress, text);
    }

    public synchronized State state() {
        return state;
    }

    /** 下载完成后在下载线程上调用（启动时已在本地的不调，由调用方自己看 {@link #state()}）。 */
    public void onReady(Runnable listener) {
        readyListeners.add(listener);
    }

    /** 在后台开始下载；已经在下载或已在本地时什么也不做。返回调用后的状态。 */
    public synchronized Status download() {
        if (state == State.ABSENT || state == State.FAILED) {
            state = State.DOWNLOADING;
            downloaded.set(0);
            worker.submit(this::fetchAll);
        }
        return status();
    }

    /**
     * 嵌入模型；文件还不在、或上次加载失败时为 null。返回的对象很轻：第一次真要算向量时才加载分词器和会话，
     * 加载失败时状态变成 {@code failed}（原因在 message 里）、抛异常给调用方。
     */
    public synchronized EmbeddingModel embedding() {
        return state == State.READY ? embedding : null;
    }

    /** 拿到已加载的模型做一件事（没加载就先加载），做完重新开始闲置计时。 */
    private <T> T use(Function<E5Embedding, T> work) {
        synchronized (useLock) {
            if (loaded == null) {
                loaded = load();
            }
            try {
                return work.apply(loaded);
            } finally {
                if (unload != null) {
                    unload.cancel(false);
                }
                unload = worker.schedule(this::unloadIdle, idle.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    private E5Embedding load() {
        synchronized (this) {
            if (state != State.READY) {
                throw new IllegalStateException("嵌入模型不能用：" + message);
            }
        }
        try {
            // 分词器的本机库从依赖里解出来，放在模型目录下，不写到用户主目录
            if (System.getProperty("DJL_CACHE_DIR") == null) {
                System.setProperty("DJL_CACHE_DIR", dir.resolve("djl").toString());
            }
            System.setProperty("OPT_OUT_TRACKING", "true");
            E5Embedding model = new E5Embedding(dir.resolve(FILES.get(0).name()), dir.resolve(FILES.get(1).name()));
            log.info("经验检索：已加载 {}，闲置 {} 后卸下", MODEL_NAME, idle);
            return model;
        } catch (Exception | LinkageError e) {
            synchronized (this) {
                state = State.FAILED;
                message = "嵌入模型加载失败：" + e.getMessage() + "。可以在设置页重新下载";
            }
            log.warn("经验检索：{}", message, e);
            deleteFiles();
            throw new IllegalStateException(message, e);
        }
    }

    /** 闲置计时到了：卸下分词器和会话（正在用的那次做完才会轮到这里）。 */
    private void unloadIdle() {
        synchronized (useLock) {
            if (loaded != null) {
                loaded.close();
                loaded = null;
                log.info("经验检索：闲置 {}，已卸下 {}", idle, MODEL_NAME);
            }
            unload = null;
        }
    }

    /** {@link #embedding()} 给出去的对象：每次调用都经 {@link #use}。 */
    private final class LazyEmbedding extends AbstractEmbeddingModel {

        @Override
        public float[] embed(String text) {
            return use(model -> model.embed(text));
        }

        @Override
        public float[] embed(Document document) {
            return use(model -> model.embed(document));
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return use(model -> model.call(request));
        }

        @Override
        public int dimensions() {
            return E5Embedding.DIMENSIONS;
        }
    }

    private boolean present() {
        for (ModelFile file : FILES) {
            Path path = dir.resolve(file.name());
            try {
                if (!Files.isRegularFile(path) || Files.size(path) != file.size()) {
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }

    private void fetchAll() {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault()).connectTimeout(Duration.ofSeconds(20)).build();
        try {
            Files.createDirectories(dir);
            long before = 0;
            for (ModelFile file : FILES) {
                fetch(http, file, before);
                before += file.size();
                downloaded.set(before);
            }
            synchronized (this) {
                state = State.READY;
                message = MODEL_NAME + " 已下载";
            }
            log.info("经验检索：{} 已下载到 {}", MODEL_NAME, dir);
            readyListeners.forEach(Runnable::run);
        } catch (Exception e) {
            String reason = e instanceof InterruptedException ? "下载被中断" : e.getMessage();
            synchronized (this) {
                state = State.FAILED;
                message = "下载 " + MODEL_NAME + " 失败：" + reason + "。现在用关键词检索，可以在设置页重试";
            }
            log.warn("经验检索：{}", message, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 下载一个文件到 {@code .part}，边写边算 SHA-256，对得上再改名。已经下载好的跳过。 */
    private void fetch(HttpClient http, ModelFile file, long before) throws IOException, InterruptedException {
        Path target = dir.resolve(file.name());
        if (Files.isRegularFile(target) && Files.size(target) == file.size()) {
            return;
        }
        Path part = dir.resolve(file.name() + ".part");
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + file.path()))
                .timeout(Duration.ofMinutes(30)).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        MessageDigest digest = sha256();
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException(file.path() + " 返回 HTTP " + response.statusCode());
            }
            try (OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[1 << 16];
                long done = 0;
                for (int n; (n = in.read(buffer)) > 0; ) {
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    done += n;
                    downloaded.set(before + done);
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(file.sha256())) {
                throw new IOException(file.name() + " 校验不对（下载不完整或文件被换了）");
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private void deleteFiles() {
        for (ModelFile model : FILES) {
            Path file = dir.resolve(model.name());
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("经验检索：删除坏掉的模型文件失败：{}", file);
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        worker.shutdownNow();
        synchronized (useLock) {
            if (loaded != null) {
                loaded.close();
                loaded = null;
            }
        }
    }
}

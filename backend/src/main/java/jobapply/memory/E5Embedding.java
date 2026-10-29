package jobapply.memory;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * multilingual-e5-small 的本地嵌入（C022）：ONNX Runtime 跑模型、HuggingFace 分词器切词，都来自
 * {@code spring-ai-transformers} 的依赖。池化（按注意力掩码取平均）和归一化在这里做，不用 DJL 的 NDArray 引擎，
 * 免得它在运行时另外联网下载 PyTorch。
 * <p>
 * e5 要求前缀：存进索引的片段（{@link #embed(Document)}）加 {@code passage: }，检索的问题（{@link #embed(String)}）加
 * {@code query: }。{@link #call} 不加前缀，调用方自己带。
 */
final class E5Embedding extends AbstractEmbeddingModel implements AutoCloseable {

    static final int DIMENSIONS = 384;
    private static final String MAX_TOKENS = "512";
    private static final String OUTPUT = "last_hidden_state";

    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final HuggingFaceTokenizer tokenizer;
    private final OrtSession session;
    private final Set<String> inputs;

    E5Embedding(Path model, Path tokenizerFile) throws IOException, OrtException {
        this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerFile,
                Map.of("maxLength", MAX_TOKENS, "truncation", "true", "padding", "false"));
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
            this.session = environment.createSession(model.toString(), options);
        } catch (OrtException | RuntimeException e) {
            tokenizer.close();
            throw e;
        }
        this.inputs = session.getInputNames();
        if (!session.getOutputNames().contains(OUTPUT)) {
            close();
            throw new IllegalStateException("模型文件没有 " + OUTPUT + " 输出：" + model);
        }
    }

    @Override
    public float[] embed(String query) {
        return vector("query: " + query);
    }

    @Override
    public float[] embed(Document document) {
        return vector("passage: " + (document.getText() == null ? "" : document.getText()));
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> out = new ArrayList<>();
        int index = 0;
        for (String text : request.getInstructions()) {
            out.add(new Embedding(vector(text), index++));
        }
        return new EmbeddingResponse(out);
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    /** 一段文字（已带前缀）的向量：最后一层按掩码取平均，再归一化。 */
    private synchronized float[] vector(String text) {
        Encoding encoding = tokenizer.encode(text);
        long[] ids = encoding.getIds();
        long[] mask = encoding.getAttentionMask();
        try (OnnxTensor idTensor = OnnxTensor.createTensor(environment, new long[][] {ids});
             OnnxTensor maskTensor = OnnxTensor.createTensor(environment, new long[][] {mask});
             OnnxTensor typeTensor = OnnxTensor.createTensor(environment, new long[][] {new long[ids.length]})) {
            Map<String, OnnxTensor> feed = new HashMap<>();
            feed.put("input_ids", idTensor);
            feed.put("attention_mask", maskTensor);
            feed.put("token_type_ids", typeTensor);
            feed.keySet().retainAll(inputs);
            try (OrtSession.Result result = session.run(feed)) {
                float[][] tokens = ((float[][][]) result.get(OUTPUT).orElseThrow().getValue())[0];
                float[] pooled = new float[tokens[0].length];
                double count = 0;
                for (int t = 0; t < tokens.length; t++) {
                    if (mask[t] == 0) {
                        continue;
                    }
                    count++;
                    for (int d = 0; d < pooled.length; d++) {
                        pooled[d] += tokens[t][d];
                    }
                }
                double norm = 0;
                for (int d = 0; d < pooled.length; d++) {
                    pooled[d] /= (float) Math.max(count, 1);
                    norm += pooled[d] * pooled[d];
                }
                norm = Math.sqrt(norm);
                for (int d = 0; d < pooled.length && norm > 0; d++) {
                    pooled[d] /= (float) norm;
                }
                return pooled;
            }
        } catch (OrtException e) {
            throw new IllegalStateException("嵌入模型运行失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            tokenizer.close();
        } finally {
            try {
                session.close();
            } catch (OrtException e) {
                // 关闭时的错误不影响别的
            }
        }
    }
}

package jobapply.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import jobapply.memory.AssetAnalyzer;
import jobapply.persistence.AssetRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 资料页：原件列表、拖拽上传、修改说明。新登记的原件做一次轻量分析（Tika 读文字，开头的文字发给辅助模型写一句说明）；
 * 同一个 SHA-256 已经登记过时返回已有的条目和 {@code duplicate: true}，不再分析。
 */
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    /**
     * @param analysisNote 没有写说明的原因（例如还没有配置模型）；写了说明或重复文件时为 null
     */
    public record UploadResponse(AssetRegistry.Asset asset, boolean duplicate, String analysisNote) {
    }

    public record DescriptionRequest(String description) {
    }

    private final AssetRegistry registry;
    private final AssetAnalyzer analyzer;

    public AssetController(AssetRegistry registry, AssetAnalyzer analyzer) {
        this.registry = registry;
        this.analyzer = analyzer;
    }

    @GetMapping
    public Map<String, List<AssetRegistry.Asset>> list() {
        return Map.of("assets", registry.list());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("读取上传文件失败", e);
        }
        String name = file.getOriginalFilename();
        AssetRegistry.Registered registered = registry.register(name, analyzer.detectType(content, name), content);
        if (registered.duplicate()) {
            return ResponseEntity.ok(new UploadResponse(registered.asset(), true, null));
        }
        AssetRegistry.Asset asset = registered.asset();
        AssetAnalyzer.Analysis analysis = analyzer.analyze(registry.originalPath(asset), asset.type());
        if (!analysis.description().isEmpty()) {
            asset = registry.describe(asset.key(), analysis.description());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(new UploadResponse(asset, false, analysis.note()));
    }

    @PatchMapping(path = "/{key}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AssetRegistry.Asset describe(@PathVariable String key, @RequestBody DescriptionRequest request) {
        return registry.describe(key, request.description());
    }
}

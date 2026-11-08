package jobapply.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import jobapply.memory.AssetAnalyzer;
import jobapply.persistence.AssetRegistry;
import jobapply.persistence.ProjectRepository;
import jobapply.persistence.StorageLayout;
import jobapply.persistence.Trash;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * <p>
 * C004-A-fix：上传带分类（简历 / 通用资料，空 = 按文件名判断）和简历归属的 Project；可以改分类、归属；删除挪进回收站。
 */
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    /**
     * @param analysisNote 没有写说明的原因（例如还没有配置模型）；写了说明或重复文件时为 null
     */
    public record UploadResponse(AssetRegistry.Asset asset, boolean duplicate, String analysisNote) {
    }

    /** 资料页改一条：为 null 的字段不改；{@code project} 写空字符串是「未归属」。 */
    public record UpdateRequest(String description, String category, String project) {
    }

    /** @param trash 回收站里这次删除的目录（相对 storage） */
    public record DeleteResponse(String key, String trash) {
    }

    private final AssetRegistry registry;
    private final AssetAnalyzer analyzer;
    private final StorageLayout layout;
    private final ProjectRepository projects;
    private final Trash trash;

    public AssetController(AssetRegistry registry, AssetAnalyzer analyzer, StorageLayout layout, Clock clock) {
        this.registry = registry;
        this.analyzer = analyzer;
        this.layout = layout;
        this.projects = new ProjectRepository(layout, clock);
        this.trash = new Trash(layout, clock);
    }

    @GetMapping
    public Map<String, List<AssetRegistry.Asset>> list() {
        return Map.of("assets", registry.list());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(name = "category", required = false) String category,
                                                 @RequestParam(name = "project", required = false) String project) {
        requireProject(project);
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("读取上传文件失败", e);
        }
        String name = file.getOriginalFilename();
        AssetRegistry.Registered registered = registry.register(name, analyzer.detectType(content, name), content,
                category, project);
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
    public AssetRegistry.Asset update(@PathVariable String key, @RequestBody UpdateRequest request) {
        requireProject(request.project());
        return registry.update(key, request.description(), request.category(), request.project());
    }

    @DeleteMapping("/{key}")
    public DeleteResponse delete(@PathVariable String key) {
        Path entry = registry.remove(key, trash);
        return new DeleteResponse(key, layout.relative(entry));
    }

    private void requireProject(String project) {
        if (project != null && !project.isBlank() && !projects.exists(project.strip())) {
            throw new IllegalArgumentException("Project 不存在：" + project);
        }
    }
}

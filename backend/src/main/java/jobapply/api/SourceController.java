package jobapply.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import jobapply.domain.Ids;
import jobapply.domain.Source;
import jobapply.persistence.SourceRegistry;
import jobapply.persistence.StorageLayout;
import jobapply.tools.DirectoryBrowser;
import jobapply.tools.GitHubPublicReader;
import jobapply.tools.LocalDirectoryScanner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资料页的「来源」：登记本地目录或 GitHub 公开仓库，列出、删除登记。只登记，不扫描（扫描在估算时做）。
 * 本地目录可以写绝对路径，也可以写「文件来自于「路径」」这类句子；目录选择框用 {@code GET /browse} 列子目录。
 * 只给 GitHub owner 时返回它的公开仓库让用户选，不登记。删除登记不碰目录或仓库里的任何东西。
 */
@RestController
@RequestMapping("/api/sources")
public class SourceController {

    public record RegisterRequest(String type, String location) {
    }

    /** 接口里的一条来源（类型写成 {@code dir} / {@code github}）。 */
    public record SourceView(String key, String type, String location, String version, String registeredAt,
                             String lastScannedAt, String fileCount, String description) {

        static SourceView of(Source source) {
            return new SourceView(source.key(), source.type().code(), source.location(), source.version(),
                    source.registeredAt(), source.lastScannedAt(), source.fileCount(), source.description());
        }
    }

    private final SourceRegistry registry;
    private final GitHubPublicReader github;
    private final StorageLayout layout;

    public SourceController(SourceRegistry registry, GitHubPublicReader github, StorageLayout layout) {
        this.registry = registry;
        this.github = github;
        this.layout = layout;
    }

    /** 目录选择框：列出 {@code path} 下的子目录（不给时从用户主目录开始），只有名字和路径。 */
    @GetMapping("/browse")
    public DirectoryBrowser.Listing browse(@RequestParam(required = false) String path) {
        return new DirectoryBrowser(layout.root()).list(path);
    }

    @GetMapping
    public Map<String, List<SourceView>> list() {
        return Map.of("sources", registry.list().stream().map(SourceView::of).toList());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest request) {
        Source.Type type = Source.Type.of(request.type());
        SourceRegistry.Registered registered;
        if (type == Source.Type.DIR) {
            Path root = LocalDirectoryScanner.validateRoot(request.location(), layout.root());
            registered = registry.register(Source.Type.DIR, root.toString(), "");
        } else {
            GitHubPublicReader.Ref ref = GitHubPublicReader.parse(request.location());
            if (ref.repo() == null) {
                return ResponseEntity.ok(Map.of("owner", ref.owner(),
                        "repositories", github.listRepositories(ref.owner())));
            }
            GitHubPublicReader.RepoInfo info = github.repository(ref.owner(), ref.repo());
            registered = registry.register(Source.Type.GITHUB, info.fullName(), info.headSha());
        }
        return ResponseEntity.status(registered.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(Map.of("source", SourceView.of(registered.source()), "duplicate", registered.duplicate()));
    }

    @DeleteMapping("/{key}")
    public Map<String, String> delete(@PathVariable String key) {
        if (!Ids.isSourceKey(key)) {
            throw new IllegalArgumentException("来源键不合法（应为 s-YYYY-MM-DD-NNN）");
        }
        registry.delete(key);
        return Map.of("deleted", key);
    }
}

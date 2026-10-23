package jobapply.api;

import java.util.List;
import java.util.Map;
import jobapply.config.ConnectionTester;
import jobapply.config.ModelSettingsService;
import jobapply.persistence.ModelSettingsFile.Assignment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设置页「模型」：提供方、Key、模型列表、测试连接、用途分配。任何返回里都没有 Key 本身，只有 {@code keySet} 和末四位。
 * 写接口仍然要求 {@code Content-Type: application/json}（没有请求体的 POST 也一样，前端发 {@code {}}）。
 */
@RestController
@RequestMapping("/api/settings/models")
public class ModelSettingsController {

    public record ProviderRequest(String preset, String format, String baseUrl, List<String> models) {
    }

    public record KeyRequest(String key) {
    }

    public record TestRequest(String model) {
    }

    public record AssignmentsRequest(Assignment main, Assignment auxiliary, Assignment crossCheck) {
    }

    private final ModelSettingsService settings;

    public ModelSettingsController(ModelSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public ModelSettingsService.View view() {
        return settings.view();
    }

    @PutMapping(path = "/providers/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ModelSettingsService.ProviderView upsert(@PathVariable String id, @RequestBody ProviderRequest request) {
        return settings.upsertProvider(id, request.preset(), request.format(), request.baseUrl(), request.models());
    }

    @DeleteMapping("/providers/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        settings.deleteProvider(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping(path = "/providers/{id}/key", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> key(@PathVariable String id, @RequestBody KeyRequest request) {
        return Map.of("keyLast4", settings.setKey(id, request.key()));
    }

    @PostMapping("/providers/{id}/catalog")
    public Map<String, List<String>> catalog(@PathVariable String id) {
        return Map.of("models", settings.catalog(id));
    }

    @PostMapping(path = "/providers/{id}/test", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ConnectionTester.Result test(@PathVariable String id, @RequestBody TestRequest request) {
        return settings.test(id, request.model());
    }

    @PutMapping(path = "/assignments", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Assignment> assign(@RequestBody AssignmentsRequest request) {
        return settings.assign(blankToNull(request.main()), blankToNull(request.auxiliary()),
                blankToNull(request.crossCheck()));
    }

    private static Assignment blankToNull(Assignment assignment) {
        if (assignment == null || assignment.provider() == null || assignment.provider().isBlank()) {
            return null;
        }
        if (assignment.model() == null || assignment.model().isBlank()) {
            throw new IllegalArgumentException("分配里缺少 model");
        }
        return assignment;
    }
}

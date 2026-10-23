package jobapply.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jobapply.domain.ImportRun;
import jobapply.memory.ImportEstimator;
import jobapply.memory.ImportRunner;
import jobapply.persistence.ImportRunStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 导入记录：估算（C004-A-1）→ 开始整理（后台执行，202）→ 查进度和候选 → 逐条决定（C004-A-2）。
 * 返回里只有候选的值和出处定位，没有文件原文。
 */
@RestController
@RequestMapping("/api/import-runs")
public class ImportRunController {

    public record EstimateRequest(List<String> sources) {
    }

    /**
     * @param value 改过的值（{@code edit} 时）
     * @param use   论断的用途（{@code forms_and_resume} / {@code interview_only} / {@code none}）
     */
    public record DecisionRequest(String candidate, String decision, Map<String, Object> value, String use) {
    }

    private final ImportEstimator estimator;
    private final ImportRunner runner;

    public ImportRunController(ImportEstimator estimator, ImportRunner runner) {
        this.estimator = estimator;
        this.runner = runner;
    }

    /** 全部导入记录（新的在前），只有概要：状态、来源、候选数和已决定数。 */
    @GetMapping
    public Map<String, Object> list() {
        return Map.of("runs", runner.list().stream().map(ImportRunController::summary).toList());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> estimate(@RequestBody EstimateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ImportRunStore.toMap(estimator.estimate(request.sources())));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return ImportRunStore.toMap(runner.view(id));
    }

    @PostMapping(path = "/{id}/start", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> start(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ImportRunStore.toMap(runner.start(id)));
    }

    @PostMapping(path = "/{id}/decisions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> decide(@PathVariable String id, @RequestBody DecisionRequest request) {
        if (request.candidate() == null || request.candidate().isBlank()) {
            throw new IllegalArgumentException("要写明是哪个候选（candidate）");
        }
        return ImportRunStore.toMap(runner.decide(id, request.candidate(), request.decision(), request.value(),
                request.use()));
    }

    private static Map<String, Object> summary(ImportRun run) {
        List<?> candidates = run.extra().get("candidates") instanceof List<?> list ? list : List.of();
        long decided = candidates.stream().filter(item -> item instanceof Map<?, ?> map && map.get("decision") != null)
                .count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", run.id());
        summary.put("status", run.status().code());
        summary.put("created_at", run.createdAt());
        summary.put("updated_at", run.updatedAt());
        summary.put("sources", run.sources());
        summary.put("candidates", candidates.size());
        summary.put("decided", decided);
        summary.put("error", run.extra().get("error"));
        return summary;
    }
}

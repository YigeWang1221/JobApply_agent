package jobapply.api;

import java.util.List;
import java.util.Map;
import jobapply.memory.ImportEstimator;
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
 * 导入记录。本段（C004-A-1）只有估算：选几个来源 → 只读扫描 → 建一条 {@code estimated} 的记录并返回估算。
 * 「开始整理」和确认在 C004-A-2。
 */
@RestController
@RequestMapping("/api/import-runs")
public class ImportRunController {

    public record EstimateRequest(List<String> sources) {
    }

    private final ImportEstimator estimator;
    private final ImportRunStore runs;

    public ImportRunController(ImportEstimator estimator, ImportRunStore runs) {
        this.estimator = estimator;
        this.runs = runs;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> estimate(@RequestBody EstimateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ImportRunStore.toMap(estimator.estimate(request.sources())));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return ImportRunStore.toMap(runs.require(id));
    }
}

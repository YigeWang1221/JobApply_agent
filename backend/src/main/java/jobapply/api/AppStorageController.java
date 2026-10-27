package jobapply.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import jobapply.config.StorageLocation;
import jobapply.workflow.ApplicationRunner;
import jobapply.workflow.SessionRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据位置（C017 第 3 节）：看当前 storage 根目录、只读检查一个候选位置、把新位置写进 {@code app.yaml}。
 * 不热切换、不复制也不移动数据：保存后重启后端才生效；有申请或对话在跑时拒绝（409）。
 */
@RestController
@RequestMapping("/api/app/storage")
public class AppStorageController {

    public record PathRequest(String path) {
    }

    public record View(String root, String source, boolean needsChoice, boolean existingData, int projects,
                       String defaultRoot) {
    }

    private final StorageLocation location;
    private final ApplicationRunner applications;
    private final SessionRunner sessions;

    public AppStorageController(StorageLocation location, ApplicationRunner applications, SessionRunner sessions) {
        this.location = location;
        this.applications = applications;
        this.sessions = sessions;
    }

    @GetMapping
    public View view() {
        Path root = location.root();
        StorageLocation.Check check = StorageLocation.check(root.toString());
        return new View(root.toString(), location.source(), location.needsChoice(), "jobapply".equals(check.kind()),
                StorageLocation.countProjects(root), location.defaultRoot().toString());
    }

    @PostMapping(path = "/check", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StorageLocation.Check check(@RequestBody PathRequest request) {
        return StorageLocation.check(request.path());
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> save(@RequestBody PathRequest request) {
        StorageLocation.Check check = StorageLocation.check(request.path());
        if (!check.ok()) {
            return ResponseEntity.badRequest().body(Map.of("error", check.problem()));
        }
        List<String> jobs = applications.activeJobs();
        List<String> chats = sessions.activeSessions();
        if (!jobs.isEmpty() || !chats.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "有申请或对话正在跑，等它们结束或停下后再换位置（申请 " + jobs.size() + " 个："
                            + String.join("、", jobs) + "；对话 " + chats.size() + " 个：" + String.join("、", chats) + "）",
                    "applications", jobs, "sessions", chats));
        }
        location.save(Path.of(check.path()));
        return ResponseEntity.ok(Map.of("saved", true, "restartRequired", true, "path", check.path()));
    }
}

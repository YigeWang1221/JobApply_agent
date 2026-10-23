package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jobapply.domain.ProjectSpec;

/**
 * 读写 {@code projects/<id>/project.md}，新建时一并写出 {@code overrides.md}、{@code preferences.md} 和空台账。
 * 循环继承在加载有效资料时检测（见 {@code jobapply.memory.ProfileResolver}）。
 */
public final class ProjectRepository {

    private final StorageLayout layout;
    private final TrackerWriter tracker;

    public ProjectRepository(StorageLayout layout, Clock clock) {
        this.layout = layout;
        this.tracker = new TrackerWriter(layout);
    }

    public boolean exists(String projectId) {
        return Files.exists(layout.projectFile(projectId));
    }

    public ProjectSpec create(ProjectSpec spec) {
        if (exists(spec.id())) {
            throw new IllegalArgumentException("Project 已存在：" + spec.id());
        }
        for (String parent : spec.parentIds()) {
            if (!exists(parent)) {
                throw new IllegalArgumentException("父 Project 不存在：" + parent);
            }
        }
        if (!spec.pinsLive()) {
            throw new IllegalArgumentException("storage 不做版本控制，base_pin 只能是 live：" + spec.basePin());
        }
        List<Path> written = new ArrayList<>();
        written.add(write(layout.projectFile(spec.id()), toFrontmatter(spec), "\n# " + spec.title() + "\n\n## 说明\n"));
        written.add(write(layout.overrides(spec.id()), Map.of("tags", List.of("jobapply/overrides")),
                "\n# overrides · " + spec.id() + "\n"));
        written.add(write(layout.preferences(spec.id()), Map.of("tags", List.of("jobapply/preferences")),
                "\n# 偏好 · " + spec.id() + "\n"));
        written.addAll(tracker.writeProject(spec.id()));
        return spec;
    }

    public ProjectSpec load(String projectId) {
        Path file = layout.projectFile(projectId);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Project 不存在：" + projectId);
        }
        Map<String, Object> fm = FrontmatterFile.read(file).frontmatter();
        try {
            Object inherits = fm.get("inherits");
            return new ProjectSpec(
                    string(fm.get("id")),
                    string(fm.get("title")),
                    inherits instanceof List<?> list ? list.stream().map(Object::toString).toList() : null,
                    string(fm.get("base_pin")),
                    string(fm.get("default_resume")),
                    string(fm.get("status")));
        } catch (IllegalArgumentException e) {
            throw new StorageFormatException(file.toString(), e.getMessage(), e);
        }
    }

    /** 改 Project 的名字（frontmatter 和正文的标题）。id 不变。 */
    public ProjectSpec rename(String projectId, String title) {
        ProjectSpec spec = load(projectId);
        String name = SessionRepository.checkTitle(title);
        Path file = layout.projectFile(projectId);
        FrontmatterFile.Document document = FrontmatterFile.read(file);
        Map<String, Object> fm = new LinkedHashMap<>(document.frontmatter());
        fm.put("title", name);
        String body = document.body();
        String heading = "# " + spec.title() + "\n";
        int at = spec.title() == null ? -1 : body.indexOf(heading);
        if (at >= 0) {
            body = body.substring(0, at) + "# " + name + "\n" + body.substring(at + heading.length());
        }
        FrontmatterFile.write(file, new FrontmatterFile.Document(fm, body));
        return load(projectId);
    }

    private static Map<String, Object> toFrontmatter(ProjectSpec spec) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("id", spec.id());
        fm.put("title", spec.title());
        fm.put("inherits", new ArrayList<>(spec.inherits()));
        fm.put("base_pin", spec.basePin());
        fm.put("default_resume", spec.defaultResume());
        fm.put("status", spec.status());
        fm.put("tags", List.of("jobapply/project"));
        return fm;
    }

    private static Path write(Path file, Map<String, Object> frontmatter, String body) {
        FrontmatterFile.write(file, new FrontmatterFile.Document(frontmatter, body));
        return file;
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}

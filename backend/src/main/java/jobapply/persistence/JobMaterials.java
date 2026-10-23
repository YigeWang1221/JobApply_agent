package jobapply.persistence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import jobapply.domain.Ids;

/**
 * {@code job.md} frontmatter 的 {@code materials}：用户在 {@code wait_materials} 选的原件（引用 {@code base/assets.md}
 * 的键）。每一项是 {@code {slot, asset, upload_name, sha256}}，按上传位置的顺序；{@code slot} 是页面上上传位置的名字，
 * 可以没有。写的时候只替换 {@code materials}，其它 frontmatter 和正文原样保留。
 */
public final class JobMaterials {

    static final String KEY = "materials";

    /** 用户的一项选择：上传位置（可以为 null）和原件键。 */
    public record Selection(String slot, String asset) {
    }

    /**
     * {@code materials} 里的一项。
     *
     * @param uploadName 上传时的文件名（{@code storage/uploads/<job-id>/} 下副本的文件名，和原件相同）
     */
    public record Material(String slot, String asset, String uploadName, String sha256) {
    }

    private final StorageLayout layout;
    private final AssetRegistry assets;

    public JobMaterials(StorageLayout layout, AssetRegistry assets, Clock clock) {
        this.layout = layout;
        this.assets = assets;
    }

    /**
     * 把选择写进 {@code materials}（整体替换上一次的选择）。原件键不存在时抛出 {@link NoSuchElementException}，
     * 什么都不写。
     */
    public synchronized List<Material> write(String projectId, String jobId, List<Selection> selections) {
        Path file = jobFile(projectId, jobId);
        List<Material> materials = new ArrayList<>();
        for (Selection selection : selections) {
            if (!Ids.isAssetKey(selection.asset())) {
                throw new IllegalArgumentException("原件键不合法（应为 a-YYYY-MM-DD-NNN）：" + selection.asset());
            }
            AssetRegistry.Asset asset = assets.find(selection.asset())
                    .orElseThrow(() -> new NoSuchElementException("原件不存在：" + selection.asset()));
            String slot = selection.slot() == null || selection.slot().isBlank() ? null : selection.slot().strip();
            materials.add(new Material(slot, asset.key(), asset.fileName(), asset.sha256()));
        }
        FrontmatterFile.Document current = FrontmatterFile.read(file);
        Map<String, Object> frontmatter = new LinkedHashMap<>(current.frontmatter());
        frontmatter.put(KEY, materials.stream().map(JobMaterials::toMap).toList());
        FrontmatterFile.write(file, new FrontmatterFile.Document(frontmatter, current.body()));
        return List.copyOf(materials);
    }

    /** 读出 {@code materials}；没有时返回空列表。格式不对的项跳过。 */
    public synchronized List<Material> read(String projectId, String jobId) {
        Object value = FrontmatterFile.read(jobFile(projectId, jobId)).frontmatter().get(KEY);
        List<Material> materials = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map && map.get("asset") != null) {
                    materials.add(new Material(string(map.get("slot")), string(map.get("asset")),
                            string(map.get("upload_name")), string(map.get("sha256"))));
                }
            }
        }
        return List.copyOf(materials);
    }

    private Path jobFile(String projectId, String jobId) {
        Path file = layout.jobFile(projectId, jobId);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Job 不存在：" + projectId + "/" + jobId);
        }
        return file;
    }

    private static Map<String, Object> toMap(Material material) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (material.slot() != null) {
            map.put("slot", material.slot());
        }
        map.put("asset", material.asset());
        map.put("upload_name", material.uploadName());
        map.put("sha256", material.sha256());
        return map;
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}

package jobapply.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

/**
 * {@code storage/uploads/<job-id>/}：一个 Job 这次要上传的文件。按 {@code job.md} 的 {@code materials}，把
 * {@code base/originals/} 里的原件复制成等字节副本，并用 {@code base/assets.md} 登记的 SHA-256 核对；
 * 浏览器只能上传这个目录里的文件（{@link jobapply.harness.policies.BrowserToolPolicy}）。
 * 线程到达关卡 B 时用 {@link #clear(String)} 清空原件副本，重新开始时用 {@link #reset(String)} 全部清空。
 */
public final class UploadArea {

    private final StorageLayout layout;
    private final AssetRegistry assets;

    public UploadArea(StorageLayout layout, AssetRegistry assets) {
        this.layout = layout;
        this.assets = assets;
    }

    /** 这个 Job 的上传目录（不一定存在）。 */
    public Path dir(String jobId) {
        return layout.uploadsDir(jobId);
    }

    /**
     * 把一个原件复制到上传目录，文件名和原件相同，已有同名副本时覆盖。
     * 复制后算副本的 SHA-256，和登记的不一致时删掉副本并抛出 {@link IllegalStateException}。
     *
     * @return 副本的路径
     */
    public synchronized Path stage(String jobId, String assetKey) {
        Path dir = dir(jobId).normalize();
        AssetRegistry.Asset asset = assets.find(assetKey)
                .orElseThrow(() -> new NoSuchElementException("原件不存在：" + assetKey));
        Path original = assets.originalPath(asset);
        if (!Files.isRegularFile(original)) {
            throw new IllegalStateException("原件文件不见了：" + asset.fileName());
        }
        Path copy = dir.resolve(asset.fileName()).normalize();
        if (!dir.equals(copy.getParent())) {
            throw new IllegalStateException("原件文件名不合法：" + asset.fileName());
        }
        try {
            Files.createDirectories(dir);
            Files.copy(original, copy, StandardCopyOption.REPLACE_EXISTING);
            String sha = AssetRegistry.sha256(Files.readAllBytes(copy));
            if (!sha.equalsIgnoreCase(asset.sha256())) {
                Files.deleteIfExists(copy);
                throw new IllegalStateException("副本的 SHA-256 和登记的不一致，原件可能被改过：" + assetKey);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("复制原件到上传目录失败：" + assetKey, e);
        }
        return copy;
    }

    /** 按顺序复制多个原件，返回副本路径（顺序同参数，即文件编号 1、2、…）。任何一个失败都抛出。 */
    public List<Path> stage(String jobId, List<String> assetKeys) {
        return assetKeys.stream().map(key -> stage(jobId, key)).toList();
    }

    /** 清空复制进来的原件（保留空目录和用户附的文件 {@code attachments/}，对话里还要显示）。目录不存在时什么都不做。 */
    public synchronized void clear(String jobId) {
        delete(jobId, layout.jobAttachmentsDir(jobId));
    }

    /** 清空整个上传目录，连同用户附的文件（重新开始申请时，旧对话丢掉了）。目录不存在时什么都不做。 */
    public synchronized void reset(String jobId) {
        delete(jobId, null);
    }

    private void delete(String jobId, Path keep) {
        Path dir = dir(jobId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(dir) && (keep == null || !path.startsWith(keep))) {
                    Files.delete(path);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("清空上传目录失败：" + jobId, e);
        }
    }
}

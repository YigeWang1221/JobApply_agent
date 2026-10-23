package jobapply.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 「来源」页的目录选择框（C004-A-2 追加）：列出一个目录下的子目录，让用户点进去选要登记的目录。
 * 只列子目录的名字和路径，不列文件、不读任何文件内容；不跟随符号链接；隐藏目录和按 {@link SecretFileRules}
 * 跳过的目录不列。storage 本身、它的上级和它里面的目录标为不能登记（和 {@link LocalDirectoryScanner#validateRoot} 一致）。
 */
public final class DirectoryBrowser {

    /** 一个子目录；{@code registrable} 为 false 时可以点进去，但不能登记。 */
    public record Entry(String name, String path, boolean registrable) {
    }

    /**
     * @param parent      上一级；已经是根目录时为 null
     * @param registrable 当前目录能不能登记
     */
    public record Listing(String path, String parent, boolean registrable, List<Entry> directories) {
    }

    private final Path storageRoot;

    public DirectoryBrowser(Path storageRoot) {
        this.storageRoot = storageRoot;
    }

    /**
     * @param location 绝对路径；为空时从用户主目录开始
     */
    public Listing list(String location) {
        Path dir;
        if (location == null || location.isBlank()) {
            dir = Path.of(System.getProperty("user.home"));
        } else {
            dir = Path.of(LocalDirectoryScanner.locationFromText(location));
            if (!dir.isAbsolute()) {
                throw new IllegalArgumentException("要写目录的绝对路径：" + location);
            }
        }
        Path real;
        try {
            real = dir.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("目录不存在或不可读：" + dir);
        }
        if (!Files.isDirectory(real) || !Files.isReadable(real)) {
            throw new IllegalArgumentException("目录不存在或不可读：" + real);
        }
        Path storage = storage();
        List<Entry> directories = new ArrayList<>();
        try (Stream<Path> children = Files.list(real)) {
            for (Path child : children.sorted().toList()) {
                String name = child.getFileName().toString();
                if (name.startsWith(".") || SecretFileRules.skippedDirectory(name).isPresent()) {
                    continue;
                }
                BasicFileAttributes attrs;
                try {
                    attrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    continue;
                }
                if (attrs.isDirectory() && !attrs.isSymbolicLink()) {
                    directories.add(new Entry(name, child.toString(), registrable(child, storage)));
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("目录不可读：" + real);
        }
        Path parent = real.getParent();
        return new Listing(real.toString(), parent == null ? null : parent.toString(), registrable(real, storage),
                directories);
    }

    private Path storage() {
        try {
            return Files.exists(storageRoot) ? storageRoot.toRealPath() : storageRoot.toAbsolutePath().normalize();
        } catch (IOException e) {
            return storageRoot.toAbsolutePath().normalize();
        }
    }

    private static boolean registrable(Path dir, Path storage) {
        return !(storage.startsWith(dir) || dir.startsWith(storage));
    }
}

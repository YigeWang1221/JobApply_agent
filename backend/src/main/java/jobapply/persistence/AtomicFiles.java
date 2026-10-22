package jobapply.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 先写同目录临时文件，再原子替换，避免留下写了一半的文件。
 */
final class AtomicFiles {

    private AtomicFiles() {
    }

    static void write(Path target, String content) {
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
            try {
                Files.writeString(temp, content, UTF_8);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入失败：" + target, e);
        }
    }

    static void writeIfAbsent(Path target, String content) {
        if (!Files.exists(target)) {
            write(target, content);
        }
    }

    static void appendLine(Path target, String line) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, line + "\n", UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("追加失败：" + target, e);
        }
    }

    static String read(Path source) {
        try {
            return Files.readString(source, UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取失败：" + source, e);
        }
    }
}

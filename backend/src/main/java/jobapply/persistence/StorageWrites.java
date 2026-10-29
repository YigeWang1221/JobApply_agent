package jobapply.persistence;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 写入通知（C022）：轮次复盘、题库、按公司汇总和填写报告写完（或删掉）以后，告诉经验检索去更新索引。
 * 这几个写入类在很多地方直接 {@code new}，所以通知是进程级的；听的一方自己按 storage 根目录过滤。
 * 听的一方出错不影响写入本身，只记日志。
 */
public final class StorageWrites {

    private static final Logger log = LoggerFactory.getLogger(StorageWrites.class);
    private static final List<Consumer<Path>> LISTENERS = new CopyOnWriteArrayList<>();

    private StorageWrites() {
    }

    /** 开始听；关掉返回值就不再听。 */
    public static AutoCloseable listen(Consumer<Path> listener) {
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    static void changed(Path file) {
        for (Consumer<Path> listener : LISTENERS) {
            try {
                listener.accept(file);
            } catch (RuntimeException e) {
                log.warn("写入通知处理失败：{}（{}）", file, e.getMessage());
            }
        }
    }

    static void changed(List<Path> files) {
        files.forEach(StorageWrites::changed);
    }
}
